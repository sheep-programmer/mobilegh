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

internal object ApprovalOAuthProtocol {
    const val CLIENT_ID = "3f8b8834a91f0caad392"
    const val REDIRECT = "github://com.github.android/oauth"
    private val random = SecureRandom()
    fun randomString() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    fun challenge(verifier: String) = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    fun callback(url: String, expectedState: String): String {
        val uri = URI(url)
        require(uri.scheme == "github" && uri.rawAuthority == "com.github.android" && uri.path == "/oauth" && uri.fragment == null) { "授权回调地址无效" }
        val pairs = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.map {
            val split = it.split('=', limit = 2)
            URLDecoder.decode(split[0], "UTF-8") to URLDecoder.decode(split.getOrElse(1) { "" }, "UTF-8")
        }
        require(pairs.map { it.first }.distinct().size == pairs.size) { "授权回调参数无效" }
        val params = pairs.toMap()
        require(params["iss"] == null || params["iss"] == "https://github.com/login/oauth") { "授权提供方不匹配" }
        require(MessageDigest.isEqual(params["state"].orEmpty().toByteArray(), expectedState.toByteArray())) { "授权请求不匹配，请重新开始" }
        check(params["error"] == null) { "GitHub 授权已取消" }
        return params["code"]?.takeIf { it.isNotBlank() } ?: error("GitHub 未返回授权码")
    }
}

data class ApprovalSetupState(val busy: Boolean = false, val error: String? = null, val waitingForBrowser: Boolean = false)

/** A single browser authorization automatically provisions a native device on this installation. */
object MobileApprovalOAuth {
    private const val PREFS = "mobile_approval_oauth"
    // Public native-client credential distributed in the provider's APK, never a user credential.
    private const val CLIENT_SECRET = "00e76fc8358899d7795a46cd04ace865fcdc0165"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(ApprovalSetupState())
    val state = mutableState.asStateFlow()
    private var job: kotlinx.coroutines.Job? = null
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).followRedirects(false).build()

    fun begin(context: Context): String {
        check(!state.value.busy) { "正在等待 GitHub 授权" }
        val verifier = ApprovalOAuthProtocol.randomString()
        val state = ApprovalOAuthProtocol.randomString()
        val pending = buildJsonObject { put("state", state); put("verifier", verifier)
            put("createdAt", System.currentTimeMillis()); put("login", Session.login.orEmpty()) }
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("pending", Crypto.encrypt(pending.toString())).commit()) { "无法保存授权请求" }
        mutableState.value = ApprovalSetupState(busy = true, waitingForBrowser = true)
        val values = listOf("client_id" to ApprovalOAuthProtocol.CLIENT_ID, "redirect_uri" to ApprovalOAuthProtocol.REDIRECT,
            "scope" to "user repo notifications gist read:org workflow", "state" to state,
            "code_challenge" to ApprovalOAuthProtocol.challenge(verifier), "code_challenge_method" to "S256")
        return "https://github.com/login/oauth/authorize?" + values.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
    }

    fun handles(url: String): Boolean = runCatching {
        URI(url).let { it.scheme == "github" && it.host == "com.github.android" && it.path == "/oauth" }
    }.getOrDefault(false)

    fun complete(context: Context, url: String) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            mutableState.value = ApprovalSetupState(busy = true)
            var stage = "callback"
            try {
                val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val encrypted = prefs.getString("pending", null)
                if (encrypted == null) {
                    mutableState.value = ApprovalSetupState(error = "授权请求已结束，请重新开始")
                    return@launch
                }
                val pending = Api.plain.parseToJsonElement(Crypto.decrypt(encrypted)).jsonObject
                check(System.currentTimeMillis() - pending.getValue("createdAt").jsonPrimitive.long in 0..900_000) { "授权请求已过期" }
                val code = ApprovalOAuthProtocol.callback(url, pending.getValue("state").jsonPrimitive.content)
                check(prefs.edit().remove("pending").commit()) { "无法更新授权请求" }
                stage = "token"
                AppLog.info("2fa_setup", "开始完成 GitHub 授权")
                val form = FormBody.Builder().add("client_id", ApprovalOAuthProtocol.CLIENT_ID).add("client_secret", CLIENT_SECRET)
                    .add("code", code).add("code_verifier", pending.getValue("verifier").jsonPrimitive.content)
                    .add("redirect_uri", ApprovalOAuthProtocol.REDIRECT).build()
                val request = Request.Builder().url("https://github.com/login/oauth/access_token")
                    .header("Accept", "application/json").header("User-Agent", "MobileGH").post(form).build()
                val response = withContext(Dispatchers.IO) { client.newCall(request).await().use {
                    check(it.isSuccessful) { "无法完成 GitHub 授权 (${it.code})" }
                    Api.plain.parseToJsonElement(it.body.string()).jsonObject
                } }
                val token = response["access_token"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: error("GitHub 未完成授权，请重新开始")
                val expectedLogin = pending.getValue("login").jsonPrimitive.content.takeIf { it.isNotBlank() } ?: Session.login
                check(expectedLogin == null || Session.login == null || expectedLogin.equals(Session.login, true)) { "账号已切换，请重新授权" }
                stage = "registration"
                AppLog.info("2fa_setup", "开始自动注册本机验证设备")
                MobileApproval.activate(app, token, expectedLogin)
                if (Session.token == null) Session.signIn(token, MobileApproval.user(token), clientId = ApprovalOAuthProtocol.CLIENT_ID)
                MobileApprovalMonitor.reload(app)
                MobileApprovalMonitor.ensureStarted(app)
                mutableState.value = ApprovalSetupState()
                AppLog.info("2fa_setup", "数字审批已自动启用")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                AppLog.warn("2fa_setup", "数字审批自动启用失败：$stage，${e.javaClass.simpleName}")
                mutableState.value = ApprovalSetupState(error = e.message ?: "数字审批启用失败")
            }
        }
    }

    fun cancel(context: Context) {
        job?.cancel()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("pending").apply()
        mutableState.value = ApprovalSetupState()
    }
}
