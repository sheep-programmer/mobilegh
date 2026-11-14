package com.mobilegh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class AppLogTest {

    @Before
    fun setup() {
        AppLog.clearBreadcrumbs()
    }

    @Test
    fun extractRootCause_unwindsSingleException() {
        val ex = IllegalArgumentException("Bad input")
        val root = AppLog.extractRootCause(ex)
        assertEquals(ex, root)
    }

    @Test
    fun extractRootCause_unwindsNestedChain() {
        val deep = IllegalStateException("Root failure")
        val mid = IOException("IO failed", deep)
        val outer = RuntimeException("Outer crash", mid)

        val root = AppLog.extractRootCause(outer)
        assertEquals(deep, root)
        assertEquals("Root failure", root.message)
    }

    @Test
    fun extractRootCause_handlesCycleSafely() {
        val ex1 = Exception("Exception 1")
        val ex2 = Exception("Exception 2", ex1)
        // Set ex1 cause to ex2 creating a cycle
        runCatching {
            val field = Throwable::class.java.getDeclaredField("cause")
            field.isAccessible = true
            field.set(ex1, ex2)
        }

        // Must terminate without StackOverflowError or infinite loop
        val root = AppLog.extractRootCause(ex1)
        assertNotNull(root)
    }

    @Test
    fun extractRootCauseLocation_findsAppClass() {
        val ex = RuntimeException("Boom")
        ex.stackTrace = arrayOf(
            StackTraceElement("android.os.Looper", "loop", "Looper.java", 120),
            StackTraceElement("com.mobilegh.ui.screens.RepoScreenKt", "RepoContent", "RepoScreen.kt", 142),
            StackTraceElement("com.mobilegh.nav.Navigator", "push", "Nav.kt", 88),
        )

        val location = AppLog.extractRootCauseLocation(ex)
        assertEquals("RepoScreen.kt:142", location)
    }

    @Test
    fun extractRootCauseLocation_fallsBackWhenNoAppClass() {
        val ex = RuntimeException("Foreign crash")
        ex.stackTrace = arrayOf(
            StackTraceElement("java.util.ArrayList", "get", "ArrayList.java", 432),
        )

        val location = AppLog.extractRootCauseLocation(ex)
        assertEquals("ArrayList.java:432", location)
    }

    @Test
    fun decodeSignal_decodesKnownPosixSignals() {
        assertEquals("SIGSEGV 段错误/非法内存访问", AppLog.decodeSignal(11))
        assertEquals("SIGABRT 中止", AppLog.decodeSignal(6))
        assertEquals("SIGKILL 强制终止", AppLog.decodeSignal(9))
        assertEquals("SIGBUS 总线错误", AppLog.decodeSignal(7))
        assertEquals("SIGFPE 浮点异常", AppLog.decodeSignal(8))

        // Encoded signal in exit status (e.g. 128 + 11 = 139)
        assertEquals("SIGSEGV 段错误/非法内存访问", AppLog.decodeSignal(139))

        // 0 means no signal
        assertNull(AppLog.decodeSignal(0))
    }

    @Test
    fun decodeImportance_decodesImportanceLevels() {
        assertTrue(AppLog.decodeImportance(100).contains("前台"))
        assertTrue(AppLog.decodeImportance(400).contains("后台"))
        assertTrue(AppLog.decodeImportance(1000).contains("销毁"))
    }

    @Test
    fun formatUptime_formatsAccurately() {
        assertEquals("0秒 (500ms)", AppLog.formatUptime(500))
        assertEquals("12秒 (12000ms)", AppLog.formatUptime(12000))
        assertEquals("1分 5秒", AppLog.formatUptime(65000))
        assertEquals("1小时 1分 5秒", AppLog.formatUptime(3665000))
    }

    @Test
    fun breadcrumbs_rollingBufferTrimsToMax() {
        for (i in 1..100) {
            AppLog.breadcrumb("test", "Action $i")
        }

        val recent = AppLog.getRecentBreadcrumbs()
        assertEquals(60, recent.size)
        assertEquals("Action 41", recent.first().message)
        assertEquals("Action 100", recent.last().message)

        val formatted = AppLog.formatBreadcrumbs()
        assertTrue(formatted.contains("[test] Action 100"))
        assertTrue(formatted.contains("[test] Action 41"))
    }

    @Test
    fun buildCrashReport_containsComprehensiveSections() {
        val root = NullPointerException("Field is null")
        root.stackTrace = arrayOf(
            StackTraceElement("com.mobilegh.data.Session", "getUser", "Session.kt", 77),
        )
        val outer = RuntimeException("Outer wrapper", root)

        AppLog.trackScreen("RepoScreen(owner=test, name=repo)")
        AppLog.breadcrumb("network", "GET /user")

        val (headline, report) = AppLog.buildCrashReport(Thread.currentThread(), outer)

        assertTrue(headline.contains("NullPointerException"))
        assertTrue(headline.contains("Session.kt:77"))

        assertTrue(report.contains("【异常崩溃诊断报告】"))
        assertTrue(report.contains("【根本原因 (Root Cause)】"))
        assertTrue(report.contains("【最近操作与事件轨迹 (Breadcrumbs)】"))
        assertTrue(report.contains("【进程与内存状态快照】"))
        assertTrue(report.contains("【完整异常堆栈 (Stack Trace)】"))
        assertTrue(report.contains("【设备与构建信息】"))
        assertTrue(report.contains("【异常链（由外到内）】"))
        assertTrue(report.contains("【全部线程快照 (All Threads)】"))
        assertTrue(report.contains("RepoScreen(owner=test, name=repo)"))
        assertTrue(report.contains("GET /user"))
    }

    @Test
    fun formatCauseChain_listsEveryWrapperFromOuterToInner() {
        val root = IllegalStateException("root failure")
        val mid = IOException("io failure", root)
        val outer = RuntimeException("outer failure", mid)

        val chain = AppLog.formatCauseChain(outer)

        assertTrue(chain.contains("[0] java.lang.RuntimeException: outer failure"))
        assertTrue(chain.contains("[1] java.io.IOException: io failure"))
        assertTrue(chain.contains("[2] java.lang.IllegalStateException: root failure"))
    }

    @Test
    fun formatCauseChain_truncatesVeryDeepChains() {
        var t: Throwable = IllegalStateException("level 0")
        repeat(20) { i -> t = RuntimeException("level ${i + 1}", t) }

        val chain = AppLog.formatCauseChain(t)

        assertTrue(chain.contains("[11]"))
        assertTrue(chain.contains("已截断"))
    }

    @Test
    fun formatAllThreads_containsCurrentThreadAndStackTrace() {
        val dump = AppLog.formatAllThreads(Thread.currentThread())

        assertTrue(dump.contains(Thread.currentThread().name))
        assertTrue(dump.contains("state="))
    }

    @Test
    fun formatBreadcrumbList_usesRelativeOffsetsFromReferenceTime() {
        val now = System.currentTimeMillis()
        val list = listOf(
            Breadcrumb(now - 5000, "nav", "first"),
            Breadcrumb(now - 1200, "network", "second"),
        )

        val text = AppLog.formatBreadcrumbList(list, now)

        assertTrue(text.contains("[-5.0s] [nav] first"))
        assertTrue(text.contains("[-1.2s] [network] second"))
    }

    @Test
    fun formatBreadcrumbList_keepsPositiveOffsetsAfterReference() {
        val now = System.currentTimeMillis()
        val list = listOf(
            Breadcrumb(now - 2000, "nav", "before"),
            Breadcrumb(now + 1500, "network", "after"),
        )

        val text = AppLog.formatBreadcrumbList(list, now)

        assertTrue(text.contains("[-2.0s] [nav] before"))
        assertTrue(text.contains("[+1.5s] [network] after"))
    }

    @Test
    fun formatBreadcrumbList_reportsEmptyBuffer() {
        assertEquals("无轨迹记录", AppLog.formatBreadcrumbList(emptyList(), System.currentTimeMillis()))
    }

    @Test
    fun buildSummary_containsVersionAndDeviceFacts() {
        val summary = AppLog.buildSummary()

        assertTrue(summary.contains("应用版本:"))
        assertTrue(summary.contains("Android"))
        assertTrue(summary.contains("ABI:"))
        assertTrue(summary.contains("CPU 核心:"))
    }

    @Test
    fun processSummary_containsPidAndThreadCount() {
        val summary = AppLog.processSummary()

        assertTrue(summary.contains("进程 PID:"))
        assertTrue(summary.contains("Java 线程数:"))
    }

    @Test
    fun warn_dedupesIdenticalMessagesWithinTheWindow() {
        val message = "重复的网络警告 " + System.nanoTime()

        assertTrue("首次出现应当记录", AppLog.warn("network", message))
        assertTrue("重复出现应当被合并", !AppLog.warn("network", message))
    }

    @Test
    fun dedupeById_keepsOneEntryPerIdInOriginalOrder() {
        val a = AppLogEntry(level = "INFO", category = "app", message = "first")
        val b = AppLogEntry(level = "INFO", category = "app", message = "second")
        val aAgain = a.copy(message = "first (重写)")

        val result = AppLog.dedupeById(listOf(a, b, aAgain))

        assertEquals(2, result.size)
        assertEquals(listOf(a.id, b.id), result.map { it.id })
        // 保留最后一条，也就是内容更新的那条
        assertEquals("first (重写)", result[0].message)
    }

    @Test
    fun error_neverDedupes() {
        val message = "重复的错误 " + System.nanoTime()

        AppLog.error("action", message)
        AppLog.error("action", message)

        assertTrue(AppLog.entries.count { it.message == message } >= 2)
    }
}
