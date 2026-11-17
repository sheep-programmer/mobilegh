package com.mobilegh.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LogExportTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun writesCompleteUtf8ContentToLogFile() {
        val content = buildString {
            appendLine("MobileGH 崩溃诊断")
            repeat(20_000) { appendLine("[$it] java.lang.IllegalArgumentException: 完整堆栈与上下文") }
            append("日志结束：没有截断")
        }

        val file = LogExport.write(tmp.root, "filtered", content)

        assertTrue(file.isFile)
        assertEquals("log", file.extension)
        assertEquals(content, file.readText(Charsets.UTF_8))
    }

    @Test fun consecutiveExportsKeepBothFiles() {
        val first = LogExport.write(tmp.root, "all", "第一次导出")
        val second = LogExport.write(tmp.root, "all", "第二次导出")

        assertNotEquals(first.absolutePath, second.absolutePath)
        assertEquals("第一次导出", first.readText())
        assertEquals("第二次导出", second.readText())
    }

    @Test fun ageBoundaryKeepsFileAtExactlyThirtyDays() {
        val now = 40L * 24 * 60 * 60 * 1000
        val expired = log("expired", now - 30L * 24 * 60 * 60 * 1000 - 1)
        val boundary = log("boundary", now - 30L * 24 * 60 * 60 * 1000)

        LogExport.prune(tmp.root, now)

        assertFalse(expired.exists())
        assertTrue(boundary.exists())
    }

    @Test fun pruningUsesAgeBeforeNewestFirstCount() {
        val now = 40L * 24 * 60 * 60 * 1000
        val expired = log("expired", now - 30L * 24 * 60 * 60 * 1000 - 1)
        val recent = (0 until 25).map { log("recent-$it", now - it) }

        LogExport.prune(tmp.root, now)

        assertFalse(expired.exists())
        assertTrue(recent.takeLast(5).all { !it.exists() })
        assertTrue(recent.take(20).all { it.exists() })
        assertEquals(20, tmp.root.listFiles { file -> file.extension == "log" }!!.size)
    }

    @Test fun currentLogAndUnrelatedEntriesAreNeverPruned() {
        val now = 100L * 24 * 60 * 60 * 1000
        val current = log("current", 1)
        val notes = File(tmp.root, "notes.txt").apply { writeText("keep") }
        val directory = File(tmp.root, "archive.log").apply { mkdir() }
        val nested = File(directory, "nested.log").apply { writeText("keep") }
        repeat(25) { log("other-$it", now - it) }

        LogExport.prune(tmp.root, now, current = current)

        assertTrue(current.isFile)
        assertEquals("keep", notes.readText())
        assertTrue(nested.isFile)
        assertEquals(21, tmp.root.listFiles { file -> file.isFile && file.extension == "log" }!!.size)
    }

    private fun log(name: String, modified: Long): java.io.File =
        File(tmp.root, "$name.log").apply {
            writeText(name)
            assertTrue(setLastModified(modified))
        }
}
