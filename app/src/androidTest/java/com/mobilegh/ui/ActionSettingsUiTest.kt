package com.mobilegh.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilegh.data.Api
import com.mobilegh.data.Net
import com.mobilegh.data.Repo
import com.mobilegh.data.Session
import com.mobilegh.data.User
import com.mobilegh.nav.ActionsSettingsSection
import com.mobilegh.nav.Loader
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Navigator
import com.mobilegh.nav.Screen
import com.mobilegh.ui.screens.ActionsScreen
import com.mobilegh.ui.screens.ActionsSettingsScreen
import com.mobilegh.ui.theme.MobileGhTheme
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActionSettingsUiTest {
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
            if (Session.login == "mobilegh-ui-test") Session.signOut()
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
        Api.cacheDir = File(compose.activity.cacheDir, "actions-settings-ui").apply { mkdirs() }
        return LoopbackApi(handler).also {
            servers += it
            Net.apiProxy = it.base
        }
    }

    private fun showActions(repoJson: String): Navigator {
        api { _, path, _ ->
            when {
                path == "/repos/octo/hello" -> 200 to repoJson
                path.startsWith("/repos/octo/hello/actions/workflows") -> 200 to """{"total_count":0,"workflows":[]}"""
                path.startsWith("/repos/octo/hello/actions/runs") -> 200 to """{"total_count":0,"workflow_runs":[]}"""
                else -> 404 to """{"message":"unexpected $path"}"""
            }
        }
        val nav = Navigator()
        nav.push(Screen.Actions("octo", "hello"))
        compose.setContent {
            MobileGhTheme {
                CompositionLocalProvider(LocalNav provides nav, LocalEntry provides nav.top) {
                    ActionsScreen("octo", "hello")
                }
            }
        }
        return nav
    }

    private fun awaitLoader(nav: Navigator, key: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            (nav.top.bag[key] as? Loader<*>)?.data != null
        }
    }

    private fun awaitRepoLoaded(nav: Navigator) = awaitLoader(nav, "repo")

    private fun awaitText(text: String, substring: Boolean = false) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun showSettings(): Navigator {
        val nav = Navigator()
        nav.push(Screen.ActionsSettings("octo", "hello", ActionsSettingsSection.General))
        compose.setContent {
            MobileGhTheme {
                CompositionLocalProvider(LocalNav provides nav, LocalEntry provides nav.top) {
                    ActionsSettingsScreen("octo", "hello", ActionsSettingsSection.General)
                }
            }
        }
        return nav
    }

    @Test fun settingsEntryRequiresAdminAndAnActiveRepository() {
        val admin = showActions(repoJson(admin = true, archived = false))
        awaitRepoLoaded(admin)
        compose.onNodeWithContentDescription("Actions 设置").assertIsDisplayed().performClick()
        assertEquals(
            Screen.ActionsSettings("octo", "hello", ActionsSettingsSection.General),
            admin.top.screen,
        )
        compose.runOnUiThread { admin.dispose() }
    }

    @Test fun readOnlyRepositoryHidesActionsSettings() {
        val readOnly = showActions(repoJson(admin = false, archived = false))
        awaitRepoLoaded(readOnly)
        compose.onNodeWithContentDescription("Actions 设置").assertIsNotDisplayed()
        compose.runOnUiThread { readOnly.dispose() }
    }

    @Test fun archivedRepositoryHidesActionsSettings() {
        val archived = showActions(repoJson(admin = true, archived = true))
        awaitRepoLoaded(archived)
        compose.onNodeWithContentDescription("Actions 设置").assertIsNotDisplayed()
        compose.runOnUiThread { archived.dispose() }
    }

    @Test fun directRestoredSettingsRejectReadOnlyRepository() {
        val server = api { _, path, _ ->
            if (path == "/repos/octo/hello") {
                200 to repoJson(admin = false, archived = false)
            } else {
                generalResponse(path)
            }
        }
        val nav = showSettings()
        try {
            awaitLoader(nav, "actions-settings-repo")
            compose.onNodeWithText("需要仓库管理员权限，且仓库不能处于归档状态。").assertIsDisplayed()
            compose.onNodeWithText("编辑 Actions").assertIsNotDisplayed()
            assertTrue(server.requests.none { it.contains("/actions/permissions") })
        } finally {
            compose.runOnUiThread { nav.dispose() }
        }
    }

    @Test fun directRestoredSettingsRejectArchivedRepository() {
        val server = api { _, path, _ ->
            if (path == "/repos/octo/hello") {
                200 to repoJson(admin = true, archived = true)
            } else {
                generalResponse(path)
            }
        }
        val nav = showSettings()
        try {
            awaitLoader(nav, "actions-settings-repo")
            compose.onNodeWithText("需要仓库管理员权限，且仓库不能处于归档状态。").assertIsDisplayed()
            compose.onNodeWithText("编辑 Actions").assertIsNotDisplayed()
            assertTrue(server.requests.none { it.contains("/actions/permissions") })
        } finally {
            compose.runOnUiThread { nav.dispose() }
        }
    }

    @Test fun generalSettingsKeepSuccessfulSectionsWhenOneRequestFails() {
        api { _, path, _ -> generalResponse(path, failingForkApproval = true) }
        val nav = showSettings()
        try {
            awaitLoader(nav, "actions-settings-repo")
            awaitLoader(nav, "actions-retention")
            awaitLoader(nav, "actions-workflow")
            awaitText("fork policy unavailable", substring = true)
            compose.onNodeWithText("Actions 设置").assertIsDisplayed()
            compose.onNodeWithText("通用权限").assertIsDisplayed()
            compose.onNodeWithText("90", substring = true).assertIsDisplayed()
            compose.onNodeWithText("fork policy unavailable", substring = true).assertIsDisplayed()
            compose.onNodeWithText("读取", substring = true).assertIsDisplayed()
        } finally {
            compose.runOnUiThread { nav.dispose() }
        }
    }

    @Test fun generalSettingsExposeLoadingBeforeResponsesArrive() {
        val release = CountDownLatch(1)
        api { _, path, _ ->
            release.await(5, TimeUnit.SECONDS)
            generalResponse(path)
        }
        val nav = showSettings()
        try {
            compose.onNodeWithText("加载中…").assertIsDisplayed()
            release.countDown()
            compose.waitUntil(timeoutMillis = 5_000) {
                (nav.top.bag["actions-retention"] as? Loader<*>)?.data != null
            }
            compose.onNodeWithText("90", substring = true).assertIsDisplayed()
        } finally {
            release.countDown()
            compose.runOnUiThread { nav.dispose() }
        }
    }

    @Test fun publicOnlyPatternsWaitForKnownRepositoryVisibility() {
        val releaseRepo = CountDownLatch(1)
        val repoRequests = AtomicInteger()
        api { _, path, _ ->
            if (path == "/repos/octo/hello" && repoRequests.incrementAndGet() > 1) {
                releaseRepo.await(5, TimeUnit.SECONDS)
            }
            generalResponse(path, isPrivate = true)
        }
        val nav = showSettings()
        try {
            awaitLoader(nav, "actions-selected")
            compose.onNodeWithText("octo/*").assertIsNotDisplayed()
            compose.onNodeWithText("编辑允许范围").performScrollTo().performClick()
            assertTrue(compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty())
        } finally {
            releaseRepo.countDown()
            awaitLoader(nav, "repo")
            compose.runOnUiThread { nav.dispose() }
        }
    }

    @Test fun publicGeneralSettingsKeepPrivateEndpointsOffAndSaveSelectedActions() {
        val server = api { method, path, _ ->
            if (method == "PUT") 204 to "" else generalResponse(path)
        }
        val nav = showSettings()
        try {
            awaitLoader(nav, "repo")
            awaitLoader(nav, "actions-selected")
            compose.onNodeWithText("octo/*").assertIsDisplayed()
            assertTrue(server.requests.none { it.contains("/actions/permissions/access") })
            assertTrue(server.requests.none { it.contains("fork-pr-workflows-private-repos") })

            compose.onNodeWithText("编辑允许范围").performScrollTo().performClick()
            compose.onNodeWithText("允许 Verified 创建者的 Actions").performClick()
            compose.onAllNodes(hasSetTextAction()).onLast().performTextReplacement("octo/*\nactions/checkout@*")
            compose.onNodeWithText("保存").performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                server.requestBodies.any { it.startsWith("PUT /repos/octo/hello/actions/permissions/selected-actions ") }
            }
            val body = server.requestBodies.last {
                it.startsWith("PUT /repos/octo/hello/actions/permissions/selected-actions ")
            }
            assertTrue(body.contains("\"verified_allowed\":true"))
            assertTrue(body.contains("actions/checkout@*"))
            assertTrue(server.requests.none { it.contains("/actions/permissions/access") })
            assertTrue(server.requests.none { it.contains("fork-pr-workflows-private-repos") })
        } finally {
            compose.runOnUiThread { nav.dispose() }
        }
    }

    @Test fun privateGeneralSettingsLoadAndEditPrivateOnlyEndpoints() {
        val server = api { method, path, _ ->
            if (method == "PUT") 204 to "" else generalResponse(path, isPrivate = true)
        }
        val nav = showSettings()
        try {
            awaitLoader(nav, "actions-access")
            awaitLoader(nav, "actions-private-forks")
            awaitLoader(nav, "actions-selected")
            compose.onNodeWithText("organization").assertIsDisplayed()
            compose.onNodeWithText("octo/*").assertIsNotDisplayed()

            compose.onNodeWithText("编辑访问").performScrollTo().performClick()
            compose.onAllNodesWithText("organization").onLast().performClick()
            compose.onNodeWithText("user").performClick()
            compose.onNodeWithText("保存").performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                server.requestBodies.any { it.startsWith("PUT /repos/octo/hello/actions/permissions/access ") }
            }
            assertTrue(server.requestBodies.last {
                it.startsWith("PUT /repos/octo/hello/actions/permissions/access ")
            }.contains("\"access_level\":\"user\""))

            compose.onNodeWithText("编辑私有 Fork").performScrollTo().performClick()
            compose.onNodeWithText("向 Fork Workflow 发送写入令牌").performClick()
            compose.onNodeWithText("保存").performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                server.requestBodies.any {
                    it.startsWith("PUT ") && it.contains("fork-pr-workflows-private-repos")
                }
            }
            assertTrue(server.requestBodies.last {
                it.startsWith("PUT ") && it.contains("fork-pr-workflows-private-repos")
            }.contains("\"send_write_tokens_to_workflows\":true"))
        } finally {
            compose.runOnUiThread { nav.dispose() }
        }
    }

    @Test fun permissionsAndForkApprovalEditorsWriteTheirOwnEndpoints() {
        val server = api { method, path, _ ->
            if (method == "PUT") 204 to "" else generalResponse(path)
        }
        val nav = showSettings()
        try {
            awaitLoader(nav, "actions-permissions")
            awaitLoader(nav, "actions-fork-approval")
            compose.onNodeWithText("编辑 Actions").performScrollTo().performClick()
            compose.onNodeWithText("要求 SHA 固定").performClick()
            compose.onNodeWithText("保存").performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                server.requestBodies.any {
                    it.startsWith("PUT /repos/octo/hello/actions/permissions ") &&
                        it.contains("\"sha_pinning_required\":false")
                }
            }

            compose.onNodeWithText("编辑 Fork 审批").performScrollTo().performClick()
            compose.onAllNodesWithText("first_time_contributors").onLast().performClick()
            compose.onNodeWithText("all_external_contributors").performClick()
            compose.onNodeWithText("保存").performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                server.requestBodies.any {
                    it.contains("fork-pr-contributor-approval") &&
                        it.contains("\"approval_policy\":\"all_external_contributors\"")
                }
            }
        } finally {
            compose.runOnUiThread { nav.dispose() }
        }
    }

    @Test fun retentionRejectsInvalidAndAboveMaximumBeforeSaving() {
        val server = api { method, path, _ ->
            if (method == "PUT") 204 to "" else generalResponse(path)
        }
        val nav = showSettings()
        try {
            awaitLoader(nav, "actions-retention")
            compose.onNodeWithText("编辑保留").performScrollTo().performClick()
            compose.onAllNodes(hasSetTextAction()).onLast().performTextReplacement("401")
            compose.onNodeWithText("保存").performClick()
            compose.onNodeWithText("不能超过 400 天", substring = true).assertIsDisplayed()

            compose.onAllNodes(hasSetTextAction()).onLast().performTextReplacement("0")
            compose.onNodeWithText("保存").performClick()
            compose.onNodeWithText("请输入正整数天数").assertIsDisplayed()
            compose.onAllNodes(hasSetTextAction()).onLast().performTextReplacement("abc")
            compose.onNodeWithText("保存").performClick()
            compose.onNodeWithText("请输入正整数天数").assertIsDisplayed()
            compose.onAllNodes(hasSetTextAction()).onLast().performTextReplacement("")
            compose.onNodeWithText("保存").performClick()
            compose.onNodeWithText("请输入正整数天数").assertIsDisplayed()
            assertTrue(server.requestBodies.none {
                it.startsWith("PUT ") && it.contains("artifact-and-log-retention")
            })

            compose.onNodeWithText("保存").assertIsDisplayed()
            compose.onAllNodesWithText("请输入正整数天数").onLast().assertIsDisplayed()
            compose.onNodeWithText("保留天数").assertIsDisplayed()
            compose.onAllNodes(hasSetTextAction()).onLast().performTextReplacement("120")
            compose.onNodeWithText("保存").performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                server.requestBodies.any {
                    it.contains("artifact-and-log-retention") && it.contains("\"days\":120")
                }
            }
        } finally {
            compose.runOnUiThread { nav.dispose() }
        }
    }

    @Test fun workflowConflictKeepsEditedInputAndServerError() {
        val server = api { method, path, _ ->
            if (method == "PUT" && path.endsWith("/actions/permissions/workflow")) {
                409 to """{"message":"blocked by organization"}"""
            } else {
                generalResponse(path)
            }
        }
        val nav = showSettings()
        try {
            awaitLoader(nav, "actions-workflow")
            compose.onNodeWithText("编辑 Workflow").performScrollTo().performClick()
            compose.onNodeWithText("read").performClick()
            compose.onNodeWithText("write").performClick()
            compose.onNodeWithText("保存").performClick()
            awaitText("blocked by organization", substring = true)
            compose.onNodeWithText("blocked by organization", substring = true).assertIsDisplayed()
            compose.onNodeWithText("write").assertIsDisplayed()
            compose.onNodeWithText("保存").assertIsDisplayed()
            compose.onNodeWithText("已保存").assertIsNotDisplayed()
            assertTrue(server.requestBodies.single {
                it.startsWith("PUT ") && it.contains("/actions/permissions/workflow")
            }.contains("\"default_workflow_permissions\":\"write\""))
        } finally {
            compose.runOnUiThread { nav.dispose() }
        }
    }

    private fun generalResponse(
        path: String,
        failingForkApproval: Boolean = false,
        isPrivate: Boolean = false,
    ): Pair<Int, String> = when {
        path == "/repos/octo/hello" -> 200 to repoJson(
            admin = true,
            archived = false,
            isPrivate = isPrivate,
        )
        path.endsWith("/actions/permissions") -> 200 to """{"enabled":true,"allowed_actions":"selected","sha_pinning_required":true}"""
        path.endsWith("/actions/permissions/access") -> 200 to """{"access_level":"organization"}"""
        path.endsWith("/actions/permissions/artifact-and-log-retention") -> 200 to """{"days":90,"maximum_allowed_days":400}"""
        path.endsWith("/actions/permissions/fork-pr-contributor-approval") && failingForkApproval -> 500 to """{"message":"fork policy unavailable"}"""
        path.endsWith("/actions/permissions/fork-pr-contributor-approval") -> 200 to """{"approval_policy":"first_time_contributors"}"""
        path.endsWith("/actions/permissions/fork-pr-workflows-private-repos") -> 200 to """{"run_workflows_from_fork_pull_requests":true,"send_write_tokens_to_workflows":false,"send_secrets_and_variables":false,"require_approval_for_fork_pr_workflows":true}"""
        path.endsWith("/actions/permissions/selected-actions") -> 200 to """{"github_owned_allowed":true,"verified_allowed":false,"patterns_allowed":["octo/*"]}"""
        path.endsWith("/actions/permissions/workflow") -> 200 to """{"default_workflow_permissions":"read","can_approve_pull_request_reviews":false}"""
        else -> 404 to """{"message":"unexpected $path"}"""
    }

    private fun repoJson(
        admin: Boolean,
        archived: Boolean,
        isPrivate: Boolean = false,
    ) = """
        {"id":1,"name":"hello","full_name":"octo/hello","private":$isPrivate,
         "archived":$archived,"permissions":{"admin":$admin,"push":true,"pull":true}}
    """.trimIndent()
}
