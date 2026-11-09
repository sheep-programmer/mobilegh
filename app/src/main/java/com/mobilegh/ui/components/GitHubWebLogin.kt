package com.mobilegh.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mobilegh.R
import com.mobilegh.data.MobileApprovalOAuth
import com.mobilegh.data.WebAttachmentUploader
import com.mobilegh.i18n.tr
import com.mobilegh.ui.theme.Gh

/**
 * 应用内的 GitHub 网页会话登录，用于附件上传及网页端独有功能（如删除编辑历史）。
 * 在应用里打开 github.com 登录页，用户自己输入账号密码（应用不接触密码）；登录成功后
 * WebView 的 cookie 即为网页会话，只随 github.com 网页私有请求发送。
 */
object GitHubWebLogin {
    var url by mutableStateOf<String?>(null)
        private set
    var title by mutableStateOf<String?>(null)
        private set
    private var onDone: (() -> Unit)? = null
    private var onDismiss: (() -> Unit)? = null

    /** 当前 WebView 里是否已有 GitHub 登录会话 */
    fun hasSession(): Boolean = runCatching {
        WebAttachmentUploader.hasSession(CookieManager.getInstance().getCookie("https://github.com"))
    }.getOrDefault(false)

    /** 需要登录时调用：打开登录页，登录成功后执行 [done]（只执行一次） */
    fun request(
        customTitle: String? = null,
        urlToOpen: String = "https://github.com/login",
        onDismiss: (() -> Unit)? = null,
        done: () -> Unit = {},
    ) {
        this.onDone = done
        this.onDismiss = onDismiss
        this.title = customTitle
        this.url = urlToOpen
    }

    fun close() {
        val dismiss = onDismiss
        url = null
        title = null
        onDone = null
        onDismiss = null
        dismiss?.invoke()
    }

    internal fun loggedIn() {
        runCatching { CookieManager.getInstance().flush() }
        val callback = onDone
        url = null
        title = null
        onDone = null
        onDismiss = null
        callback?.invoke()
    }
}

/** 放在应用根部，[GitHubWebLogin.request] 之后显示全屏登录页 */
@Composable
fun GitHubWebLoginHost() {
    val address = GitHubWebLogin.url ?: return
    val g = Gh.c
    var progress by remember(address) { mutableIntStateOf(0) }
    var isPageLoading by remember(address) { mutableStateOf(true) }

    Dialog(
        onDismissRequest = { GitHubWebLogin.close() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(Modifier.fillMaxSize().background(g.canvas)) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().background(g.canvasSubtle).padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OcButton(R.drawable.oc_x, { GitHubWebLogin.close() }, g.fg, description = tr("关闭"))
                Text(
                    GitHubWebLogin.title ?: tr("登录 GitHub（用于网页会话与附件）"),
                    Modifier.padding(horizontal = 6.dp).weight(1f),
                    color = g.fg, fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isPageLoading && progress < 100) {
                LinearProgressIndicator(
                    progress = { (progress.coerceIn(8, 100)) / 100f },
                    modifier = Modifier.fillMaxWidth().height(2.5.dp),
                    color = g.accent,
                    trackColor = g.canvasSubtle,
                )
            } else {
                HDivider()
            }
            Box(Modifier.fillMaxSize().navigationBarsPadding()) {
                AndroidView(
                    factory = { c ->
                        WebView(c).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            CookieManager.getInstance().setAcceptCookie(true)
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    progress = newProgress
                                    if (newProgress >= 100) isPageLoading = false
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    isPageLoading = true
                                }

                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    val u = request?.url?.toString() ?: return false
                                    return handleRedirect(u, c)
                                }

                                @Deprecated("Deprecated in Java")
                                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                    if (url == null) return false
                                    return handleRedirect(url, c)
                                }

                                private fun handleRedirect(u: String, context: Context): Boolean {
                                    if (MobileApprovalOAuth.handles(u)) {
                                        runCatching { CookieManager.getInstance().flush() }
                                        MobileApprovalOAuth.complete(context, u)
                                        GitHubWebLogin.close()
                                        return true
                                    }
                                    return false
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    isPageLoading = false
                                    val opening = GitHubWebLogin.url.orEmpty()
                                    val isPureLogin = opening.startsWith("https://github.com/login") && !opening.contains("/device") && !opening.contains("/oauth")
                                    if (isPureLogin && GitHubWebLogin.hasSession()) {
                                        GitHubWebLogin.loggedIn()
                                    }
                                }
                            }
                            loadUrl(address)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (isPageLoading && progress < 65) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(g.canvas),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            strokeWidth = 3.dp,
                            color = g.accent,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            tr("正在加载 GitHub 页面…"),
                            color = g.fgMuted,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
        }
    }
}
