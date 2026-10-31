package com.mobilegh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.i18n.tr
import com.mobilegh.ui.theme.Gh

/**
 * 输入确认是否匹配：去掉首尾空白后与 [expected] 比较。
 * [expected] 为空时永远不匹配，避免空名称让确认按钮亮起；[ignoreCase] 用于 GitHub 仓库全名这类大小写不敏感的名称。
 */
internal fun typeToConfirmMatches(typed: String, expected: String, ignoreCase: Boolean): Boolean {
    if (expected.isEmpty()) return false
    return typed.trim().equals(expected, ignoreCase = ignoreCase)
}

/**
 * 不可逆删除的二次确认：必须完整输入 [expected] 后确认按钮才会亮起，防止误触。
 * [content] 放后果说明；[hint] 是输入框上方的提示；[busy] 为 true 时禁用确认与关闭，防止重复提交。
 */
@Composable
fun TypeToConfirmDialog(
    title: String,
    expected: String,
    hint: String,
    fieldLabel: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    ignoreCase: Boolean = false,
    busy: Boolean = false,
    content: @Composable () -> Unit,
) {
    val g = Gh.c
    val ctx = rememberCtx()
    var typed by remember { mutableStateOf("") }
    GhDialog(
        title, onDismiss = { if (!busy) onDismiss() },
        confirm = if (busy) tr("删除中…") else confirm, danger = true,
        confirmEnabled = !busy && typeToConfirmMatches(typed, expected, ignoreCase),
        dismissEnabled = !busy,
        onConfirm = onConfirm,
    ) {
        Column {
            content()
            Spacer(Modifier.height(10.dp))
            Text(hint, color = g.fgMuted, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(g.canvasSubtle).padding(start = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SelectionContainer(Modifier.weight(1f)) {
                    Text(expected, color = g.fg, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                }
                TextLink(tr("复制")) { ctx.copy(expected) }
            }
            Spacer(Modifier.height(8.dp))
            GhField(typed, { typed = it }, fieldLabel, placeholder = expected)
        }
    }
}
