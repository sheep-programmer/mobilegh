package com.mobilegh.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Navigator
import com.mobilegh.ui.components.HtmlView
import com.mobilegh.ui.components.Images
import com.mobilegh.ui.theme.MobileGhTheme
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BusyImageLoadingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val badge = """<svg xmlns="http://www.w3.org/2000/svg" width="64" height="20"><rect width="64" height="20" fill="green"/></svg>"""

    @Test fun badgeBurstDoesNotOverwhelmTheOrigin() = runBlocking {
        val active = AtomicInteger()
        val throttled = AtomicInteger()
        LoopbackApi.media {
            val concurrent = active.incrementAndGet()
            try {
                Thread.sleep(300)
                if (concurrent > 6) {
                    throttled.incrementAndGet()
                    LoopbackApi.Response(503, "text/plain")
                } else LoopbackApi.Response(200, "image/svg+xml", badge.toByteArray())
            } finally {
                active.decrementAndGet()
            }
        }.use { server ->
            val batch = UUID.randomUUID()
            val imageCount = 60
            val images = withTimeout(30_000) {
                (1..imageCount).map { id -> async { Images.load("${server.base}/$batch/$id.svg", 256) } }.awaitAll()
            }
            assertEquals("A burst should not trigger origin throttling", 0, throttled.get())
            assertEquals(imageCount, images.count { it != null })
        }
    }

    @Test fun anUndecodableBadgeResponseIsRefetchedWithoutUserInput() = runBlocking {
        val requests = AtomicInteger()
        LoopbackApi.media {
            val bytes = if (requests.incrementAndGet() == 1) {
                """<svg xmlns="http://www.w3.org/2000/svg" width="64" height="20">"""
            } else badge
            LoopbackApi.Response(200, "image/svg+xml", bytes.toByteArray())
        }.use { server ->
            val url = "${server.base}/${UUID.randomUUID()}.svg"
            assertNotNull(Images.load(url, 256))
            assertEquals(2, requests.get())
        }
    }

    @Test fun visibleDocumentImagesRecoverBeforeOffscreenImagesEnterTheQueue() {
        val first = AtomicInteger()
        val nav = Navigator()
        LoopbackApi.media { path ->
            if (path.endsWith("top.svg") && first.incrementAndGet() <= 2) {
                LoopbackApi.Response(503, "text/plain")
            } else LoopbackApi.Response(200, "image/svg+xml", badge.toByteArray())
        }.use { server ->
            try {
                val batch = UUID.randomUUID()
                compose.setContent {
                    CompositionLocalProvider(LocalNav provides nav) {
                        MobileGhTheme {
                            Column(Modifier.height(180.dp).verticalScroll(rememberScrollState()).testTag("document-scroll")) {
                                HtmlView("""<img src="${server.base}/$batch/top.svg" width="120" height="80" alt="Recovered image">""")
                                Spacer(Modifier.height(360.dp))
                                HtmlView("""<img src="${server.base}/$batch/bottom.svg" width="64" height="20" alt="Later badge">""")
                            }
                        }
                    }
                }
                compose.waitUntil(5_000) { first.get() >= 1 }
                assertFalse(server.requests.any { it.endsWith("bottom.svg") })
                compose.waitUntil(12_000) {
                    // Advance the effect clock while the actual network runs on the IO dispatcher.
                    compose.mainClock.advanceTimeBy(1_000)
                    first.get() >= 3
                }
                compose.waitUntil(12_000) {
                    compose.onAllNodesWithContentDescription("Recovered image").fetchSemanticsNodes().isNotEmpty()
                }
                assertEquals(3, first.get())
                assertFalse(server.requests.any { it.endsWith("bottom.svg") })
                repeat(4) { compose.onNodeWithTag("document-scroll").performTouchInput { swipeUp() } }
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithContentDescription("Later badge").fetchSemanticsNodes().isNotEmpty()
                }
            } finally {
                compose.runOnUiThread { nav.dispose() }
            }
        }
    }
}
