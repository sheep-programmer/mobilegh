package com.mobilegh.widget

import com.mobilegh.data.WidgetStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetRefreshTest {
    private class BlockingUpdates {
        val started = Channel<WidgetStore.Kind?>(Channel.UNLIMITED)
        private val releases = Channel<Unit>(Channel.UNLIMITED)
        var active = 0
        var maxActive = 0
        var count = 0

        suspend fun update(kind: WidgetStore.Kind?) {
            count++
            active++
            maxActive = maxOf(maxActive, active)
            started.send(kind)
            releases.receive()
            active--
        }

        suspend fun release() = releases.send(Unit)
    }

    @Test fun sameKindBurstKeepsOnlyOneActiveAndOneTrailingRefresh() = runTest {
        val updates = BlockingUpdates()
        val refresh = WidgetRefresh(backgroundScope, updates::update)
        refresh.request(WidgetStore.Kind.Inbox)
        runCurrent()
        assertEquals(WidgetStore.Kind.Inbox, updates.started.receive())

        refresh.request(WidgetStore.Kind.Inbox)
        refresh.request(WidgetStore.Kind.Inbox)
        refresh.request(WidgetStore.Kind.Inbox)
        runCurrent()
        assertEquals(1, updates.active)

        updates.release()
        runCurrent()
        assertEquals(WidgetStore.Kind.Inbox, updates.started.receive())
        updates.release()
        runCurrent()
        assertEquals(2, updates.count)
        assertEquals(0, updates.active)
        assertEquals(1, updates.maxActive)
    }

    @Test fun differentKindsRunIndependentlyInsteadOfGloballySerializing() = runTest {
        val updates = BlockingUpdates()
        val refresh = WidgetRefresh(backgroundScope, updates::update)
        refresh.request(WidgetStore.Kind.Inbox)
        refresh.request(WidgetStore.Kind.PullRequests)
        refresh.request(WidgetStore.Kind.Shortcuts)
        runCurrent()
        assertEquals(
            setOf(WidgetStore.Kind.Inbox, WidgetStore.Kind.PullRequests, WidgetStore.Kind.Shortcuts),
            List(3) { updates.started.receive() }.toSet(),
        )
        assertEquals(3, updates.active)
        repeat(3) { updates.release() }
        runCurrent()
        assertEquals(0, updates.active)
    }

    @Test fun failedRefreshDoesNotPreventTheTrailingLatestRefresh() = runTest {
        val attempts = Channel<Int>(Channel.UNLIMITED)
        var count = 0
        val refresh = WidgetRefresh(backgroundScope, update = {
            count++
            attempts.send(count)
            if (count == 1) error("first update failed")
        })
        refresh.request(WidgetStore.Kind.Contributions)
        runCurrent()
        assertEquals(1, attempts.receive())
        refresh.request(WidgetStore.Kind.Contributions)
        runCurrent()
        assertEquals(2, attempts.receive())
        assertEquals(2, count)
    }

    @Test fun requestsArrivingDuringCompletionStillCauseAnotherRefresh() = runTest {
        val gate = CompletableDeferred<Unit>()
        var count = 0
        val refresh = WidgetRefresh(backgroundScope, update = {
            count++
            if (count == 1) gate.await()
        })
        refresh.request(null)
        runCurrent()
        assertEquals(1, count)
        refresh.request(null)
        gate.complete(Unit)
        runCurrent()
        assertEquals(2, count)
    }

    @Test fun cancelledUpdateDoesNotBlockFutureRequestsForThatKind() = runTest {
        var count = 0
        val refresh = WidgetRefresh(backgroundScope, update = {
            count++
            if (count == 1) throw CancellationException("one update was cancelled")
        })
        refresh.request(WidgetStore.Kind.Shortcuts)
        runCurrent()
        assertEquals(1, count)

        refresh.request(WidgetStore.Kind.Shortcuts)
        runCurrent()
        assertEquals(2, count)
    }
}
