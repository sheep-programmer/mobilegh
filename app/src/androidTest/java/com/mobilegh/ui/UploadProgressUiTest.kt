package com.mobilegh.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilegh.data.UploadProgress
import com.mobilegh.data.UploadStage
import com.mobilegh.ui.screens.UploadProgressCard
import com.mobilegh.ui.theme.MobileGhTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UploadProgressUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun render(progress: UploadProgress) {
        compose.setContent { MobileGhTheme { UploadProgressCard(progress) } }
    }

    private fun range(): ProgressBarRangeInfo = compose.onNodeWithTag("upload-progress")
        .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]

    @Test fun readingShowsActualByteProgress() {
        render(UploadProgress(1, 4, 250, 1000, "a.txt"))
        assertEquals(0.25f, range().current)
        compose.onNodeWithText("1/4 个文件 · 250 B / 1000 B", substring = true).assertExists()
    }

    @Test fun sendingDoesNotReuseReadingPercentage() {
        render(UploadProgress(4, 4, 1000, 1000, "打包上传", UploadStage.Sending))
        assertSame(ProgressBarRangeInfo.Indeterminate, range())
        compose.onNodeWithText("1000 B / 1000 B", substring = true).assertDoesNotExist()
    }
}
