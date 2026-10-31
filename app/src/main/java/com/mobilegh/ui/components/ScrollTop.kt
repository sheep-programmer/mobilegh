package com.mobilegh.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import com.mobilegh.R
import com.mobilegh.ui.theme.Gh
import com.mobilegh.i18n.tr
import kotlinx.coroutines.launch

/** 页面里当前的主滚动列表，「回到顶部」按钮读取它的位置。 */
class ScrollTopTarget {
    var list: LazyListState? by mutableStateOf(null)
}

val LocalScrollTopTarget = compositionLocalOf<ScrollTopTarget?> { null }

/** 把页面的主列表登记给所在页面的「回到顶部」按钮；离开组合时自动撤销。 */
@Composable
fun RegisterScrollTop(state: LazyListState) {
    val target = LocalScrollTopTarget.current ?: return
    DisposableEffect(target, state) {
        target.list = state
        onDispose { if (target.list === state) target.list = null }
    }
}

/** 向下翻过第一屏（顶部项已滑出）后显示；点按平滑滚回顶部。 */
@Composable
fun ScrollToTopButton(target: ScrollTopTarget) {
    val g = Gh.c
    val state = target.list
    val scope = rememberCoroutineScope()
    val away by remember(state) { derivedStateOf { state != null && state.firstVisibleItemIndex > 0 } }
    AnimatedVisibility(visible = away, enter = fadeIn(), exit = fadeOut()) {
        SmallFloatingActionButton(
            onClick = { scope.launch { state?.animateScrollToItem(0) } },
            containerColor = g.canvas,
            contentColor = g.fg,
            modifier = Modifier.padding(bottom = 8.dp).semantics { contentDescription = tr("回到顶部") },
        ) {
            Oc(R.drawable.oc_arrow_up, g.fg, 18.dp)
        }
    }
}
