package com.mobilegh.data

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.mobilegh.service.MobileApprovalService
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf

data class ApprovalMonitorState(
    val device: MobileApprovalDevice? = null,
    val enabled: Boolean = true,
    val request: MobileApprovalRequest? = null,
    val dialogVisible: Boolean = false,
    val connected: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val approvedId: Int? = null,
)

/** Global request state: survives navigation, never shares approval between device/account identities. */
object MobileApprovalMonitor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(ApprovalMonitorState())
    val state = mutableState.asStateFlow()
    private var refreshJob: kotlinx.coroutines.Job? = null
    private val pollMutex = Mutex()
    private var notifiedId: Int? = null
    private var dismissedId: Int? = null
    private var notificationTarget: Int? = null
    var foreground = false
        private set

    fun reload(context: Context) {
        val device = MobileApproval.device(context)
        val enabled = MobileApproval.enabled(context)
        if (device != state.value.device || enabled != state.value.enabled) {
            val previous = state.value
            refreshJob?.cancel()
            notifiedId = null; dismissedId = null
            mutableState.value = ApprovalMonitorState(device, enabled)
            // Cold starts must preserve the system notification being tapped; poll validates its id.
            if (previous.device != null || device == null || !enabled) MobileApprovalNotifications.clearRequest(context)
        }
    }

    /**
     * 启动检测。用普通服务（不是前台服务）：前台服务必须常驻一条通知，
     * 而验证请求只需要「应用开着时弹窗、退到后台时提醒一次」。
     */
    fun ensureStarted(context: Context) {
        reload(context)
        // 顺手清掉旧版本留下的「正在监控」常驻通知频道
        runCatching { MobileApprovalNotifications.channels(context) }
        if (state.value.device == null || !state.value.enabled) return
        runCatching { context.startService(Intent(context, MobileApprovalService::class.java)) }
            .onFailure { AppLog.warn("2fa", tr("系统暂未允许后台验证检测，请打开 App 后重试")) }
    }

    fun setForeground(context: Context, visible: Boolean) {
        foreground = visible
        if (visible) {
            // 人已经在应用里了，后台那条提醒没有意义，弹窗才是主路径
            MobileApprovalNotifications.clearRequest(context)
            ensureStarted(context)
            refresh(context)
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        MobileApproval.setEnabled(context, enabled)
        reload(context)
        if (enabled) ensureStarted(context)
        else {
            context.stopService(Intent(context, MobileApprovalService::class.java))
            MobileApprovalNotifications.clearRequest(context)
        }
    }

    fun dismiss() {
        dismissedId = state.value.request?.id
        mutableState.value = state.value.copy(dialogVisible = false)
    }

    fun show() {
        if (state.value.request != null) mutableState.value = state.value.copy(dialogVisible = true, error = null)
    }

    fun openNotification(context: Context, id: Int) {
        notificationTarget = id
        refresh(context)
    }

    fun refresh(context: Context) {
        if (refreshJob?.isActive == true || state.value.busy) return
        refreshJob = scope.launch { pollOnce(context.applicationContext) }
    }

    suspend fun pollOnce(context: Context) = pollMutex.withLock {
        val snapshot = state.value
        val device = snapshot.device ?: return@withLock
        if (!snapshot.enabled || snapshot.busy) return@withLock
        try {
            val request = MobileApproval.poll(context, device)
            if (state.value.device != device || !state.value.enabled || state.value.busy) return@withLock
            val current = state.value
            if (request != null && request.id == current.approvedId) return@withLock
            val changed = request != current.request
            if (request == null) {
                MobileApprovalNotifications.clearRequest(context)
                if (notificationTarget != null) {
                    notificationTarget = null
                    mutableState.value = current.copy(request = null, dialogVisible = false, connected = true,
                        error = tr("验证请求已结束，请在浏览器重新发起"))
                } else mutableState.value = current.copy(request = null, dialogVisible = false, connected = true)
                return@withLock
            }
            val fromNotification = notificationTarget == request.id
            notificationTarget = null
            val visible = fromNotification || current.dialogVisible || (changed && dismissedId != request.id)
            mutableState.value = current.copy(request = request, dialogVisible = visible, connected = true,
                error = if (changed) null else current.error, approvedId = if (changed) null else current.approvedId)
            // 应用开着就直接弹对话框，不发系统通知；只有退到后台才提醒一次
            if (!foreground && notifiedId != request.id) {
                if (MobileApprovalNotifications.request(context, device, request)) notifiedId = request.id
            }
            if (changed) AppLog.info("2fa", trf("收到 GitHub 验证请求 {0}", request.id))
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            if (state.value.device == device) mutableState.value = state.value.copy(connected = false, error = e.networkHint() ?: e.message ?: tr("无法读取验证请求"))
        }
    }

    fun approve(context: Context, expected: MobileApprovalRequest, digits: String) {
        val current = state.value
        val device = current.device ?: return
        if (current.busy || current.request != expected || !current.enabled) return
        mutableState.value = current.copy(busy = true, error = null)
        scope.launch {
            try {
                MobileApproval.approve(context.applicationContext, device, expected, digits)
                if (state.value.device == device && state.value.request == expected) {
                    mutableState.value = state.value.copy(request = null, dialogVisible = false, busy = false, approvedId = expected.id, error = null)
                    MobileApprovalNotifications.clearRequest(context)
                    AppLog.info("2fa", trf("GitHub 验证请求 {0} 已批准", expected.id))
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (state.value.device == device) mutableState.value = state.value.copy(busy = false, error = e.networkHint() ?: e.message ?: tr("批准失败"))
            } finally {
                if (state.value.device == device && state.value.busy) mutableState.value = state.value.copy(busy = false)
            }
        }
    }
}
