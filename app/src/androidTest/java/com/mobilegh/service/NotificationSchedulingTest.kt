package com.mobilegh.service

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.mobilegh.data.BackgroundNotificationConfig
import com.mobilegh.data.BackgroundNotificationSettings
import com.mobilegh.data.Session
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSchedulingTest {
    @Test fun enabledLoginEnqueuesAndDisablingCancelsRealPeriodicWork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().build(),
        )
        BackgroundNotificationSettings.init(context)

        val oldConfig = BackgroundNotificationSettings.config
        val oldForeground = BackgroundNotificationSettings.foreground
        val oldLogin = Session.login
        val manager = WorkManager.getInstance(context)
        try {
            // If the first interval starts immediately, doWork exits without touching GitHub.
            BackgroundNotificationSettings.setForeground(true)
            BackgroundNotificationSettings.save(BackgroundNotificationConfig(enabled = true))
            Session.login = "notification-scheduling-test"
            NotificationPollingWorker.sync(context)

            val enqueued = awaitState(manager, WorkInfo.State.ENQUEUED)
            assertEquals(TimeUnit.MINUTES.toMillis(15), enqueued.periodicityInfo?.repeatIntervalMillis)
            assertEquals(NetworkType.CONNECTED, enqueued.constraints.requiredNetworkType)

            NotificationPollingWorker.sync(context)
            val unchanged = awaitState(manager, WorkInfo.State.ENQUEUED)
            assertEquals(enqueued.id, unchanged.id)
            assertEquals(enqueued.nextScheduleTimeMillis, unchanged.nextScheduleTimeMillis)

            BackgroundNotificationSettings.save(BackgroundNotificationConfig(enabled = false))
            NotificationPollingWorker.sync(context)
            awaitState(manager, WorkInfo.State.CANCELLED)
        } finally {
            manager.cancelUniqueWork(UNIQUE).result.get(10, TimeUnit.SECONDS)
            BackgroundNotificationSettings.save(oldConfig)
            BackgroundNotificationSettings.setForeground(oldForeground)
            Session.login = oldLogin
            NotificationPollingWorker.sync(context)
        }
    }

    private fun awaitState(manager: WorkManager, expected: WorkInfo.State): WorkInfo {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        var infos = manager.getWorkInfosForUniqueWork(UNIQUE).get(10, TimeUnit.SECONDS)
        while (SystemClock.elapsedRealtime() < deadline) {
            infos.singleOrNull { it.state == expected }?.let { match ->
                return match
            }
            SystemClock.sleep(100)
            infos = manager.getWorkInfosForUniqueWork(UNIQUE).get(10, TimeUnit.SECONDS)
        }
        assertTrue("Expected $expected, got ${infos.map { it.state }}", false)
        error("unreachable")
    }

    private companion object {
        const val UNIQUE = "github_notification_polling"
    }
}
