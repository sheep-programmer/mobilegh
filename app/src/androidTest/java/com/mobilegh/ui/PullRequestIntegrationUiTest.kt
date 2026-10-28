package com.mobilegh.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.material3.Text
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilegh.data.GitHubStarList
import com.mobilegh.data.OrganizationDirectory
import com.mobilegh.data.Repo
import com.mobilegh.data.Session
import com.mobilegh.data.User
import com.mobilegh.nav.Loader
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Navigator
import com.mobilegh.nav.Pager
import com.mobilegh.ui.components.PagedList
import com.mobilegh.ui.screens.RepoScreen
import com.mobilegh.ui.screens.LIST_SORTS
import com.mobilegh.ui.screens.SortBar
import com.mobilegh.ui.screens.StarSearchControls
import com.mobilegh.ui.screens.StarredRepoPage
import com.mobilegh.ui.screens.autoPageStarFeed
import com.mobilegh.ui.screens.rememberSharedListBrowser
import com.mobilegh.ui.screens.resetListBrowsers
import com.mobilegh.ui.theme.MobileGhTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** PR #2/#3/#4 的设备 UI 回归；所有数据都是本地 fixture，不写 GitHub。 */
@RunWith(AndroidJUnit4::class)
class PullRequestIntegrationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var navigator: Navigator? = null

    @After fun cleanUp() {
        compose.runOnUiThread {
            navigator?.dispose()
            navigator = null
            if (Session.login == TEST_LOGIN) Session.signOut()
        }
    }

    private fun signInFixture(): Navigator {
        Session.signIn("local-fixture-token", User(login = TEST_LOGIN))
        return Navigator().also { navigator = it }
    }

    @Test fun sharedListBrowserIsClearedWhenTheAccountGenerationChanges() {
        val previousGeneration = Session.generation
        var initial: com.mobilegh.ui.screens.ListBrowserState? = null
        var current: com.mobilegh.ui.screens.ListBrowserState? = null
        try {
            compose.setContent {
                current = rememberSharedListBrowser("account-switch-test")
                if (initial == null) initial = current
            }
            compose.runOnIdle { current!!.input = "previous account query" }
            compose.runOnUiThread { Session.generation++ }
            compose.runOnIdle {
                assertTrue(current !== initial)
                assertEquals("", current!!.input)
                assertEquals("", current!!.query)
            }
        } finally {
            compose.runOnUiThread {
                Session.generation = previousGeneration
                resetListBrowsers()
            }
        }
    }

    @Test fun starListNameAndMetadataStayOnSeparateRowsAtLargeFonts() {
        val longName = "A very long Star list name whose metadata must not squeeze or overlap the title"
        val list = GitHubStarList("list-1", longName, "", true, "learning", TEST_LOGIN, 128)
        val repo = Repo(42, "Hello-World", "octocat/Hello-World", User(login = "octocat"))
        val nav = signInFixture()
        val catalog = Loader(nav.root.scope) { listOf(list) }.also { it.accept(listOf(list)) }
        nav.root.bag["star-lists:$TEST_LOGIN:$TEST_LOGIN:${Session.generation}"] = catalog
        val feed = Pager<Repo>(nav.root.scope, fetch = { _, _ -> emptyList() }).also {
            it.end = true
            it.loadedOnce = true
        }
        nav.root.bag["rl-starred:$TEST_LOGIN:0:true::true"] = feed
        val detail = Pager<Repo>(nav.root.scope, fetch = { _, _ -> listOf(repo) }).also {
            it.items += repo
            it.end = true
            it.loadedOnce = true
        }
        nav.root.bag["rl-list:list-1:true"] = detail

        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                MobileGhTheme {
                    CompositionLocalProvider(LocalNav provides nav, LocalEntry provides nav.root) {
                        StarredRepoPage(TEST_LOGIN, "")
                    }
                }
            }
        }
        compose.onNodeWithText("列表").performClick()
        val row = compose.onNodeWithText(longName).performScrollTo().assertIsDisplayed()
        val title = compose.onNodeWithText(longName, useUnmergedTree = true).assertIsDisplayed()
        val private = compose.onNodeWithText("私有", useUnmergedTree = true).assertIsDisplayed()
        val titleBounds = title.getUnclippedBoundsInRoot()
        val metadataBounds = private.getUnclippedBoundsInRoot()
        assertTrue("列表标签不能压住名称：title=$titleBounds, metadata=$metadataBounds", metadataBounds.top >= titleBounds.bottom)
        row.performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Hello-World", substring = true))
        compose.onNodeWithText("Hello-World", substring = true).assertIsDisplayed()
        val remove = compose.onNodeWithText("移除").performScrollTo().assertIsDisplayed()
        val repoBounds = compose.onNodeWithText("Hello-World", substring = true).getUnclippedBoundsInRoot()
        assertTrue("移除操作不能挤压仓库卡片", remove.getUnclippedBoundsInRoot().top >= repoBounds.bottom)
        compose.onNodeWithText("移除分类不会取消 Star。").assertIsDisplayed()
    }

    @Test fun searchStatusKeepsDirectionVisibleAtLargeFonts() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                MobileGhTheme {
                    StarSearchControls(
                        input = "hello", query = "hello", index = 0, descending = true,
                        searching = true, status = "", onInput = {}, onApply = {}, onClear = {},
                        onPick = {}, onFlip = {},
                    )
                }
            }
        }
        compose.onNodeWithText("倒序").assertIsDisplayed()
    }

    @Test fun disabledSortsDoNotReact() {
        compose.setContent {
            MobileGhTheme {
                SortBar(LIST_SORTS, index = 0, descending = true, onPick = {}, onFlip = {}, enabled = false)
            }
        }
        compose.onNodeWithText("最后更新时间").assertIsNotEnabled()
        compose.onNodeWithText("倒序").assertIsNotEnabled()
    }

    @Test fun disablingSortsClosesAnAlreadyOpenMenu() {
        val enabled = mutableStateOf(true)
        compose.setContent {
            MobileGhTheme {
                SortBar(
                    LIST_SORTS, index = 0, descending = true,
                    onPick = {}, onFlip = {}, enabled = enabled.value,
                )
            }
        }
        compose.onNodeWithText("最后更新时间").performClick()
        compose.onNodeWithText("创建时间").assertIsDisplayed()
        compose.runOnUiThread { enabled.value = false }
        compose.onNodeWithText("创建时间").assertDoesNotExist()
    }

    @Test fun forkFieldsRemainScrollableAndValidateRepositoryNamesAtLargeFonts() {
        val repo = Repo(
            42, "Hello-World", "octocat/Hello-World", User(login = "octocat"),
            description = "Original description", defaultBranch = "main",
        )
        val nav = signInFixture()
        nav.root.bag["repo"] = Loader(nav.root.scope) { repo }.also { it.accept(repo) }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                MobileGhTheme {
                    CompositionLocalProvider(LocalNav provides nav, LocalEntry provides nav.root) {
                        RepoScreen("octocat", "Hello-World")
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Fork 此仓库").performClick()
        compose.onNodeWithText("Fork 到我的账号").assertIsSelected()
        compose.onNodeWithText("仓库名称（可选）").performScrollTo().performTextReplacement("repo..name")
        compose.onNodeWithText("仓库名称无效").assertDoesNotExist()
        compose.onNodeWithText("仓库名称（可选）").performTextReplacement("bad/name")
        compose.onNodeWithText("仓库名称无效").assertIsDisplayed()
        compose.onNodeWithText("Fork").assertIsNotEnabled()
        compose.onNodeWithText("只复制默认分支").performScrollTo().assertIsOn()
        compose.onNodeWithText("Fork 到组织").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Fork").assertIsNotEnabled()
    }

    @Test fun localStarSearchPagesOnlyAfterAnExplicitRequest() {
        val nav = signInFixture()
        val requests = AtomicInteger()
        val repo = Repo(42, "Hello-World", "octocat/Hello-World", User(login = "octocat"))
        val pager = Pager<Repo>(nav.root.scope, pageSize = 1, fetch = { page, _ ->
            requests.incrementAndGet()
            if (page == 1) listOf(repo) else emptyList()
        }).also { it.loadMore() }

        compose.setContent {
            MobileGhTheme {
                PagedList(
                    pager,
                    autoLoad = autoPageStarFeed(query = "missing", searching = false),
                    itemFilter = { false },
                    filteredEmpty = "已加载的 Star 中没有匹配的仓库",
                ) { Text(it.name) }
            }
        }
        compose.waitForIdle()
        assertEquals("本地过滤不能自动请求下一页", 1, requests.get())
        compose.onNodeWithText("加载更多").performClick()
        compose.waitForIdle()
        assertEquals(2, requests.get())
        compose.onNodeWithText("已加载的 Star 中没有匹配的仓库").assertIsDisplayed()
    }

    @Test fun localListDetailSearchPagesOnlyAfterAnExplicitRequest() {
        val nav = signInFixture()
        val list = GitHubStarList("list-1", "Reading", "", true, "reading", TEST_LOGIN, 1)
        val repo = Repo(42, "Hello-World", "octocat/Hello-World", User(login = "octocat"))
        val requests = AtomicInteger()
        val requestedPages = CopyOnWriteArrayList<Int>()
        nav.root.bag["star-lists:$TEST_LOGIN:$TEST_LOGIN:${Session.generation}"] =
            Loader(nav.root.scope) { listOf(list) }.also { it.accept(listOf(list)) }
        nav.root.bag["rl-starred:$TEST_LOGIN:0:true::true"] = Pager<Repo>(nav.root.scope, fetch = { _, _ -> emptyList() }).also {
            it.loadedOnce = true
            it.end = true
        }
        val detail = Pager(nav.root.scope, pageSize = 20, fetch = { page, _ ->
            requestedPages += page
            requests.incrementAndGet()
            if (page <= 2) List(20) { index ->
                repo.copy(
                    id = page * 100L + index,
                    name = "Hello-$page-$index",
                    fullName = "octocat/Hello-$page-$index",
                )
            } else emptyList()
        })
        detail.loadMore()
        compose.waitUntil(5000) { requests.get() == 1 && !detail.loading }
        compose.runOnUiThread {
            // 首屏数据已就绪；等详情完成首次布局后再允许自动分页，隔离搜索行为。
            detail.loadedOnce = false
            detail.loading = true
            nav.root.bag["rl-list:list-1:true"] = detail
        }
        compose.setContent {
            MobileGhTheme {
                CompositionLocalProvider(LocalNav provides nav, LocalEntry provides nav.root) {
                    StarredRepoPage(TEST_LOGIN, "")
                }
            }
        }
        compose.onNodeWithText("列表").performClick()
        compose.onNodeWithText("Reading").performClick()
        compose.waitForIdle()
        val initialRequests = requests.get()
        compose.onNodeWithContentDescription("搜索列表内仓库").performTextReplacement("missing")
        compose.onNodeWithContentDescription("应用搜索").performClick()
        compose.waitForIdle()
        compose.runOnUiThread { detail.loading = false }
        compose.waitForIdle()
        compose.onNodeWithText("加载更多").assertIsDisplayed()
        assertEquals("列表内本地搜索不能自动请求下一页；实际页码=$requestedPages", initialRequests, requests.get())
        compose.onNodeWithText("加载更多").performClick()
        compose.waitForIdle()
        assertEquals(initialRequests + 1, requests.get())
    }

    @Test fun forkShowsPartialOrganizationDiscoveryWarnings() {
        val repo = Repo(42, "Hello-World", "octocat/Hello-World", User(login = "octocat"))
        val nav = signInFixture()
        nav.root.bag["repo"] = Loader(nav.root.scope) { repo }.also { it.accept(repo) }
        val directory = OrganizationDirectory(
            orgs = emptyList(),
            warnings = listOf("组织成员身份：读取失败"),
            scopes = null,
        )
        nav.root.bag["fork-orgs:true"] = Loader(nav.root.scope) { directory }.also { it.accept(directory) }
        compose.setContent {
            MobileGhTheme {
                CompositionLocalProvider(LocalNav provides nav, LocalEntry provides nav.root) {
                    RepoScreen("octocat", "Hello-World")
                }
            }
        }
        compose.onNodeWithContentDescription("Fork 此仓库").performClick()
        compose.onNodeWithText("Fork 到组织").performClick()
        compose.onNodeWithText("组织成员身份：读取失败", substring = true).assertIsDisplayed()
        compose.onNodeWithText("当前授权没有返回可用的组织").assertDoesNotExist()
    }

    @Test fun onlyTheDisplayedRetainedStarFeedRefreshes() {
        val nav = signInFixture()
        nav.root.bag["star-lists:$TEST_LOGIN:$TEST_LOGIN:${Session.generation}"] =
            Loader(nav.root.scope) { emptyList<GitHubStarList>() }.also { it.accept(emptyList()) }
        val visibleRequests = AtomicInteger()
        val abandonedRequests = AtomicInteger()
        val visible = Pager<Repo>(nav.root.scope, fetch = { _, _ ->
            visibleRequests.incrementAndGet()
            emptyList()
        }).also { it.loadedOnce = true; it.end = true }
        val abandoned = Pager<Repo>(nav.root.scope, fetch = { _, _ ->
            abandonedRequests.incrementAndGet()
            emptyList()
        }).also { it.loadedOnce = true; it.end = true }
        nav.root.bag["rl-starred:$TEST_LOGIN:0:true::true"] = visible
        nav.root.bag["rl-starred:$TEST_LOGIN:0:true:old:true"] = abandoned
        compose.setContent {
            MobileGhTheme {
                CompositionLocalProvider(LocalNav provides nav, LocalEntry provides nav.root) {
                    StarredRepoPage(TEST_LOGIN, "")
                }
            }
        }
        compose.waitUntil(5000) { visibleRequests.get() == 1 }
        assertEquals(0, abandonedRequests.get())
    }

    private companion object {
        const val TEST_LOGIN = "mobilegh-ui-test"
    }
}
