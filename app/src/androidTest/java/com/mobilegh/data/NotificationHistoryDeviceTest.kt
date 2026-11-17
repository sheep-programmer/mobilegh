package com.mobilegh.data

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationHistoryDeviceTest {
    @Test fun persistedNotificationHistoryIsIsolatedByLogin() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        BackgroundNotificationSettings.init(context)
        val suffix = System.nanoTime().toString()
        val first = "MobileGH-history-a-$suffix"
        val second = "MobileGH-history-b-$suffix"
        val thread = Notification(id = "thread-1", unread = true, updatedAt = "2026-11-14T12:00:00Z")
        try {
            BackgroundNotificationSettings.recordNotified(first, listOf(thread))

            assertEquals("2026-11-14T12:00:00Z", BackgroundNotificationSettings.notified(first)[thread.id])
            assertEquals("2026-11-14T12:00:00Z", BackgroundNotificationSettings.notified(first.uppercase())[thread.id])
            assertTrue(BackgroundNotificationSettings.notified(second).isEmpty())
        } finally {
            clearHistory(context, first)
            clearHistory(context, second)
        }
    }

    @Test fun persistedNotificationHistoryIsIsolatedByCanonicalHost() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        BackgroundNotificationSettings.init(context)
        val login = "MobileGH-history-host-${System.nanoTime()}"
        val thread = Notification(id = "thread-host", unread = true, updatedAt = "2026-11-14T12:00:00Z")
        try {
            BackgroundNotificationSettings.recordNotified(login, listOf(thread), "https://API.GIT.EXAMPLE.CORP:443")

            assertEquals("2026-11-14T12:00:00Z", BackgroundNotificationSettings.notified(login, "api.git.example.corp")[thread.id])
            assertTrue(BackgroundNotificationSettings.notified(login, "api.other.example.corp").isEmpty())
            assertTrue(BackgroundNotificationSettings.notified(login, "api.github.com").isEmpty())
        } finally {
            clearHistory(context, login)
        }
    }

    @Test fun legacyPublicHistoryMigratesWithoutLeakingToEnterpriseAccounts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        BackgroundNotificationSettings.init(context)
        val login = "MobileGH-history-legacy-${System.nanoTime()}"
        val legacyKey = "sent_${login.lowercase()}"
        val thread = Notification(id = "thread-legacy", unread = true, updatedAt = "2026-11-14T11:00:00Z")
        val prefs = context.getSharedPreferences("background_notifications", Context.MODE_PRIVATE)
        try {
            prefs.edit().putString(legacyKey, "{\"thread-legacy\":\"2026-11-14T11:00:00Z\"}").commit()

            assertEquals("2026-11-14T11:00:00Z", BackgroundNotificationSettings.notified(login, "api.github.com")[thread.id])
            assertTrue(BackgroundNotificationSettings.notified(login, "api.git.example.corp").isEmpty())
            BackgroundNotificationSettings.recordNotified(login, listOf(thread), "api.github.com")
            assertTrue(prefs.contains(legacyKey).not())
        } finally {
            clearHistory(context, login)
        }
    }

    private fun clearHistory(context: Context, login: String) {
        val prefs = context.getSharedPreferences("background_notifications", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.endsWith(login.lowercase()) }.forEach(editor::remove)
        editor.commit()
    }
}
