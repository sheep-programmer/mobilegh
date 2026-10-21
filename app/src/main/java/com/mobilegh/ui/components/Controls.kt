package com.mobilegh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
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
                    // 涟漪要裁进胶囊形状，否则点击效果是越界的矩形
                    .clip(RoundedCornerShape(50))
                    .clickable { onSelect(i) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                fontSize = 13.sp,
                fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                color = if (sel) g.accent else g.fg,
            )
        }
    }
}

/** 下拉选择按钮；active 时以强调色显示（表示已设置筛选） */
@Composable
fun Dropdown(label: String, options: List<String>, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, active: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    val g = Gh.c
    DropdownButton(label, modifier, active, menu = {
        DropdownMenu(open, { open = false }, containerColor = g.canvas) {
            options.forEachIndexed { i, s ->
                DropdownMenuItem(text = { Text(s, color = g.fg) }, onClick = { open = false; onSelect(i) })
            }
        }
    }) { open = true }
}

/** 与 [Dropdown] 外观一致的按钮，点按后由调用方决定打开什么（如选择弹窗） */
@Composable
fun DropdownButton(label: String, modifier: Modifier = Modifier, active: Boolean = false, menu: @Composable () -> Unit = {}, onClick: () -> Unit) {
    val g = Gh.c
    Row(
        modifier
            // 放在横向滚动行里时宽度无上限，weight 会把标签压成 0 宽只剩箭头；先按内容定宽，空间不够时仍会被约束截断
            .width(IntrinsicSize.Max)
            .border(1.dp, if (active) g.accent.copy(alpha = 0.6f) else g.border, RoundedCornerShape(6.dp))
            .background(if (active) g.accentSubtle else g.btnBg, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f, fill = false), fontSize = 13.sp, color = if (active) g.accent else g.fg,
            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(4.dp))
        Oc(R.drawable.oc_chevron_down, if (active) g.accent else g.fgMuted, 12.dp)
        menu()
    }
}

data class MenuAction(val text: String, val danger: Boolean = false, val onClick: () -> Unit)

/** 右上角"更多"菜单 */
@Composable
fun MoreMenu(actions: List<MenuAction>, tint: Color = Gh.c.fg) {
    var open by remember { mutableStateOf(false) }
    val g = Gh.c
    OcButton(R.drawable.oc_kebab_horizontal, { open = true }, tint)
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
    // GitHub 类输入（令牌、分支、用户名）不该被键盘首字母大写，否则精确匹配永远失败
    keyboardOptions: KeyboardOptions = KeyboardOptions(
        capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None, imeAction = ImeAction.Done,
    ),
    keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    // 表单里 label 放在输入框上方常驻，避免空值时只剩一行灰字分不清是标签还是提示
    labelAbove: Boolean = false,
) {
    val g = Gh.c
    val field: @Composable (Modifier) -> Unit = { m ->
        OutlinedTextField(
            value, onValueChange,
            modifier = m,
            label = if (labelAbove) null else { { Text(label) } },
            placeholder = placeholder?.let { { Text(it, color = g.fgMuted) } },
            singleLine = singleLine,
            minLines = minLines,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            leadingIcon = leading,
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
    // 只有常驻标签才包一层 Column；其余调用保持原结构，调用方的 weight/padding/testTag 仍作用在输入框上
    if (labelAbove) {
        Column(modifier.fillMaxWidth()) {
            // 标签文字只做视觉展示，语义名称挂到输入框本身，读屏聚焦输入框时能读出字段名
            Text(label, Modifier.padding(start = 4.dp, bottom = 6.dp).clearAndSetSemantics {}, fontSize = 13.sp, color = g.fg)
            field(Modifier.fillMaxWidth().semantics { contentDescription = label })
        }
    } else {
        field(modifier.fillMaxWidth())
    }
}

@Composable
fun SwitchRow(title: String, desc: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val g = Gh.c
    Row(
        Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = g.fg)
            if (desc != null) Text(desc, fontSize = 12.sp, color = g.fgMuted)
        }
        Switch(
            checked, onCheckedChange = null, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = g.btnPrimary, checkedThumbColor = Color.White, uncheckedTrackColor = g.neutralMuted, uncheckedBorderColor = g.border),
        )
    }
}

/** 分段切换（如 GitHub / 本机），选中项为实底 */
@Composable
fun Segmented(options: List<String>, selected: Int, modifier: Modifier = Modifier, enabled: Boolean = true, onSelect: (Int) -> Unit) {
    val g = Gh.c
    Row(modifier.selectableGroup().background(g.canvasSubtle, RoundedCornerShape(8.dp)).border(1.dp, g.borderMuted, RoundedCornerShape(8.dp)).padding(3.dp)) {
        options.forEachIndexed { i, label ->
            val sel = i == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(6.dp))
                    .then(if (sel) Modifier.background(g.canvas, RoundedCornerShape(6.dp)).border(1.dp, g.border, RoundedCornerShape(6.dp)) else Modifier)
                    .selectable(selected = sel, enabled = enabled, role = Role.Tab) { if (!sel) onSelect(i) }
                    .heightIn(min = 48.dp).padding(horizontal = 6.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, fontSize = 14.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, color = if (sel) g.fg else g.fgMuted) }
        }
    }
}

/** 单选行：整行可点，带说明文字；用于弹窗中的选项 */
@Composable
fun RadioRow(selected: Boolean, title: String, desc: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val g = Gh.c
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.RadioButton(selected, null, enabled = enabled,
            colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = g.accent, unselectedColor = g.fgMuted))
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(title, fontSize = 15.sp, color = if (enabled) g.fg else g.fgMuted)
            if (desc != null) Text(desc, fontSize = 12.sp, lineHeight = 16.sp, color = g.fgMuted)
        }
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
        // 弹窗与浅色页面底色接近时容易看不出边界，明确描边
        modifier = Modifier.border(1.dp, g.border, RoundedCornerShape(12.dp)),
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
