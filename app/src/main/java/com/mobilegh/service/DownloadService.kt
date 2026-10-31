package com.mobilegh.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mobilegh.MainActivity
import com.mobilegh.R
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf

/**
 * 下载进行时的前台服务：应用退到后台也能继续下载，常驻通知显示进行中的数量。
 * 没有下载时由 [sync] 停止服务。
 */
class DownloadService : Service() {
    override fun onCreate() {
        super.onCreate()
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val running = intent?.getIntExtra(EXTRA_RUNNING, 1) ?: 1
        val notification = ongoing(this, running)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ONGOING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ONGOING_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val EXTRA_RUNNING = "running"
        private const val ONGOING_ID = 7101
        private const val CHANNEL_ONGOING = "downloads_progress"
        private const val CHANNEL_DONE = "downloads_done"
        private var doneId = 7200

        /** 有进行中的下载时启动前台服务（并更新通知里的数量），没有时停止。 */
        fun sync(context: Context, running: Int) {
            if (running > 0) {
                val intent = Intent(context, DownloadService::class.java).putExtra(EXTRA_RUNNING, running)
                runCatching { ContextCompat.startForegroundService(context, intent) }
            } else {
                runCatching { context.stopService(Intent(context, DownloadService::class.java)) }
            }
        }

        /** 下载完成或失败时的通知；没有通知权限时静默跳过，不影响下载结果本身。 */
        @SuppressLint("MissingPermission")
        fun notifyFinished(context: Context, title: String, text: String) {
            createChannels(context)
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_DONE)
                .setSmallIcon(R.drawable.oc_download)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(doneId++, notification) }
        }

        private fun ongoing(context: Context, running: Int): Notification {
            val open = PendingIntent.getActivity(
                context, 1, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, CHANNEL_ONGOING)
                .setSmallIcon(R.drawable.oc_download)
                .setContentTitle(tr("正在下载"))
                .setContentText(trf("共 {0} 个文件", running))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .build()
        }

        private fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ONGOING, tr("下载进度"), NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(CHANNEL_DONE, tr("下载完成"), NotificationManager.IMPORTANCE_DEFAULT))
        }
    }
}
