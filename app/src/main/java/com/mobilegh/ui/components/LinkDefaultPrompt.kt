package com.mobilegh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mobilegh.R
import com.mobilegh.data.LinkDefault
import com.mobilegh.i18n.tr
import com.mobilegh.ui.theme.Gh

/**
 * 每次启动应用时询问一次「是否用 MobileGH 打开 GitHub 链接」（仅在尚未设为默认时）。
 * 「跳过」下次启动再问；「同意」「拒绝」之后不再问，设置页里仍可手动设置。
 */
@Composable
fun LinkDefaultPrompt() {
    val ctx = rememberCtx()
    val g = Gh.c
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!LinkDefault.askedThisLaunch) {
            LinkDefault.askedThisLaunch = true
            show = LinkDefault.shouldAsk(ctx)
        }
    }
    if (!show) return
    AlertDialog(
        onDismissRequest = {
            show = false
            LinkDefault.set(LinkDefault.Status.Skipped)
        },
        containerColor = g.canvas,
        title = { Text(tr("用 MobileGH 打开 GitHub 链接？"), color = g.fg, fontSize = 18.sp) },
        text = {
            Text(
                tr("设为默认后，点开 github.com 链接会直接用 MobileGH 打开，不再交给官方 GitHub App。") + "\n\n" +
                    tr("系统仍可能先弹出选择框：选择 MobileGH 并勾选「始终」。在接下来打开的系统设置里，部分机型还需要点「添加链接」把 github.com 加进来。"),
                color = g.fgMuted, fontSize = 14.sp, lineHeight = 20.sp,
            )
        },
        // 三个操作都用圆角框：拒绝、跳过为描边，同意为绿色实心（与应用里的主按钮一致）
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PromptButton(tr("拒绝"), filled = false, color = g.fgMuted) {
                    show = false
                    LinkDefault.set(LinkDefault.Status.Declined)
                }
                PromptButton(tr("跳过"), filled = false, color = g.fgMuted) {
                    show = false
                    LinkDefault.set(LinkDefault.Status.Skipped)
                }
                PromptButton(tr("同意"), filled = true, color = Color.White) {
                    show = false
                    LinkDefault.set(LinkDefault.Status.Accepted)
                    openLinkSettings(ctx)
                }
            }
        },
        dismissButton = null,
    )
}

/** 弹窗里的圆角按钮：filled 为绿色实心，否则白底描边 */
@Composable
private fun PromptButton(text: String, filled: Boolean, color: Color, onClick: () -> Unit) {
    val g = Gh.c
    val shape = RoundedCornerShape(6.dp)
    val bg = if (filled) g.btnPrimary else g.canvas
    Box(
        Modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, if (filled) g.btnPrimary else g.border, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/** 设置页里的一行：显示当前是否已是默认打开方式，点按去系统设置。回到应用时重新检查。 */
@Composable
fun LinkDefaultRow() {
    val ctx = rememberCtx()
    val owner = LocalLifecycleOwner.current
    var isDefault by remember { mutableStateOf(LinkDefault.isConfigured(ctx)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) isDefault = LinkDefault.isConfigured(ctx)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val status = LinkDefault.status
    MenuRow(
        R.drawable.oc_link_external,
        tr("默认打开链接"),
        value = when {
            isDefault -> tr("已设为默认")
            status == LinkDefault.Status.Accepted -> tr("已同意，点此设置")
            status == LinkDefault.Status.Declined -> tr("已拒绝，点此设置")
            else -> tr("未设置")
        },
    ) { openLinkSettings(ctx) }
}

private fun openLinkSettings(ctx: Context) {
    runCatching { ctx.startActivity(LinkDefault.settingsIntent(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { ctx.toast(tr("无法打开系统设置，请到应用信息中设置")) }
}
