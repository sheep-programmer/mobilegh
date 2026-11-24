package com.mobilegh.ui.screens

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ExplorePreviewGateTest {
    @Test
    fun previewRequestsAreLimitedToTwoConcurrentLoads() = runBlocking {
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)

        (0 until 8).map {
            async {
                ExplorePreviewGate.withPermit {
                    val now = active.incrementAndGet()
                    peak.updateAndGet { current -> maxOf(current, now) }
                    delay(10)
                    active.decrementAndGet()
                }
            }
        }.awaitAll()

        assertTrue("preview concurrency peak was ${peak.get()}", peak.get() <= 2)
    }
}
