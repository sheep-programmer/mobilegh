package com.mobilegh.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilegh.data.DownloadState
import com.mobilegh.data.DownloadTask
import com.mobilegh.data.Downloads
import com.mobilegh.data.ImageFetch
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Navigator
import com.mobilegh.ui.components.*
import com.mobilegh.ui.theme.Gh
import com.mobilegh.ui.theme.MobileGhTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class IssuesComprehensiveVerificationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun saveScreen(name: String) {
        compose.waitForIdle()
        Thread.sleep(400)
        var shot: Bitmap? = null
        repeat(5) {
            if (shot == null) shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            if (shot == null) Thread.sleep(300)
        }
        assertNotNull("Failed to capture screenshot", shot)

        val dir = File(compose.activity.cacheDir, "screenshots").apply { mkdirs() }
        val target = File(dir, "$name.png")
        FileOutputStream(target).use {
            shot!!.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun testIssue30_ImageRetryBehavior() {
        ImageViewer.close()
        val testUrl = "http://127.0.0.1:1/nonexistent.png"
        ImageFetch.markFailed(testUrl)
        val nav = Navigator()
        compose.setContent {
            CompositionLocalProvider(LocalNav provides nav) {
                MobileGhTheme {
                    Box(Modifier.fillMaxSize().background(Gh.c.canvas).padding(24.dp)) {
                        Column {
                            HtmlView("<p>Block image retry:</p><img src=\"$testUrl\" width=\"140\" height=\"80\" />")
                            Spacer(Modifier.height(16.dp))
                            HtmlView("<p>Badge image retry:</p><img src=\"$testUrl\" width=\"80\" height=\"20\" />")
                        }
                    }
                }
            }
        }

        // Wait for both failure UI states to appear
        compose.waitUntil(5000) {
            runCatching {
                compose.onNodeWithText("重新加载").assertIsDisplayed()
                compose.onNodeWithText("重试").assertIsDisplayed()
                true
            }.getOrDefault(false)
        }

        assertNull("ImageViewer should be closed initially", ImageViewer.url)
        saveScreen("verify_issue_30_image_retry")

        // Clicking block image retry must NOT open ImageViewer
        compose.onNodeWithText("重新加载").performClick()
        compose.waitForIdle()
        assertNull("ImageViewer must NOT open when clicking retry on block image", ImageViewer.url)

        // Clicking badge retry must NOT open ImageViewer
        compose.onNodeWithText("重试").performClick()
        compose.waitForIdle()
        assertNull("ImageViewer must NOT open when clicking retry on badge image", ImageViewer.url)
    }

    @Test
    fun testIssue28_CannotReopenIssueButtonDisabled() {
        var clickedReopen = false
        var clickedCopy = false
        compose.setContent {
            MobileGhTheme {
                Box(Modifier.fillMaxSize().background(Gh.c.canvas).padding(24.dp)) {
                    CardDropdownMenu(
                        expanded = true,
                        onDismissRequest = {},
                        actions = listOf(
                            MenuAction("重新开启", enabled = false) { clickedReopen = true },
                            MenuAction("复制链接", enabled = true) { clickedCopy = true },
                        ),
                    )
                }
            }
        }
        val reopenNode = compose.onNodeWithText("重新开启")
        reopenNode.assertIsDisplayed()
        reopenNode.assertIsNotEnabled()

        saveScreen("verify_issue_28_reopen_disabled")

        reopenNode.performClick()
        compose.runOnIdle {
            assertFalse("Disabled reopen action must not be clicked", clickedReopen)
        }
    }

    @Test
    fun testIssue29_DownloadUiRegularApkAndAppUpdate() {
        val dummy1 = File(compose.activity.cacheDir, "other-app.apk").apply { writeText("dummy") }
        val dummy2 = File(compose.activity.cacheDir, "mobilegh-v0.40.2.apk").apply { writeText("dummy") }

        // Part 1: Regular APK download completed -> button must be "安装应用", NOT "安装更新"
        compose.runOnIdle {
            Downloads.tasks.clear()
            val regularTask = DownloadTask(
                key = "reg-task",
                url = "https://github.com/someone/other-repo/releases/download/v1.0/other-app.apk",
                filename = "other-app.apk",
                filePath = dummy1.absolutePath,
                state = DownloadState.Complete,
                isAppUpdate = false,
            )
            Downloads.tasks.add(regularTask)
            Downloads.selectedKey = regularTask.key
        }

        compose.setContent {
            MobileGhTheme {
                Box(Modifier.fillMaxSize().background(Gh.c.canvas)) {
                    DownloadHost()
                }
            }
        }
        compose.onNodeWithText("下载完成").assertIsDisplayed()
        compose.onNodeWithText("安装应用").assertIsDisplayed()
        compose.onAllNodesWithText("安装更新").assertCountEquals(0)
        saveScreen("verify_issue_29_regular_download")

        // Part 2: App update APK completed -> button must be "安装更新"
        compose.runOnIdle {
            Downloads.tasks.clear()
            val updateTask = DownloadTask(
                key = "update-task",
                url = "https://github.com/sheep-programmer/mobilegh/releases/download/v0.40.2/mobilegh-v0.40.2.apk",
                filename = "mobilegh-v0.40.2.apk",
                filePath = dummy2.absolutePath,
                state = DownloadState.Complete,
                isAppUpdate = true,
            )
            Downloads.tasks.add(updateTask)
            Downloads.selectedKey = updateTask.key
        }
        compose.onNodeWithText("下载完成").assertIsDisplayed()
        compose.onNodeWithText("安装更新").assertIsDisplayed()
        saveScreen("verify_issue_29_app_update")

        // Part 3: Active task details dialog title -> "下载详情"
        compose.runOnIdle {
            Downloads.tasks.clear()
            val activeTask = DownloadTask(
                key = "active-task",
                url = "https://example.com/huge-archive.zip",
                filename = "huge-archive.zip",
                state = DownloadState.Running,
                downloaded = 25000000,
                total = 50000000,
                isAppUpdate = false,
            )
            Downloads.tasks.add(activeTask)
            Downloads.selectedKey = activeTask.key
        }
        compose.onNodeWithText("下载详情").assertIsDisplayed()
        compose.onNodeWithText("后台下载").assertIsDisplayed()
        saveScreen("verify_issue_29_download_details")

        // Clean up
        compose.runOnIdle {
            Downloads.tasks.clear()
            Downloads.selectedKey = null
        }
    }

    @Test
    fun testIssue27_ReadmeInlineZoomAndResetPill() {
        var scale by mutableFloatStateOf(1.6f)
        var offset by mutableStateOf(Offset.Zero)
        compose.setContent {
            MobileGhTheme {
                Box(Modifier.fillMaxSize().background(Gh.c.canvas)) {
                    ReadmeInlineZoomBox(
                        scale = scale,
                        offset = offset,
                        onTransform = { s, o -> scale = s; offset = o },
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text("MobileGH README Document", fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = Gh.c.fg)
                            Spacer(Modifier.height(8.dp))
                            Text("Inline smooth zoom demonstration. Dual finger pinch zoom directly without leaving the screen.", color = Gh.c.fgMuted)
                        }
                    }
                    ReadmeZoomResetPill(
                        scale = scale,
                        onReset = { scale = 1.0f; offset = Offset.Zero },
                        modifier = Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(16.dp),
                    )
                }
            }
        }
        compose.onNodeWithText("160% · 还原").assertIsDisplayed()
        saveScreen("verify_issue_27_readme_zoom")

        // Click reset pill -> scale resets to 1.0f and pill disappears
        compose.onNodeWithText("160% · 还原").performClick()
        compose.runOnIdle {
            assertEquals(1.0f, scale, 0.01f)
        }
        compose.onAllNodesWithText("160% · 还原").assertCountEquals(0)
        saveScreen("verify_issue_27_readme_reset")
    }
}
