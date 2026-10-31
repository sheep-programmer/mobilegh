package com.mobilegh.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mobilegh.data.ApiException
import com.mobilegh.data.DeviceAuthorization
import com.mobilegh.data.DevicePoll
import com.mobilegh.nav.friendly
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.IOException
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf

/** 网络中断后两次重试之间的等待上限：再长用户会以为应用卡住了 */
internal const val LOGIN_RETRY_CAP_MS = 30_000L

/**
 * 网络失败后、下一次轮询前的等待：在上一次等待的基础上翻倍，但不短于正常轮询间隔，也不超过 [capMs]。
 * 原先没有上限，连续 5 次失败后会静默约 5 分钟。
 */
internal fun nextRetryDelayMs(intervalMs: Long, previousMs: Long, capMs: Long = LOGIN_RETRY_CAP_MS): Long =
    maxOf(intervalMs, minOf(previousMs * 2, capMs))

/** 距离重试时刻还剩几秒（向上取整，不为负）；[retryAtMs] 为 0 表示没有等待中的重试。 */
internal fun retrySecondsLeft(retryAtMs: Long, nowMs: Long): Int {
    if (retryAtMs <= 0L) return 0
    val left = (retryAtMs - nowMs).coerceAtLeast(0L)
    return ((left + 999L) / 1000L).toInt()
}

/** Retained by the navigation entry, so configuration changes do not restart OAuth. */
internal class LoginFlow(
    private val scope: CoroutineScope,
    private val start: suspend () -> DeviceAuthorization,
    private val poll: suspend (String) -> DevicePoll,
    private val complete: suspend (DevicePoll.Authorized) -> Unit,
    private val tokenLogin: suspend (String) -> Unit,
    private val now: () -> Long,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    var busy by mutableStateOf(false)
        private set
    var device by mutableStateOf<DeviceAuthorization?>(null)
        private set
    var confirming by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** 授权过程中的临时提示（浏览器未打开、网络重试等），界面可直接补充 */
    var notice by mutableStateOf<String?>(null)
    var deadline = 0L
        private set
    /** 网络中断后下一次轮询的时刻（与 [now] 同一时间基准），界面据此显示倒计时；0 表示没有等待中的重试 */
    var retryAt by mutableLongStateOf(0L)
        private set
    private var job: Job? = null
    private var generation = 0

    fun cancel() {
        generation++
        job?.cancel()
        job = null
        device = null
        confirming = false
        busy = false
        notice = null
        retryAt = 0L
        error = null
    }

    fun signIn(token: String) = run {
        tokenLogin(token.trim())
    }

    fun authorize() = run {
        val d = start()
        currentCoroutineContext().ensureActive()
        device = d
        deadline = now() + d.expiresIn * 1000L
        var interval = d.interval.coerceAtLeast(5) * 1000L
        var nextDelay = interval
        while (now() < deadline) {
            pause(minOf(nextDelay, (deadline - now()).coerceAtLeast(0)))
            currentCoroutineContext().ensureActive()
            if (now() >= deadline) break
            val result = try {
                poll(d.deviceCode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (!e.retryable()) throw e
                // Network failures never replace the device code or extend its lifetime.
                nextDelay = nextRetryDelayMs(interval, nextDelay)
                retryAt = now() + nextDelay
                notice = tr("连接中断，正在等待重试，授权有效期不变")
                continue
            }
            currentCoroutineContext().ensureActive()
            notice = null
            retryAt = 0L
            nextDelay = interval
            when (result) {
                is DevicePoll.Authorized -> {
                    confirming = true
                    // Once issued, verify the token without polling the consumed device code again.
                    finish(result)
                    return@run
                }
                DevicePoll.Pending -> Unit
                is DevicePoll.SlowDown -> {
                    interval = maxOf(interval + 5_000, (result.interval ?: 0) * 1000L)
                    nextDelay = interval
                }
                is DevicePoll.Failed -> error(result.message)
            }
        }
        error(tr("授权码已过期，请重新开始"))
    }

    private suspend fun finish(result: DevicePoll.Authorized) {
        var retries = 0
        while (true) {
            try {
                complete(result)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (!e.retryable() || retries == 3) throw e
                val wait = 5_000L shl retries++
                notice = trf("正在连接 GitHub 确认账号，{0} 秒后重试", wait / 1000)
                pause(wait)
                currentCoroutineContext().ensureActive()
            }
        }
    }

    private fun run(block: suspend () -> Unit) {
        if (busy) return
        val version = ++generation
        busy = true
        error = null
        notice = null
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (version == generation) error = e.friendly()
            } finally {
                if (version == generation) {
                    device = null
                    confirming = false
                    busy = false
                    notice = null
                    retryAt = 0L
                }
            }
        }
    }

    private fun Exception.retryable() = this is IOException ||
        this is ApiException && (code == 429 || code in 500..599)
}
