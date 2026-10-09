package com.mobilegh.data

import android.app.ActivityOptions
import android.app.NotificationManager
import android.content.Intent
import android.content.ComponentName
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import com.mobilegh.MainActivity
import java.io.File

class MobileApprovalNotificationTest {
    @Test fun actualSystemNotificationOpensItsBoundApprovalRequest() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("openLive2faNotification") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        Thread.sleep(1500)
        instrumentation.runOnMainSync {
            context.startActivity(Intent().setComponent(ComponentName("com.mobilegh.test", "com.mobilegh.testing.OutsideAppActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        MobileApprovalNotifications.clearRequest(context)
        File(context.filesDir, "2fa-background-test-ready").writeText("ready")
        var entry = manager.activeNotifications.firstOrNull { it.id == 20002 }
        val deadline = System.currentTimeMillis() + 90_000
        while (entry == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(250)
            entry = manager.activeNotifications.firstOrNull { it.id == 20002 }
        }
        check(entry != null) { "No actual background verification notification" }
        val expectedId = MobileApprovalMonitor.state.value.request?.id ?: error("Notification is not bound to an active request")
        assertEquals("github_2fa_requests", entry.notification.channelId)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(entry.notification.channelId).importance)
        val intent = entry.notification.contentIntent
        assertNotNull(intent)
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 34) intent.send(context, 0, null, null, null, null,
            ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle())
        else intent.send()
        File(context.filesDir, "2fa-background-test-opened").writeText(expectedId.toString())
        val approvalDeadline = System.currentTimeMillis() + 40_000
        while (MobileApprovalMonitor.state.value.approvedId != expectedId && System.currentTimeMillis() < approvalDeadline) Thread.sleep(250)
        assertEquals(expectedId, MobileApprovalMonitor.state.value.approvedId)
        assertFalse(manager.activeNotifications.any { it.id == 20002 })
        println("LIVE2FA background notification approval passed for request $expectedId")
    }
}
