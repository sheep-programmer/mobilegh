package com.mobilegh.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilegh.data.Api
import com.mobilegh.data.DraftOption
import com.mobilegh.data.DraftValue
import com.mobilegh.data.Drafts
import com.mobilegh.data.Net
import com.mobilegh.data.Session
import com.mobilegh.data.User
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Navigator
import com.mobilegh.nav.Screen
import com.mobilegh.ui.screens.NewIssueScreen
import com.mobilegh.ui.theme.MobileGhTheme
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NewIssueSubmissionUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var previousProxy = ""
    private var previousCache: File? = null
    private var server: LoopbackApi? = null

    @After fun restore() {
        compose.runOnUiThread {
            if (Session.login == "mobilegh-ui-test") Session.signOut()
            Net.apiProxy = previousProxy
            previousCache?.let { Api.cacheDir = it }
            Drafts.store(compose.activity).clear("mobilegh-ui-test", KEY)
        }
        server?.close()
    }

    @Test fun readOnlyRestoredMetadataNeverReachesCreateRequests() {
        val fixture = LoopbackApi { method, path, _ ->
            when {
                path == "/repos/octo/hello" -> 200 to """{"id":1,"name":"hello","full_name":"octo/hello","owner":{"login":"octo"},"permissions":{"push":false,"maintain":false,"admin":false}}"""
                path.startsWith("/repos/octo/hello/contents/.github/ISSUE_TEMPLATE") -> 404 to """{"message":"no templates"}"""
                method == "POST" && path == "/repos/octo/hello/issues" -> 201 to """{"id":1,"number":42,"title":"Crash","node_id":"I_created"}"""
                path == "/graphql" -> 200 to """{"data":{}}"""
                else -> 404 to """{"message":"unexpected $method $path"}"""
            }
        }
        server = fixture
        previousProxy = Net.apiProxy
        previousCache = Api.cacheDir
        Api.cacheDir = File(compose.activity.cacheDir, "new-issue-submission-ui").apply { mkdirs() }
        Net.apiProxy = fixture.base
        val nav = Navigator()
        try {
            compose.runOnUiThread {
                Session.signIn("local-fixture-token", User(login = "mobilegh-ui-test"))
                Drafts.store(compose.activity).save(
                    "mobilegh-ui-test", KEY,
                    DraftValue(
                        title = "Crash", body = "steps",
                        labels = listOf(DraftOption("bug", "bug")),
                        assignees = listOf(DraftOption("alice", "alice")),
                        milestone = DraftOption("3", "v1"),
                        issueType = DraftOption("IT_bug", "Bug"),
                    ),
                )
                nav.push(Screen.NewIssue("octo", "hello"))
            }
            compose.setContent {
                MobileGhTheme {
                    CompositionLocalProvider(LocalNav provides nav, LocalEntry provides nav.top) {
                        NewIssueScreen("octo", "hello")
                    }
                }
            }
            compose.onNodeWithText("创建 Issue").performClick()
            compose.waitUntil(5_000) { nav.top.screen is Screen.IssueDetail }

            val body = fixture.requestBodies.single { it.startsWith("POST /repos/octo/hello/issues ") }
                .removePrefix("POST /repos/octo/hello/issues ")
            assertEquals(setOf("title", "body"), Json.parseToJsonElement(body).jsonObject.keys)
            assertFalse(fixture.requests.any { it.startsWith("POST /graphql") })
        } finally {
            compose.runOnUiThread { nav.dispose() }
        }
    }

    private companion object {
        const val KEY = "new-issue:octo/hello"
    }
}
