package com.mobilegh.ui

import android.content.Context
import android.content.ClipboardManager
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
class ActionSecretsVariablesUiTest {
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
        Api.cacheDir = File(compose.activity.cacheDir, "actions-secrets-variables-ui-${System.nanoTime()}").apply { mkdirs() }
        return LoopbackApi { method, path, body ->
            if (path == "/repos/octo/hello") {
                200 to """{"id":1,"name":"hello","full_name":"octo/hello","owner":{"type":"Organization"},"private":false,"archived":false,"permissions":{"admin":true,"push":true,"pull":true}}"""
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

    private fun waitForText(text: String, substring: Boolean = false) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(
                text,
                substring = substring,
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForContentDescription(text: String, useUnmergedTree: Boolean = false) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription(
                text,
                useUnmergedTree = useUnmergedTree,
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun SemanticsNodeInteraction.assertEditableText(expected: String) =
        assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(expected)))

    private fun SemanticsNodeInteraction.assertInputText(expected: String) =
        assert(SemanticsMatcher.expectValue(SemanticsProperties.InputText, AnnotatedString(expected)))

    @Test fun secretsShowOnlyMetadataAndOrganizationSecretsAreReadOnly() {
        api { _, path, _ -> secretResponse(path) }
        val nav = show(ActionsSettingsSection.Secrets)
        try {
            waitForText("DEPLOY_TOKEN")
            waitForText("ORG_TOKEN")
            compose.onNodeWithText("仓库 Secrets").assertIsDisplayed()
            compose.onNodeWithText("DEPLOY_TOKEN").assertIsDisplayed()
            compose.onNodeWithText("组织 Secrets").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("ORG_TOKEN").performScrollTo().assertIsDisplayed()
            compose.onAllNodesWithText("2026-01-02T03:04:05Z", substring = true).assertCountEquals(2)
            compose.onNodeWithContentDescription("覆盖 Secret ORG_TOKEN").assertDoesNotExist()
            compose.onNodeWithContentDescription("删除 Secret ORG_TOKEN").assertDoesNotExist()
            compose.onNodeWithContentDescription("覆盖 Secret DEPLOY_TOKEN").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Secret 值").assertDoesNotExist()
        } finally {
            compose.runOnUiThread { nav.dispose() }
        }
    }

    @Test fun secretListsKeepSuccessfulContentWhenOneRequestFails() {
        api { _, path, _ ->
            if (path.startsWith("/repos/octo/hello/actions/secrets?")) {
                500 to """{"message":"repository secrets unavailable"}"""
            } else {
                secretResponse(path)
            }
        }
        val nav = show(ActionsSettingsSection.Secrets)
        try {
            waitForText("repository secrets unavailable", substring = true)
            compose.onNodeWithText("repository secrets unavailable", substring = true, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("ORG_TOKEN").performScrollTo().assertIsDisplayed()
        } finally {
            compose.runOnUiThread { nav.dispose() }
        }
    }

    @Test fun secretCreateUsesPublicKeySealedValueAndAccepts201() {
        val plaintext = "create-secret-never-on-wire"
        val created = AtomicBoolean(false)
        val server = api { method, path, _ ->
            when {
                method == "PUT" && path.endsWith("/actions/secrets/NEW_TOKEN") -> {
                    created.set(true)
                    201 to ""
                }
                path.startsWith("/repos/octo/hello/actions/secrets?") && created.get() ->
                    200 to secretList("NEW_TOKEN")
                else -> secretResponse(path, repositorySecrets = emptySecretList)
            }
        }
        val nav = show(ActionsSettingsSection.Secrets)
        try {
            compose.onNodeWithText("新建 Secret").performScrollTo().performClick()
            compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("NEW_TOKEN")
            compose.onAllNodes(hasSetTextAction())[1].performTextReplacement(plaintext)
            compose.onNodeWithText("保存").performClick()
            compose.waitUntil(timeoutMillis = 8_000) {
                server.requestBodies.any { it.startsWith("PUT /repos/octo/hello/actions/secrets/NEW_TOKEN ") }
            }
            val request = server.requestBodies.single {
                it.startsWith("PUT /repos/octo/hello/actions/secrets/NEW_TOKEN ")
            }
            assertFalse(request.contains(plaintext))
            val body = Json.parseToJsonElement(request.substring(request.indexOf('{'))).jsonObject
            assertEquals("public-key-1", body.getValue("key_id").jsonPrimitive.content)
            assertTrue(body.getValue("encrypted_value").jsonPrimitive.content.isNotBlank())
            assertTrue(server.requests.any { it.startsWith("GET /repos/octo/hello/actions/secrets/public-key") })
            compose.onNodeWithText("保存").assertDoesNotExist()
            compose.onNodeWithText("NEW_TOKEN").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("新建 Secret").performScrollTo().performClick()
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(2)
            compose.onAllNodes(hasSetTextAction())[0].assertEditableText("")
            compose.onAllNodes(hasSetTextAction())[1].assertEditableText("")
        } finally {
            compose.runOnUiThread { nav.dispose() }
        }
    }

    @Test fun secretOverwriteAccepts204AndDismissClearsPlaintext() {
        val plaintext = "overwrite-secret-never-on-wire"
        val server = api { method, path, _ ->
            if (method == "PUT" && path.endsWith("/actions/secrets/DEPLOY_TOKEN")) 204 to ""
            else secretResponse(path)
        }
        val nav = show(ActionsSettingsSection.Secrets)
        try {
            waitForContentDescription("覆盖 Secret DEPLOY_TOKEN")
            compose.onNodeWithContentDescription("覆盖 Secret DEPLOY_TOKEN").performScrollTo().performClick()
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
            compose.onAllNodes(hasSetTextAction())[0].performTextReplacement(plaintext)
            compose.onNodeWithText("取消").performClick()
            compose.onNodeWithContentDescription("覆盖 Secret DEPLOY_TOKEN").performScrollTo().performClick()
            compose.onAllNodes(hasSetTextAction())[0].assertEditableText("")
            compose.onAllNodes(hasSetTextAction())[0].performTextReplacement(plaintext)
            compose.onNodeWithText("保存").performClick()
            compose.waitUntil(timeoutMillis = 8_000) {
                server.requestBodies.any { it.startsWith("PUT /repos/octo/hello/actions/secrets/DEPLOY_TOKEN ") }
            }
            val request = server.requestBodies.single {
                it.startsWith("PUT /repos/octo/hello/actions/secrets/DEPLOY_TOKEN ")
            }
            assertFalse(request.contains(plaintext))
            compose.onNodeWithText("覆盖 Secret").assertDoesNotExist()
            compose.onNodeWithContentDescription("覆盖 Secret DEPLOY_TOKEN", useUnmergedTree = true).performScrollTo().performClick()
            compose.onAllNodes(hasSetTextAction())[0].assertEditableText("")
        } finally {
            compose.runOnUiThread { nav.dispose() }
        }
    }

    @Test fun secretFailureKeepsInputAndPlaintextIsAbsentFromSavedState() {
        val plaintext = "failed-secret-must-not-persist"
        api { method, path, _ ->
            when {
                method == "PUT" -> 422 to """{"message":"secret rejected by server"}"""
                path.startsWith("/repos/octo/hello/actions/secrets?") -> 200 to emptySecretList
                else -> secretResponse(path)
            }
        }
        val nav = show(ActionsSettingsSection.Secrets)
        try {
            compose.onNodeWithText("新建 Secret").performScrollTo().performClick()
            compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("NEW_TOKEN")
            compose.onAllNodes(hasSetTextAction())[1].performTextReplacement(plaintext)
            compose.onNodeWithText("保存").performClick()
            waitForText("secret rejected by server", substring = true)
            compose.onNodeWithText("secret rejected by server", substring = true, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            compose.onAllNodes(hasSetTextAction())[0].assertEditableText("NEW_TOKEN")
            compose.onAllNodes(hasSetTextAction())[1].assertInputText(plaintext)
            compose.onNodeWithText("取消").performClick()
            compose.onNodeWithText("新建 Secret").performScrollTo().performClick()
            compose.onAllNodes(hasSetTextAction())[0].assertEditableText("")
            compose.onAllNodes(hasSetTextAction())[1].assertEditableText("")
            compose.onAllNodes(hasSetTextAction())[1].performTextReplacement(plaintext)
            compose.runOnUiThread {
                val state = Bundle()
                InstrumentationRegistry.getInstrumentation().callActivityOnSaveInstanceState(compose.activity, state)
                assertFalse(containsText(state, plaintext))
            }
        } finally {
            compose.runOnUiThread { nav.dispose() }
        }
    }

    @Test fun secretDeleteRequiresConfirmation() {
        val server = api { method, path, _ -> secretResponse(path, deleteSucceeds = method == "DELETE") }
        val nav = show(ActionsSettingsSection.Secrets)
        try {
            waitForContentDescription("删除 Secret DEPLOY_TOKEN", useUnmergedTree = true)
            compose.onNodeWithContentDescription("删除 Secret DEPLOY_TOKEN", useUnmergedTree = true).performScrollTo().performClick()
            compose.onNodeWithText("删除 Secret？").assertIsDisplayed()
            assertTrue(server.requestBodies.none { it.startsWith("DELETE ") })
            compose.onNodeWithText("删除").performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                server.requestBodies.any { it.startsWith("DELETE /repos/octo/hello/actions/secrets/DEPLOY_TOKEN ") }
            }
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithText("DEPLOY_TOKEN").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithText("DEPLOY_TOKEN").assertDoesNotExist()
            compose.onNodeWithText("ORG_TOKEN").performScrollTo().assertIsDisplayed()
        } finally {
            compose.runOnUiThread { nav.dispose() }
        }
    }

    @Test fun secretWriteIsBlockedAfterAccountSwitch() {
        val server = api { _, path, _ -> secretResponse(path, repositorySecrets = emptySecretList) }
        val nav = show(ActionsSettingsSection.Secrets)
        try {
            compose.onNodeWithText("新建 Secret").performScrollTo().performClick()
            compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("NEW_TOKEN")
            compose.onAllNodes(hasSetTextAction())[1].performTextReplacement("account-bound-secret")
            compose.runOnUiThread {
                Session.signIn("other-fixture-token", User(login = "mobilegh-ui-test-other"))
            }
            compose.onNodeWithText("保存").performClick()
            compose.onNodeWithText("账号已切换，请重新打开编辑器", substring = true).assertIsDisplayed()
            assertTrue(server.requests.none { it.contains("/actions/secrets/public-key") })
            assertTrue(server.requestBodies.none { it.startsWith("PUT ") })
        } finally {
            compose.runOnUiThread { nav.dispose() }
        }
    }

    @Test fun variableCreateAllowsEmptyValueAndVariableValueCanBeCopied() {
        val created = AtomicBoolean(false)
        val server = api { method, path, body ->
            when {
                method == "POST" && path.endsWith("/actions/variables") -> {
                    created.set(true)
                    201 to """{"name":"EMPTY_VALUE","value":"","created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z"}"""
                }
                path.startsWith("/repos/octo/hello/actions/variables?") && created.get() ->
                    200 to variableList("""{"name":"EMPTY_VALUE","value":"","created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z"}""")
                else -> variableResponse(path)
            }
        }
        val nav = show(ActionsSettingsSection.Variables)
        try {
            waitForText("DEBUG_FLAG")
            compose.onNodeWithText("DEBUG_FLAG").assertIsDisplayed()
            compose.onNodeWithContentDescription("复制 Variable DEBUG_FLAG").performClick()
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            compose.waitUntil(timeoutMillis = 2_000) { clipboard.primaryClip?.getItemAt(0)?.text == "true" }

            compose.onNodeWithText("新建 Variable").performScrollTo().performClick()
            compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("EMPTY_VALUE")
            compose.onAllNodes(hasSetTextAction())[1].assertEditableText("")
            compose.onNodeWithText("保存").performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                server.requestBodies.any { it.startsWith("POST /repos/octo/hello/actions/variables ") }
            }
            val request = server.requestBodies.single { it.startsWith("POST /repos/octo/hello/actions/variables ") }
            val body = Json.parseToJsonElement(request.substring(request.indexOf('{'))).jsonObject
            assertEquals("EMPTY_VALUE", body.getValue("name").jsonPrimitive.content)
            assertEquals("", body.getValue("value").jsonPrimitive.content)
            compose.onNodeWithText("EMPTY_VALUE").performScrollTo().assertIsDisplayed()
        } finally {
            compose.runOnUiThread { nav.dispose() }
        }
    }

    @Test fun variableCreateAcceptsAnEmptyJsonObjectWithoutInvitingAnotherPost() {
        val posts = AtomicInteger(0)
        val server = api { method, path, _ ->
            when {
                method == "POST" && path.endsWith("/actions/variables") -> {
                    posts.incrementAndGet()
                    201 to "{}"
                }
                else -> variableResponse(path)
            }
        }
        val nav = show(ActionsSettingsSection.Variables)
        try {
            waitForText("DEBUG_FLAG")
            compose.onNodeWithText("新建 Variable").performScrollTo().performClick()
            compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("EMPTY_RESPONSE")
            compose.onAllNodes(hasSetTextAction())[1].performTextReplacement("accepted")
            compose.onNodeWithText("保存").performClick()
            compose.waitUntil(timeoutMillis = 5_000) { posts.get() == 1 }
            compose.onNodeWithText("保存").assertDoesNotExist()
            compose.onNodeWithText("EMPTY_RESPONSE").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("accepted").performScrollTo().assertIsDisplayed()
            assertEquals(1, server.requestBodies.count { it.startsWith("POST /repos/octo/hello/actions/variables ") })
        } finally {
            compose.runOnUiThread { nav.dispose() }
        }
    }

    @Test fun variableRenamePatchesOnlyChangedName() {
        val server = api { method, path, _ ->
            if (method == "PATCH") 204 to "" else variableResponse(path)
        }
        val nav = show(ActionsSettingsSection.Variables)
        try {
            waitForContentDescription("编辑 Variable DEBUG_FLAG")
            compose.onNodeWithContentDescription("编辑 Variable DEBUG_FLAG").performScrollTo().performClick()
            compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("RENAMED_FLAG")
            compose.onNodeWithText("保存").performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                server.requestBodies.any { it.startsWith("PATCH /repos/octo/hello/actions/variables/DEBUG_FLAG ") }
            }
            val request = server.requestBodies.single {
                it.startsWith("PATCH /repos/octo/hello/actions/variables/DEBUG_FLAG ")
            }
            val body = Json.parseToJsonElement(request.substring(request.indexOf('{'))).jsonObject
            assertEquals(setOf("name"), body.keys)
            assertEquals("RENAMED_FLAG", body.getValue("name").jsonPrimitive.content)
            compose.onNodeWithText("RENAMED_FLAG").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("true").performScrollTo().assertIsDisplayed()
        } finally {
            compose.runOnUiThread { nav.dispose() }
        }
    }

    @Test fun variableEditAllowsEmptyValueAndPatchesOnlyValue() {
        val server = api { method, path, _ ->
            if (method == "PATCH") 204 to "" else variableResponse(path)
        }
        val nav = show(ActionsSettingsSection.Variables)
        try {
            waitForContentDescription("编辑 Variable DEBUG_FLAG")
            compose.onNodeWithContentDescription("编辑 Variable DEBUG_FLAG").performScrollTo().performClick()
            compose.onAllNodes(hasSetTextAction())[1].performTextReplacement("")
            compose.onNodeWithText("保存").performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                server.requestBodies.any { it.startsWith("PATCH /repos/octo/hello/actions/variables/DEBUG_FLAG ") }
            }
            val request = server.requestBodies.single {
                it.startsWith("PATCH /repos/octo/hello/actions/variables/DEBUG_FLAG ")
            }
            val body = Json.parseToJsonElement(request.substring(request.indexOf('{'))).jsonObject
            assertEquals(setOf("value"), body.keys)
            assertEquals("", body.getValue("value").jsonPrimitive.content)
            compose.onNodeWithText("DEBUG_FLAG").performScrollTo().assertIsDisplayed()
        } finally {
            compose.runOnUiThread { nav.dispose() }
        }
    }

    @Test fun variableDeleteRequiresConfirmationAndEmptyStateIsShown() {
        val server = api { method, path, _ ->
            if (method == "DELETE") 204 to "" else variableResponse(path)
        }
        val nav = show(ActionsSettingsSection.Variables)
        try {
            waitForContentDescription("删除 Variable DEBUG_FLAG")
            compose.onNodeWithContentDescription("删除 Variable DEBUG_FLAG").performScrollTo().performClick()
            compose.onNodeWithText("删除 Variable？").assertIsDisplayed()
            assertTrue(server.requestBodies.none { it.startsWith("DELETE ") })
            compose.onNodeWithText("删除").performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                server.requestBodies.any { it.startsWith("DELETE /repos/octo/hello/actions/variables/DEBUG_FLAG ") }
            }
            waitForText("暂无 Variables")
            compose.onNodeWithText("暂无 Variables").assertIsDisplayed()
            compose.onNodeWithText("DEBUG_FLAG").assertDoesNotExist()
        } finally {
            compose.runOnUiThread { nav.dispose() }
        }
    }

    private fun secretResponse(
        path: String,
        repositorySecrets: String = secretList("DEPLOY_TOKEN"),
        deleteSucceeds: Boolean = false,
    ): Pair<Int, String> = when {
        path.startsWith("/repos/octo/hello/actions/organization-secrets?") -> 200 to secretList("ORG_TOKEN")
        path.startsWith("/repos/octo/hello/actions/secrets/public-key") -> 200 to """{"key_id":"public-key-1","key":"$publicKey"}"""
        path.startsWith("/repos/octo/hello/actions/secrets?") -> 200 to repositorySecrets
        deleteSucceeds -> 204 to ""
        else -> 404 to """{"message":"unexpected $path"}"""
    }

    private fun variableResponse(path: String): Pair<Int, String> = when {
        path.startsWith("/repos/octo/hello/actions/variables?") -> 200 to variableList(
            """{"name":"DEBUG_FLAG","value":"true","created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-02T00:00:00Z"}""",
        )
        else -> 404 to """{"message":"unexpected $path"}"""
    }

    private fun secretList(name: String) =
        """{"total_count":1,"secrets":[{"name":"$name","created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-02T03:04:05Z"}]}"""

    private fun variableList(variable: String) = """{"total_count":1,"variables":[$variable]}"""

    private fun containsText(value: Any?, needle: String): Boolean = when (value) {
        null -> false
        is String -> value.contains(needle)
        is Bundle -> value.keySet().any { containsText(value.get(it), needle) }
        is Array<*> -> value.any { containsText(it, needle) }
        is Iterable<*> -> value.any { containsText(it, needle) }
        else -> false
    }

    private companion object {
        val emptySecretList = """{"total_count":0,"secrets":[]}"""
        val publicKey: String = Base64.encodeToString(ByteArray(32) { (it + 1).toByte() }, Base64.NO_WRAP)
    }
}
