package com.mobilegh.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.mobilegh.data.AppLog
import com.mobilegh.data.MobileApprovalDevice
import com.mobilegh.data.MobileApprovalPush
import com.mobilegh.data.MobileApproval
import com.mobilegh.data.MobileApprovalMonitor
import com.mobilegh.data.MobileApprovalNotifications
import kotlinx.coroutines.*

/** User-visible persistent monitoring; Android receives notifications even when our Activity is stopped. */
class MobileApprovalService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var pushJob: Job? = null
    private var push: MobileApprovalPush? = null
    private var device: MobileApprovalDevice? = null
    private var pushConnected = false

    override fun onCreate() {
        super.onCreate()
        val notification = MobileApprovalNotifications.monitoring(this)
        if (Build.VERSION.SDK_INT >= 34) startForeground(MobileApprovalNotifications.MONITOR_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(MobileApprovalNotifications.MONITOR_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MobileApprovalMonitor.reload(this)
        val activeDevice = MobileApproval.device(this)
        if (!MobileApproval.enabled(this) || activeDevice == null) { stopSelf(); return START_NOT_STICKY }
        if (device != activeDevice) {
            push?.close(); pushJob?.cancel(); job?.cancel(); job = null; pushJob = null
            device = activeDevice; pushConnected = false
        }
        if (pushJob?.isActive != true) pushJob = scope.launch {
            var retry = 2000L
            while (isActive && MobileApproval.enabled(this@MobileApprovalService)) {
                try {
                    val credentials = MobileApproval.preparePush(this@MobileApprovalService, activeDevice)
                    val prefs = getSharedPreferences("mobile_approval_push", MODE_PRIVATE)
                    val key = "received_${activeDevice.alias}"
                    val ids = prefs.getStringSet(key, emptySet()).orEmpty().toMutableSet()
                    val connection = MobileApprovalPush(credentials, ids.toList())
                    push = connection
                    connection.listen(onConnected = { ready -> withContext(Dispatchers.Main.immediate) {
                        pushConnected = ready
                        if (ready) { retry = 2000; AppLog.info("2fa_push", "认证推送连接已就绪") }
                    } }, onAuthRequest = { id, persistentId -> withContext(Dispatchers.Main.immediate) {
                        if (persistentId != null && !ids.add(persistentId)) return@withContext
                        if (persistentId != null) prefs.edit().putStringSet(key, ids.takeLastIds()).apply()
                        if (MobileApprovalMonitor.state.value.device != activeDevice) return@withContext
                        AppLog.info("2fa_push", "收到认证推送 $id")
                        val wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mobilegh:2fa-request")
                        wake.acquire(30_000)
                        scope.launch {
                            try { MobileApprovalMonitor.pollOnce(this@MobileApprovalService) }
                            finally { if (wake.isHeld) wake.release() }
                        }
                    } })
                } catch (e: CancellationException) { throw e
                } catch (_: Exception) {
                    pushConnected = false
                    AppLog.warn("2fa_push", "认证推送连接中断，正在重连；请求检测继续运行")
                }
                delay(retry)
                retry = (retry * 2).coerceAtMost(30_000)
            }
        }
        if (job?.isActive != true) job = scope.launch {
            while (isActive && MobileApproval.enabled(this@MobileApprovalService)) {
                MobileApprovalMonitor.pollOnce(this@MobileApprovalService)
                val state = MobileApprovalMonitor.state.value
                delay(if (MobileApprovalMonitor.foreground || state.request != null) 4000 else if (!state.connected) 30_000 else if (pushConnected) 60_000 else 15_000)
            }
            stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { push?.close(); scope.cancel(); super.onDestroy() }

    private fun Set<String>.takeLastIds(): Set<String> = toList().takeLast(100).toSet()
}

class MobileApprovalRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            MobileApprovalMonitor.ensureStarted(context)
        }
    }
}
