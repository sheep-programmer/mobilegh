package com.mobilegh.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.mobilegh.data.*
import com.mobilegh.ui.theme.Gh
import com.mobilegh.i18n.tr

@Composable
fun rememberDraft(contextKey: String): DraftController {
    val context = LocalContext.current
    val login = Session.login
    return remember(login, Session.generation, contextKey) { DraftController(Drafts.store(context), login, contextKey) }
}

@Composable
fun DraftBinding(controller: DraftController, value: DraftValue) {
    SideEffect { controller.update(value) }
    DisposableEffect(controller) { onDispose { controller.flush() } }
    // 保存本身是静默的；只有读写失败时才占一行提示，避免在每个编辑框下面常驻说明文字
    controller.error?.let { Text(it, color = Gh.c.danger, fontSize = 11.sp) }
}
