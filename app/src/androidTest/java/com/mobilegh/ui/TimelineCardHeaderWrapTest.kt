package com.mobilegh.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilegh.data.User
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Navigator
import com.mobilegh.ui.screens.TimelineCard
import com.mobilegh.ui.theme.MobileGhTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 时间线卡片头部：作者名太长时，徽章不能被挤成竖排，要换到单独一行完整显示。 */
@RunWith(AndroidJUnit4::class)
class TimelineCardHeaderWrapTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val badge = "审查了代码"

    @Composable
    private fun Card(login: String) {
        CompositionLocalProvider(LocalNav provides Navigator()) {
            MobileGhTheme {
                Box(Modifier.width(300.dp)) {
                    TimelineCard(
                        user = User(login = login),
                        time = "2026-11-17T02:00:00Z",
                        badge = badge,
                        html = null,
                        baseUrl = "https://github.com/o/r/",
                        menu = emptyList(),
                    )
                }
            }
        }
    }

    @Test fun longBotLoginWrapsTheBadgeOntoItsOwnLineInsteadOfSqueezingIt() {
        val bot = "chatgpt-codex-connector[bot]"
        compose.setContent { Card(bot) }

        val badgeNode = compose.onNodeWithText(badge)
        // 竖排挤压时文字宽度只有几个 dp，高度却有五六行
        badgeNode.assertWidthIsAtLeast(40.dp)
        val b = badgeNode.fetchSemanticsNode().boundsInRoot
        val maxSingleLineHeight = with(compose.density) { 30.dp.toPx() }
        assertTrue("徽章不应被压成多行（badge=$b）", b.height <= maxSingleLineHeight)
        val login = compose.onNodeWithText(bot).fetchSemanticsNode().boundsInRoot
        assertTrue("徽章应在作者行下方（badge=$b, login=$login）", b.top >= login.bottom - 1f)
    }

    @Test fun shortLoginKeepsTheBadgeOnTheAuthorLine() {
        val login = "octocat"
        compose.setContent { Card(login) }

        val b = compose.onNodeWithText(badge).fetchSemanticsNode().boundsInRoot
        val author = compose.onNodeWithText(login).fetchSemanticsNode().boundsInRoot
        assertTrue("徽章不必换行时仍留在作者行（badge=$b, author=$author）", b.top < author.bottom)
    }
}
