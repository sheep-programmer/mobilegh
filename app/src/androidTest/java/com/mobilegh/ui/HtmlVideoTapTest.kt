package com.mobilegh.ui

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Navigator
import com.mobilegh.ui.components.HtmlView
import com.mobilegh.ui.theme.MobileGhTheme
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HtmlVideoTapTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun playStaysInlineAndExplicitExpandKeepsTheNativePlayer() = withVideoFixture { src ->
        showHtml("<p>demo</p><video controls src=\"$src\"></video>")
        val play = compose.onNodeWithContentDescription("播放")
        val expand = compose.onNodeWithText("放大播放")
        val close = compose.onNodeWithContentDescription("关闭")
        play.performClick()
        close.assertDoesNotExist()
        val player = compose.runOnIdle { findView<PlayerView>(compose.activity.window.decorView)?.player!! }
        compose.waitUntil(10_000) { compose.runOnIdle { player.currentPosition > 0 } }
        val beforeExpand = compose.runOnIdle { player.currentPosition }
        expand.performClick()
        close.assertIsDisplayed().performClick()
        close.assertDoesNotExist()
        compose.runOnIdle {
            assertSame(player, findView<PlayerView>(compose.activity.window.decorView)?.player)
            assertTrue(player.currentPosition >= beforeExpand)
        }
    }

    @Test fun youtubePlayStaysInlineAndClosingFullscreenReturnsTheSameEmbed() {
        showHtml("""<iframe src="https://www.youtube.com/embed/M7lc1UVf-VE" title="YouTube test"></iframe>""")
        compose.onNodeWithContentDescription("播放").performClick()
        val close = compose.onNodeWithContentDescription("关闭")
        close.assertDoesNotExist()
        val web = compose.runOnIdle { findView<WebView>(compose.activity.window.decorView)!! }
        compose.onNodeWithText("放大播放").performClick()
        close.assertIsDisplayed().performClick()
        close.assertDoesNotExist()
        compose.runOnIdle { assertSame(web, findView<WebView>(compose.activity.window.decorView)) }
    }

    private fun showHtml(html: String) {
        val nav = Navigator()
        compose.setContent {
            MobileGhTheme {
                CompositionLocalProvider(LocalNav provides nav) {
                    HtmlView(html)
                }
            }
        }
    }
}

private inline fun <reified T : View> findView(root: View): T? = findView(root, T::class.java)

private fun <T : View> findView(root: View, type: Class<T>): T? {
    if (type.isInstance(root)) return type.cast(root)
    if (root is ViewGroup) for (index in 0 until root.childCount) {
        findView(root.getChildAt(index), type)?.let { return it }
    }
    return null
}
