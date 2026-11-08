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
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
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
import com.mobilegh.i18n.tr
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.foundation.layout.ExperimentalLayoutApi

/** 可横向滑动的筛选标签 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Chips(options: List<String>, selected: Int, modifier: Modifier = Modifier, enabled: Boolean = true, onSelect: (Int) -> Unit) {
    // 与筛选页一致：标准筛选胶囊，选中项填充色，整行可横向滑动
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEachIndexed { i, s ->
            FilterChip(selected = i == selected, onClick = { onSelect(i) }, enabled = enabled, label = { Text(s) })
        }
    }
}

/** 下拉选择按钮；active 时以强调色显示（表示已设置筛选） */
@Composable
fun Dropdown(label: String, options: List<String>, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, active: Boolean = false, enabled: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) open = false }
    val g = Gh.c
    DropdownButton(label, modifier, active, enabled = enabled, menu = {
        DropdownMenu(open, { open = false }, containerColor = g.canvas) {
            options.forEachIndexed { i, s ->
                DropdownMenuItem(
                    text = { Text(s, color = g.fg) },
                    onClick = { open = false; onSelect(i) },
                    enabled = enabled,
                )
            }
        }
    }) { open = true }
}

/** 与 [Dropdown] 外观一致的按钮，点按后由调用方决定打开什么（如选择弹窗） */
@Composable
fun DropdownButton(label: String, modifier: Modifier = Modifier, active: Boolean = false, menu: @Composable () -> Unit = {}, enabled: Boolean = true, onClick: () -> Unit) {
    // 与筛选胶囊一致：标准 FilterChip 加向下箭头，菜单锚定在胶囊上
    Box(modifier) {
        FilterChip(
            selected = active, onClick = onClick, enabled = enabled,
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { Oc(R.drawable.oc_chevron_down, LocalContentColor.current, 14.dp) },
        )
        menu()
    }
}

data class MenuAction(val text: String, val danger: Boolean = false, val onClick: () -> Unit)

/**
 * 「更多」菜单里统一的链接操作，顺序固定：复制链接、分享、在浏览器打开。
 * 所有页面的链接菜单都用它，避免有的有「复制链接」、有的没有。
 */
fun MutableList<MenuAction>.addLinkActions(ctx: android.content.Context, url: String) {
    add(MenuAction(com.mobilegh.i18n.tr("复制链接")) { ctx.copy(url, com.mobilegh.i18n.tr("已复制链接")) })
    add(MenuAction(com.mobilegh.i18n.tr("分享")) { ctx.share(url) })
    add(MenuAction(com.mobilegh.i18n.tr("在浏览器打开")) { ctx.openBrowser(url) })
}

/** 右上角"更多"菜单 */
@Composable
fun MoreMenu(actions: List<MenuAction>, tint: Color = Gh.c.fg) {
    var open by remember { mutableStateOf(false) }
    val g = Gh.c
    // 菜单必须和按钮放在同一个 Box 里：DropdownMenu 锚定在父布局上，直接放进 Row 会跑到整行的起点（左边）
    Box {
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
}

@Composable
fun GhField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    enabled: Boolean = true,
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
    /** labelAbove 时显示在标签下方的说明文字 */
    description: String? = null,
) {
    val g = Gh.c
    val field: @Composable (Modifier) -> Unit = { m ->
        OutlinedTextField(
            value, onValueChange,
            modifier = m,
            enabled = enabled,
            label = if (labelAbove) null else { { Text(label) } },
            placeholder = placeholder?.let { { Text(it, color = g.fgMuted) } },
            singleLine = singleLine,
            minLines = minLines,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            leadingIcon = leading,
            trailingIcon = trailing,
            shape = OutlinedTextFieldDefaults.shape,
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
            Text(label, Modifier.padding(start = 4.dp, bottom = if (description == null) 6.dp else 2.dp).clearAndSetSemantics {}, fontSize = 13.sp, color = g.fg)
            if (description != null) {
                Text(
                    description, Modifier.padding(start = 4.dp, end = 4.dp, bottom = 6.dp),
                    fontSize = 12.sp, lineHeight = 16.sp, color = g.fgMuted,
                )
            }
            field(Modifier.fillMaxWidth().semantics { contentDescription = label })
        }
    } else {
        field(modifier.fillMaxWidth())
    }
}

@Composable
fun SwitchRow(title: String, desc: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val g = Gh.c
    val tick = rememberTick()
    Row(
        Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = { tick(); onChange(it) })
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Segmented(options: List<String>, selected: Int, modifier: Modifier = Modifier, enabled: Boolean = true, onSelect: (Int) -> Unit) {
    // 与筛选页一致：标准分段按钮，选中项填充色
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { i, label ->
            SegmentedButton(
                selected = i == selected, onClick = { onSelect(i) }, enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

/** 单选行：整行可点，带说明文字；用于弹窗中的选项 */
@Composable
fun RadioRow(selected: Boolean, title: String, desc: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val g = Gh.c
    val tick = rememberTick()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = { if (!selected) tick(); onClick() })
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

/**
 * 弹窗的描边：比页面分隔线（border）深得多。弹窗是白底、页面也是浅色，用分隔线的颜色几乎看不出边界；
 * 这里直接取次要文字色（不透明、2dp），浅色和深色主题下都能一眼看出弹窗的范围。
 */
@Composable
fun Modifier.dialogFrame(): Modifier {
    val g = Gh.c
    // 不透明：半透明的描边会和弹窗外被压暗的页面混在一起，反而看不出来
    return border(2.dp, g.fgMuted, RoundedCornerShape(12.dp))
}

/** 强制显示的弹窗属性：点框外、点遮罩都不会关闭，只能通过弹窗里的按钮（或返回键）处理 */
val ModalDialogProperties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false)

@Composable
fun GhDialog(
    title: String,
    onDismiss: () -> Unit,
    confirm: String = tr("确定"),
    danger: Boolean = false,
    confirmEnabled: Boolean = true,
    dismissEnabled: Boolean = true,
    /** 为 false 时点框外不会关闭：授权、更新这类必须明确作出选择的弹窗使用 */
    dismissOnClickOutside: Boolean = true,
    onConfirm: (() -> Unit)? = null,
    content: @Composable () -> Unit = {},
) {
    val g = Gh.c
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = dismissOnClickOutside),
        containerColor = g.canvas,
        titleContentColor = g.fg,
        textContentColor = g.fg,
        shape = RoundedCornerShape(12.dp),
        // 弹窗与浅色页面底色接近时容易看不出边界，明确描边
        modifier = Modifier.dialogFrame(),
        title = { Text(title, fontWeight = FontWeight.SemiBold, fontSize = 18.sp) },
        text = { content() },
        // 与网页版弹窗一致：确认是绿色主按钮，危险操作是红色描边按钮，取消是白底描边的默认按钮。
        // 之前用的是蓝色文字按钮，和页面里的按钮风格对不上。
        confirmButton = {
            if (onConfirm != null) {
                GhButton(confirm, primary = !danger, danger = danger, enabled = confirmEnabled, onClick = onConfirm)
            }
        },
        dismissButton = {
            GhButton(if (onConfirm == null) tr("关闭") else tr("取消"), enabled = dismissEnabled, onClick = onDismiss)
        },
    )
}

val ListPad = PaddingValues(bottom = 24.dp)
