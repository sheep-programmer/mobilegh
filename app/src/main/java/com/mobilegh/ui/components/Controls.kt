package com.mobilegh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.ui.theme.Gh

/** 可横向滑动的筛选标签 */
@Composable
fun Chips(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val g = Gh.c
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEachIndexed { i, s ->
            val sel = i == selected
            Text(
                s,
                Modifier
                    .background(if (sel) g.accentSubtle else g.canvas, RoundedCornerShape(50))
                    .border(1.dp, if (sel) g.accent.copy(alpha = 0.6f) else g.border, RoundedCornerShape(50))
                    .clickable { onSelect(i) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                fontSize = 13.sp,
                fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                color = if (sel) g.accent else g.fg,
            )
        }
    }
}

/** 下拉选择按钮 */
@Composable
fun Dropdown(label: String, options: List<String>, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val g = Gh.c
    Row(
        modifier
            .border(1.dp, g.border, RoundedCornerShape(6.dp))
            .background(g.btnBg, RoundedCornerShape(6.dp))
            .clickable { open = true }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, color = g.fg, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(4.dp))
        Oc(R.drawable.oc_chevron_down, g.fgMuted, 12.dp)
        DropdownMenu(open, { open = false }, containerColor = g.canvas) {
            options.forEachIndexed { i, s ->
                DropdownMenuItem(text = { Text(s, color = g.fg) }, onClick = { open = false; onSelect(i) })
            }
        }
    }
}

data class MenuAction(val text: String, val danger: Boolean = false, val onClick: () -> Unit)

/** 右上角"更多"菜单 */
@Composable
fun MoreMenu(actions: List<MenuAction>) {
    var open by remember { mutableStateOf(false) }
    val g = Gh.c
    OcButton(R.drawable.oc_kebab_horizontal, { open = true })
    DropdownMenu(open, { open = false }, containerColor = g.canvas) {
        actions.forEach { a ->
            DropdownMenuItem(
                text = { Text(a.text, color = if (a.danger) g.danger else g.fg) },
                onClick = { open = false; a.onClick() },
            )
        }
    }
}

@Composable
fun GhField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    placeholder: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
    keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    val g = Gh.c
    OutlinedTextField(
        value, onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = g.fgMuted) } },
        singleLine = singleLine,
        minLines = minLines,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        trailingIcon = trailing,
        shape = RoundedCornerShape(6.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = g.accent,
            unfocusedBorderColor = g.border,
            focusedContainerColor = g.canvas,
            unfocusedContainerColor = g.canvasSubtle,
            focusedLabelColor = g.accent,
            unfocusedLabelColor = g.fgMuted,
            cursorColor = g.accent,
        ),
    )
}

@Composable
fun SwitchRow(title: String, desc: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val g = Gh.c
    Row(
        Modifier.fillMaxWidth().clickable(enabled) { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = g.fg)
            if (desc != null) Text(desc, fontSize = 12.sp, color = g.fgMuted)
        }
        Switch(
            checked, onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = g.btnPrimary, checkedThumbColor = Color.White, uncheckedTrackColor = g.neutralMuted, uncheckedBorderColor = g.border),
        )
    }
}

@Composable
fun GhDialog(
    title: String,
    onDismiss: () -> Unit,
    confirm: String = "确定",
    danger: Boolean = false,
    confirmEnabled: Boolean = true,
    onConfirm: (() -> Unit)? = null,
    content: @Composable () -> Unit = {},
) {
    val g = Gh.c
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = g.canvas,
        titleContentColor = g.fg,
        textContentColor = g.fg,
        shape = RoundedCornerShape(12.dp),
        title = { Text(title, fontWeight = FontWeight.SemiBold, fontSize = 18.sp) },
        text = { content() },
        confirmButton = {
            if (onConfirm != null) {
                TextButton(onConfirm, enabled = confirmEnabled) {
                    Text(confirm, color = if (!confirmEnabled) g.fgMuted else if (danger) g.danger else g.accent, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(if (onConfirm == null) "关闭" else "取消", color = g.fgMuted) } },
    )
}

val ListPad = PaddingValues(bottom = 24.dp)
