package com.mobilegh.data

import androidx.activity.ComponentActivity
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class AppLogThreadingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun backgroundNetworkLogsApplyComposeStateOnTheMainThread() {
        val applyingThread = AtomicReference<Thread?>()
        var mainThread: Thread? = null
        compose.runOnUiThread { mainThread = Thread.currentThread() }
        val observer = Snapshot.registerApplyObserver { changed, _ ->
            if (AppLog.version in changed) applyingThread.compareAndSet(null, Thread.currentThread())
        }
        try {
            val worker = thread(name = "fixture-okhttp") {
                AppLog.info("test", "background logging fixture")
            }
            compose.waitUntil(10_000) { applyingThread.get() != null }
            worker.join(10_000)
            assertSame(mainThread, applyingThread.get())
        } finally {
            observer.dispose()
        }
    }

    @Test fun clearDoesNotResurrectAnAlreadyQueuedBackgroundEntry() {
        val message = "queued before clear fixture"
        compose.runOnUiThread {
            val worker = thread(name = "fixture-okhttp-clear") { AppLog.info("test", message) }
            worker.join(10_000)
            assertTrue("background logger did not finish", !worker.isAlive)
            AppLog.clear()
        }
        compose.runOnIdle {
            assertTrue("cleared entry returned to Compose state", AppLog.entries.none { it.message == message })
        }
    }
}
