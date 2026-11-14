package com.mobilegh.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ScreenCodecTest {

    /**
     * 编译生成的 Screen.serializer() 每次调用都会新建 SealedClassSerializer 并重建
     * 75 个页面的描述符；必须经由 ScreenCodec 复用同一份，否则每次导航保存状态都在主线程重建一遍。
     */
    @Test
    fun screenSerializerIsConstructedOnce() {
        assertSame(ScreenCodec.screen, ScreenCodec.screen)
    }

    @Test
    fun warmUpDoesNotChangeBehaviour() {
        ScreenCodec.warmUp()
        val text = NavRestore.encode(Tab.Home, listOf(Screen.Repo("octo", "hello")), null)
        assertEquals(RestoredNav(Tab.Home, listOf(Screen.Repo("octo", "hello"))), NavRestore.decode(text, null))
    }

    /** 草稿里的来源页面沿用 kotlinx 默认的多态字段名 type：已存的草稿必须还能读回来 */
    @Test
    fun draftJsonKeepsExistingDiscriminatorAndDropsDefaults() {
        val encoded = ScreenCodec.draftJson.encodeToString(ScreenCodec.screen, Screen.Settings)
        assertEquals("""{"type":"com.mobilegh.nav.Screen.Settings"}""", encoded)
        assertEquals(Screen.Settings, ScreenCodec.draftJson.decodeFromString(ScreenCodec.screen, encoded))
    }
}
