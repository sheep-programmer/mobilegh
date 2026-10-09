package com.mobilegh.data

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mobilegh.MainActivity
import com.mobilegh.R

object MobileApprovalNotifications {
    const val EXTRA_REQUEST = "com.mobilegh.extra.APPROVAL_REQUEST"
    const val EXTRA_OPEN = "com.mobilegh.extra.OPEN_APPROVAL"
    const val MONITOR_ID = 20001
    private const val REQUEST_ID = 20002
    private const val REQUEST_CHANNEL = "github_2fa_requests"
    private const val MONITOR_CHANNEL = "github_2fa_monitor"

    fun allowed(context: Context): Boolean = (Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(REQUEST_CHANNEL, "GitHub 两步验证", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "GitHub 登录与敏感操作的数字确认请求"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
        manager.createNotificationChannel(NotificationChannel(MONITOR_CHANNEL, "数字审批后台监听", NotificationManager.IMPORTANCE_LOW).apply {
            description = "保持 GitHub 验证请求的自动检测"
            setShowBadge(false)
        })
    }

    private fun intent(context: Context, request: Int? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_OPEN, true)
        request?.let { intent.putExtra(EXTRA_REQUEST, it) }
        return PendingIntent.getActivity(context, request ?: MONITOR_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun monitoring(context: Context): Notification {
        channels(context)
        return NotificationCompat.Builder(context, MONITOR_CHANNEL).setSmallIcon(R.drawable.oc_shield_lock)
            .setContentTitle("GitHub 数字审批已开启").setContentText("正在自动检测验证请求")
            .setContentIntent(intent(context)).setOngoing(true).setSilent(true).setCategory(NotificationCompat.CATEGORY_SERVICE).build()
    }

    fun request(context: Context, device: MobileApprovalDevice, request: MobileApprovalRequest): Boolean {
        channels(context)
        if (!allowed(context)) return false
        val notification = NotificationCompat.Builder(context, REQUEST_CHANNEL).setSmallIcon(R.drawable.oc_shield_lock)
            .setContentTitle("GitHub 验证请求").setContentText("@${device.login} · 点击输入浏览器显示的数字")
            .setContentIntent(intent(context, request.id)).setAutoCancel(true).setOnlyAlertOnce(true)
            .setTimeoutAfter(120_000).setPriority(NotificationCompat.PRIORITY_HIGH).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_STATUS).build()
        @Suppress("MissingPermission")
        return try {
            NotificationManagerCompat.from(context).notify(REQUEST_ID, notification)
            true
        } catch (_: SecurityException) { false }
    }

    fun clearRequest(context: Context) = NotificationManagerCompat.from(context).cancel(REQUEST_ID)
}
