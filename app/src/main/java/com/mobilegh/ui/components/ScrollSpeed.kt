package com.mobilegh.ui.components

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * 列表快速甩动时的最大初速度（像素/秒）。
 *
 * 列表里有 WebView（评论正文、README）：内容移动得比 WebView 光栅化快时，会露出一块块空白。
 * 把甩动的初速度限制在这个值以内，内容移动得慢一些，WebView 来得及画出来。
 */
internal const val MAX_FLING_VELOCITY = 4000f

/** 与默认甩动相同，但初速度不超过 [maxVelocity]。用在包含网页内容的列表上。 */
@Composable
fun cappedFlingBehavior(maxVelocity: Float = MAX_FLING_VELOCITY): FlingBehavior {
    val base = ScrollableDefaults.flingBehavior()
    return remember(base, maxVelocity) {
        object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float =
                with(base) { performFling(initialVelocity.coerceIn(-maxVelocity, maxVelocity)) }
        }
    }
}
