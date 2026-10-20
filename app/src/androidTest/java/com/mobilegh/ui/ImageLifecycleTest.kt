package com.mobilegh.ui

import android.util.LruCache
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilegh.data.ImageFetch
import com.mobilegh.ui.components.Images
import com.mobilegh.ui.components.NetImage
import com.mobilegh.ui.components.ImageViewer
import com.mobilegh.ui.components.ZoomImage
import com.mobilegh.ui.theme.MobileGhTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun changingUrlReplacesTheBitmapAndNullClearsIt() {
        val first = "https://example.invalid/first.png"
        val second = "https://example.invalid/second.png"
        val url = mutableStateOf<String?>(first)
        // Seed only the real memory cache; this test performs no network requests.
        @Suppress("UNCHECKED_CAST")
        val cache = Images::class.java.getDeclaredField("mem").apply { isAccessible = true }
            .get(Images) as LruCache<String, ImageBitmap>
        fun bitmap(color: Int) = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
            .apply { eraseColor(color) }.asImageBitmap()
        val maxDim = (40 * compose.activity.resources.displayMetrics.density).toInt().coerceIn(64, 2048)
        val firstKey = "${ImageFetch.cacheKey(first)}#$maxDim"
        val secondKey = "${ImageFetch.cacheKey(second)}#$maxDim"
        cache.put(firstKey, bitmap(android.graphics.Color.RED))
        cache.put(secondKey, bitmap(android.graphics.Color.BLUE))
        try {
            compose.setContent { MobileGhTheme { NetImage(url.value, Modifier.size(40.dp).testTag("image"), placeholder = false) } }
            fun pixel() = compose.onNodeWithTag("image").captureToImage().toPixelMap().let { it[it.width / 2, it.height / 2] }
            assertEquals(Color.Red, pixel())
            compose.runOnIdle { url.value = second }
            assertEquals(Color.Blue, pixel())
            compose.runOnIdle { url.value = null }
            org.junit.Assert.assertNotEquals(Color.Blue, pixel())
        } finally {
            cache.remove(firstKey)
            cache.remove(secondKey)
        }
    }

    @Test fun zoomImageTapUsesTheCurrentUrlAfterRecomposition() {
        val url = mutableStateOf("https://example.invalid/first.png")
        compose.setContent { MobileGhTheme { ZoomImage(url.value, Modifier.size(80.dp).testTag("zoom")) } }
        compose.runOnIdle { url.value = "https://example.invalid/second.png" }
        try {
            compose.onNodeWithTag("zoom").performTouchInput { click() }
            compose.waitUntil(2_000) { ImageViewer.url != null }
            compose.runOnIdle { assertEquals("https://example.invalid/second.png", ImageViewer.url) }
        } finally {
            compose.runOnIdle { ImageViewer.close() }
        }
    }
}
