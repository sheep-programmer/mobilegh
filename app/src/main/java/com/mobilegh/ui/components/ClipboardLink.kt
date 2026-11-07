package com.mobilegh.ui.components

import android.content.ClipboardManager
import android.content.Context
import android.view.ViewTreeObserver
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mobilegh.nav.Links
import com.mobilegh.nav.Navigator
import com.mobilegh.ui.theme.Gh
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf

/** 回到前台时窗口焦点可能晚于 ON_RESUME 到达：在这段时间内重试读取剪贴板。 */
private const val CLIPBOARD_FOCUS_RETRY_DELAY_MS = 100L
private const val CLIPBOARD_FOCUS_RETRIES = 5

/** 全局底部快捷打开入口。Android 仅允许获得输入焦点的前台应用读取剪贴板。 */
@Composable
fun ClipboardLinkHost(nav: Navigator, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val clipboard = remember(context) { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    val host = remember(nav) { SnackbarHostState() }
    var pending by remember(nav) { mutableStateOf<Links.RepositoryLink?>(null) }
    val g = Gh.c

    DisposableEffect(clipboard, view, lifecycle, nav) {
        var disposed = false
        /** [suggest] 为 false 表示剪贴板是在应用内被改动的（复制链接等），只记录、不提示跳转。 */
        fun inspect(suggest: Boolean, attempt: Int = 0) {
            if (disposed) return
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
            if (suggest && !view.hasWindowFocus()) {
                // 刚从后台回来时窗口焦点可能还没到，等一会儿再读；应用内弹窗拿走焦点则不算前台可读
                if (attempt < CLIPBOARD_FOCUS_RETRIES) {
                    view.postDelayed({ inspect(suggest = true, attempt = attempt + 1) }, CLIPBOARD_FOCUS_RETRY_DELAY_MS)
                }
                return
            }
            val clip = runCatching { clipboard.primaryClip }.getOrElse { return }
            val sensitive = clip?.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true
            val texts = if (clip == null || sensitive) emptyList() else (0 until clip.itemCount.coerceAtMost(8)).mapNotNull { i ->
                val item = clip.getItemAt(i)
                (item.text ?: item.uri?.toString())?.take(65_536)?.toString()
            }
            if (!suggest) {
                nav.clipboardLinks.note(texts)
                return
            }
            val update = nav.clipboardLinks.inspect(texts) ?: return
            pending = update.link?.takeUnless { it.target == nav.top.screen }
        }
        val inspectLater = Runnable { inspect(suggest = true) }
        val clipboardChanged = Runnable { inspect(suggest = false) }
        val listener = ClipboardManager.OnPrimaryClipChangedListener { view.post(clipboardChanged) }
        val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (focused) view.post(inspectLater) }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // 退到后台：应用内复制的内容下次回来可以提示，后台复制的新链接也能被识别
                Lifecycle.Event.ON_STOP -> nav.clipboardLinks.onBackground()
                Lifecycle.Event.ON_RESUME -> view.post(inspectLater)
                else -> Unit
            }
        }
        clipboard.addPrimaryClipChangedListener(listener)
        view.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
        lifecycle.addObserver(observer)
        view.post(inspectLater)
        onDispose {
            disposed = true
            clipboard.removePrimaryClipChangedListener(listener)
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)
            lifecycle.removeObserver(observer)
            view.removeCallbacks(inspectLater)
            view.removeCallbacks(clipboardChanged)
        }
    }

    LaunchedEffect(nav.top.screen) {
        if (pending?.target == nav.top.screen) pending = null
    }
    LaunchedEffect(pending) {
        val link = pending ?: return@LaunchedEffect
        val result = host.showSnackbar(
            message = trf("剪贴板有 {0}，可快速打开", link.targetLabel),
            actionLabel = tr("打开"),
            withDismissAction = true,
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed && nav.top.screen != link.target) nav.pushLink(link.target)
        if (pending == link) pending = null
    }
    SnackbarHost(host, modifier) { data ->
        Snackbar(
            data,
            shape = RoundedCornerShape(8.dp),
            containerColor = g.canvasSubtle,
            contentColor = g.fg,
            actionColor = g.accent,
            dismissActionContentColor = g.fgMuted,
        )
    }
}
