package com.mobilegh.nav

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** 恢复结果：Tab 与栈底到栈顶的页面（不含 Root）。 */
data class RestoredNav(val tab: Tab, val screens: List<Screen>)

/**
 * 进程被系统回收后恢复导航栈与当前 Tab。
 * encode / decode 都是纯函数，导航状态的写入与读取只经过这里。
 */
object NavRestore {
    /** SavedStateHandle 中保存导航状态所用的 key */
    const val KEY = "navState"

    private const val VERSION = 1
    private val HOME = RestoredNav(Tab.Home, emptyList())

    // 多态标记不能与 Screen 的字段重名（例如 Search.type、Projects.kind）
    private val json = ScreenCodec.json

    /** 把 Tab 与栈编码为 JSON 文本；登录账号一并记录，恢复时用于校验。 */
    fun encode(tab: Tab, screens: List<Screen>, login: String?): String {
        val stack = screens.filter { it != Screen.Root }
        return buildJsonObject {
            put("v", VERSION)
            put("login", login)
            put("tab", tab.name)
            put("stack", json.encodeToJsonElement(ScreenCodec.screenList, stack))
        }.toString()
    }

    /**
     * 解码并校验。下列情况都回退到首页（Home Tab、空栈）：
     * 文本为空或不是 JSON 对象、版本不对、Tab 无法识别、缺少 stack、账号与当前登录账号不一致。
     * 栈中无法识别的页面（类型已删除、字段缺失等）逐条丢弃，其余页面照常恢复。
     * 栈全部被丢弃时保留已保存的 Tab：那是用户所在的标签根页面。
     */
    fun decode(text: String?, currentLogin: String?): RestoredNav {
        if (text.isNullOrBlank()) return HOME
        val obj = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return HOME
        if ((obj["v"] as? JsonPrimitive)?.intOrNull != VERSION) return HOME
        if ((obj["login"] as? JsonPrimitive)?.contentOrNull != currentLogin) return HOME
        val tab = Tab.entries.firstOrNull { it.name == (obj["tab"] as? JsonPrimitive)?.contentOrNull } ?: return HOME
        val items = obj["stack"] as? JsonArray ?: return HOME
        val screens = items.mapNotNull { item ->
            runCatching { json.decodeFromJsonElement(ScreenCodec.screen, item) }.getOrNull()
        }.filter { it != Screen.Root }
        return RestoredNav(tab, screens)
    }
}

/** IntRange 无法直接序列化，按 "first..last" 字符串存储。 */
object IntRangeSerializer : KSerializer<IntRange> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.mobilegh.nav.IntRange", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: IntRange) {
        encoder.encodeString("${value.first}..${value.last}")
    }

    override fun deserialize(decoder: Decoder): IntRange {
        val text = decoder.decodeString()
        val parts = text.split("..")
        val first = parts.getOrNull(0)?.toIntOrNull()
        val last = parts.getOrNull(1)?.toIntOrNull()
        if (parts.size != 2 || first == null || last == null) throw SerializationException("bad IntRange: $text")
        return first..last
    }
}
