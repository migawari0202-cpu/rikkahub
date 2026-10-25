package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Settings03
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DrawerActionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun lightThemeDoesNotUseBlackPrimaryContainer() = verifyColors(dark = false)

    @Test
    fun darkThemeUsesLightIconsOnNeutralContainer() = verifyColors(dark = true)

    private fun verifyColors(dark: Boolean) {
        val background = if (dark) Color(0xFF303030) else Color(0xFFE8E8E8)
        val foreground = if (dark) Color(0xFFF0F0F0) else Color(0xFF202020)
        val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
            primaryContainer = Color.Black,
            surfaceContainerHigh = background,
            onSurface = foreground,
        )
        var actualContentColor = Color.Unspecified
        var clicked = false
        compose.setContent {
            MaterialTheme(colorScheme = scheme) {
                DrawerAction(
                    modifier = Modifier.testTag("drawer-action"),
                    icon = {
                        actualContentColor = LocalContentColor.current
                        Icon(HugeIcons.Settings03, "Settings")
                    },
                    label = { Text("Settings") },
                    onClick = { clicked = true },
                )
            }
        }
        val icon = compose.onNodeWithContentDescription("Settings")
        icon.assertExists()
        val node = compose.onNodeWithTag("drawer-action")
        val pixels = node.captureToImage().toPixelMap()
        // Sample inside the circle, above the icon, avoiding anti-aliased edges.
        val pixel = pixels[pixels.width / 2, (pixels.height * 0.1f).toInt()]
        assertEquals(background.red, pixel.red, 0.01f)
        assertEquals(background.green, pixel.green, 0.01f)
        assertEquals(background.blue, pixel.blue, 0.01f)
        compose.runOnIdle { assertEquals(foreground, actualContentColor) }
        node.assertHasClickAction().performClick()
        compose.runOnIdle { assertTrue(clicked) }
    }
}