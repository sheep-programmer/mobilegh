package com.mobilegh.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilegh.data.Api
import com.mobilegh.data.ImportDestination
import com.mobilegh.data.ImportDestinationRequest
import com.mobilegh.data.ImportPhase
import com.mobilegh.data.ImportSource
import com.mobilegh.data.ImportSourceCredentials
import com.mobilegh.data.ImportTask
import com.mobilegh.data.ImportTasks
import com.mobilegh.data.Net
import com.mobilegh.data.Session
import com.mobilegh.data.User
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Navigator
import com.mobilegh.nav.Screen
import com.mobilegh.service.ImportUiActions
import com.mobilegh.ui.screens.CreateRepoScreen
import com.mobilegh.ui.screens.ImportRepoScreen
import com.mobilegh.ui.screens.ReposTab
import com.mobilegh.ui.theme.MobileGhTheme
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportRepoUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val servers = mutableListOf<LoopbackApi>()
    private var previousProxy = ""
    private var previousCache: File? = null
    private lateinit var root: File
    private lateinit var tasks: ImportTasks
    private lateinit var actions: RecordingImportActions
    private var nextId = 0

    @Before fun setUp() {
        root = Files.createTempDirectory("import-ui").toFile()
        tasks = ImportTasks(root, ids = { "task-${++nextId}" })
        actions = RecordingImportActions(tasks)
        compose.runOnUiThread {
            Session.signIn("local-fixture-token", User(login = "octo"))
        }
    }

    @After fun tearDown() {
        compose.runOnUiThread {
            if (Session.login == "octo") Session.signOut()
            Net.apiProxy = previousProxy
            previousCache?.let { Api.cacheDir = it }
        }
        servers.forEach { it.close() }
        root.deleteRecursively()
    }

    private fun api(handler: (String, String, String) -> Pair<Int, String>): LoopbackApi {
        if (servers.isEmpty()) {
            previousProxy = Net.apiProxy
            previousCache = Api.cacheDir
        }
        Api.cacheDir = File(compose.activity.cacheDir, "import-ui-${System.nanoTime()}").apply { mkdirs() }
        return LoopbackApi(handler).also {
            servers += it
            Net.apiProxy = it.base
        }
    }

    private fun show(
        content: @androidx.compose.runtime.Composable () -> Unit = {
            ImportRepoScreen(tasks = tasks, actions = actions)
        },
    ): Navigator {
        val nav = Navigator()
        compose.setContent {
            MobileGhTheme {
                CompositionLocalProvider(LocalNav provides nav, LocalEntry provides nav.top) {
                    content()
                }
            }
        }
        return nav
    }

    private fun field(label: String, value: String) {
        compose.onNodeWithContentDescription(label).performScrollTo().performTextReplacement(value)
    }

    private fun waitForText(text: String, substring: Boolean = false) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun repositoryListAndCreateRepositoryBothOpenNativeImport() {
        api { _, path, _ ->
            when {
                path.startsWith("/user/repos") -> 200 to "[]"
                path.startsWith("/user/orgs") -> 200 to "[]"
                path.startsWith("/gitignore/templates") -> 200 to "[]"
                path.startsWith("/licenses") -> 200 to "[]"
                else -> 200 to """{"orgs":[],"warnings":[]}"""
            }
        }
        val fromRepos = show { ReposTab() }
        compose.onNodeWithContentDescription("导入仓库").performClick()
        assertEquals(Screen.ImportRepo, fromRepos.top.screen)
        compose.runOnUiThread { fromRepos.dispose() }

        // ComposeTestRule 的 setContent 只能设置当前 Activity 的初始内容；换新的 Activity 验证第二个入口。
        compose.activityRule.scenario.recreate()
        val fromCreate = show { CreateRepoScreen() }
        compose.onNodeWithText("导入已有仓库").performScrollTo().performClick()
        assertEquals(Screen.ImportRepo, fromCreate.top.screen)
        compose.runOnUiThread { fromCreate.dispose() }
    }

    @Test fun privateSourceSecretsAreHandedOnlyToLauncherAndNeverSaved() {
        val server = api { method, path, body ->
            when {
                method == "POST" && path == "/user/repos" -> {
                    assertTrue(body.contains("\"auto_init\":false"))
                    201 to repoJson()
                }
                path == "/user/orgs" -> 200 to "[]"
                else -> 404 to """{"message":"unexpected $method $path"}"""
            }
        }
        val nav = show()
        try {
            compose.onNodeWithText("仅迁移 Git 提交历史、分支和标签", substring = true).assertIsDisplayed()
            field("源 Git URL", "https://source.example/team/original.git#readme")
            compose.onNodeWithText("私有源仓库").performScrollTo().performClick()
            field("源用户名", "source-user")
            field("源密码或访问令牌", "/token")
            compose.onNodeWithText("开始导入").performScrollTo().performClick()

            compose.waitUntil(timeoutMillis = 5_000) { actions.starts.size == 1 }
            val (id, credentials) = actions.starts.single()
            assertEquals(ImportSourceCredentials("source-user", "/token"), credentials)
            val task = tasks.get(id)!!
            assertEquals("https://source.example/team/original.git", task.sourceUrl)
            assertEquals("original", task.name)
            assertTrue(task.sourceRequiresCredentials)
            assertEquals(ImportPhase.Cloning, task.phase)
            assertFalse(File(root, "tasks/$id.json").readText().contains("/token"))
            assertTrue(server.requestBodies.single { it.startsWith("POST ") }.contains("\"name\":\"original\""))

            val state = Bundle()
            compose.runOnUiThread {
                InstrumentationRegistry.getInstrumentation().callActivityOnSaveInstanceState(compose.activity, state)
            }
            assertFalse(containsText(state, "/token"))
            assertFalse(containsText(state, "source-user"))
        } finally {
            compose.runOnUiThread { nav.dispose() }
        }
    }

    @Test fun insecureHttpRequiresConfirmationBeforeDestinationCreation() {
        val server = api { method, path, _ ->
            when {
                method == "POST" && path == "/user/repos" -> 201 to repoJson()
                path == "/user/orgs" -> 200 to "[]"
                else -> 404 to """{"message":"unexpected $method $path"}"""
            }
        }
        val nav = show()
        try {
            field("源 Git URL", "http://source.example/team/plain.git")
            compose.onNodeWithText("开始导入").performScrollTo().performClick()
            compose.onNodeWithText("HTTP 连接不安全").assertIsDisplayed()
            compose.onNodeWithText("HTTP 会以明文传输 Git 内容和凭据。确定继续吗？").assertIsDisplayed()
            assertTrue(server.requests.none { it.startsWith("POST ") })
            assertTrue(actions.starts.isEmpty())

            compose.onNodeWithText("继续导入").performClick()
            compose.waitUntil(timeoutMillis = 5_000) { actions.starts.size == 1 }
            assertTrue(tasks.get(actions.starts.single().first)!!.sourceUrl.startsWith("http://"))
        } finally {
            compose.runOnUiThread { nav.dispose() }
        }
    }

    @Test fun interruptedPrivateSourceRequiresNewCredentialsAndAccountIsolation() {
        val task = createTask(ImportPhase.Interrupted, requiresCredentials = true)
        val nav = show()
        try {
            compose.onNodeWithText("需要重新输入源仓库凭据").performScrollTo().assertIsDisplayed()
            compose.onAllNodesWithText("重试").onFirst().performScrollTo().performClick()
            assertTrue(actions.starts.isEmpty())
            field("源密码或访问令牌", "/token")
            compose.onAllNodesWithText("继续导入").onFirst().performClick()
            compose.waitUntil(timeoutMillis = 5_000) { actions.starts.size == 1 }
            assertEquals(task.id, actions.starts.single().first)
            assertEquals("/token", actions.starts.single().second?.password)

            compose.runOnUiThread {
                Session.signIn("other-token", User(login = "other"))
            }
            waitForText("没有导入任务")
            assertTrue(actions.starts.size == 1)
            compose.onAllNodesWithText("重试").fetchSemanticsNodes().also { assertTrue(it.isEmpty()) }
        } finally {
            compose.runOnUiThread {
                if (Session.login == "other") Session.signOut()
                nav.dispose()
            }
        }
    }

    @Test fun creationUnknownWithoutAJobCanRetry() {
        val task = createTask(ImportPhase.CreationUnknown)
        val nav = show()
        try {
            compose.onAllNodesWithText("取消导入").fetchSemanticsNodes().also { assertTrue(it.isEmpty()) }
            compose.onNodeWithText("重试").performScrollTo().performClick()
            compose.waitUntil(timeoutMillis = 5_000) { actions.starts.size == 1 }
            assertEquals(task.id, actions.starts.single().first)
        } finally {
            compose.runOnUiThread { nav.dispose() }
        }
    }

    @Test fun cancelledPrivateSourceRequiresNewCredentials() {
        val task = createTask(ImportPhase.Cancelled, requiresCredentials = true)
        val nav = show()
        try {
            compose.onNodeWithText("需要重新输入源仓库凭据").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("重试").performScrollTo().performClick()
            assertTrue(actions.starts.isEmpty())
            field("源密码或访问令牌", "replacement-token")
            compose.onNodeWithText("继续导入").performScrollTo().performClick()
            compose.waitUntil(timeoutMillis = 5_000) { actions.starts.size == 1 }
            assertEquals(task.id, actions.starts.single().first)
            assertEquals("replacement-token", actions.starts.single().second?.password)
        } finally {
            compose.runOnUiThread { nav.dispose() }
        }
    }

    @Test fun taskActionsCancelDeleteAndOpenDestination() {
        val active = createTask(ImportPhase.Cloning)
        val completed = createTask(ImportPhase.Completed)
        actions.active += active.id
        val nav = show()
        try {
            compose.onAllNodesWithText("删除任务").fetchSemanticsNodes().also { assertTrue(it.isEmpty()) }
            compose.onAllNodesWithText("取消导入").onFirst().performScrollTo().performClick()
            compose.waitUntil(timeoutMillis = 5_000) { active.id in actions.cancellations }
            compose.onAllNodesWithText("删除任务").onFirst().performScrollTo().performClick()
            compose.onNodeWithText("删除导入任务？").assertIsDisplayed()
            compose.onNodeWithText("删除", substring = false).performClick()
            compose.waitUntil(timeoutMillis = 5_000) { tasks.get(active.id) == null }

            compose.onNodeWithText("打开仓库").performScrollTo().performClick()
            assertEquals(Screen.Repo("octo", "imported"), nav.top.screen)
            assertEquals(ImportPhase.Completed, tasks.get(completed.id)?.phase)
        } finally {
            compose.runOnUiThread { nav.dispose() }
        }
    }

    private fun createTask(phase: ImportPhase, requiresCredentials: Boolean = false): ImportTask {
        val task = tasks.create(
            "octo",
            ImportSource("https://source.example/repo.git", "repo", false),
            ImportDestinationRequest("octo", "imported", isPrivate = false),
            sourceRequiresCredentials = requiresCredentials,
        )
        return tasks.update(task.id) {
            it.copy(
                destination = ImportDestination("octo", "imported", "https://github.com/octo/imported.git", "main"),
                phase = phase,
            )
        }
    }

    private fun repoJson() = """{"id":1,"name":"original","full_name":"octo/original","owner":{"login":"octo"},"private":false,"clone_url":"https://github.com/octo/original.git","default_branch":"main"}"""

    private fun containsText(value: Any?, needle: String): Boolean = when (value) {
        null -> false
        is String -> value.contains(needle)
        is Bundle -> value.keySet().any { containsText(value.get(it), needle) }
        is Array<*> -> value.any { containsText(it, needle) }
        is Iterable<*> -> value.any { containsText(it, needle) }
        else -> false
    }
}

private class RecordingImportActions(
    private val tasks: ImportTasks,
) : ImportUiActions {
    val starts = mutableListOf<Pair<String, ImportSourceCredentials?>>()
    val cancellations = mutableListOf<String>()
    val active = mutableSetOf<String>()

    override fun start(context: Context, id: String, sourceCredentials: ImportSourceCredentials?) {
        starts += id to sourceCredentials
    }

    override fun isActive(id: String): Boolean = id in active

    override fun cancel(context: Context, id: String) {
        cancellations += id
        active -= id
        tasks.cancel(id)
    }
}
