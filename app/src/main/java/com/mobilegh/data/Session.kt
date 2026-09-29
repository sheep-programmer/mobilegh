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
data class Account(val login: String, val avatarUrl: String = "", val tk: String = "")

/** 登录态与偏好设置。Token 使用 Android Keystore(AES-GCM) 加密后存储。 */
object Session {
    private lateinit var prefs: SharedPreferences

    @Volatile
    var token: String? = null
        private set

    var login by mutableStateOf<String?>(null)
    var avatar by mutableStateOf<String?>(null)
    var themeMode by mutableIntStateOf(0) // 0 跟随系统 1 浅色 2 深色
    var codeWrap by mutableStateOf(false)
    var rateRemaining by mutableStateOf<String?>(null)
    val accounts = mutableStateListOf<Account>()

    /** 每次切换账号自增，用于重置界面状态 */
    var generation by mutableIntStateOf(0)

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("session", Context.MODE_PRIVATE)
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
        login = a.login
        avatar = a.avatarUrl
    }

    fun signIn(token: String, user: User) {
        val acc = Account(user.login, user.avatarUrl, Crypto.encrypt(token))
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
        login = null
        avatar = null
        Api.clearCache()
        val next = accounts.firstOrNull()
        prefs.edit().putString("current", next?.login).apply()
        next?.let { activate(it) }
        generation++
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
