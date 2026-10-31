package com.mobilegh.data

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 端到端：通过本机 HTTPS 测试服务器（10.0.2.2:8765，限速、支持 Range、自签名证书仅 debug 信任）下载 8 MB 文件，
 * 中途暂停再继续，校验暂停期间不再增长、最终内容完整。需要先在主机运行测试服务器。
 */
@RunWith(AndroidJUnit4::class)
class DownloadFlowTest {
    private val url = "https://10.0.2.2:8765/big.bin"
    private val size = 8 * 1024 * 1024
    private fun task() = Downloads.tasks.firstOrNull { it.url == url }

    @Test fun pauseKeepsProgressAndResumeCompletesWithFullContent() {
        runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        Downloads.tasks.filter { it.url == url }.forEach { Downloads.forget(ctx, it) }
        Downloads.start(url, "flow-test.bin", isPrivate = false)

        withTimeout(60_000) { while ((task()?.downloaded ?: 0L) < 2_000_000L) { assertNotEquals(DownloadState.Failed, task()?.state); delay(100) } }
        Downloads.togglePause(task()!!)
        withTimeout(20_000) { while (task()?.state != DownloadState.Waiting) delay(100) }
        assertTrue(task()!!.paused)
        val pausedAt = task()!!.downloaded
        delay(2_000)
        assertEquals("暂停期间进度不应增长", pausedAt, task()!!.downloaded)

        Downloads.togglePause(task()!!)
        withTimeout(180_000) {
            while (task()?.state != DownloadState.Complete) { assertNotEquals(DownloadState.Failed, task()?.state); delay(200) }
        }
        val done = task()!!
        assertTrue("文件应可读取", Downloads.fileExists(done))
        val bytes = readAll(ctx.contentResolver, done)
        assertEquals("文件大小应与服务器一致", size, bytes.size)
        // 逐字节比对：第一个不一致的位置（没有则为 -1）
        var firstMismatch = -1
        for (i in bytes.indices) {
            if (bytes[i] != ((i * 31 + i / 251) and 0xFF).toByte()) { firstMismatch = i; break }
        }
        assertEquals("第一个不一致的字节位置", -1, firstMismatch)
        Downloads.deleteFile(ctx, done)
        }
    }

    private fun readAll(resolver: android.content.ContentResolver, task: DownloadTask): ByteArray {
        val stream = task.contentUri?.let { resolver.openInputStream(Uri.parse(it)) }
            ?: task.filePath?.let { File(it).inputStream() }
            ?: error("没有可读取的文件")
        return stream.use { it.readBytes() }
    }
}
