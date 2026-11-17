package com.mobilegh.ui.components

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志导出的用户可见契约：分享面板收到的是可读取的 .log 文件 URI，
 * 而不是可能被系统截断的 EXTRA_TEXT 文本。
 */
class LogShareTest {
    /** 拦住 startActivity，避免真的弹出系统分享面板。 */
    private class RecordingContext(base: Context) : ContextWrapper(base) {
        val started: MutableList<Intent> = Collections.synchronizedList(mutableListOf())

        override fun startActivity(intent: Intent) {
            started += intent
        }
    }

    private fun export(
        context: RecordingContext,
        kind: String,
        waitMs: Long = 15_000,
        content: () -> String,
    ): List<Intent> {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            context.shareLog(CoroutineScope(Dispatchers.Main), kind) { content() }
        }
        val deadline = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < deadline && context.started.isEmpty()) Thread.sleep(20)
        return context.started.toList()
    }

    @Test fun sharedLogIsAReadableUtf8FileNotExtraText() {
        val context = RecordingContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val content = buildString {
            appendLine("MobileGH 日志导出")
            repeat(2_000) { appendLine("[$it] 中文与 emoji 也要完整保留 😀") }
            append("结尾没有截断")
        }

        val chooser = export(context, "share-contract") { content }.single()

        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = requireNotNull(IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertNull("日志内容不应塞进 EXTRA_TEXT", send.getStringExtra(Intent.EXTRA_TEXT))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)

        val stream = requireNotNull(IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals("content", stream.scheme)
        assertEquals(context.packageName + ".fileprovider", stream.authority)
        assertTrue("导出文件必须带 .log 后缀，实际=${stream.lastPathSegment}", stream.lastPathSegment.orEmpty().endsWith(".log"))
        assertEquals(stream, send.clipData?.getItemAt(0)?.uri)

        val received = context.contentResolver.openInputStream(stream)!!.use {
            it.readBytes().toString(Charsets.UTF_8)
        }
        assertEquals(content, received)
    }

    @Test fun failedExportStartsNoShareSheetAndLeavesNoLogFile() {
        val context = RecordingContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val directory = File(context.cacheDir, "log-exports")
        val kind = "share-failure-${System.currentTimeMillis()}"
        val before = directory.listFiles().orEmpty().map { it.name }.toSet()

        // 失败分支没有成功信号，只能在有限窗口内确认它确实什么也没启动。
        export(context, kind, waitMs = 2_000) { throw IllegalStateException("collect failed") }

        // 失败分支只会写一条警告和弹提示，不会启动分享面板，也不会留下半截日志。
        assertEquals(emptyList<Intent>(), context.started.toList())
        val added = directory.listFiles().orEmpty().map { it.name }.filter { it !in before }
        assertTrue("失败的导出不应留下文件，实际新增=$added", added.none { it.contains(kind) })
    }
}
