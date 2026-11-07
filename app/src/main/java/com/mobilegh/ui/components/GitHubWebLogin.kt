package com.mobilegh.ui.components

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.Text
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mobilegh.R
import com.mobilegh.data.WebAttachmentUploader
import com.mobilegh.i18n.tr
import com.mobilegh.ui.theme.Gh

/**
 * 应用内的 GitHub 网页登录，只用于附件上传。
 * 在应用里打开 github.com 登录页，用户自己输入账号密码（应用不接触密码）；登录成功后
 * WebView 的 cookie 即为网页会话，附件上传时只随 github.com 请求发送。
 */
object GitHubWebLogin {
    var url by mutableStateOf<String?>(null)
        private set
    private var onDone: (() -> Unit)? = null

    /** 当前 WebView 里是否已有 GitHub 登录会话 */
    fun hasSession(): Boolean = WebAttachmentUploader.hasSession(CookieManager.getInstance().getCookie("https://github.com"))

    /** 需要登录时调用：打开登录页，登录成功后执行 [done]（只执行一次） */
    fun request(done: () -> Unit) {
        onDone = done
        url = "https://github.com/login"
    }

    fun close() {
        url = null
        onDone = null
    }

    internal fun loggedIn() {
        CookieManager.getInstance().flush()
        val callback = onDone
        url = null
        onDone = null
        callback?.invoke()
    }
}

/** 放在应用根部，[GitHubWebLogin.request] 之后显示全屏登录页 */
@Composable
fun GitHubWebLoginHost() {
    val address = GitHubWebLogin.url ?: return
    val g = Gh.c
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
                    tr("登录 GitHub（用于添加附件）"),
                    Modifier.padding(horizontal = 6.dp),
                    color = g.fg, fontSize = 15.sp,
                )
            }
            HDivider()
            AndroidView(
                factory = { c ->
                    WebView(c).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        webViewClient = object : WebViewClient() {
                            // 登录成功会跳转到 GitHub 首页并写入会话 cookie：页面加载完成时检查
                            override fun onPageFinished(view: WebView?, url: String?) {
                                if (GitHubWebLogin.hasSession()) GitHubWebLogin.loggedIn()
                            }
                        }
                        loadUrl(address)
                    }
                },
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
            )
        }
    }
}
