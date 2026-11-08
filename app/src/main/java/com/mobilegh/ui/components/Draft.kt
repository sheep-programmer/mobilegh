package com.mobilegh.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.mobilegh.data.*
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.Screen
import com.mobilegh.ui.theme.Gh
import com.mobilegh.i18n.tr
import kotlinx.serialization.json.Json

private val originJson = Json { encodeDefaults = false }

@Composable
fun rememberDraft(contextKey: String): DraftController {
    val context = LocalContext.current
    val login = Session.login
    // 记下草稿所在的页面：「我的 → 草稿」里点一下就能回到这里继续写
    val screen = LocalEntry.current.screen
    return remember(login, Session.generation, contextKey) {
        val origin = runCatching { originJson.encodeToString(Screen.serializer(), screen) }.getOrNull()
        DraftController(Drafts.store(context), login, contextKey, origin)
    }
}

@Composable
fun DraftBinding(controller: DraftController, value: DraftValue) {
    val ctx = LocalContext.current
    SideEffect { controller.update(value) }
    DisposableEffect(controller) {
        onDispose {
            // 离开页面时还有没发出去的内容：告诉用户草稿已经保存在哪里，不再是静默保存
            if (controller.hasContent) ctx.toast(tr("草稿已保存，可在「我的 → 草稿」继续编辑"))
            controller.flush()
        }
    }
    // 保存本身是静默的；只有读写失败时才占一行提示，避免在每个编辑框下面常驻说明文字
    controller.error?.let { Text(it, color = Gh.c.danger, fontSize = 11.sp) }
}
