package com.mobilegh.ui

import android.content.Context
import android.content.ClipboardManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilegh.data.Api
import com.mobilegh.data.Net
import com.mobilegh.data.Session
import com.mobilegh.data.User
import com.mobilegh.nav.ActionsSettingsSection
import com.mobilegh.nav.Loader
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Navigator
import com.mobilegh.nav.Screen
import com.mobilegh.ui.screens.ActionsSettingsScreen
import com.mobilegh.ui.theme.MobileGhTheme
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActionRunnersCachesUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val servers = mutableListOf<LoopbackApi>()
    private var previousProxy = ""
    private var previousCache: File? = null

    @Before fun signInTestAccount() {
        compose.runOnUiThread {
            Session.signIn("local-fixture-token", User(login = "mobilegh-ui-test"))
        }
    }

    @After fun restore() {
        compose.runOnUiThread {
            if (Session.login?.startsWith("mobilegh-ui-test") == true) Session.signOut()
            Net.apiProxy = previousProxy
            previousCache?.let { Api.cacheDir = it }
        }
        servers.forEach { it.close() }
    }

    private fun api(handler: (String, String, String) -> Pair<Int, String>): LoopbackApi {
        if (servers.isEmpty()) {
            previousProxy = Net.apiProxy
            previousCache = Api.cacheDir
        }
        Api.cacheDir = File(compose.activity.cacheDir, "actions-runners-caches-ui-${System.nanoTime()}").apply { mkdirs() }
        return LoopbackApi { method, path, body ->
            if (path == "/repos/octo/hello") {
                200 to """{"id":1,"name":"hello","full_name":"octo/hello","private":false,"archived":false,"permissions":{"admin":true,"push":true,"pull":true}}"""
            } else {
                handler(method, path, body)
            }
        }.also {
            servers += it
            Net.apiProxy = it.base
        }
    }

    private fun show(section: ActionsSettingsSection): Navigator {
        val nav = Navigator()
        nav.push(Screen.ActionsSettings("octo", "hello", section))
        compose.setContent {
            MobileGhTheme {
                CompositionLocalProvider(LocalNav provides nav, LocalEntry provides nav.top) {
                    ActionsSettingsScreen("octo", "hello", section)
                }
            }
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            (nav.top.bag["actions-settings-repo"] as? Loader<*>)?.data != null
        }
        return nav
    }

    private fun waitForText(text: String, substring: Boolean = false, timeoutMillis: Long = 5_000) {
        compose.waitUntil(timeoutMillis = timeoutMillis) {
            compose.onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForLoaderData(nav: Navigator, key: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            (nav.top.bag[key] as? Loader<*>)?.data != null
        }
    }

    private fun dialogFields() =
        compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(isDialog()))

    @Test fun runnersRenderMetadataFilterEmptyStateAndDownloadsWithoutVersion() {
        val server = api { _, path, _ ->
            when {
                path == "/repos/octo/hello/actions/runners/downloads" -> 200 to """[{"os":"linux","architecture":"x64","download_url":"https://example.invalid/runner.tar.gz","filename":"actions-runner-linux-x64.tar.gz","temp_download_token":"download-token-must-not-display","sha256_checksum":"abc123"}]"""
                path.startsWith("/repos/octo/hello/actions/runners?") && path.contains("name=linux+x64") -> 200 to runnerList()
                path.startsWith("/repos/octo/hello/actions/runners?") -> 200 to runnerList(runnerJson())
                else -> 404 to """{"message":"unexpected $path"}"""
            }
        }
        val nav = show(ActionsSettingsSection.Runners)
        try {
            waitForText("self-hosted-1")
            compose.onNodeWithText("self-hosted-1").assertIsDisplayed()
            compose.onNodeWithText("#42", substring = true).assertIsDisplayed()
            compose.onNodeWithText("linux · online · busy · ephemeral", substring = true).assertIsDisplayed()
            compose.onNodeWithText("self-hosted, Linux, X64", substring = true).assertIsDisplayed()
            compose.onNodeWithText("9.9.9-test-only").assertDoesNotExist()

            compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("linux x64")
            compose.onNodeWithText("筛选").performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                server.requests.any { it.contains("/actions/runners?per_page=100&page=1&name=linux+x64") }
            }
            waitForText("暂无 Runners")
            compose.onNodeWithText("暂无 Runners").assertIsDisplayed()
            compose.onNodeWithText("self-hosted-1").assertDoesNotExist()

            compose.onNodeWithText("下载 Runner").performScrollTo().performClick()
            waitForText("actions-runner-linux-x64.tar.gz")
            compose.onNodeWithText("actions-runner-linux-x64.tar.gz").assertIsDisplayed()
            compose.onNodeWithText("linux / x64", substring = true).assertIsDisplayed()
            compose.onNodeWithText("https://example.invalid/runner.tar.gz", substring = true).assertIsDisplayed()
            compose.onNodeWithText("download-token-must-not-display").assertDoesNotExist()
        } finally {
            compose.runOnUiThread { nav.dispose() }
        }
    }

    @Test fun runnerTokenCanBeCopiedIsDiscardedOnCloseAndNeverEntersSavedState() {
        val tokens = AtomicInteger(0)
        val server = api { method, path, _ ->
            when {
                method == "POST" && path.endsWith("/actions/runners/registration-token") -> {
                    val n = tokens.incrementAndGet()
                    201 to """{"token":"registration-token-$n","expires_at":"2026-01-02T03:04:05Z"}"""
                }
                path.startsWith("/repos/octo/hello/actions/runners?") -> 200 to runnerList()
                else -> 404 to """{"message":"unexpected $path"}"""
            }
        }
        val nav = show(ActionsSettingsSection.Runners)
        try {
            compose.onNodeWithText("注册 Runner").performScrollTo().performClick()
            compose.waitUntil(timeoutMillis = 10_000) {
                server.requestBodies.any { it.startsWith("POST /repos/octo/hello/actions/runners/registration-token ") }
            }
            try {
                waitForText("registration-token-1", timeoutMillis = 10_000)
            } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
                throw AssertionError(compose.onNode(isDialog(), useUnmergedTree = true).printToString(), error)
            }
            compose.onNodeWithText("registration-token-1", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("2026-01-02T03:04:05Z", substring = true, useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithContentDescription("复制注册令牌").performClick()
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            compose.waitUntil(timeoutMillis = 5_000) {
                clipboard.primaryClip?.getItemAt(0)?.text == "registration-token-1"
            }
            compose.onNodeWithText("关闭").performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithText("registration-token-1", useUnmergedTree = true).assertDoesNotExist()

            compose.onNodeWithText("注册 Runner").performScrollTo().performClick()
            compose.waitUntil(timeoutMillis = 10_000) { tokens.get() == 2 }
            waitForText("registration-token-2", timeoutMillis = 10_000)
            compose.onNodeWithText("registration-token-2", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("registration-token-1", useUnmergedTree = true).assertDoesNotExist()
            compose.runOnUiThread {
                val state = Bundle()
                InstrumentationRegistry.getInstrumentation().callActivityOnSaveInstanceState(compose.activity, state)
                assertFalse(containsText(state, "registration-token-1"))
                assertFalse(containsText(state, "registration-token-2"))
            }
        } finally {
            compose.runOnUiThread { nav.dispose() }
        }
    }

    @Test fun runnerDeleteRequiresConfirmationKeeps422ThenRefreshesEmptyList() {
        val deleted = AtomicBoolean(false)
        val deleteAttempts = AtomicInteger(0)
        val server = api { method, path, _ ->
            when {
                method == "DELETE" && path.endsWith("/actions/runners/42") -> {
                    if (deleteAttempts.incrementAndGet() == 1) {
                        422 to """{"message":"runner is busy and cannot be removed"}"""
                    } else {
                        deleted.set(true)
                        204 to ""
                    }
                }
                path.startsWith("/repos/octo/hello/actions/runners?") ->
                    200 to if (deleted.get()) runnerList() else runnerList(runnerJson())
                else -> 404 to """{"message":"unexpected $path"}"""
            }
        }
        val nav = show(ActionsSettingsSection.Runners)
        try {
            waitForText("self-hosted-1")
            compose.onNodeWithContentDescription("删除 Runner self-hosted-1").performScrollTo().performClick()
            compose.onNodeWithText("删除 Runner？").assertIsDisplayed()
            assertTrue(server.requestBodies.none { it.startsWith("DELETE ") })
            compose.onNodeWithText("删除").performClick()
            waitForText("runner is busy and cannot be removed", substring = true)
            compose.onNodeWithText("runner is busy and cannot be removed", substring = true).assertIsDisplayed()
            assertEquals(1, deleteAttempts.get())
            compose.onNode(hasText("self-hosted-1") and !hasAnyAncestor(isDialog())).assertIsDisplayed()

            compose.onNodeWithText("删除").performClick()
            compose.waitUntil(timeoutMillis = 5_000) { deleteAttempts.get() == 2 }
            waitForText("暂无 Runners")
            compose.onNodeWithText("暂无 Runners").assertIsDisplayed()
            compose.onNodeWithText("self-hosted-1").assertDoesNotExist()
            assertTrue(server.requests.count { it.startsWith("GET /repos/octo/hello/actions/runners?") } >= 2)
        } finally {
            compose.runOnUiThread { nav.dispose() }
        }
    }

    @Test fun runnerDeleteRefreshesEveryRetainedFilterBeforeItCanShowStaleData() {
        val deleted = AtomicBoolean(false)
        val server = api { method, path, _ ->
            when {
                method == "DELETE" && path.endsWith("/actions/runners/42") -> {
                    deleted.set(true)
                    204 to ""
                }
                path.startsWith("/repos/octo/hello/actions/runners?") ->
                    200 to if (deleted.get()) runnerList() else runnerList(runnerJson(name = "shared-runner"))
                else -> 404 to """{"message":"unexpected $path"}"""
            }
        }
        val nav = show(ActionsSettingsSection.Runners)
        try {
            compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("shared")
            compose.onNodeWithText("筛选").performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                server.requests.any { it.contains("/actions/runners?") && it.contains("name=shared") }
            }
            waitForLoaderData(nav, "actions-runners:shared")
            waitForText("shared-runner")
            compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("linux")
            compose.onNodeWithText("筛选").performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                server.requests.any { it.contains("/actions/runners?") && it.contains("name=linux") }
            }
            waitForLoaderData(nav, "actions-runners:linux")
            compose.waitUntil(timeoutMillis = 5_000) {
                nav.top.bag.keys.containsAll(setOf("actions-runners:shared", "actions-runners:linux"))
            }
            compose.onNodeWithContentDescription("删除 Runner shared-runner").performScrollTo().performClick()
            compose.onNodeWithText("删除").performClick()
            compose.waitUntil(timeoutMillis = 5_000) { deleted.get() }
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithText("删除 Runner？").fetchSemanticsNodes().isEmpty()
            }
            compose.waitUntil(timeoutMillis = 5_000) {
                server.requests.count { it.contains("/actions/runners?") && it.contains("name=shared") } >= 2
            }

            compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("shared")
            compose.onNodeWithText("筛选").performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                server.requests.count { it.contains("/actions/runners?") && it.contains("name=shared") } >= 2
            }
            waitForText("暂无 Runners")
            compose.onNodeWithText("shared-runner").assertDoesNotExist()
            compose.onNodeWithText("暂无 Runners").performScrollTo().assertIsDisplayed()
        } finally {
            compose.runOnUiThread { nav.dispose() }
        }
    }

    @Test fun runnersLoadEveryPage() {
        val server = api { _, path, _ ->
            when {
                path.endsWith("/actions/runners?per_page=100&page=1") ->
                    200 to runnerList(*Array(100) { runnerJson(id = it + 1L, name = "runner-${it + 1}") }, totalCount = 101)
                path.endsWith("/actions/runners?per_page=100&page=2") ->
                    200 to runnerList(runnerJson(id = 101, name = "runner-101"))
                else -> 404 to """{"message":"unexpected $path"}"""
            }
        }
        val nav = show(ActionsSettingsSection.Runners)
        try {
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodes(hasText("runner-101"))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onAllNodes(hasText("runner-101")).onFirst().performScrollTo().assertIsDisplayed()
            assertTrue(server.requests.any { it.endsWith("/actions/runners?per_page=100&page=2") })
        } finally {
            compose.runOnUiThread { nav.dispose() }
        }
    }

    @Test fun cachesShowUsageFilterEmptyStateAndKeepIndependentContent() {
        val server = api { _, path, _ ->
            when {
                path.endsWith("/actions/cache/usage") -> 200 to cacheUsage
                path.endsWith("/actions/cache/retention-limit") -> 200 to """{"max_cache_retention_days":14}"""
                path.endsWith("/actions/cache/storage-limit") -> 200 to """{"max_cache_size_gb":120}"""
                path.startsWith("/repos/octo/hello/actions/caches?") && path.contains("key=npm+cache") -> 200 to cacheList()
                path.startsWith("/repos/octo/hello/actions/caches?") -> 200 to cacheList(cacheJson())
                else -> 404 to """{"message":"unexpected $path"}"""
            }
        }
        val nav = show(ActionsSettingsSection.Caches)
        try {
            waitForText("活跃缓存：2 · 2.0 KB")
            compose.onNodeWithText("活跃缓存：2 · 2.0 KB").assertIsDisplayed()
            compose.onNodeWithText("npm-cache").assertIsDisplayed()
            compose.onNodeWithText("refs/heads/main", substring = true).assertIsDisplayed()
            compose.onNodeWithText("cache-version", substring = true).assertIsDisplayed()
            compose.onNodeWithText("1.0 KB", substring = true).assertIsDisplayed()
            compose.onNodeWithText("14 天", substring = true).assertIsDisplayed()
            compose.onNodeWithText("120 GB", substring = true).assertIsDisplayed()

            compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("npm cache")
            compose.onAllNodes(hasSetTextAction())[1].performTextReplacement("refs/heads/main")
            compose.onNodeWithText("筛选").performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                server.requests.any {
                    it.contains("key=npm+cache") && it.contains("ref=refs%2Fheads%2Fmain")
                }
            }
            waitForText("暂无 Caches")
            compose.onNodeWithText("暂无 Caches").assertIsDisplayed()
            compose.onNodeWithText("活跃缓存：2 · 2.0 KB").assertIsDisplayed()
            compose.onNodeWithText("14 天", substring = true).assertIsDisplayed()
        } finally {
            compose.runOnUiThread { nav.dispose() }
        }
    }

    @Test fun cacheDeleteByIdAccepts204AndRefreshesList() {
        val deleted = AtomicBoolean(false)
        val server = api { method, path, _ ->
            when {
                method == "DELETE" && path.endsWith("/actions/caches/42") -> {
                    deleted.set(true)
                    204 to ""
                }
                path.startsWith("/repos/octo/hello/actions/caches?") ->
                    200 to if (deleted.get()) cacheList() else cacheList(cacheJson())
                else -> cacheResponse(path)
            }
        }
        val nav = show(ActionsSettingsSection.Caches)
        try {
            waitForText("npm-cache")
            compose.onNodeWithContentDescription("删除 Cache npm-cache").performScrollTo().performClick()
            compose.onNodeWithText("删除 Cache？").assertIsDisplayed()
            assertTrue(server.requestBodies.none { it.startsWith("DELETE ") })
            compose.onNodeWithText("删除").performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                server.requestBodies.any { it.startsWith("DELETE /repos/octo/hello/actions/caches/42 ") }
            }
            waitForText("暂无 Caches")
            compose.onNodeWithText("暂无 Caches").assertIsDisplayed()
            compose.onNodeWithText("npm-cache").assertDoesNotExist()
            assertTrue(server.requests.count { it.startsWith("GET /repos/octo/hello/actions/caches?") } >= 2)
        } finally {
            compose.runOnUiThread { nav.dispose() }
        }
    }

    @Test fun cacheDeleteRefreshesEveryRetainedFilterBeforeItCanShowStaleData() {
        val deleted = AtomicBoolean(false)
        val server = api { method, path, _ ->
            when {
                method == "DELETE" && path.endsWith("/actions/caches/42") -> {
                    deleted.set(true)
                    204 to ""
                }
                path.startsWith("/repos/octo/hello/actions/caches?") ->
                    200 to if (deleted.get()) cacheList() else cacheList(cacheJson())
                else -> cacheResponse(path)
            }
        }
        val nav = show(ActionsSettingsSection.Caches)
        try {
            waitForText("npm-cache")
            compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("npm cache")
            compose.onNodeWithText("筛选").performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                server.requests.any { it.contains("/actions/caches?") && it.contains("key=npm+cache") }
            }
            waitForLoaderData(nav, "actions-caches:npm cache:")
            compose.onNodeWithContentDescription("删除 Cache npm-cache").performScrollTo().performClick()
            compose.onNodeWithText("删除").performClick()
            compose.waitUntil(timeoutMillis = 5_000) { deleted.get() }

            compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("")
            compose.onNodeWithText("筛选").performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                server.requests.count {
                    it.startsWith("GET /repos/octo/hello/actions/caches?") && !it.contains("key=")
                } >= 2
            }
            waitForText("暂无 Caches")
            compose.onNodeWithText("npm-cache").assertDoesNotExist()
            compose.onNodeWithText("暂无 Caches").performScrollTo().assertIsDisplayed()
        } finally {
            compose.runOnUiThread { nav.dispose() }
        }
    }

    @Test fun cacheDeleteByKeyUsesReturnedListInsteadOfTreatingItAs204() {
        val deleted = AtomicBoolean(false)
        val server = api { method, path, _ ->
            when {
                method == "DELETE" && path.contains("/actions/caches?key=npm+cache") -> {
                    deleted.set(true)
                    200 to cacheList(cacheJson(id = 43, key = "remaining-cache", ref = "refs/heads/dev"))
                }
                path.startsWith("/repos/octo/hello/actions/caches?") ->
                    200 to if (deleted.get()) {
                        cacheList(cacheJson(id = 43, key = "remaining-cache", ref = "refs/heads/dev"))
                    } else {
                        cacheList(cacheJson(), cacheJson(id = 43, key = "remaining-cache", ref = "refs/heads/dev"))
                    }
                else -> cacheResponse(path)
            }
        }
        val nav = show(ActionsSettingsSection.Caches)
        try {
            compose.onNodeWithText("按 key 删除").performScrollTo().performClick()
            dialogFields().assertCountEquals(2)
            dialogFields()[0].performTextReplacement("npm cache")
            dialogFields()[1].performTextReplacement("refs/heads/main")
            compose.onNodeWithText("删除").performClick()
            compose.waitUntil(timeoutMillis = 5_000) { deleted.get() }
            waitForText("remaining-cache")
            compose.onNodeWithText("npm-cache").assertDoesNotExist()
            compose.onNodeWithText("remaining-cache").performScrollTo().assertIsDisplayed()
            val request = server.requestBodies.single { it.startsWith("DELETE /repos/octo/hello/actions/caches?") }
            assertTrue(request.contains("key=npm+cache"))
            assertTrue(request.contains("ref=refs%2Fheads%2Fmain"))
        } finally {
            compose.runOnUiThread { nav.dispose() }
        }
    }

    @Test fun cacheDeleteByKeyPreservesCompleteKeyAndRef() {
        val deleted = AtomicBoolean(false)
        val server = api { method, path, _ ->
            when {
                method == "DELETE" && path.startsWith("/repos/octo/hello/actions/caches?") -> {
                    deleted.set(true)
                    200 to ""
                }
                path.startsWith("/repos/octo/hello/actions/caches?") ->
                    200 to if (deleted.get()) cacheList() else cacheList(cacheJson())
                else -> cacheResponse(path)
            }
        }
        val nav = show(ActionsSettingsSection.Caches)
        try {
            waitForText("npm-cache")
            compose.onNodeWithText("按 key 删除").performScrollTo().performClick()
            dialogFields()[0].performTextReplacement(" npm cache ")
            dialogFields()[1].performTextReplacement(" refs/heads/main ")
            compose.onNodeWithText("删除").performClick()
            compose.waitUntil(timeoutMillis = 5_000) { deleted.get() }
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty()
            }
            val request = server.requestBodies.single { it.startsWith("DELETE /repos/octo/hello/actions/caches?") }
            assertTrue("complete key/ref must not be trimmed: $request",
                request.startsWith("DELETE /repos/octo/hello/actions/caches?key=+npm+cache+&ref=+refs%2Fheads%2Fmain+ "))
        } finally {
            compose.runOnUiThread { nav.dispose() }
        }
    }

    @Test fun cacheDeleteByKeyAcceptsEmptySuccessAndRefreshesListAndUsageOnce() {
        val deleted = AtomicBoolean(false)
        val deleteAttempts = AtomicInteger(0)
        val server = api { method, path, _ ->
            when {
                method == "DELETE" && path.contains("/actions/caches?key=npm+cache") -> {
                    deleteAttempts.incrementAndGet()
                    deleted.set(true)
                    200 to ""
                }
                path.endsWith("/actions/cache/usage") -> 200 to if (deleted.get()) {
                    """{"full_name":"octo/hello","active_caches_size_in_bytes":0,"active_caches_count":0}"""
                } else {
                    cacheUsage
                }
                path.startsWith("/repos/octo/hello/actions/caches?") ->
                    200 to if (deleted.get()) cacheList() else cacheList(cacheJson())
                else -> cacheResponse(path)
            }
        }
        val nav = show(ActionsSettingsSection.Caches)
        try {
            waitForText("npm-cache")
            compose.onNodeWithText("按 key 删除").performScrollTo().performClick()
            dialogFields()[0].performTextReplacement("npm cache")
            compose.onNodeWithText("删除").performClick()
            waitForText("活跃缓存：0 · 0 B")
            compose.onNodeWithText("按 key 删除").performScrollTo()
            compose.onAllNodes(isDialog()).assertCountEquals(0)
            compose.onNodeWithText("删除 Cache 返回无效响应").assertDoesNotExist()
            compose.onNodeWithText("npm-cache").assertDoesNotExist()
            compose.waitUntil(timeoutMillis = 5_000) {
                server.requests.count { it.startsWith("GET /repos/octo/hello/actions/caches?") } >= 2
            }
            assertEquals(1, deleteAttempts.get())
        } finally {
            compose.runOnUiThread { nav.dispose() }
        }
    }

    @Test fun cacheDeleteByKeyRefreshesTheCurrentFilter() {
        val deleted = AtomicBoolean(false)
        val server = api { method, path, _ ->
            when {
                method == "DELETE" && path.contains("/actions/caches?key=npm+cache") -> {
                    deleted.set(true)
                    200 to cacheList(cacheJson(id = 43, key = "wrong-filter-result", ref = "refs/heads/main"))
                }
                path.startsWith("/repos/octo/hello/actions/caches?") && path.contains("key=dev+cache") ->
                    200 to cacheList(cacheJson(id = 44, key = "dev-cache", ref = "refs/heads/dev"))
                else -> cacheResponse(path)
            }
        }
        val nav = show(ActionsSettingsSection.Caches)
        try {
            waitForText("npm-cache")
            compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("dev cache")
            compose.onAllNodes(hasSetTextAction())[1].performTextReplacement("refs/heads/dev")
            compose.onNodeWithText("筛选").performClick()
            waitForText("dev-cache")

            compose.onNodeWithText("按 key 删除").performScrollTo().performClick()
            dialogFields()[0].performTextReplacement("npm cache")
            dialogFields()[1].performTextReplacement("refs/heads/main")
            compose.onNodeWithText("删除").performClick()
            compose.waitUntil(timeoutMillis = 5_000) { deleted.get() }
            compose.waitUntil(timeoutMillis = 5_000) {
                server.requests.count {
                    it.startsWith("GET /repos/octo/hello/actions/caches?") && it.contains("key=dev+cache")
                } >= 2
            }
            compose.onNodeWithText("dev-cache").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("wrong-filter-result").assertDoesNotExist()
        } finally {
            compose.runOnUiThread { nav.dispose() }
        }
    }

    @Test fun cacheDeletionRefreshesUsageAfterIdAndKeyDeletes() {
        val deletedById = AtomicBoolean(false)
        val deletedByKey = AtomicBoolean(false)
        api { method, path, _ ->
            when {
                method == "DELETE" && path.endsWith("/actions/caches/42") -> {
                    deletedById.set(true)
                    204 to ""
                }
                method == "DELETE" && path.contains("/actions/caches?key=remaining+cache") -> {
                    deletedByKey.set(true)
                    200 to cacheList()
                }
                path.endsWith("/actions/cache/usage") -> 200 to when {
                    deletedByKey.get() -> """{"full_name":"octo/hello","active_caches_size_in_bytes":0,"active_caches_count":0}"""
                    deletedById.get() -> """{"full_name":"octo/hello","active_caches_size_in_bytes":1024,"active_caches_count":1}"""
                    else -> cacheUsage
                }
                path.startsWith("/repos/octo/hello/actions/caches?") -> 200 to when {
                    deletedByKey.get() -> cacheList()
                    deletedById.get() -> cacheList(cacheJson(id = 43, key = "remaining-cache"))
                    else -> cacheList(cacheJson())
                }
                else -> cacheResponse(path)
            }
        }
        val nav = show(ActionsSettingsSection.Caches)
        try {
            waitForText("活跃缓存：2 · 2.0 KB")
            compose.onNodeWithContentDescription("删除 Cache npm-cache").performScrollTo().performClick()
            compose.onNodeWithText("删除").performClick()
            waitForText("活跃缓存：1 · 1.0 KB")

            compose.onNodeWithText("按 key 删除").performScrollTo().performClick()
            dialogFields()[0].performTextReplacement("remaining cache")
            compose.onNodeWithText("删除").performClick()
            waitForText("活跃缓存：0 · 0 B")
        } finally {
            compose.runOnUiThread { nav.dispose() }
        }
    }

    @Test fun cacheLimitsValidatePositiveIntegersAndUseIndependentFields() {
        val server = api { method, path, _ ->
            if (method == "PUT") 204 to "" else cacheResponse(path)
        }
        val nav = show(ActionsSettingsSection.Caches)
        try {
            waitForText("14 天", substring = true)
            compose.onNodeWithText("14 天", substring = true).assertIsDisplayed()
            compose.onNodeWithContentDescription("编辑缓存保留").performScrollTo().performClick()
            listOf("", "abc", "0", "-2").forEach { invalid ->
                dialogFields()[0].performTextReplacement(invalid)
                compose.onNodeWithText("保存").performClick()
                compose.onNodeWithText("请输入正整数").assertIsDisplayed()
            }
            assertTrue(server.requestBodies.none { it.startsWith("PUT ") })
            dialogFields()[0].performTextReplacement("30")
            compose.onNodeWithText("保存").performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                server.requestBodies.any { it.startsWith("PUT /repos/octo/hello/actions/cache/retention-limit ") }
            }
            val retention = server.requestBodies.single {
                it.startsWith("PUT /repos/octo/hello/actions/cache/retention-limit ")
            }
            val retentionBody = Json.parseToJsonElement(retention.substring(retention.indexOf('{'))).jsonObject
            assertEquals(setOf("max_cache_retention_days"), retentionBody.keys)
            assertEquals("30", retentionBody.getValue("max_cache_retention_days").jsonPrimitive.content)
            waitForText("30 天", substring = true)
            compose.onNodeWithText("30 天", substring = true).performScrollTo().assertIsDisplayed()

            compose.onNodeWithContentDescription("编辑缓存大小").performScrollTo().performClick()
            dialogFields()[0].performTextReplacement("150")
            compose.onNodeWithText("保存").performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                server.requestBodies.any { it.startsWith("PUT /repos/octo/hello/actions/cache/storage-limit ") }
            }
            val storage = server.requestBodies.single {
                it.startsWith("PUT /repos/octo/hello/actions/cache/storage-limit ")
            }
            val storageBody = Json.parseToJsonElement(storage.substring(storage.indexOf('{'))).jsonObject
            assertEquals(setOf("max_cache_size_gb"), storageBody.keys)
            assertEquals("150", storageBody.getValue("max_cache_size_gb").jsonPrimitive.content)
            waitForText("150 GB", substring = true)
            compose.onNodeWithText("150 GB", substring = true).performScrollTo().assertIsDisplayed()
        } finally {
            compose.runOnUiThread { nav.dispose() }
        }
    }

    @Test fun cachesLoadEveryPage() {
        val server = api { _, path, _ ->
            when {
                path.endsWith("/actions/caches?per_page=100&page=1") ->
                    200 to cacheList(*Array(100) { cacheJson(id = it + 1L, key = "cache-${it + 1}") }, totalCount = 101)
                path.endsWith("/actions/caches?per_page=100&page=2") ->
                    200 to cacheList(cacheJson(id = 101, key = "cache-101"))
                else -> cacheResponse(path)
            }
        }
        val nav = show(ActionsSettingsSection.Caches)
        try {
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodes(hasText("cache-101"))
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
            }
            compose.onAllNodes(hasText("cache-101")).onFirst().performScrollTo().assertIsDisplayed()
            assertTrue(server.requests.any { it.endsWith("/actions/caches?per_page=100&page=2") })
        } finally {
            compose.runOnUiThread { nav.dispose() }
        }
    }

    private fun cacheResponse(path: String): Pair<Int, String> = when {
        path.endsWith("/actions/cache/usage") -> 200 to cacheUsage
        path.endsWith("/actions/cache/retention-limit") -> 200 to """{"max_cache_retention_days":14}"""
        path.endsWith("/actions/cache/storage-limit") -> 200 to """{"max_cache_size_gb":120}"""
        path.startsWith("/repos/octo/hello/actions/caches?") -> 200 to cacheList(cacheJson())
        else -> 404 to """{"message":"unexpected $path"}"""
    }

    private fun runnerJson(id: Long = 42, name: String = "self-hosted-1") =
        """{"id":$id,"runner_group_id":1,"name":"$name","os":"linux","status":"online","busy":true,"labels":[{"id":1,"name":"self-hosted","type":"read-only"},{"id":2,"name":"Linux","type":"read-only"},{"id":3,"name":"X64","type":"read-only"}],"ephemeral":true,"version":"9.9.9-test-only"}"""

    private fun runnerList(vararg runners: String, totalCount: Int = runners.size) =
        """{"total_count":$totalCount,"runners":[${runners.joinToString(",")}]}"""

    private fun cacheJson(id: Long = 42, key: String = "npm-cache", ref: String = "refs/heads/main") =
        """{"id":$id,"ref":"$ref","key":"$key","version":"cache-version","last_accessed_at":"2026-01-02T03:04:05Z","created_at":"2026-01-01T00:00:00Z","size_in_bytes":1024}"""

    private fun cacheList(vararg caches: String, totalCount: Int = caches.size) =
        """{"total_count":$totalCount,"actions_caches":[${caches.joinToString(",")}]}"""

    private fun containsText(value: Any?, needle: String): Boolean = when (value) {
        null -> false
        is String -> value.contains(needle)
        is Bundle -> value.keySet().any { containsText(value.get(it), needle) }
        is Array<*> -> value.any { containsText(it, needle) }
        is Iterable<*> -> value.any { containsText(it, needle) }
        else -> false
    }

    private companion object {
        const val cacheUsage = """{"full_name":"octo/hello","active_caches_size_in_bytes":2048,"active_caches_count":2}"""
    }
}
