package com.mobilegh.nav

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Screen 的唯一序列化入口。
 *
 * 编译生成的 `Screen.serializer()` 不缓存：每次调用都 `new SealedClassSerializer`，
 * 并重新构造全部 75 个页面类型的 SerialDescriptor。冷启动第一次调用（要连带加载
 * 75 个 `$$serializer` 类）在主线程上足以撞到 ANR 阈值——卡顿看门狗实测抓到过
 * 「主线程卡顿超过 5 秒」，堆栈就是 PluginGeneratedSerialDescriptor.<init>。
 * 之后每次导航保存状态又会重建一遍。
 *
 * 所以这里只构造一次并复用；[warmUp] 让 App 启动时在后台线程先把它建好。
 */
object ScreenCodec {
    val json = Json {
        classDiscriminator = "_screen"
        ignoreUnknownKeys = true
    }

    /** 草稿里记录来源页面用：不写默认值让文本更短；旧版本留下的字段忽略掉 */
    val draftJson = Json {
        encodeDefaults = false
        ignoreUnknownKeys = true
    }

    /** 构造一次，之后所有编解码共用 */
    val screen: KSerializer<Screen> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { Screen.serializer() }

    /** 导航栈用；同样只建一次 */
    val screenList: KSerializer<List<Screen>> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { ListSerializer(screen) }

    /** 在后台线程调用：把构造描述符的开销挪出主线程 */
    fun warmUp() {
        runCatching { screenList }
    }
}
