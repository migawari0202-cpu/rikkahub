package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.agent.AgentEvent
import me.rerere.ai.agent.AgentHooks
import me.rerere.ai.agent.AgentRequest
import me.rerere.ai.agent.StepRequest
import me.rerere.ai.agent.runAgent
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.UsageRecorder
import me.rerere.rikkahub.data.ai.UsageSource
import me.rerere.rikkahub.data.ai.UsageTag
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.search.SearchService
import me.rerere.search.SearchServiceOptions
import kotlin.uuid.Uuid

private const val MAX_RESEARCH_TASKS = 4
private const val MAX_TASK_CHARS = 2_000
private const val MAX_RESULT_CHARS = 1_500
private const val MAX_RESEARCH_STEPS = 3
private const val MAX_RESEARCH_TOKENS = 400

private const val RESEARCH_SYSTEM_PROMPT = """
    You are a low-cost research sub-agent. Investigate only the specific task provided by the user.
    You do not know the parent conversation; use only the task text and information you can verify.
    You always have the search_web tool. Use it to investigate the task and cite the source URLs it returns.
    If scrape_web is available, use it to verify important source details. Do not call any other tool.
    Do not perform actions, edit files, or send information to third parties.
    Return a concise result with these sections:
    Conclusion: the direct answer.
    Evidence: the key supporting facts and any important caveats.
    Sources: URLs for the claims, or "None" if no reliable source was found.
    Confidence: high, medium, or low, with a short reason.
    Clearly label uncertainty; never invent facts or sources.
"""

/** Builds a parallel research delegation tool. Each task gets an isolated, bounded sub-agent. */
internal fun buildDelegateResearchTool(
    settings: Settings,
    conversation: Conversation,
    providerManager: ProviderManager,
    usageRecorder: UsageRecorder,
): Tool = Tool(
    name = "delegate_research",
    description = """
        Delegate up to $MAX_RESEARCH_TASKS independent research tasks to the configured fast model in parallel.
        Use for comparisons, independent research angles, or splitting long source reviews. Do not use for simple questions,
        dependent tasks, final decisions, private or confidential information, or actions with side effects. Each sub-agent
        receives only its own self-contained task, not this conversation or other task results. Include the subject, purpose,
        scope, assumptions, and evaluation criteria in every task. Results are short summaries with evidence, source URLs,
        and confidence; verify important claims yourself, especially if sources are missing or results disagree. Partial
        failures are reported per task. You remain responsible for synthesizing and verifying the final answer.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("tasks", buildJsonObject {
                    put("type", "array")
                    put("minItems", 1)
                    put("maxItems", MAX_RESEARCH_TASKS)
                    put("description", "Independent research tasks. Each must be self-contained and must not need another task's result.")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("id", buildJsonObject {
                                put("type", "string")
                                put("description", "Short unique identifier for this task")
                            })
                            put("task", buildJsonObject {
                                put("type", "string")
                                put("description", "Self-contained research request, maximum $MAX_TASK_CHARS characters")
                            })
                        })
                        put("required", buildJsonArray {
                            add("id")
                            add("task")
                        })
                    })
                })
            },
            required = listOf("tasks")
        )
    },
    execute = { input ->
        val tasks = parseResearchTasks(input.jsonObject["tasks"])

        val configuredResearchModel = settings.findModelById(settings.fastModelId)
            ?: error("No fast model is configured. Select a fast model in model settings before using delegate_research.")
        // OpenAI-compatible providers omit function definitions unless the selected model is marked TOOL-capable.
        // Delegated research always receives our search tools, so opt this per-request model copy in without
        // changing the configured model or the parent conversation's normal tool capability setting.
        val researchModel = configuredResearchModel.withResearchToolAbility()
        val providerSetting = researchModel.findProvider(settings.providers)
            ?: error("The configured fast model provider could not be found.")
        val researchTools = createResearchTools(settings)
        val results = coroutineScope {
            tasks.map { task ->
                async {
                    runResearchTask(
                        task = task,
                        model = researchModel,
                        providerSetting = providerSetting,
                        providerManager = providerManager,
                        usageRecorder = usageRecorder,
                        settings = settings,
                        conversation = conversation,
                        tools = researchTools,
                    )
                }
            }.awaitAll()
        }

        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("model", researchModel.displayName.ifBlank { researchModel.modelId })
                    put("parallel_limit", MAX_RESEARCH_TASKS)
                    put("results", buildJsonArray {
                        results.forEach { result ->
                            add(buildJsonObject {
                                put("id", result.id)
                                put("success", result.success)
                                put("summary", result.summary)
                            })
                        }
                    })
                }.toString()
            )
        )
    }
)

internal data class ResearchTask(val id: String, val task: String)

private data class ResearchResult(val id: String, val success: Boolean, val summary: String)

internal fun Model.withResearchToolAbility(): Model =
    if (ModelAbility.TOOL in abilities) this else copy(abilities = abilities + ModelAbility.TOOL)

internal fun parseResearchTasks(rawTasks: kotlinx.serialization.json.JsonElement?): List<ResearchTask> {
    val tasksArray = rawTasks?.jsonArray ?: error("tasks is required and must be an array")
    require(tasksArray.isNotEmpty()) { "At least one research task is required" }
    require(tasksArray.size <= MAX_RESEARCH_TASKS) { "At most $MAX_RESEARCH_TASKS research tasks are allowed" }

    val tasks = tasksArray.mapIndexed { index, value ->
        val taskObject = value.jsonObject
        val id = taskObject["id"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: error("tasks[$index].id is required")
        val task = taskObject["task"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: error("tasks[$index].task is required")
        require(id.isNotBlank()) { "tasks[$index].id must not be blank" }
        require(id.length <= 64) { "tasks[$index].id exceeds 64 characters" }
        require(task.isNotBlank()) { "tasks[$index].task must not be blank" }
        require(task.length <= MAX_TASK_CHARS) { "tasks[$index].task exceeds $MAX_TASK_CHARS characters" }
        ResearchTask(id = id, task = task)
    }
    require(tasks.map { it.id }.distinct().size == tasks.size) { "Task ids must be unique" }
    return tasks
}

private fun createResearchTools(settings: Settings): List<Tool> {
    val options = settings.searchServices.getOrElse(settings.searchServiceSelected) { SearchServiceOptions.DEFAULT }
    val service = SearchService.getService(options)
    return buildList {
        add(
            Tool(
                name = "search_web",
                description = "Search the web for reliable sources relevant to this isolated research task.",
                parameters = { service.parameters(options) },
                execute = { params ->
                    val result = service.search(
                        params = params.jsonObject,
                        commonOptions = settings.searchCommonOptions.copy(
                            resultSize = settings.searchCommonOptions.resultSize.coerceIn(1, 5)
                        ),
                        serviceOptions = options,
                    ).getOrThrow()
                    listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                result.answer?.take(500)?.let { put("answer", it) }
                                put("items", buildJsonArray {
                                    result.items.take(5).forEach { item ->
                                        add(buildJsonObject {
                                            put("title", item.title.take(200))
                                            put("url", item.url.take(500))
                                            put("text", item.text.take(500))
                                            item.publishedDate?.take(100)?.let { put("publishedDate", it) }
                                        })
                                    }
                                })
                                result.retrievedAt?.take(100)?.let { put("retrievedAt", it) }
                            }.toString()
                        )
                    )
                },
            )
        )
        if (service.scrapingParameters(options) != null) {
            add(
                Tool(
                    name = "scrape_web",
                    description = "Read a source page to verify details relevant to this research task.",
                    parameters = { service.scrapingParameters(options) },
                    execute = { params ->
                        val result = service.scrape(
                            params = params.jsonObject,
                            commonOptions = settings.searchCommonOptions,
                            serviceOptions = options,
                        ).getOrThrow()
                        listOf(
                            UIMessagePart.Text(
                                buildJsonObject {
                                    put("urls", buildJsonArray {
                                        result.urls.take(2).forEach { page ->
                                            add(buildJsonObject {
                                                put("url", page.url.take(500))
                                                put("content", page.content.take(1_000))
                                                page.metadata?.title?.take(200)?.let { put("title", it) }
                                                page.metadata?.publishedDate?.take(100)?.let { put("publishedDate", it) }
                                            })
                                        }
                                    })
                                    result.retrievedAt?.take(100)?.let { put("retrievedAt", it) }
                                }.toString()
                            )
                        )
                    },
                )
            )
        }
    }
}

private suspend fun runResearchTask(
    task: ResearchTask,
    model: Model,
    providerSetting: ProviderSetting,
    providerManager: ProviderManager,
    usageRecorder: UsageRecorder,
    settings: Settings,
    conversation: Conversation,
    tools: List<Tool>,
): ResearchResult {
    return try {
        val provider = usageRecorder.wrap(
            providerManager.getProviderByType(providerSetting),
            UsageTag(UsageSource.DELEGATE_RESEARCH, conversation.assistantId, conversation.id),
        )
        var finalMessage: UIMessage? = null
        val sessionId = "delegate-research-${conversation.id}-${Uuid.random()}"
        val hooks = object : AgentHooks {
            override suspend fun prepareStep(messages: List<UIMessage>): StepRequest = StepRequest(
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    maxTokens = MAX_RESEARCH_TOKENS,
                    tools = tools,
                    reasoningLevel = settings.fastModelReasoningLevel,
                    customHeaders = buildList {
                        addAll(providerSetting.customHeaders)
                        addAll(model.customHeaders)
                    },
                    customBody = model.customBodies,
                    sessionId = sessionId,
                ),
            )
        }
        var completed = false
        runAgent(
            request = AgentRequest(
                provider = provider,
                providerSetting = providerSetting,
                messages = listOf(
                    UIMessage.system(RESEARCH_SYSTEM_PROMPT),
                    UIMessage.user(task.task),
                ),
                tools = tools,
                stream = false,
                maxSteps = MAX_RESEARCH_STEPS,
            ),
            hooks = hooks,
        ).collect { event ->
            if (event is AgentEvent.Finished) {
                finalMessage = event.message
                completed = event.reason == me.rerere.ai.agent.AgentFinishReason.Completed
            }
        }
        val message = finalMessage
        val summary = message?.toText()?.trim().orEmpty().take(MAX_RESULT_CHARS)
        if (!completed || summary.isBlank() || message?.getTools()?.any { !it.isExecuted } == true) {
            ResearchResult(
                id = task.id,
                success = false,
                summary = "The sub-agent did not return a final text summary within $MAX_RESEARCH_STEPS model requests. Research this task directly.",
            )
        } else {
            ResearchResult(task.id, success = true, summary = summary)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        ResearchResult(
            id = task.id,
            success = false,
            summary = (e.message ?: e::class.simpleName ?: "Unknown error").take(MAX_RESULT_CHARS),
        )
    }
}
