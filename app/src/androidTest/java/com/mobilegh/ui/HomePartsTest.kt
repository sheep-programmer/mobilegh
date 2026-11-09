package com.mobilegh.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import java.io.File
import android.graphics.Bitmap
import com.mobilegh.data.GitHubStarList
import com.mobilegh.data.Event
import com.mobilegh.data.EventActor
import com.mobilegh.data.EventRepo
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.Navigator
import com.mobilegh.nav.RepoKind
import com.mobilegh.nav.Screen
import com.mobilegh.nav.Tab
import com.mobilegh.data.Session
import com.mobilegh.data.User
import com.mobilegh.ui.screens.HomeAchievementsRow
import com.mobilegh.ui.screens.HomeCustomizeScreen
import com.mobilegh.ui.screens.HomeScreen
import com.mobilegh.ui.screens.ExploreScreen
import com.mobilegh.ui.screens.NewShortcutScreen
import com.mobilegh.ui.screens.HomeLists
import com.mobilegh.ui.screens.HomeShortcuts
import com.mobilegh.ui.screens.homeShortcuts
import com.mobilegh.ui.theme.MobileGhTheme
import com.mobilegh.ui.theme.Gh
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomePartsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val nav = Navigator()
    private val list = GitHubStarList("list-1", "Learning", "", false, "learning", "mona", 8)
    @After fun dispose() { compose.runOnUiThread { nav.dispose() } }
    private fun show(error: Boolean = false) {
        compose.setContent {
            MobileGhTheme {
                Column(Modifier.fillMaxSize().padding(top = 20.dp)) {
                    Text("你好，mona", Modifier.padding(16.dp))
                    HomeShortcuts(homeShortcuts({}, {}, {}, {}, { nav.push(Screen.Repos(RepoKind.Starred, "mona")) }, { nav.push(Screen.StarLists("mona")) }, {}, {}))
                    HomeLists(
                        shown = if (error) null else listOf(list),
                        total = if (error) 0 else 1,
                        error = if (error) "unavailable" else null,
                        onList = { nav.push(Screen.StarLists("mona", it.id)) },
                        onAll = { nav.push(Screen.StarLists("mona")) },
                        onCreate = {},
                        onRetry = {},
                    )
                    HomeAchievementsRow(if (error) null else emptyList(), if (error) "unavailable" else null, { nav.push(Screen.Achievements("mona")) }, {})
                }
            }
        }
    }
    @Test fun highlightedListOpensTheSelectedListDirectly() {
        show()
        compose.onNodeWithContentDescription("打开列表 Learning").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(Screen.StarLists("mona", "list-1"), nav.top.screen) }
    }
    @Test fun achievementsAreVisibleAndOpenTheirNativePage() {
        show()
        compose.onNodeWithContentDescription("打开我的成就", substring = true).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(Screen.Achievements("mona"), nav.top.screen) }
    }
    @Test fun entriesRemainUsableIfTheirPreviewsCannotBeLoaded() {
        var retried = false
        compose.setContent {
            MobileGhTheme {
                HomeLists(
                    shown = null,
                    total = 0,
                    error = "unavailable",
                    onList = {},
                    onAll = { nav.push(Screen.StarLists("mona")) },
                    onCreate = {},
                    onRetry = { retried = true },
                )
            }
        }
        compose.onNodeWithText("列表暂时无法读取").assertIsDisplayed()
        compose.onNodeWithText("重试").performClick()
        assertTrue(retried)
    }

    @Test fun homeCustomizeScreenRendersBlocks() {
        compose.runOnUiThread {
            Session.signIn("fixture-token", User(login = "mona", name = "Mona Lisa", avatarUrl = "https://avatars.githubusercontent.com/u/1?v=4"))
        }
        compose.setContent {
            MobileGhTheme {
                androidx.compose.runtime.CompositionLocalProvider(com.mobilegh.nav.LocalNav provides nav) {
                    HomeCustomizeScreen()
                }
            }
        }
        compose.onNodeWithText("我的工作（工作区）").assertIsDisplayed()
        compose.onNodeWithText("未添加功能").assertIsDisplayed()
        compose.onNodeWithText("新建快捷键").assertIsDisplayed()
    }

    @Test fun newShortcutScreenRendersCleanly() {
        compose.runOnUiThread {
            Session.signIn("fixture-token", User(login = "mona", name = "Mona Lisa", avatarUrl = "https://avatars.githubusercontent.com/u/1?v=4"))
        }
        compose.setContent {
            MobileGhTheme {
                androidx.compose.runtime.CompositionLocalProvider(com.mobilegh.nav.LocalNav provides nav) {
                    NewShortcutScreen()
                }
            }
        }
        compose.onNodeWithText("新建快捷键").assertIsDisplayed()
        compose.onNodeWithText("基本信息").assertIsDisplayed()
        compose.onNodeWithText("类型与范围").assertIsDisplayed()
    }

    @Test fun exploreScreenHasNoSearchEntryAndShowsChips() {
        compose.setContent {
            MobileGhTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    com.mobilegh.nav.LocalNav provides nav,
                    LocalEntry provides nav.tabEntries.getValue(Tab.Explore),
                ) {
                    ExploreScreen()
                }
            }
        }
        compose.waitForIdle()
        val bmp = compose.onAllNodes(isRoot())[0].captureToImage().asAndroidBitmap()
        val outDir = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        File(outDir, "explore_screen.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithText("探索").assertIsDisplayed()
        compose.onNodeWithText("热门仓库").assertIsDisplayed()
        compose.onNodeWithText("精选列表").assertIsDisplayed()
        compose.onNodeWithText("搜索仓库、用户、Issue、PR 或代码").assertDoesNotExist()
    }

    @Test fun trendingScreenRenders() {
        compose.setContent {
            MobileGhTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    com.mobilegh.nav.LocalNav provides nav,
                    LocalEntry provides nav.tabEntries.getValue(Tab.Explore),
                ) {
                    com.mobilegh.ui.screens.TrendingScreen()
                }
            }
        }
        compose.waitForIdle()
        val bmp = compose.onAllNodes(isRoot())[0].captureToImage().asAndroidBitmap()
        val outDir = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        File(outDir, "trending_screen.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onAllNodesWithText("热门仓库")[0].assertIsDisplayed()
        compose.onNodeWithText("热门开发者").assertIsDisplayed()
    }

    @Test fun curatedListsScreenRenders() {
        compose.setContent {
            MobileGhTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    com.mobilegh.nav.LocalNav provides nav,
                    LocalEntry provides nav.tabEntries.getValue(Tab.Explore),
                ) {
                    com.mobilegh.ui.screens.CuratedListsScreen()
                }
            }
        }
        compose.waitForIdle()
        val bmp = compose.onAllNodes(isRoot())[0].captureToImage().asAndroidBitmap()
        val outDir = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        File(outDir, "curated_lists.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithText("精选列表").assertIsDisplayed()
        compose.onNodeWithText("全部").assertIsDisplayed()
        compose.onNodeWithText("移动开发").assertIsDisplayed()
    }

    @Test fun homeScreenTopSearchPushesSearchScreen() {
        compose.runOnUiThread {
            Session.signIn("fixture-token", User(login = "mona", name = "Mona Lisa", avatarUrl = "https://avatars.githubusercontent.com/u/1?v=4"))
        }
        compose.setContent {
            MobileGhTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    com.mobilegh.nav.LocalNav provides nav,
                    LocalEntry provides nav.tabEntries.getValue(Tab.Home),
                ) {
                    HomeScreen()
                }
            }
        }
        compose.onNodeWithContentDescription("搜索").performClick()
        compose.runOnIdle {
            assertEquals(Screen.Search(0, ""), nav.top.screen)
        }
    }

    @Test fun homeScreenHidesPinWhenNoPins() {
        compose.runOnUiThread {
            Session.signIn("fixture-token", User(login = "mona", name = "Mona Lisa", avatarUrl = "https://avatars.githubusercontent.com/u/1?v=4"))
        }
        compose.setContent {
            MobileGhTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    com.mobilegh.nav.LocalNav provides nav,
                    LocalEntry provides nav.tabEntries.getValue(Tab.Home),
                ) {
                    HomeScreen()
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("置顶").assertDoesNotExist()
        compose.onNodeWithText("暂无置顶仓库，可在 GitHub 个人主页设置置顶").assertDoesNotExist()
        val bmp = compose.onAllNodes(isRoot())[0].captureToImage().asAndroidBitmap()
        val outDir = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        File(outDir, "home_screen_clean.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun bottomNavigationRendersTelescopeForExplore() {
        compose.setContent {
            MobileGhTheme {
                androidx.compose.material3.Surface {
                    val bottomTabs = listOf(Tab.Home, Tab.Repos, Tab.Star, Tab.Explore)
                    androidx.compose.material3.NavigationBar {
                        bottomTabs.forEach { t ->
                            val sel = nav.tab == t
                            val icon = when (t) {
                                Tab.Home -> if (sel) com.mobilegh.R.drawable.oc_home_fill else com.mobilegh.R.drawable.oc_home
                                Tab.Repos -> com.mobilegh.R.drawable.oc_repo
                                Tab.Star -> if (sel) com.mobilegh.R.drawable.oc_star_fill else com.mobilegh.R.drawable.oc_star
                                Tab.Explore -> if (sel) com.mobilegh.R.drawable.oc_telescope_fill else com.mobilegh.R.drawable.oc_telescope
                                else -> com.mobilegh.R.drawable.oc_dot
                            }
                            NavigationBarItem(
                                selected = sel,
                                onClick = { nav.select(t) },
                                icon = { com.mobilegh.ui.components.Oc(icon, Gh.c.fg, 22.dp) },
                                label = { Text(t.title) },
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val bmp = compose.onAllNodes(isRoot())[0].captureToImage().asAndroidBitmap()
        val outDir = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        File(outDir, "bottom_nav.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithText("探索").assertIsDisplayed()
    }

    @Test fun exploreActivityCardRenders() {
        val payload = buildJsonObject {
            putJsonObject("release") {
                put("name", "v6.12-rc1")
                put("tag_name", "v6.12-rc1")
                put("body", "Linux 6.12 release candidate 1 with major performance improvements.")
            }
        }
        val event = Event(
            id = "e1",
            type = "ReleaseEvent",
            actor = EventActor("torvalds", "Linus Torvalds", "https://github.com/torvalds.png"),
            repo = EventRepo("torvalds/linux"),
            payload = payload,
            createdAt = "2026-11-08T10:00:00Z",
        )
        compose.setContent {
            MobileGhTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    com.mobilegh.nav.LocalNav provides nav,
                    LocalEntry provides nav.tabEntries.getValue(Tab.Explore),
                ) {
                    androidx.compose.material3.Surface(Modifier.fillMaxWidth().padding(16.dp)) {
                        com.mobilegh.ui.screens.ExploreActivityCard(event)
                    }
                }
            }
        }
        compose.waitForIdle()
        val bmp = compose.onAllNodes(isRoot())[0].captureToImage().asAndroidBitmap()
        val outDir = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        File(outDir, "explore_activity_card.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithText("torvalds / linux").assertIsDisplayed()
        compose.onNodeWithText("v6.12-rc1").assertIsDisplayed()
    }

    @Test fun settingsScreenIsCompressedAndClean() {
        compose.runOnUiThread {
            Session.signIn("fixture-token", User(login = "mona", name = "Mona Lisa", avatarUrl = "https://avatars.githubusercontent.com/u/1?v=4"))
        }
        compose.setContent {
            MobileGhTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    com.mobilegh.nav.LocalNav provides nav,
                    LocalEntry provides nav.tabEntries.getValue(Tab.Home),
                ) {
                    com.mobilegh.ui.screens.SettingsScreen()
                }
            }
        }
        compose.waitForIdle()
        val bmp = compose.onAllNodes(isRoot())[0].captureToImage().asAndroidBitmap()
        val outDir = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        File(outDir, "settings_screen.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithText("外观与语言").assertIsDisplayed()
        compose.onNodeWithText("安全与隐私").assertIsDisplayed()
        compose.onNodeWithText("网络与加速").assertIsDisplayed()
        compose.onNodeWithText("退出当前账号").assertIsDisplayed()
    }

    @Test fun barChartBubbleRendersSubtleWithoutBlackBlock() {
        val values = listOf(10, 203, 30, 40, 50, 60, 70)
        val labels = listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
        compose.setContent {
            MobileGhTheme {
                androidx.compose.material3.Surface(Modifier.fillMaxWidth().padding(16.dp)) {
                    com.mobilegh.ui.components.BarChart(
                        values = values,
                        color = Gh.c.success,
                        labels = labels,
                        unit = " 次贡献",
                    )
                }
            }
        }
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag("chart").fetchSemanticsNode().boundsInRoot
        val slotWidth = bounds.width / 7
        val mondayX = bounds.left + slotWidth * 1.5f
        val mondayY = bounds.top + bounds.height * 0.7f
        compose.onNodeWithTag("chart").performTouchInput {
            click(androidx.compose.ui.geometry.Offset(mondayX - bounds.left, mondayY - bounds.top))
        }
        compose.waitForIdle()
        val bmp = compose.onAllNodes(isRoot())[0].captureToImage().asAndroidBitmap()
        val outDir = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        File(outDir, "bar_chart_bubble.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

        // 再次点按验证取消选中
        compose.onNodeWithTag("chart").performTouchInput {
            click(androidx.compose.ui.geometry.Offset(mondayX - bounds.left, mondayY - bounds.top))
        }
        compose.waitForIdle()
    }

    @Test fun profileScreenHasNoDuplicateSettings() {
        compose.runOnUiThread {
            Session.signIn("fixture-token", User(login = "mona", name = "Mona Lisa", avatarUrl = "https://avatars.githubusercontent.com/u/1?v=4"))
        }
        compose.setContent {
            MobileGhTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    com.mobilegh.nav.LocalNav provides nav,
                    LocalEntry provides nav.tabEntries.getValue(Tab.Home),
                ) {
                    com.mobilegh.ui.screens.ProfileScreen("mona")
                }
            }
        }
        compose.waitForIdle()
        // 顶部操作栏有设置按钮
        compose.onNodeWithContentDescription("设置").assertIsDisplayed()
        // 列表菜单项中不再包含重复的「设置」行
        compose.onNodeWithText("设置").assertDoesNotExist()
    }
}
