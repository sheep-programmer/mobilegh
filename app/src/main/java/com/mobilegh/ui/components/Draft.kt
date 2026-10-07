package com.mobilegh.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.mobilegh.data.*
import com.mobilegh.ui.theme.Gh

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
    Text(controller.error ?: "草稿自动保存在本机", color = if (controller.error != null) Gh.c.danger else Gh.c.fgMuted, fontSize = 11.sp)
}
