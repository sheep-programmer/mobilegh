package com.mobilegh.ui.screens

import com.mobilegh.data.ApiException
import com.mobilegh.data.DeviceAuthorization
import com.mobilegh.data.DevicePoll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class LoginFlowTest {
    private val authorization = DeviceAuthorization("device-only-test", "ABCD-EFGH", "https://github.com/login/device", 900, 5)
    private suspend fun awaitCondition(condition: () -> Boolean) = withTimeout(5000) {
        while (!condition()) delay(1)
    }

    @Test fun networkRecoveryKeepsOneDeviceRequestAndDoesNotSignInOnPending() = runBlocking {
        var time = 0L
        var starts = 0
        var signedIn = 0
        val delays = mutableListOf<Long>()
        val codes = mutableListOf<String>()
        val flow = LoginFlow(this, { starts++; authorization }, {
            codes += it
            when (codes.size) {
                1, 2 -> throw IOException("offline")
                3 -> DevicePoll.Pending.also { assertEquals(0, signedIn) }
                else -> DevicePoll.Authorized("test-token")
            }
        }, { signedIn++ }, {}, { time }, { delays += it; time += it; yield() })
        flow.authorize()
        awaitCondition { !flow.busy }
        assertEquals(1, starts)
        assertEquals(List(4) { authorization.deviceCode }, codes)
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 5_000L), delays)
        assertEquals(1, signedIn)
        assertNull(flow.error)
        assertNull(flow.device)
    }

    @Test fun slowDownRemainsInEffectAfterNetworkRecovery() = runBlocking {
        var time = 0L
        var calls = 0
        val delays = mutableListOf<Long>()
        val flow = LoginFlow(this, { authorization }, {
            when (++calls) {
                1 -> DevicePoll.SlowDown(12)
                2 -> throw ApiException(503, "temporarily unavailable")
                3 -> DevicePoll.Pending
                else -> DevicePoll.Authorized("test-token")
            }
        }, {}, {}, { time }, { delays += it; time += it; yield() })
        flow.authorize()
        awaitCondition { !flow.busy }
        assertEquals(listOf(5_000L, 12_000L, 24_000L, 12_000L), delays)
        assertNull(flow.error)
    }

    @Test fun originalDeadlineStopsOfflineRetriesWithoutIssuingANewCode() = runBlocking {
        var time = 0L
        var starts = 0
        var polls = 0
        var signedIn = false
        val flow = LoginFlow(this, { starts++; authorization.copy(expiresIn = 30) }, {
            polls++
            throw IOException("offline")
        }, { signedIn = true }, {}, { time }, { time += it; yield() })
        flow.authorize()
        awaitCondition { !flow.busy }
        assertEquals(30_000L, time)
        assertEquals(1, starts)
        assertEquals(2, polls)
        assertFalse(signedIn)
        assertEquals("授权码已过期，请重新开始", flow.error)
        assertNull(flow.device)
    }

    @Test fun consecutiveTimeoutsKeepReducingPollingFrequencyUntilExpiry() = runBlocking {
        var time = 0L
        var polls = 0
        val delays = mutableListOf<Long>()
        val flow = LoginFlow(this, { authorization.copy(expiresIn = 1000) }, {
            polls++
            throw java.net.SocketTimeoutException("timeout")
        }, { fail("Must not sign in without a token") }, {}, { time }, {
            delays += it
            time += it
            yield()
        })
        flow.authorize()
        awaitCondition { !flow.busy }
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 160_000L, 320_000L, 365_000L), delays)
        assertEquals(7, polls)
        assertEquals(1_000_000L, time)
        assertEquals("授权码已过期，请重新开始", flow.error)
    }

    @Test fun denialIsTerminalAndClientErrorsAreNotRetried() = runBlocking {
        for (denied in listOf(true, false)) {
            var time = 0L
            var calls = 0
            var signedIn = false
            val flow = LoginFlow(this, { authorization }, {
                calls++
                if (denied) DevicePoll.Failed("已取消授权") else throw ApiException(400, "invalid request")
            }, { signedIn = true }, {}, { time }, { time += it; yield() })
            flow.authorize()
            awaitCondition { !flow.busy }
            assertEquals(1, calls)
            assertFalse(signedIn)
            assertEquals(if (denied) "已取消授权" else "invalid request", flow.error)
            assertNull(flow.device)
        }
    }

    @Test fun accountVerificationRetriesTheIssuedTokenWithoutRepolling() = runBlocking {
        var time = 0L
        var polls = 0
        var checks = 0
        lateinit var flow: LoginFlow
        flow = LoginFlow(this, { authorization }, {
            polls++
            DevicePoll.Authorized("test-token", "test-refresh", 28800)
        }, {
            assertTrue(flow.confirming)
            assertEquals("test-token", it.token)
            assertEquals("test-refresh", it.refreshToken)
            if (++checks < 3) throw IOException("offline during user lookup")
        }, {}, { time }, { time += it; yield() })
        flow.authorize()
        awaitCondition { !flow.busy }
        assertEquals(1, polls)
        assertEquals(3, checks)
        assertEquals(20_000L, time)
        assertNull(flow.error)
    }

    @Test fun cancellingAnOldRequestCannotClearANewRequest() = runBlocking {
        val oldStarted = CompletableDeferred<Unit>()
        val oldFinished = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val newStarted = CompletableDeferred<Unit>()
        val releaseNew = CompletableDeferred<Unit>()
        var starts = 0
        var signedIn = false
        val flow = LoginFlow(this, {
            if (++starts == 1) {
                oldStarted.complete(Unit)
                try {
                    CompletableDeferred<Unit>().await()
                    authorization
                } finally {
                    withContext(NonCancellable) { releaseOld.await() }
                    oldFinished.complete(Unit)
                }
            } else {
                newStarted.complete(Unit)
                releaseNew.await()
                authorization.copy(deviceCode = "new-code")
            }
        }, { DevicePoll.Failed("已取消授权") }, { signedIn = true }, {}, { 0L }, { yield() })
        flow.authorize()
        withTimeout(5000) { oldStarted.await() }
        flow.cancel()
        flow.authorize()
        withTimeout(5000) { newStarted.await() }
        releaseOld.complete(Unit)
        withTimeout(5000) { oldFinished.await() }
        yield()
        assertTrue(flow.busy)
        assertNull(flow.device)
        assertNull(flow.error)
        releaseNew.complete(Unit)
        awaitCondition { !flow.busy }
        assertFalse(signedIn)
        assertEquals(2, starts)
        assertEquals("已取消授权", flow.error)
    }

    @Test fun repeatedContinueTapsStartOnlyOneAuthorization() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var starts = 0
        val flow = LoginFlow(this, {
            starts++
            started.complete(Unit)
            release.await()
            authorization
        }, { DevicePoll.Failed("已取消授权") }, {}, {}, { 0L }, { yield() })
        flow.authorize()
        withTimeout(5000) { started.await() }
        flow.authorize()
        flow.signIn("test-token")
        assertEquals(1, starts)
        release.complete(Unit)
        awaitCondition { !flow.busy }
    }
}
