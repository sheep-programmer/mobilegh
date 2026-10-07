package com.mobilegh.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilegh.data.*
import com.mobilegh.nav.*
import com.mobilegh.ui.screens.*
import com.mobilegh.ui.theme.MobileGhTheme
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CodeReadingFlowsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val nav = Navigator()
    private val sha = "a".repeat(40)
    @After fun dispose() { compose.runOnUiThread { nav.dispose() } }
    @Test fun fileFinderOpensTheFileAtTheIndexedCommit() {
        compose.runOnUiThread {
            nav.root.bag["file-index:main"] = Loader<RepositoryFileIndex>(nav.root.scope) { error("unexpected network request") }.apply {
                data = RepositoryFileIndex(sha, listOf(RepositoryTreeEntry("src/Main.kt", "blob")), false)
            }
        }
        compose.setContent { MobileGhTheme { CompositionLocalProvider(LocalNav provides nav, LocalEntry provides nav.root) { FileFinderScreen("mona", "repo", "main") } } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("src/Main.kt").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("src/Main.kt").performClick()
        compose.runOnIdle { assertEquals(Screen.FileView("mona", "repo", "src/Main.kt", sha), nav.top.screen) }
    }
    @Test fun blameCanFindALineAndOpenItsCommit() {
        val second = "b".repeat(40)
        compose.runOnUiThread {
            nav.root.bag["blame:main"] = Loader<BlameSnapshot>(nav.root.scope) { error("unexpected network request") }.apply {
                data = BlameSnapshot(sha, listOf(BlameRange(1, 3, sha, "first change", "Alice", null, ""), BlameRange(4, 5, second, "second change", "Bob", null, "")), listOf("one", "two", "three", "four", "five"))
            }
        }
        compose.setContent { MobileGhTheme { CompositionLocalProvider(LocalNav provides nav, LocalEntry provides nav.root) { BlameScreen("mona", "repo", "src/Main.kt", "main") } } }
        compose.onNodeWithText("定位行号（留空查看全部）").performTextInput("4")
        compose.onNodeWithText("first change").assertDoesNotExist()
        compose.onNodeWithText("second change").performClick()
        compose.runOnIdle { assertEquals(Screen.CommitDetail("mona", "repo", second), nav.top.screen) }
    }
    @Test fun codeSearchResultOpensTheNativeFileFromItsGithubUrl() {
        compose.runOnUiThread {
            nav.root.bag["code-search:foo"] = Pager<CodeSearchHit>(nav.root.scope) { _, _ -> error("unexpected network request") }.apply {
                items.add(CodeSearchHit(path = "src/Test.kt", htmlUrl = "https://github.com/mona/repo/blob/main/src/Test.kt", repository = Repo(name = "repo", fullName = "mona/repo", owner = User(login = "mona"))))
                loadedOnce = true
                end = true
            }
        }
        compose.setContent { MobileGhTheme { CompositionLocalProvider(LocalNav provides nav, LocalEntry provides nav.root) { CodeSearchResults("foo") } } }
        compose.onNodeWithText("src/Test.kt").performClick()
        compose.runOnIdle { assertEquals(Screen.FileView("mona", "repo", "src/Test.kt", "main"), nav.top.screen) }
    }
    @Test fun directoriesOpenedFromSearchCanNavigateToTheirRealParent() {
        compose.setContent { androidx.compose.material3.Text("fixture") }
        compose.runOnIdle {
            nav.push(Screen.FileFinder("mona", "repo", "main"))
            nav.push(Screen.Files("mona", "repo", "src/main", sha))
            nav.parentFiles("mona", "repo", "src/main", sha)
            assertEquals(Screen.Files("mona", "repo", "src", sha), nav.top.screen)
            assertEquals(2, nav.stack.size)
        }
    }
}
