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
 * 自动走完 GitHub 设备授权的各个页面，每次页面加载只处理一次：
 * 0. 登录页：把刚输入的密码暂存在 github.com 页面自己的 sessionStorage 里（App 不读取），用于第 4 步
 * 1. 账号选择页（/login/device/select_account）：点「Continue」
 * 2. 验证码页：9 个输入框，第 5 个是只读的「-」，只往可编辑的格子里按顺序填
 * 3. 授权页：确认页面上的 user_code 就是本次申请的，等「Authorize」按钮解除禁用后点击
 * 4. 授权要求二次确认（sudo）时：用暂存的密码提交页面自带的「Use your password」表单，用完即删；
 *    只尝试一次，密码不对就留给用户手动确认
 */
private fun autoAuthorizeJs(code: String) = """
(function(code){
  if (window.__mgh) return;
  window.__mgh = 1;
  var store = window.sessionStorage;
  var pw = document.querySelector('form[action="/session"] input[name="password"]');
  var uf = document.querySelector('form[action="/session"] input[name="login"]');
  if (pw || uf) {
    if (uf) uf.addEventListener('input', function() { store.setItem('__mgh_user', uf.value.trim()); });
    if (pw) pw.addEventListener('input', function() { store.setItem('__mgh_pw', pw.value); });
    return;
  }
  function click(b) { if (b) setTimeout(function() { b.click(); }, 300); }
  function same(f) {
    var uc = f && f.querySelector('input[name="user_code"]');
    return uc && uc.value.toUpperCase() === code.toUpperCase();
  }
  var pick = document.querySelector('form[action="/login/device/select_account"] [type="submit"]');
  if (pick) return click(pick);

  var setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
  function put(e, v) {
    setter.call(e, v);
    e.dispatchEvent(new Event('input', {bubbles: true}));
    e.dispatchEvent(new Event('change', {bubbles: true}));
  }
  var sudo = document.querySelector('form[action="/login/device/authorize"] input[name="sudo_password"]');
  if (sudo) {
    var saved = store.getItem('__mgh_pw');
    store.removeItem('__mgh_pw');
    if (saved && same(sudo.form) && !store.getItem('__mgh_sudo')) {
      store.setItem('__mgh_sudo', '1');
      put(sudo, saved);
      HTMLFormElement.prototype.submit.call(sudo.form);
    }
    return;
  }

  var boxes = [].slice.call(document.querySelectorAll('input[name^="user-code-"]'))
    .filter(function(e) { return !e.readOnly && e.type !== 'hidden'; });
  var raw = code.replace(/-/g, '');
  if (boxes.length === raw.length) {
    boxes.forEach(function(e, i) { put(e, raw.charAt(i)); });
    return click(boxes[0].form.querySelector('[type="submit"]'));
  }
  var one = document.querySelector('input[name="user_code"]:not([type="hidden"])');
  if (one) { put(one, code); return click(one.form.querySelector('[type="submit"]')); }

  var form = document.querySelector('form[action="/login/device/authorize"]');
  var ok = form && form.querySelector('button[name="authorize"][value="1"]');
  if (!ok || !same(form)) return;
  // GitHub 只有在按钮完整出现在屏幕上、页面获得焦点 1 秒后才解除禁用，先滚到按钮处
  (form.querySelector('.js-authorization-buttons') || ok).scrollIntoView({block: 'center'});
  var n = 0, t = setInterval(function() {
    if (!ok.disabled) { clearInterval(t); ok.click(); return; }
    if (++n < 20) return;
    // 4 秒后仍未解除禁用：直接提交授权表单（等同于点击 Authorize）
    clearInterval(t);
    var v = document.createElement('input');
    v.type = 'hidden'; v.name = 'authorize'; v.value = '1';
    form.appendChild(v);
    HTMLFormElement.prototype.submit.call(form);
  }, 200);
})('$code');
"""

/** 检测到验证器（TOTP）两步验证页时，用本机存的密钥算出 6 位码并回填。码在 Kotlin 侧算，不下放给页面 JS。 */
private const val OTP_CTX_JS = """
(function(){
  var f = document.querySelector('input[name="otp"],input[name="app_otp"],input#app_totp,input[autocomplete="one-time-code"]');
  if (!f || f.value) return "";
  return sessionStorage.getItem('__mgh_user') || "";
})()
"""

private fun fillTotpJs(code: String) = """
(function(code){
  var f = document.querySelector('input[name="otp"],input[name="app_otp"],input#app_totp,input[autocomplete="one-time-code"]');
  if (!f || f.value) return;
  var set = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
  set.call(f, code);
  f.dispatchEvent(new Event('input', {bubbles: true}));
  f.dispatchEvent(new Event('change', {bubbles: true}));
  var btn = f.form && f.form.querySelector('button[type="submit"], input[type="submit"]');
  if (btn) setTimeout(function() { btn.click(); }, 200);
})('$code');
"""

private fun autofillTotp(view: WebView) {
    view.evaluateJavascript(OTP_CTX_JS) { raw ->
        // raw 是被 JSON 编码的字符串，形如 "octocat" 或 ""；空说明当前页没有验证器输入框
        val login = raw?.trim('"')?.takeIf { it.isNotBlank() && it != "null" } ?: com.mobilegh.data.Session.login
        val code = com.mobilegh.data.Session.totpCode(login) ?: com.mobilegh.data.Session.totpCode(com.mobilegh.data.Session.login)
        if (code != null) view.post { view.evaluateJavascript(fillTotpJs(code), null) }
    }
}

/**
 * 内置 GitHub 网页登录：在 github.com 上输入账号密码（及两步验证），然后授权 MobileGH。
 * 走的是 OAuth 设备码流程，App 只拿到最终的 access token，接触不到密码。
 * 若在设置里存了 TOTP 密钥，验证器两步验证这一步也会自动填码。
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
            Text(if (com.mobilegh.data.Session.hasTotp(com.mobilegh.data.Session.login)) "自动填写验证码、两步验证并授权" else "登录后自动填写并授权", color = g.fgMuted, fontSize = 12.sp, maxLines = 1)
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
                            if (android.net.Uri.parse(url).host == "github.com") {
                                view.evaluateJavascript(autoAuthorizeJs(userCode), null)
                                autofillTotp(view)
                            }
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
