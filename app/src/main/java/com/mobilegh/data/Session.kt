package com.mobilegh.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
data class Account(val login: String, val avatarUrl: String = "", val tk: String = "", val refreshTk: String = "", val expiresAt: Long = 0, val clientId: String = "")

data class OAuthCredentials(val token: String, val refreshToken: String, val expiresAt: Long, val clientId: String, val previousToken: String? = null)

/** 登录态与偏好设置。Token 使用 Android Keystore(AES-GCM) 加密后存储。 */
object Session {
    private lateinit var prefs: SharedPreferences

    @Volatile
    var token: String? = null
        private set

    @Volatile
    var oauth: OAuthCredentials? = null
        private set

    var login by mutableStateOf<String?>(null)
    var avatar by mutableStateOf<String?>(null)

    /** 当前 Token 被 GitHub 判定失效（401），界面据此弹窗提示重新授权 */
    var authExpired by mutableStateOf(false)
    var themeMode by mutableIntStateOf(0) // 0 跟随系统 1 浅色 2 深色
    var codeWrap by mutableStateOf(false)
    var rateRemaining by mutableStateOf<String?>(null)
    val accounts = mutableStateListOf<Account>()

    /** 每次切换账号自增，用于重置界面状态 */
    var generation by mutableIntStateOf(0)

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("session", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("native_browser_migration", false) && prefs.contains("accounts")) {
            // Retire stale embedded-login cookies and password sessionStorage from earlier versions.
            runCatching {
                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                android.webkit.CookieManager.getInstance().flush()
                android.webkit.WebStorage.getInstance().deleteAllData()
            }
            prefs.edit().putBoolean("native_browser_migration", true).apply()
        }
        themeMode = prefs.getInt("theme", 0)
        codeWrap = prefs.getBoolean("wrap", false)
        accounts.clear()
        prefs.getString("accounts", null)?.let {
            runCatching { accounts.addAll(Api.plain.decodeFromString(ListSerializer(Account.serializer()), it)) }
        }
        val cur = prefs.getString("current", null)
        accounts.firstOrNull { it.login == cur }?.let { activate(it) }
    }

    private fun activate(a: Account) {
        val t = runCatching { Crypto.decrypt(a.tk) }.getOrNull() ?: return
        token = t
        oauth = a.refreshTk.takeIf { it.isNotBlank() }?.let { encrypted ->
            runCatching { OAuthCredentials(t, Crypto.decrypt(encrypted), a.expiresAt, a.clientId) }.getOrNull()
        }
        login = a.login
        avatar = a.avatarUrl
        authExpired = false
    }

    fun signIn(token: String, user: User, refreshToken: String? = null, expiresIn: Long? = null, clientId: String = "") {
        val acc = Account(user.login, user.avatarUrl, Crypto.encrypt(token),
            refreshToken?.takeIf { it.isNotBlank() }?.let(Crypto::encrypt).orEmpty(),
            expiresIn?.let { System.currentTimeMillis() + it.coerceAtMost(31_536_000) * 1000 } ?: 0, clientId)
        accounts.removeAll { it.login.equals(user.login, true) }
        accounts.add(0, acc)
        saveAccounts()
        prefs.edit().putString("current", acc.login).apply()
        Api.clearCache()
        activate(acc)
        generation++
    }

    fun switchTo(login: String) {
        val a = accounts.firstOrNull { it.login == login } ?: return
        prefs.edit().putString("current", a.login).apply()
        Api.clearCache()
        activate(a)
        generation++
    }

    fun signOut() {
        val cur = login
        accounts.removeAll { it.login == cur }
        saveAccounts()
        token = null
        oauth = null
        login = null
        avatar = null
        Api.clearCache()
        val next = accounts.firstOrNull()
        prefs.edit().putString("current", next?.login).apply()
        next?.let { activate(it) }
        generation++
    }

    /** Called on the main dispatcher; refreshing credentials must not reset the navigation stack. */
    fun renewOAuth(expectedToken: String, result: DevicePoll.Authorized): String? {
        val old = oauth ?: return null
        if (token != expectedToken || old.token != expectedToken) return null
        val current = accounts.firstOrNull { it.login == login } ?: return null
        val next = current.copy(tk = Crypto.encrypt(result.token),
            refreshTk = Crypto.encrypt(result.refreshToken ?: old.refreshToken),
            expiresAt = result.expiresIn?.let { System.currentTimeMillis() + it.coerceAtMost(31_536_000) * 1000 } ?: 0)
        accounts[accounts.indexOf(current)] = next
        saveAccounts()
        activate(next)
        oauth = oauth?.copy(previousToken = expectedToken)
        return token
    }

    /** 是否为该账号存了 TOTP 密钥（用于设置界面显示状态） */
    fun hasTotp(login: String?): Boolean = login != null && prefs.contains(totpKey(login))

    /** 取出该账号的 TOTP 密钥明文（仅在本机算码时用） */
    fun totpSecret(login: String?): String? {
        login ?: return null
        val enc = prefs.getString(totpKey(login), null) ?: return null
        return runCatching { Crypto.decrypt(enc) }.getOrNull()
    }

    /** 存 / 删该账号的 TOTP 密钥；密钥同样经 Keystore 加密 */
    fun setTotpSecret(login: String, secret: String?) {
        require(login.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}"))) { "GitHub 用户名格式无效" }
        require(secret.isNullOrBlank() || Totp.isValid(secret)) { "验证器密钥无效" }
        val e = prefs.edit()
        if (secret.isNullOrBlank()) e.remove(totpKey(login)) else e.putString(totpKey(login), Crypto.encrypt(Totp.normalize(secret)))
        e.apply()
    }

    /** 该账号当前的 6 位验证码，没存密钥时返回 null */
    fun totpCode(login: String?): String? {
        val s = totpSecret(login) ?: return null
        return runCatching { Totp.now(s) }.getOrNull()
    }

    private fun totpKey(login: String) = "totp_${login.lowercase()}"

    fun totpAccounts(): List<String> = prefs.all.keys.filter { it.startsWith("totp_") }
        .map { it.removePrefix("totp_") }.sorted()

    fun setTheme(mode: Int) {
        themeMode = mode
        prefs.edit().putInt("theme", mode).apply()
    }

    fun toggleWrap() {
        codeWrap = !codeWrap
        prefs.edit().putBoolean("wrap", codeWrap).apply()
    }

    private fun saveAccounts() {
        prefs.edit().putString("accounts", Api.plain.encodeToString(ListSerializer(Account.serializer()), accounts.toList())).apply()
    }
}

private object Crypto {
    private const val ALIAS = "mobilegh_token_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return kg.generateKey()
    }

    fun encrypt(s: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(c.iv + c.doFinal(s.toByteArray()), Base64.NO_WRAP)
    }

    fun decrypt(s: String): String {
        val b = Base64.decode(s, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12))
        return String(c.doFinal(b, 12, b.size - 12))
    }
}
