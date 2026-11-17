package com.mobilegh.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mobilegh.R
import com.mobilegh.data.AppLog
import com.mobilegh.data.BackgroundNotificationSettings
import com.mobilegh.data.GitHub
import com.mobilegh.data.MobileApprovalNotifications
import com.mobilegh.data.Notification as GitHubNotification
import com.mobilegh.data.Session
import com.mobilegh.i18n.tr
import com.mobilegh.nav.Tab
import com.mobilegh.widget.WidgetIntents
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class NotificationPollingWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        return try {
            NotificationPollRunner.run(
                config = { BackgroundNotificationSettings.config },
                login = { Session.login },
                generation = { Session.generation },
                foreground = { BackgroundNotificationSettings.foreground },
                fetch = { GitHub.notifications(all = false, participating = false, page = 1, force = true) },
                notified = BackgroundNotificationSettings::notified,
                deliver = { GitHubNotificationDelivery.show(applicationContext, it) },
                record = { BackgroundNotificationSettings.recordNotified(requireNotNull(Session.login), it) },
            )
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.warn("notifications", tr("后台通知检查失败"), e)
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE = "github_notification_polling"

        fun sync(ctx: Context) {
            if (!BackgroundNotificationSettings.config.enabled || Session.login == null) {
                WorkManager.getInstance(ctx.applicationContext).cancelUniqueWork(UNIQUE)
                return
            }
            val manager = WorkManager.getInstance(ctx.applicationContext)
            val request = PeriodicWorkRequestBuilder<NotificationPollingWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            manager.enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}

/** 通用 GitHub 收件箱提醒与两步验证提醒分频道，避免用户只能同时开关两种功能。 */
object GitHubNotificationDelivery {
    private const val CHANNEL = "github_inbox_updates"
    private const val GROUP = "github_inbox"
    private const val SUMMARY_ID = 31000

    fun show(ctx: Context, n: GitHubNotification): Boolean {
        if (!MobileApprovalNotifications.allowed(ctx)) return false
        val manager = ctx.getSystemService(NotificationManager::class.java) ?: return false
        manager.createNotificationChannel(NotificationChannel(CHANNEL, tr("GitHub 收件箱"), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = tr("Issue、PR 等未读通知更新时提醒")
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
        val repo = n.repository.fullName.ifBlank {
            listOf(n.repository.owner.login, n.repository.name).filter { it.isNotBlank() }.joinToString("/")
        }
        val open = WidgetIntents.open(ctx, Tab.Notifications, n.subject.url).apply {
            // 不同线程不能使用同一 PendingIntent，否则 extra 会互相覆盖。
            data = android.net.Uri.parse("mobilegh://notification/${n.id}")
        }
        val content = PendingIntent.getActivity(ctx, n.id.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.oc_bell)
            .setContentTitle(n.subject.title.ifBlank { tr("GitHub 通知") })
            .setContentText(repo)
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOf(repo, n.subject.title).filter { it.isNotBlank() }.joinToString("\n")))
            .setContentIntent(content)
            .setAutoCancel(true)
            .setGroup(GROUP)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        @Suppress("MissingPermission")
        return try {
            val compat = NotificationManagerCompat.from(ctx)
            compat.notify(n.id.hashCode(), notification)
            compat.notify(SUMMARY_ID, NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.oc_bell)
                .setContentTitle(tr("GitHub 通知"))
                .setContentText(tr("有新的未读通知"))
                .setContentIntent(PendingIntent.getActivity(ctx, SUMMARY_ID,
                    WidgetIntents.open(ctx, Tab.Notifications), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true).setGroup(GROUP).setGroupSummary(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
            true
        } catch (_: SecurityException) { false }
    }
}
