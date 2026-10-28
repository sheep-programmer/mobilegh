package com.mobilegh.data

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf

internal object ApprovalOAuthProtocol {
    const val CLIENT_ID = "3f8b8834a91f0caad392"
    const val REDIRECT = "github://com.github.android/oauth"
    private val random = SecureRandom()
    fun randomString() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    fun challenge(verifier: String) = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    fun callback(url: String, expectedState: String): String {
        val uri = URI(url)
        require(uri.scheme == "github" && uri.rawAuthority == "com.github.android" && uri.path == "/oauth" && uri.fragment == null) { tr("授权回调地址无效") }
        val pairs = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.map {
            val split = it.split('=', limit = 2)
            URLDecoder.decode(split[0], "UTF-8") to URLDecoder.decode(split.getOrElse(1) { "" }, "UTF-8")
        }
        require(pairs.map { it.first }.distinct().size == pairs.size) { tr("授权回调参数无效") }
        val params = pairs.toMap()
        require(params["iss"] == null || params["iss"] == "https://github.com/login/oauth") { tr("授权提供方不匹配") }
        require(MessageDigest.isEqual(params["state"].orEmpty().toByteArray(), expectedState.toByteArray())) { tr("授权请求不匹配，请重新开始") }
        check(params["error"] == null) { tr("GitHub 授权已取消") }
        return params["code"]?.takeIf { it.isNotBlank() } ?: error(tr("GitHub 未返回授权码"))
    }
}

data class ApprovalSetupState(
    val busy: Boolean = false, val error: String? = null, val waitingForBrowser: Boolean = false,
    val notice: String? = null, val canRetry: Boolean = false,
)

enum class ApprovalOAuthPurpose { SignIn, Reauthorize, ApprovalOnly }

/** A single browser authorization automatically provisions a native device on this installation. */
object MobileApprovalOAuth {
    private const val PREFS = "mobile_approval_oauth"
    private const val EXCHANGE_ATTEMPTS = 3
    // Public native-client credential distributed in the provider's APK, never a user credential.
    private const val CLIENT_SECRET = "00e76fc8358899d7795a46cd04ace865fcdc0165"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(ApprovalSetupState())
    val state = mutableState.asStateFlow()
    private var job: kotlinx.coroutines.Job? = null
    // 网络失败后保留回调与已换得的 Token：重试时不必重开浏览器，也不重复兑换一次性的授权码
    private var lastCallback: String? = null
    private var issued: Triple<String, String, Set<String>?>? = null
    private var initiatingGeneration: Int? = null
    private var operation = 0
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).followRedirects(false).build()

    fun begin(context: Context, purpose: ApprovalOAuthPurpose = ApprovalOAuthPurpose.ApprovalOnly): String {
        check(!state.value.busy) { tr("正在等待 GitHub 授权") }
        lastCallback = null
        issued = null
        operation++
        initiatingGeneration = Session.generation
        val verifier = ApprovalOAuthProtocol.randomString()
        val state = ApprovalOAuthProtocol.randomString()
        val pending = buildJsonObject { put("state", state); put("verifier", verifier)
            put("createdAt", System.currentTimeMillis()); put("login", Session.login.orEmpty()); put("purpose", purpose.name) }
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("pending", Crypto.encrypt(pending.toString())).commit()) { tr("无法保存授权请求") }
        mutableState.value = ApprovalSetupState(busy = true, waitingForBrowser = true)
        val values = listOf("client_id" to ApprovalOAuthProtocol.CLIENT_ID, "redirect_uri" to ApprovalOAuthProtocol.REDIRECT,
            "scope" to DeviceAuth.SCOPES, "state" to state,
            "code_challenge" to ApprovalOAuthProtocol.challenge(verifier), "code_challenge_method" to "S256")
        return "https://github.com/login/oauth/authorize?" + values.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
    }

    fun handles(url: String): Boolean = runCatching {
        URI(url).let { it.scheme == "github" && it.host == "com.github.android" && it.path == "/oauth" }
    }.getOrDefault(false)

    fun complete(context: Context, url: String) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        lastCallback = url
        val authorization = operation
        job = scope.launch {
            mutableState.value = ApprovalSetupState(busy = true)
            var stage = "callback"
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            try {
                val encrypted = prefs.getString("pending", null)
                if (encrypted == null) {
                    mutableState.value = ApprovalSetupState(error = tr("授权请求已结束，请重新开始"))
                    return@launch
                }
                val pending = Api.plain.parseToJsonElement(Crypto.decrypt(encrypted)).jsonObject
                check(System.currentTimeMillis() - pending.getValue("createdAt").jsonPrimitive.long in 0..900_000) { tr("授权请求已过期") }
                val code = ApprovalOAuthProtocol.callback(url, pending.getValue("state").jsonPrimitive.content)
                val purpose = pending["purpose"]?.jsonPrimitive?.content?.let { value -> ApprovalOAuthPurpose.entries.firstOrNull { it.name == value } }
                    ?: ApprovalOAuthPurpose.ApprovalOnly
                val startingLogin = pending.getValue("login").jsonPrimitive.content.takeIf { it.isNotBlank() }
                val generation = initiatingGeneration ?: Session.generation.also { initiatingGeneration = it }
                fun checkSession() {
                    check(operation == authorization && Session.generation == generation && startingLogin.equals(Session.login, true)) { tr("账号已切换，请重新授权") }
                }
                checkSession()
                stage = "token"
                AppLog.info("2fa_setup", tr("开始完成 GitHub 授权"))
                val (token, grantedScopes) = issued?.takeIf { it.first == url }?.let { it.second to it.third }
                    ?: exchange(code, pending.getValue("verifier").jsonPrimitive.content).also { issued = Triple(url, it.first, it.second) }
                checkSession()
                val user = MobileApproval.user(token)
                checkSession()
                val expectedLogin = if (purpose == ApprovalOAuthPurpose.SignIn) null else startingLogin
                check(expectedLogin == null || expectedLogin.equals(user.login, true)) { trf("请授权当前账号 @{0}", expectedLogin) }
                stage = "registration"
                AppLog.info("2fa_setup", tr("开始自动注册本机验证设备"))
                MobileApproval.activate(app, token, expectedLogin, ::checkSession)
                checkSession()
                if (purpose != ApprovalOAuthPurpose.ApprovalOnly || Session.token == null) {
                    Session.signIn(token, user, clientId = ApprovalOAuthProtocol.CLIENT_ID, scopes = grantedScopes)
                }
                check(prefs.edit().remove("pending").commit()) { tr("无法更新授权请求") }
                lastCallback = null
                issued = null
                MobileApprovalMonitor.reload(app)
                MobileApprovalMonitor.ensureStarted(app)
                mutableState.value = ApprovalSetupState()
                AppLog.info("2fa_setup", tr("数字审批已自动启用"))
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                val raw = e.networkHint() ?: (e as? ApiException)?.takeIf { it.code == -1 }?.message
                // 注册推送设备阶段连接的是 Google 的服务而非 GitHub，提示要如实反映
                val hint = if (raw != null && stage == "registration") raw.replace("GitHub", tr("Google 推送服务")) else raw
                AppLog.warn("2fa_setup", trf("数字审批自动启用失败：{0}，{1}", stage, e.javaClass.simpleName))
                if (hint == null) {
                    prefs.edit().remove("pending").apply()
                    lastCallback = null
                    issued = null
                }
                mutableState.value = ApprovalSetupState(error = hint ?: e.message ?: tr("数字审批启用失败"), canRetry = hint != null)
            }
        }
    }

    /** 网络失败后用同一次浏览器授权重新完成登录 */
    fun retry(context: Context) {
        val url = lastCallback ?: return
        complete(context, url)
    }

    /** 返回 Token 与 GitHub 实际授予的权限范围（兑换响应的 scope 字段，可能缺省） */
    private suspend fun exchange(code: String, verifier: String): Pair<String, Set<String>?> {
        val form = FormBody.Builder().add("client_id", ApprovalOAuthProtocol.CLIENT_ID).add("client_secret", CLIENT_SECRET)
            .add("code", code).add("code_verifier", verifier)
            .add("redirect_uri", ApprovalOAuthProtocol.REDIRECT).build()
        val request = Request.Builder().url("https://github.com/login/oauth/access_token")
            .header("Accept", "application/json").header("User-Agent", "MobileGH").post(form).build()
        var attempt = 1
        while (true) {
            try {
                val response = withContext(Dispatchers.IO) { client.routed().newCall(request).await().use {
                    check(it.isSuccessful) { trf("无法完成 GitHub 授权 ({0})", it.code) }
                    Api.plain.parseToJsonElement(it.body.string()).jsonObject
                } }
                val token = response["access_token"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: error(tr("GitHub 未完成授权，请重新开始"))
                return token to DeviceAuth.parseScopes(response["scope"]?.jsonPrimitive?.content)
            } catch (e: java.io.IOException) {
                if (attempt == EXCHANGE_ATTEMPTS) throw e
                AppLog.warn("2fa_setup", trf("兑换授权码连接失败，第 {0} 次：{1}", attempt, e.javaClass.simpleName))
                mutableState.value = ApprovalSetupState(busy = true, notice = trf("连接 GitHub 较慢，正在重试（{0}/{1}）", attempt + 1, EXCHANGE_ATTEMPTS))
                delay(2_000L * attempt++)
            }
        }
    }

    fun cancel(context: Context) {
        operation++
        job?.cancel()
        lastCallback = null
        issued = null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("pending").apply()
        mutableState.value = ApprovalSetupState()
    }
}
