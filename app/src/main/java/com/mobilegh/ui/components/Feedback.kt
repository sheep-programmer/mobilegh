package com.mobilegh.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import com.mobilegh.i18n.tr
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** 撤销窗口：本地先生效，窗口结束后才真正提交到 GitHub */
const val UNDO_WINDOW_MS = 5000L

/** 成功类操作的轻震动（开关、选中、完成滑动等）；API 30 以下退回按键震动 */
@Composable
fun rememberTick(): () -> Unit {
    val view = LocalView.current
    return remember(view) { { tick(view) } }
}

private fun tick(view: View) {
    val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
    view.performHapticFeedback(type)
}

/**
 * 显示带「撤销」的提示并等待。窗口内点撤销返回 true；超时、被关闭或被新提示顶掉都返回 false，
 * 调用方据此决定提交。
 */
suspend fun SnackbarHostState.offerUndo(message: String): Boolean {
    currentSnackbarData?.dismiss()
    val result = try {
        withTimeout(UNDO_WINDOW_MS) {
            showSnackbar(message, actionLabel = tr("撤销"), withDismissAction = true, duration = SnackbarDuration.Indefinite)
        }
    } catch (_: TimeoutCancellationException) {
        SnackbarResult.Dismissed
    }
    return result == SnackbarResult.ActionPerformed
}
