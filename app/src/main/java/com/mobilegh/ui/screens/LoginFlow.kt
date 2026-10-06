package com.mobilegh.ui.screens

import androidx.compose.runtime.getValue
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
    var notice by mutableStateOf<String?>(null)
        private set
    var deadline = 0L
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
                nextDelay = maxOf(interval, nextDelay.coerceAtMost(Long.MAX_VALUE / 2) * 2)
                notice = "连接中断，正在等待重试，授权有效期不变"
                continue
            }
            currentCoroutineContext().ensureActive()
            notice = null
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
        error("授权码已过期，请重新开始")
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
                notice = "正在连接 GitHub 确认账号，${wait / 1000} 秒后重试"
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
                }
            }
        }
    }

    private fun Exception.retryable() = this is IOException ||
        this is ApiException && (code == 429 || code in 500..599)
}
