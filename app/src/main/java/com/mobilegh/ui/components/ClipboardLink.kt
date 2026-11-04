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
        /** [suggest] 为 false 表示剪贴板是在应用内被改动的（复制链接等），只记录、不提示跳转。 */
        fun inspect(suggest: Boolean) {
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
            // 应用内的弹窗会暂时拿走窗口焦点，此时仍属于应用内复制，只需要记录
            if (suggest && !view.hasWindowFocus()) return
            val clip = runCatching { clipboard.primaryClip }.getOrElse { return }
            val sensitive = clip?.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true
            val texts = if (clip == null || sensitive) emptyList() else (0 until clip.itemCount.coerceAtMost(8)).mapNotNull { i ->
                val item = clip.getItemAt(i)
                (item.text ?: item.uri?.toString())?.take(65_536)?.toString()
            }
            val timestamp = clip?.description?.timestamp ?: 0
            if (!suggest) {
                nav.clipboardLinks.note(timestamp, texts)
                return
            }
            val update = nav.clipboardLinks.inspect(timestamp, texts) ?: return
            pending = update.link?.takeUnless { it.target == nav.top.screen }
        }
        val inspectLater = Runnable { inspect(suggest = true) }
        val clipboardChanged = Runnable { inspect(suggest = false) }
        val listener = ClipboardManager.OnPrimaryClipChangedListener { view.post(clipboardChanged) }
        val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (focused) view.post(inspectLater) }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) view.post(inspectLater) }
        clipboard.addPrimaryClipChangedListener(listener)
        view.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
        lifecycle.addObserver(observer)
        view.post(inspectLater)
        onDispose {
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
            message = trf("剪贴板有 {0}，可快速打开", link.label),
            actionLabel = tr("打开"),
            withDismissAction = true,
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed && nav.top.screen != link.target) nav.push(link.target)
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
