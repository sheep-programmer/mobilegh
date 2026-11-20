package com.mobilegh.ui

import androidx.activity.ComponentActivity
import android.view.KeyEvent
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilegh.data.DownloadState
import com.mobilegh.data.DownloadTask
import com.mobilegh.data.Downloads
import com.mobilegh.ui.components.DownloadHost
import com.mobilegh.ui.components.DownloadOverlayLayout
import com.mobilegh.ui.components.DownloadPill
import com.mobilegh.ui.screens.HomeDownloadsButton
import com.mobilegh.ui.theme.MobileGhTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DownloadPresentationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var previous = emptyList<DownloadTask>()
    private var selected: String? = null
    private var finished: String? = null
    private var history = false
    private var minimized = false
    private var closed = false
    private var captured = false
    private lateinit var file: File
    private lateinit var task: DownloadTask

    @Before fun fixture() {
        compose.runOnUiThread {
            assumeTrue("Do not interrupt real active downloads", Downloads.activeCount == 0)
            previous = Downloads.tasks.toList()
            selected = Downloads.selectedKey
            finished = Downloads.finishedKey
            history = Downloads.showHistory
            minimized = Downloads.minimized
            closed = Downloads.overlayClosed
            captured = true
            file = File.createTempFile("download-ui-", ".apk", compose.activity.cacheDir)
            file.writeText("UI fixture; never installed")
            task = DownloadTask(
                url = "https://example.invalid/mobilegh-test.apk",
                filename = "MobileGH-更新安装包-with-a-long-name.apk",
                state = DownloadState.Complete,
                filePath = file.absolutePath,
                appDirectory = true,
                total = file.length(),
                downloaded = file.length(),
                isAppUpdate = true,
            )
            Downloads.closeOverlay()
            Downloads.tasks.clear()
            Downloads.tasks.add(task)
            Downloads.overlayClosed = false
        }
    }

    @After fun restore() {
        if (!captured) return
        compose.runOnUiThread {
            Downloads.tasks.clear()
            Downloads.tasks.addAll(previous)
            Downloads.selectedKey = selected
            Downloads.finishedKey = finished
            Downloads.showHistory = history
            Downloads.minimized = minimized
            Downloads.overlayClosed = closed
            file.delete()
        }
    }

    @Test fun completionRemainsAfterViewingAndMinimizingAndHomeReopensAfterClose() {
        compose.runOnUiThread { Downloads.finishedKey = task.key }
        compose.setContent {
            MobileGhTheme {
                Column(Modifier.fillMaxSize()) {
                    HomeDownloadsButton()
                    Box(Modifier.width(320.dp).weight(1f)) {
                        DownloadOverlayLayout(bottomBarVisible = true) { DownloadHost() }
                    }
                }
            }
        }
        val safe = compose.onNodeWithTag("download-safe-area").fetchSemanticsNode().boundsInRoot
        val notice = compose.onNodeWithTag("download-completion-notice").fetchSemanticsNode().boundsInRoot
        assertTrue(notice.top >= safe.top && notice.bottom <= safe.bottom)
        val image = compose.onNodeWithTag("download-safe-area").captureToImage().asAndroidBitmap()
        File(compose.activity.getExternalFilesDir(null), "download-completed-safe-area.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("点按查看").performClick()
        compose.onNodeWithText("安装更新").assertIsDisplayed()
        compose.onNodeWithContentDescription("最小化").performClick()
        compose.onNodeWithText("下载完成").assertIsDisplayed()
        compose.onNodeWithText("点按查看").performClick()
        compose.onNodeWithText("安装更新").assertIsDisplayed()
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText("下载完成").assertIsDisplayed()
        compose.onNodeWithText("点按查看").performClick()
        compose.onNodeWithText("彻底关闭").performClick()
        compose.onNodeWithTag("download-completion-notice").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, Downloads.tasks.size)
            assertTrue(file.isFile)
        }
        compose.onNodeWithContentDescription("下载记录").performClick()
        compose.onNodeWithText(task.filename).assertIsDisplayed()
        compose.onNodeWithContentDescription("最小化").performClick()
        compose.onNodeWithTag("download-minimized-pill").assertIsDisplayed()
    }

    @Test fun explicitCloseDoesNotCancelPausedTransfer() {
        val paused = task.copy(state = DownloadState.Waiting, paused = true, message = "等待网络恢复")
        compose.runOnUiThread {
            Downloads.tasks[0] = paused
            Downloads.selectedKey = paused.key
        }
        compose.setContent { MobileGhTheme { DownloadHost() } }
        compose.onNodeWithContentDescription("最小化").performClick()
        compose.onNodeWithTag("download-minimized-pill").performClick()
        compose.onNodeWithContentDescription("最小化").performClick()
        compose.onNodeWithTag("download-minimized-pill").performClick()
        compose.onNodeWithText("彻底关闭").performClick()
        compose.runOnIdle {
            assertTrue(Downloads.overlayClosed)
            assertEquals(DownloadState.Waiting, Downloads.tasks.single().state)
            assertTrue(Downloads.tasks.single().paused)
        }
        compose.onNodeWithTag("download-minimized-pill").assertDoesNotExist()
    }

    @Test fun updateDetailsKeepActionsVisibleWithLargeFontAndLongMessage() {
        compose.runOnUiThread {
            Downloads.tasks[0] = task.copy(message = "更新安装包已经下载，请确认文件信息后安装。".repeat(60))
            Downloads.selectedKey = task.key
        }
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 1.5f)) {
                MobileGhTheme { DownloadHost() }
            }
        }
        compose.onNodeWithText("安装更新").assertIsDisplayed()
        compose.onNodeWithContentDescription("最小化").assertIsDisplayed()
        val minimize = compose.onNodeWithContentDescription("最小化").fetchSemanticsNode().boundsInRoot
        val install = compose.onNodeWithText("安装更新").fetchSemanticsNode().boundsInRoot
        val dialog = compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
        assertTrue("Minimize belongs in the upper-right of the dialog", minimize.center.x > dialog.center.x && minimize.bottom < install.top)
        compose.onNodeWithText("彻底关闭").assertIsDisplayed()
        val image = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        File(compose.activity.getExternalFilesDir(null), "download-update-large-font.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun draggingThenShrinkingWindowKeepsPillInsideSafeArea() {
        val small = mutableStateOf(false)
        compose.setContent {
            MobileGhTheme {
                Box(Modifier.requiredSize(if (small.value) 280.dp else 340.dp, if (small.value) 240.dp else 480.dp)) {
                    DownloadOverlayLayout(bottomBarVisible = true) { DownloadPill(1, 0) {} }
                }
            }
        }
        compose.onNodeWithTag("download-minimized-pill").performTouchInput { swipe(center, Offset(-600f, -1000f)) }
        compose.runOnIdle { small.value = true }
        val safe = compose.onNodeWithTag("download-safe-area").fetchSemanticsNode().boundsInRoot
        val pill = compose.onNodeWithTag("download-minimized-pill").fetchSemanticsNode().boundsInRoot
        assertTrue(pill.left >= safe.left && pill.right <= safe.right)
        assertTrue(pill.top >= safe.top && pill.bottom <= safe.bottom)
    }
}
