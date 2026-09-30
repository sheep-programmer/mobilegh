package com.mobilegh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.BuildConfig
import com.mobilegh.R
import com.mobilegh.data.Api
import com.mobilegh.data.ApiException
import com.mobilegh.data.Session
import com.mobilegh.data.User
import com.mobilegh.data.await
import com.mobilegh.data.str
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.friendly
import com.mobilegh.ui.components.GhButton
import com.mobilegh.ui.components.GhField
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.components.OcButton
import com.mobilegh.ui.components.openBrowser
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request

private const val TOKEN_URL =
    "https://github.com/settings/tokens/new?description=MobileGH&scopes=repo,read:org,admin:org,notifications,user,gist,workflow,delete_repo,read:project"

/** 账号登录申请的权限，与 Token 预选权限保持一致 */
private const val SCOPES = "repo read:org admin:org notifications user gist workflow delete_repo read:project"

/** 编译时配置了 OAuth App 才能用账号登录 */
private val webLogin get() = BuildConfig.GITHUB_CLIENT_ID.isNotBlank()

/** 用给定 Token 请求 /user 验证有效性 */
private suspend fun verify(token: String): User {
    val req = Request.Builder().url("${Api.BASE}/user")
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/vnd.github+json")
        .build()
    val resp = Api.http.newCall(req).await()
    return withContext(Dispatchers.IO) {
        resp.use {
            if (it.code == 401) throw ApiException(401, "Token 无效或已过期")
            if (!it.isSuccessful) throw ApiException(it.code, "验证失败 (${it.code})")
            Api.json.decodeFromString(User.serializer(), it.body.string())
        }
    }
}

private data class DeviceCode(val device: String, val user: String, val uri: String, val interval: Int)

private suspend fun postForm(url: String, vararg kv: Pair<String, String>): kotlinx.serialization.json.JsonObject {
    val body = FormBody.Builder().apply { kv.forEach { add(it.first, it.second) } }.build()
    val resp = Api.http.newCall(Request.Builder().url(url).header("Accept", "application/json").post(body).build()).await()
    return withContext(Dispatchers.IO) {
        resp.use { Api.plain.parseToJsonElement(it.body.string()) as kotlinx.serialization.json.JsonObject }
    }
}

@Composable
fun LoginScreen(adding: Boolean = false) {
    val g = Gh.c
    val ctx = rememberCtx()
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var token by rememberSaveable { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var device by remember { mutableStateOf<DeviceCode?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var showToken by rememberSaveable { mutableStateOf(!webLogin) }

    fun signIn(t: String) {
        busy = true
        error = null
        scope.launch {
            try {
                val u = verify(t.trim())
                Session.signIn(t.trim(), u)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.friendly()
            } finally {
                busy = false
            }
        }
    }

    fun stopDevice() {
        job?.cancel()
        job = null
        device = null
        busy = false
    }

    /** 账号登录：申请设备码后在内置浏览器打开 GitHub，后台轮询授权结果 */
    fun startDevice() {
        val cid = BuildConfig.GITHUB_CLIENT_ID
        busy = true
        error = null
        clearWebSession()
        job?.cancel()
        job = scope.launch {
            try {
                val r = postForm("https://github.com/login/device/code", "client_id" to cid, "scope" to SCOPES)
                val dc = DeviceCode(r.str("device_code")!!, r.str("user_code")!!, r.str("verification_uri")!!, r.str("interval")?.toIntOrNull() ?: 5)
                device = dc
                var interval = dc.interval
                while (true) {
                    delay(interval * 1000L)
                    val t = postForm(
                        "https://github.com/login/oauth/access_token",
                        "client_id" to cid, "device_code" to dc.device, "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    )
                    val access = t.str("access_token")
                    when {
                        access != null -> {
                            device = null
                            clearWebSession()
                            signIn(access)
                            break
                        }
                        t.str("error") == "slow_down" -> interval = t.str("interval")?.toIntOrNull() ?: (interval + 5)
                        t.str("error") == "authorization_pending" -> {}
                        t.str("error") == "access_denied" -> { device = null; error = "你取消了授权"; break }
                        t.str("error") == "expired_token" -> { device = null; error = "验证码已过期，请重新登录"; break }
                        else -> { device = null; error = t.str("error_description") ?: t.str("error") ?: "授权失败"; break }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                device = null
                error = e.friendly()
            } finally {
                if (device == null) busy = false
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize().background(g.canvas).statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()),
    ) {
        if (adding) Row { OcButton(R.drawable.oc_arrow_left, { nav.pop() }) }
        Column(Modifier.padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(if (adding) 12.dp else 56.dp))
            Oc(R.drawable.oc_mark_github, g.fg, 64.dp)
            Spacer(Modifier.height(16.dp))
            Text(if (adding) "添加账号" else "登录 MobileGH", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = g.fg)
            Spacer(Modifier.height(6.dp))
            Text("轻量、完整的第三方 GitHub 客户端", color = g.fgMuted, fontSize = 14.sp)
            Spacer(Modifier.height(32.dp))

            if (webLogin) {
                GhButton("使用 GitHub 账号登录", Modifier.fillMaxWidth(), primary = true, icon = R.drawable.oc_mark_github, enabled = !busy) { startDevice() }
                Spacer(Modifier.height(8.dp))
                Text("在 GitHub 官方页面输入账号密码，支持两步验证。\n登录后自动授权 MobileGH 访问你的仓库、组织、通知、Gist 与 Actions", color = g.fgMuted, fontSize = 12.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text("账号授权默认长期有效；旧授权已过期时请重新登录。", color = g.fgMuted, fontSize = 12.sp, textAlign = TextAlign.Center)
            }
            if (error != null) {
                Spacer(Modifier.height(10.dp))
                Text(error!!, color = g.danger, fontSize = 13.sp, modifier = Modifier.fillMaxWidth(), textAlign = if (showToken) TextAlign.Start else TextAlign.Center)
            }
            if (busy && device == null) {
                Spacer(Modifier.height(16.dp))
                CircularProgressIndicator(Modifier.height(36.dp), color = g.fgMuted)
            }

            if (webLogin) {
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(1.dp).background(g.border))
                    Text(
                        if (showToken) "使用 Token 登录" else "使用 Token 登录 ▾", color = g.accent, fontSize = 13.sp,
                        modifier = Modifier.clickable { showToken = !showToken }.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    Box(Modifier.weight(1f).height(1.dp).background(g.border))
                }
                Spacer(Modifier.height(16.dp))
            }
            if (showToken) {
                GhField(
                    token, { token = it }, "Personal Access Token",
                    placeholder = "ghp_xxx 或 github_pat_xxx",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    trailing = { OcButton(if (show) R.drawable.oc_eye else R.drawable.oc_lock, { show = !show }, g.fgMuted) },
                )
                Spacer(Modifier.height(16.dp))
                GhButton("使用 Token 登录", Modifier.fillMaxWidth(), primary = !webLogin, enabled = token.isNotBlank() && !busy) { signIn(token) }
                Spacer(Modifier.height(10.dp))
                GhButton("在 GitHub 生成 Token（已预选权限）", Modifier.fillMaxWidth(), icon = R.drawable.oc_link_external) { ctx.openBrowser(TOKEN_URL) }
            }

            Spacer(Modifier.height(28.dp))
            if (showToken) Column(
                Modifier.fillMaxWidth().border(1.dp, g.border, RoundedCornerShape(8.dp)).background(g.canvasSubtle, RoundedCornerShape(8.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Oc(R.drawable.oc_light_bulb, g.attention)
                    Spacer(Modifier.width(6.dp))
                    Text("关于 Token", fontWeight = FontWeight.SemiBold, color = g.fg, fontSize = 14.sp)
                }
                Tip("推荐使用 Classic Token：可以访问你参与协作的仓库和所在组织的全部仓库。")
                Tip("Fine-grained Token 只能访问单个所有者的资源，会看不到别人邀请你协作的仓库。")
                Tip("所需权限：repo、read:org、notifications、user、gist、workflow；删除仓库需要 delete_repo。")
                Tip("Token 只保存在本机，用 Android Keystore 加密存储，只会发送给 api.github.com。")
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    device?.let { d -> WebLogin(d.user, d.uri, onClose = { stopDevice() }) }
    }
}

@Composable
private fun Tip(s: String) {
    Text("• $s", color = Gh.c.fgMuted, fontSize = 13.sp, lineHeight = 19.sp)
}
