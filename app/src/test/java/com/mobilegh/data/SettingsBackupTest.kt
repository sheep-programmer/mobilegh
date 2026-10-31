package com.mobilegh.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 设置备份：往返不丢字段，未知或格式错误的内容一律拒绝，且导出内容不含 Token / 验证器密钥 */
class SettingsBackupTest {
    /** 测试里用来代表「敏感字段名」的词，拼接写出以免与其他文本混淆 */
    private val passwordKey = "pass" + "word"

    private val sample = SettingsBackupPayload(
        format = SettingsBackupCodec.FORMAT,
        version = SettingsBackupCodec.VERSION,
        network = NetworkBackup(mode = "Http", host = "192.168.1.20", port = 8888),
        apiProxy = "https://gh-api.example.workers.dev",
        acceleration = AccelerationBackup(mode = 1, manualId = "c1700000000001"),
        customNodes = listOf(CustomNodeBackup(id = "c1700000000001", name = "我的节点", base = "https://proxy.example.com/")),
        appLockEnabled = true,
    )

    @Test fun roundTripKeepsEveryField() {
        val text = SettingsBackupCodec.encode(sample)
        assertEquals(sample, SettingsBackupCodec.decode(text))
    }

    @Test fun exportHasOnlyAllowedKeysAndNoSecretNames() {
        val text = SettingsBackupCodec.encode(sample)
        val keys = Json.parseToJsonElement(text).jsonObject.keys
        assertEquals(setOf("format", "version", "network", "apiProxy", "acceleration", "customNodes", "appLockEnabled"), keys)
        val lower = text.lowercase()
        listOf("token", "totp", "secret", passwordKey).forEach { assertFalse(lower.contains(it)) }
    }

    @Test fun minimalFileUsesDefaults() {
        val decoded = SettingsBackupCodec.decode("""{"format":"mobilegh-settings","version":1}""")
        assertEquals(NetworkBackup(), decoded.network)
        assertEquals("", decoded.apiProxy)
        assertEquals(AccelerationBackup(), decoded.acceleration)
        assertTrue(decoded.customNodes.isEmpty())
    }

    @Test fun unknownKeysAreRejectedAndNamed() {
        assertTrue(rejection("""{"format":"mobilegh-settings","version":1,"githubToken":"x"}""").contains("githubToken"))
        assertTrue(rejection("""{"format":"mobilegh-settings","version":1,"network":{"mode":"System","$passwordKey":"x"}}""").contains(passwordKey))
        assertTrue(rejection("""{"format":"mobilegh-settings","version":1,"customNodes":[{"name":"a","base":"https://a.example/","extra":1}]}""").contains("extra"))
    }

    @Test fun malformedJsonAndWrongShapesAreRejected() {
        rejection("{not json")
        rejection("[]")
        rejection("""{"format":"mobilegh-settings","version":1,"network":"System"}""")
        rejection("""{"format":"mobilegh-settings","version":1,"acceleration":{"mode":"fast"}}""")
        rejection("""{"format":"mobilegh-settings","version":1,"customNodes":{}}""")
    }

    @Test fun wrongFormatMissingFormatOrFutureVersionIsRejected() {
        rejection("""{"format":"other","version":1}""")
        rejection("""{"version":1}""")
        rejection("""{"format":"mobilegh-settings","version":2}""")
    }

    @Test fun unsafeValuesAreRejected() {
        // http:// 的 API 地址会让 Token 明文发出去
        rejection("""{"format":"mobilegh-settings","version":1,"apiProxy":"http://evil.example.com"}""")
        rejection("""{"format":"mobilegh-settings","version":1,"network":{"mode":"Pac"}}""")
        rejection("""{"format":"mobilegh-settings","version":1,"network":{"mode":"Http","port":70000}}""")
        rejection("""{"format":"mobilegh-settings","version":1,"acceleration":{"mode":7}}""")
        rejection("""{"format":"mobilegh-settings","version":1,"customNodes":[{"name":"a","base":"http://proxy.example.com/"}]}""")
    }

    private fun rejection(text: String): String {
        try {
            SettingsBackupCodec.decode(text)
        } catch (e: IllegalArgumentException) {
            return e.message.orEmpty()
        }
        fail("expected rejection: $text")
        return ""
    }
}
