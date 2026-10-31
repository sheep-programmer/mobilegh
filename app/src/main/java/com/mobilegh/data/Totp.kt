package com.mobilegh.data

import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import com.mobilegh.i18n.tr

/**
 * 生成基于时间的一次性密码（TOTP，RFC 6238）——GitHub「Authenticator app」两步验证用的就是这套。
 * 密钥在设置里由用户自己填入（从 GitHub 添加验证器时给的那串），本地即可算码，不联网、不需要第二台设备。
 */
object Totp {
    private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /**
     * otpauth://totp/… 链接解析结果。
     * [issuer] 与 [account] 来自标签（如 `GitHub:用户名`）和 issuer 参数，用于让用户确认这条验证器属于哪个账号。
     */
    data class OtpAuth(val issuer: String?, val account: String?, val secret: String)

    /** 解析 otpauth:// 链接；不是链接返回 null。算法、位数、周期不是 GitHub 使用的值时抛出异常。 */
    fun parseOtpAuth(input: String): OtpAuth? {
        val s = input.trim()
        if (!s.startsWith("otpauth://", ignoreCase = true)) return null
        val uri = URI(s)
        require(uri.host.equals("totp", true)) { tr("仅支持 TOTP 链接") }
        val params = uri.rawQuery.orEmpty().split('&').associate {
            val parts = it.split('=', limit = 2)
            parts[0] to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        }
        require(params["algorithm"].orEmpty().ifBlank { "SHA1" }.equals("SHA1", true)) { tr("GitHub 使用 SHA1 验证码") }
        require(params["digits"].orEmpty().ifBlank { "6" } == "6") { tr("GitHub 使用 6 位验证码") }
        require(params["period"].orEmpty().ifBlank { "30" } == "30") { tr("GitHub 使用 30 秒验证码") }
        // URI.path 已经解码百分号编码；标签形如 发行方:账号，只有账号时整个标签就是账号
        val label = uri.path.orEmpty().trimStart('/')
        val colon = label.indexOf(':')
        val labelIssuer = if (colon >= 0) label.substring(0, colon).trim() else ""
        val labelAccount = (if (colon >= 0) label.substring(colon + 1) else label).trim()
        return OtpAuth(
            issuer = params["issuer"]?.takeIf { it.isNotBlank() } ?: labelIssuer.takeIf { it.isNotBlank() },
            account = labelAccount.takeIf { it.isNotBlank() },
            secret = params["secret"].orEmpty(),
        )
    }

    /** 兼容直接粘贴密钥或整条 otpauth:// 链接；去掉空格/连字符并大写 */
    fun normalize(input: String): String {
        val secret = parseOtpAuth(input)?.secret ?: input.trim()
        return secret.filterNot { it.isWhitespace() || it == '-' }.uppercase(Locale.ROOT)
    }

    /** 校验密钥是否为合法 Base32 且能算出码 */
    fun isValid(secret: String): Boolean =
        runCatching { base32Decode(normalize(secret)).size >= 10 && now(secret).length == 6 }.getOrDefault(false)

    /** 当前时间片的 6 位验证码 */
    fun now(secret: String, timeMs: Long = System.currentTimeMillis(), digits: Int = 6, periodSec: Int = 30): String {
        require(digits in 6..8 && periodSec > 0 && timeMs >= 0) { "invalid TOTP parameters" }
        val key = base32Decode(normalize(secret))
        require(key.isNotEmpty()) { "empty secret" }
        val counter = timeMs / 1000L / periodSec
        val msg = ByteArray(8)
        var v = counter
        for (i in 7 downTo 0) {
            msg[i] = (v and 0xff).toByte()
            v = v shr 8
        }
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }
        val h = mac.doFinal(msg)
        val off = h[h.size - 1].toInt() and 0x0f
        val bin = ((h[off].toInt() and 0x7f) shl 24) or
            ((h[off + 1].toInt() and 0xff) shl 16) or
            ((h[off + 2].toInt() and 0xff) shl 8) or
            (h[off + 3].toInt() and 0xff)
        var mod = 1
        repeat(digits) { mod *= 10 }
        return (bin % mod).toString().padStart(digits, '0')
    }

    /** 当前码还有多少秒过期，用于界面倒计时 */
    fun secondsRemaining(periodSec: Int = 30): Int =
        (periodSec - (System.currentTimeMillis() / 1000L % periodSec)).toInt()

    /**
     * 校验输入的 6 位验证码（允许前后各一个时间片的时钟漂移，与 GitHub 服务端行为一致）。
     * 用于导入密钥后的自检：输入网页当前要求的码，确认两端一致。
     */
    fun verify(secret: String, code: String, timeMs: Long = System.currentTimeMillis(), periodSec: Int = 30): Boolean {
        if (code.length != 6 || code.any { !it.isDigit() }) return false
        val counter = timeMs / 1000L / periodSec
        return (counter - 1..counter + 1).any { runCatching { now(secret, it * periodSec * 1000L) == code }.getOrDefault(false) }
    }

    private fun base32Decode(s: String): ByteArray {
        val clean = s.trim().trimEnd('=').uppercase(Locale.ROOT)
        require(clean.isNotEmpty() && clean.all { it in BASE32 }) { "invalid Base32 secret" }
        require(clean.length % 8 !in listOf(1, 3, 6)) { "truncated Base32 secret" }
        var buffer = 0
        var bits = 0
        val out = ArrayList<Byte>(clean.length * 5 / 8 + 1)
        for (c in clean) {
            val idx = BASE32.indexOf(c)
            require(idx >= 0) { "invalid Base32 character" }
            buffer = (buffer shl 5) or idx
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.add(((buffer shr bits) and 0xff).toByte())
            }
        }
        return out.toByteArray()
    }
}
