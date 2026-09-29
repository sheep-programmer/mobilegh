package com.mobilegh.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.mobilegh.R
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.components.OcButton
import com.mobilegh.ui.components.copy
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.theme.Gh

/** 清掉内置浏览器里的 GitHub 登录态，保证每次登录（含添加第二个账号）都从登录页开始 */
fun clearWebSession() {
    CookieManager.getInstance().removeAllCookies(null)
    CookieManager.getInstance().flush()
    WebStorage.getInstance().deleteAllData()
}

/**
 * 在 GitHub 设备激活页填入验证码并点「Continue」。
 * 页面是 8 个单字符输入框（旧版是一个 user_code 输入框），两种都兼容；每次页面加载只填一次。
 */
private fun fillCodeJs(code: String) = """
(function(code){
  if (window.__mgh) return;
  var raw = code.replace(/-/g, '');
  var setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
  function put(e, v) {
    setter.call(e, v);
    e.dispatchEvent(new Event('input', {bubbles: true}));
    e.dispatchEvent(new Event('change', {bubbles: true}));
  }
  var boxes = [].slice.call(document.querySelectorAll('input[name^="user-code-"]'));
  var form;
  if (boxes.length >= raw.length) {
    boxes.forEach(function(e, i) { put(e, raw.charAt(i)); });
    form = boxes[0].form;
  } else {
    var one = document.querySelector('input[name="user_code"]:not([type="hidden"])');
    if (!one) return;
    put(one, code);
    form = one.form;
  }
  window.__mgh = 1;
  var btn = form && form.querySelector('button[type="submit"], input[type="submit"]');
  if (btn) setTimeout(function() { btn.click(); }, 300);
})('$code');
"""

/**
 * 内置 GitHub 网页登录：在 github.com 上输入账号密码（及两步验证），然后授权 MobileGH。
 * 走的是 OAuth 设备码流程，App 只拿到最终的 access token，接触不到密码。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebLogin(userCode: String, verifyUrl: String, onClose: () -> Unit) {
    val g = Gh.c
    val ctx = rememberCtx()
    var loading by remember { mutableStateOf(true) }
    var title by remember { mutableStateOf("github.com") }
    var web by remember { mutableStateOf<WebView?>(null) }

    BackHandler {
        val w = web
        if (w != null && w.canGoBack()) w.goBack() else onClose()
    }
    DisposableEffect(Unit) { onDispose { web?.destroy() } }

    Column(Modifier.fillMaxSize().background(g.canvas).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            OcButton(R.drawable.oc_x, onClose)
            Column(Modifier.weight(1f)) {
                Text("登录 GitHub", color = g.fg, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Oc(R.drawable.oc_lock, g.success, 12.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(title, color = g.fgMuted, fontSize = 12.sp, maxLines = 1)
                }
            }
            Spacer(Modifier.width(12.dp))
        }
        Row(
            Modifier.fillMaxWidth().background(g.canvasSubtle).clickable { ctx.copy(userCode, "验证码已复制") }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Oc(R.drawable.oc_key, g.fgMuted, 14.dp)
            Spacer(Modifier.width(6.dp))
            Text("验证码 ", color = g.fgMuted, fontSize = 13.sp)
            Text(userCode, color = g.fg, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
            Text("登录后自动填写，点「Authorize」即可", color = g.fgMuted, fontSize = 12.sp, maxLines = 1)
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = g.accent, trackColor = g.canvas)
        else Spacer(Modifier.height(2.dp))
        AndroidView(
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            loading = true
                            title = android.net.Uri.parse(url).host ?: url
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            loading = false
                            if (url.contains("/login/device")) view.evaluateJavascript(fillCodeJs(userCode), null)
                        }
                    }
                    web = this
                    loadUrl(verifyUrl)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
