package com.mobilegh.data

import android.net.Uri
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 生成基于时间的一次性密码（TOTP，RFC 6238）——GitHub「Authenticator app」两步验证用的就是这套。
 * 密钥在设置里由用户自己填入（从 GitHub 添加验证器时给的那串），本地即可算码，不联网、不需要第二台设备。
 */
object Totp {
    private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** 兼容直接粘贴密钥或整条 otpauth:// 链接；去掉空格/连字符并大写 */
    fun normalize(input: String): String {
        val s = input.trim()
        val secret = if (s.startsWith("otpauth://", ignoreCase = true)) {
            runCatching { Uri.parse(s).getQueryParameter("secret") }.getOrNull() ?: ""
        } else {
            s
        }
        return secret.replace(" ", "").replace("-", "").uppercase()
    }

    /** 校验密钥是否为合法 Base32 且能算出码 */
    fun isValid(secret: String): Boolean =
        runCatching { base32Decode(normalize(secret)).isNotEmpty() && now(secret).length == 6 }.getOrDefault(false)

    /** 当前时间片的 6 位验证码 */
    fun now(secret: String, timeMs: Long = System.currentTimeMillis(), digits: Int = 6, periodSec: Int = 30): String {
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

    private fun base32Decode(s: String): ByteArray {
        val clean = s.trim().trimEnd('=').uppercase()
        var buffer = 0
        var bits = 0
        val out = ArrayList<Byte>(clean.length * 5 / 8 + 1)
        for (c in clean) {
            val idx = BASE32.indexOf(c)
            if (idx < 0) continue
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
