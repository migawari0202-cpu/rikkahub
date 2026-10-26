package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.Json
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DelegateResearchToolTest {
    @Test
    fun `enables function tools on the delegated model copy`() {
        val configuredModel = Model(abilities = listOf(ModelAbility.REASONING))

        val researchModel = configuredModel.withResearchToolAbility()

        assertEquals(listOf(ModelAbility.REASONING), configuredModel.abilities)
        assertEquals(listOf(ModelAbility.REASONING, ModelAbility.TOOL), researchModel.abilities)
    }

    @Test
    fun `does not duplicate tool ability when already supported`() {
        val configuredModel = Model(abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING))

        val researchModel = configuredModel.withResearchToolAbility()

        assertEquals(configuredModel, researchModel)
    }

    @Test
    fun `parses independent task identifiers and requests`() {
        val tasks = parseResearchTasks(
            Json.parseToJsonElement(
                """
                    [
                      {"id":"price", "task":"Compare current prices for product A."},
                      {"id":"reliability", "task":"Find reliability reports for product A."}
                    ]
                """.trimIndent()
            )
        )

        assertEquals(listOf("price", "reliability"), tasks.map { it.id })
        assertEquals("Compare current prices for product A.", tasks.first().task)
    }

    @Test
    fun `trims task identifiers and task text`() {
        val task = parseResearchTasks(
            Json.parseToJsonElement("""[{"id":" price ","task":"  Compare prices.  "}]""")
        ).single()

        assertEquals("price", task.id)
        assertEquals("Compare prices.", task.task)
    }

    @Test
    fun `requires at least one task and limits task count`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseResearchTasks(Json.parseToJsonElement("[]"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseResearchTasks(
                Json.parseToJsonElement(
                    (1..5).joinToString(prefix = "[", postfix = "]") { """{"id":"$it","task":"Task $it"}""" }
                )
            )
        }
    }

    @Test
    fun `rejects duplicate or oversized task data`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseResearchTasks(
                Json.parseToJsonElement("""[{"id":"same","task":"First"},{"id":"same","task":"Second"}]""")
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseResearchTasks(
                Json.parseToJsonElement("""[{"id":"x","task":"${"x".repeat(2_001)}"}]""")
            )
        }
        assertEquals(
            2_000,
            parseResearchTasks(
                Json.parseToJsonElement("""[{"id":"x","task":"${"x".repeat(2_000)}"}]""")
            ).single().task.length
        )
    }
}