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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.mobilegh.R
import com.mobilegh.ui.components.GhButton
import com.mobilegh.ui.components.MenuAction
import com.mobilegh.ui.components.MoreMenu
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.components.OcButton
import com.mobilegh.ui.components.copy
import com.mobilegh.ui.components.openBrowser
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.components.toast
import com.mobilegh.ui.theme.Gh

/** 清掉内置浏览器里的 GitHub 登录态；默认登录会保留会话，只有用户主动切换网页账号时调用。 */
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
  var f = document.querySelector('input[name="otp"],input[name="app_otp"],input[name="sudo_app_otp"],input#app_totp,input[autocomplete="one-time-code"]');
  if (!f || f.value) return "";
  return sessionStorage.getItem('__mgh_user') || "";
})()
"""

private fun fillTotpJs(code: String) = """
(function(code){
  var f = document.querySelector('input[name="otp"],input[name="app_otp"],input[name="sudo_app_otp"],input#app_totp,input[autocomplete="one-time-code"]');
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

/** GitHub Mobile 数字匹配页提供了合法的替代方式；有 TOTP 时自动打开验证器入口。 */
private const val chooseTotpMethodJs = """
(function(){
  function text(el){ return (el.innerText || el.textContent || '').trim(); }
  var inputs = document.querySelector('input[name="otp"],input[name="app_otp"],input[name="sudo_app_otp"],input#app_totp,input[autocomplete="one-time-code"]');
  if (inputs) return;
  var all = Array.prototype.slice.call(document.querySelectorAll('button,a,input[type="submit"]'));
  var alt = all.find(function(e){ return /use another method|try another way|more options|使用其他方式|尝试其他方式|其他验证方式/i.test(text(e)); });
  if (alt) { alt.click(); return; }
  var totp = all.find(function(e){ return /use your authenticator app|authenticator app|验证器应用|验证器/i.test(text(e)); });
  if (totp) totp.click();
})()
"""

/**
 * 经典 PAT 登录：在 GitHub 官方页面用账号密码 + 两步验证登录后，
 * 自动在「新建 Token」页勾选所需权限并生成一个 Classic Token，读回本地。
 *
 * Classic Token 不受「第三方 OAuth 应用访问限制」影响，因此能看到全部组织仓库，
 * 和官方 App 的可见范围一致。
 */
object TokenLogin {
    /**
     * 新建 Token 页地址。
     *
     * 只用最短形式：带一大串 `scopes=` 的查询串在部分链路上会被中断（ERR_CONNECTION_ABORTED），
     * 描述与权限改由页面内的 JS 勾选，效果相同。
     */
    const val URL = "https://github.com/settings/tokens/new"

    /** 需要勾选的权限 */
    private val SCOPES = listOf(
        "repo", "read:org", "admin:org", "notifications",
        "user", "gist", "workflow", "delete_repo", "read:project",
    )

    /**
     * 处理新建 Token 页；token 生成后返回 token 值（形如 ghp_xxx），否则返回空串。
     * 只读取本页生成的 token，不做任何其他操作。
     */
    fun createTokenJs(desc: String) = """
    (function(desc){
      if (window.__mghTok) return "";
      var path = location.pathname;
      // 1) 生成成功后跳到列表页，页面上会一次性展示新 token
      var shown = document.querySelector('#new-oauth-token, .new-token, #new-access-token, code.js-token-value, input.js-token-value');
      var v = shown ? (shown.value || shown.textContent || '') : '';
      v = v.trim();
      if (/^gh[po]_[A-Za-z0-9]{20,}$/.test(v)) { window.__mghTok = 1; return v; }
      if (path.indexOf('/settings/tokens/new') < 0) return "";
      // 2) 新建页：确认描述和权限，然后提交
      var form = document.querySelector('form[action="/settings/tokens"]') ||
                 document.querySelector('form[action*="/settings/tokens"]') ||
                 document.querySelector('form[method="post"]');
      if (!form) return "";
      var set = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
      var note = form.querySelector('input[name="description"], input[name="oauth_access[description]"], input#oauth_access_description');
      if (note && !note.value) {
        set.call(note, desc);
        note.dispatchEvent(new Event('input', {bubbles: true}));
        note.dispatchEvent(new Event('change', {bubbles: true}));
      }
      // 勾选所需权限：按 value 匹配，不依赖具体 name 前缀
      var boxes = [].slice.call(form.querySelectorAll('input[type="checkbox"]'));
      function pick(scope) {
        return boxes.find(function(c){ return c.value === scope; });
      }
      var missing = [];
      ['repo','read:org','admin:org','notifications','user','gist','workflow','delete_repo','read:project']
        .forEach(function(s){
          var cb = pick(s);
          if (cb) { if (!cb.checked) { cb.checked = true; cb.dispatchEvent(new Event('change', {bubbles: true})); } }
          else missing.push(s);
        });
      // 一个权限都没勾上就不要提交，否则会生成一个没有任何权限的废 Token
      var anyChecked = boxes.some(function(c){ return c.checked; });
      if (!anyChecked) return "";
      if (window.__mghSubmit) return "";
      var btn = form.querySelector('button[type="submit"], input[type="submit"]');
      if (!btn || btn.disabled) return "";
      window.__mghSubmit = 1;
      setTimeout(function(){ btn.click(); }, 1500);
      return "";
    })('$desc')
    """

    /** 读回 token 后立即从页面移除明文，避免停留在剪贴板/页面里 */
    const val SWEEP_JS = """
    (function(){
      var el = document.querySelector('#new-oauth-token, .new-token, #new-access-token, code.js-token-value');
      if (el) { el.textContent = ''; }
    })()
    """
}

/**
 * 内置 GitHub 网页登录：在 github.com 上输入账号密码（及两步验证），然后授权 MobileGH。
 * 走的是 OAuth 设备码流程，App 只拿到最终的 access token，接触不到密码。
 * 若在设置里存了 TOTP 密钥，验证器两步验证这一步也会自动填码。
 */
/**
 * 经典 PAT 网页登录：账号密码 + 两步验证（可用已存 TOTP 自动填码）→ 自动生成 Classic Token。
 * 与设备授权流程共用同一套网页会话与验证码自动填充。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TokenWebLogin(onClose: () -> Unit, onToken: (String) -> Unit) {
    val g = Gh.c
    val ctx = rememberCtx()
    var loading by remember { mutableStateOf(true) }
    var title by remember { mutableStateOf("github.com") }
    var web by remember { mutableStateOf<WebView?>(null) }
    var got by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var everLoaded by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }

    // 连接被拦截时常常是「挂起不报错」，靠超时兜底：15 秒还没出现页面就判定打不开
    LaunchedEffect(attempt) {
        failed = false
        kotlinx.coroutines.delay(15000)
        if (!everLoaded) failed = true
    }

    BackHandler {
        val w = web
        if (w != null && w.canGoBack()) w.goBack() else onClose()
    }
    DisposableEffect(Unit) { onDispose { web?.destroy() } }

    Column(Modifier.fillMaxSize().background(g.canvas).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            OcButton(R.drawable.oc_x, onClose)
            Column(Modifier.weight(1f)) {
                Text("使用 GitHub 账号登录", color = g.fg, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Oc(R.drawable.oc_lock, g.success, 12.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(title, color = g.fgMuted, fontSize = 12.sp, maxLines = 1)
                }
            }
            MoreMenu(listOf(
                MenuAction("清除 GitHub 网页会话", danger = true) {
                    clearWebSession()
                    onClose()
                },
            ))
        }
        Row(
            Modifier.fillMaxWidth().background(g.canvasSubtle).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Oc(R.drawable.oc_key, g.fgMuted, 14.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                if (com.mobilegh.data.Session.hasTotp(com.mobilegh.data.Session.login))
                    "两步验证码会自动填入，登录后自动生成访问令牌；若页面已显示 ghp_ 开头的 Token，可复制后返回上一页粘贴登录"
                else
                    "登录后自动创建 Classic Token；两步验证请选择「验证器应用」；若自动创建失败，复制页面上的 Token 返回上一页粘贴登录",
                color = g.fgMuted, fontSize = 12.sp, maxLines = 3,
            )
        }
        if (loading && !failed) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = g.accent, trackColor = g.canvas)
        else Spacer(Modifier.height(2.dp))
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { c ->
                    WebView(c).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        webViewClient = object : WebViewClient() {
                            // 本次导航是否出错：WebView 加载失败时仍会回调 onPageFinished（展示自带错误页），
                            // 且该 url 的 host 还是 github.com，必须用这个标志区分真加载和错误页
                            private var navError = false

                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                loading = true
                                navError = false
                                title = android.net.Uri.parse(url).host ?: url
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                loading = false
                                if (navError || android.net.Uri.parse(url).host != "github.com") return
                                everLoaded = true
                                failed = false
                                // 登录页 / 两步验证页：复用设备授权流程的验证器自动填码逻辑
                                view.postDelayed({
                                    view.evaluateJavascript(chooseTotpMethodJs, null)
                                    view.postDelayed({ autofillTotp(view) }, 450)
                                }, 250)
                                if (got) return
                                view.evaluateJavascript(TokenLogin.createTokenJs("MobileGH")) { raw ->
                                    val token = raw?.trim('"')?.takeIf { it.startsWith("gh") && it.length > 20 }
                                    if (token != null && !got) {
                                        got = true
                                        view.evaluateJavascript(TokenLogin.SWEEP_JS, null)
                                        onToken(token)
                                    }
                                }
                            }

                            override fun onReceivedError(view: WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError) {
                                if (!request.isForMainFrame) return
                                navError = true
                                loading = false
                                com.mobilegh.data.AppLog.warn("auth", "登录页加载失败：" + error.description + " " + request.url)
                                if (!everLoaded) failed = true
                            }
                        }
                        web = this
                        loadUrl(TokenLogin.URL)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (failed) {
                LoadFailPanel(
                    host = "github.com",
                    onRetry = { attempt++; web?.loadUrl(TokenLogin.URL) },
                    onBrowser = { ctx.openBrowser(TokenLogin.URL) },
                )
            }
        }
    }
}

/** WebView 打不开时的错误面板：给出重试和用系统浏览器打开两条出路，不再无限白屏 */
@Composable
private fun LoadFailPanel(host: String, onRetry: () -> Unit, onBrowser: () -> Unit) {
    val g = Gh.c
    Column(
        Modifier.fillMaxSize().background(g.canvas).padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Oc(R.drawable.oc_alert, g.attention, 40.dp)
        Spacer(Modifier.height(16.dp))
        Text("打不开 $host", color = g.fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text(
            "你的网络无法直接访问 GitHub 网页。可以重试，或用系统浏览器打开——系统浏览器可以使用手机上的 VPN / 代理。" +
                "也可以返回上一页，用「使用 Token 登录」粘贴一个已创建的 Token（Token 走加速节点，通常可用）。",
            color = g.fgMuted, fontSize = 13.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        GhButton("重试", Modifier.fillMaxWidth(), primary = true, icon = R.drawable.oc_sync, onClick = onRetry)
        Spacer(Modifier.height(10.dp))
        GhButton("用系统浏览器打开", Modifier.fillMaxWidth(), icon = R.drawable.oc_link_external, onClick = onBrowser)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebLogin(userCode: String, verifyUrl: String, onClose: () -> Unit) {
    val g = Gh.c
    val ctx = rememberCtx()
    var loading by remember { mutableStateOf(true) }
    var title by remember { mutableStateOf("github.com") }
    var web by remember { mutableStateOf<WebView?>(null) }
    var failed by remember { mutableStateOf(false) }
    var everLoaded by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        failed = false
        kotlinx.coroutines.delay(15000)
        if (!everLoaded) failed = true
    }

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
            MoreMenu(listOf(
                MenuAction("清除 GitHub 网页会话", danger = true) {
                    clearWebSession()
                    onClose()
                },
            ))
        }
        Row(
            Modifier.fillMaxWidth().background(g.canvasSubtle).clickable { ctx.copy(userCode, "授权码已复制") }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Oc(R.drawable.oc_key, g.fgMuted, 14.dp)
            Spacer(Modifier.width(6.dp))
            Text("授权码 ", color = g.fgMuted, fontSize = 13.sp)
            Text(userCode, color = g.fg, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
            Text(
                if (com.mobilegh.data.Session.hasTotp(com.mobilegh.data.Session.login)) "自动填写两步验证码并授权"
                else "这是授权码，不是两步验证码；两步验证请在页面选择验证器应用",
                color = g.fgMuted, fontSize = 12.sp, maxLines = 2,
            )
        }
        if (loading && !failed) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = g.accent, trackColor = g.canvas)
        else Spacer(Modifier.height(2.dp))
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { c ->
                    WebView(c).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        webViewClient = object : WebViewClient() {
                            private var navError = false

                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                loading = true
                                navError = false
                                title = android.net.Uri.parse(url).host ?: url
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                loading = false
                                if (!navError && android.net.Uri.parse(url).host == "github.com") {
                                    everLoaded = true
                                    failed = false
                                    view.evaluateJavascript(autoAuthorizeJs(userCode), null)
                                    // 数字匹配页先切换到 GitHub 提供的验证器选项，再尝试填入 TOTP。
                                    view.postDelayed({
                                        view.evaluateJavascript(chooseTotpMethodJs, null)
                                        view.postDelayed({ autofillTotp(view) }, 450)
                                    }, 250)
                                }
                            }

                            override fun onReceivedError(view: WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError) {
                                if (!request.isForMainFrame) return
                                navError = true
                                loading = false
                                if (!everLoaded) failed = true
                            }
                        }
                        web = this
                        loadUrl(verifyUrl)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (failed) {
                LoadFailPanel(
                    host = "github.com",
                    onRetry = { attempt++; web?.loadUrl(verifyUrl) },
                    onBrowser = { ctx.openBrowser(verifyUrl) },
                )
            }
        }
    }
}
