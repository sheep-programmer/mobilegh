package com.mobilegh.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.height
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.mutableStateListOf
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
import com.mobilegh.i18n.trf
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.foundation.layout.ExperimentalLayoutApi

/** 筛选标签选项（支持图标） */
data class IconChipOption(
    val label: String,
    @get:DrawableRes val icon: Int? = null,
)

/** 可横向滑动且支持首部图标的筛选标签 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IconChips(
    options: List<IconChipOption>,
    selected: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { i, opt ->
            val chipSelected = i == selected
            FilterChip(
                selected = chipSelected,
                onClick = { onSelect(i) },
                enabled = enabled,
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Gh.c.canvasSubtle,
                    labelColor = Gh.c.fgMuted,
                    selectedContainerColor = if (Gh.c.dark) Color(0xFF28303C) else Gh.c.accent.copy(alpha = 0.12f),
                    selectedLabelColor = if (Gh.c.dark) Gh.c.fg else Gh.c.accent,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = enabled,
                    selected = chipSelected,
                    borderColor = Gh.c.borderMuted,
                    selectedBorderColor = if (Gh.c.dark) Color(0xFF444C56) else Gh.c.accent.copy(alpha = 0.4f),
                ),
                leadingIcon = opt.icon?.let { ic ->
                    { Oc(ic, if (chipSelected) (if (Gh.c.dark) Gh.c.fg else Gh.c.accent) else Gh.c.fgMuted, 14.dp) }
                },
                label = { Text(opt.label) },
            )
        }
    }
}

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
            val chipSelected = i == selected
            FilterChip(
                selected = chipSelected,
                onClick = { onSelect(i) },
                enabled = enabled,
                label = { Text(s) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Gh.c.canvasSubtle,
                    labelColor = Gh.c.fgMuted,
                    selectedContainerColor = if (Gh.c.dark) Color(0xFF28303C) else Gh.c.accent.copy(alpha = 0.12f),
                    selectedLabelColor = if (Gh.c.dark) Gh.c.fg else Gh.c.accent,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = enabled,
                    selected = chipSelected,
                    borderColor = Gh.c.borderMuted,
                    selectedBorderColor = if (Gh.c.dark) Color(0xFF444C56) else Gh.c.accent.copy(alpha = 0.4f),
                ),
            )
        }
    }
}

/** 下拉选择按钮；active 时以强调色显示（表示已设置筛选） */
@Composable
fun Dropdown(label: String, options: List<String>, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, active: Boolean = false, enabled: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) open = false }
    val g = Gh.c
    val scrollState = rememberScrollState()
    DropdownButton(label, modifier, active, enabled = enabled, menu = {
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier
                .widthIn(min = 180.dp, max = 280.dp)
                .heightIn(max = 360.dp),
            shape = RoundedCornerShape(12.dp),
            containerColor = g.canvas,
            border = BorderStroke(1.dp, g.borderMuted.copy(alpha = 0.5f)),
            shadowElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScrollWithBar(scrollState)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                options.forEachIndexed { i, s ->
                    val isSelected = s == label
                    val cardBg = if (isSelected) g.accent.copy(alpha = 0.12f) else g.canvasSubtle
                    val cardBorder = if (isSelected) g.accent.copy(alpha = 0.35f) else g.borderMuted
                    val textFg = if (isSelected) g.accent else g.fg
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(9.dp))
                            .background(cardBg)
                            .border(1.dp, cardBorder, RoundedCornerShape(9.dp))
                            .clickable(enabled = enabled) {
                                open = false
                                onSelect(i)
                            }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = s,
                            color = textFg,
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (isSelected) {
                            Spacer(Modifier.width(8.dp))
                            Oc(R.drawable.oc_check, g.accent, 14.dp)
                        }
                    }
                }
            }
        }
    }) { open = true }
}

/** 多选项定义 */
data class MultiSelectOption(
    val id: String,
    val label: String,
    @get:DrawableRes val icon: Int? = null,
)

/**
 * 支持多选、带滚动条和最大高度限制的卡片式下拉菜单或搜索弹窗。
 * 针对仓库等大量、动态项，通过 [searchable] 启用窗口弹窗模式，支持实时模糊搜索、全选与清空。
 * 只有在用户点击「完成」时才会真正提交应用筛选，避免点选过程中界面频繁刷新。
 */
@Composable
fun MultiSelectDropdown(
    label: String,
    options: List<MultiSelectOption>,
    selectedIds: Set<String>,
    onApply: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    searchable: Boolean = false,
    dialogTitle: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) open = false }

    if (searchable) {
        DropdownButton(label, modifier, active = active, enabled = enabled, menu = {}) {
            open = true
        }
        if (open) {
            MultiSelectDialog(
                title = dialogTitle ?: label,
                options = options,
                selectedIds = selectedIds,
                onDismiss = { open = false },
                onApply = {
                    onApply(it)
                    open = false
                },
            )
        }
        return
    }

    val g = Gh.c
    val scrollState = rememberScrollState()

    // 仅在打开时初始化一份本地暂存集合，点「完成」前绝不触发外部筛选更新
    val tempSelected = remember { mutableStateListOf<String>() }
    LaunchedEffect(open) {
        if (open) {
            tempSelected.clear()
            tempSelected.addAll(selectedIds)
        }
    }

    DropdownButton(label, modifier, active = active, enabled = enabled, menu = {
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier
                .widthIn(min = 210.dp, max = 310.dp)
                .heightIn(max = 360.dp),
            shape = RoundedCornerShape(12.dp),
            containerColor = g.canvas,
            border = BorderStroke(1.dp, g.borderMuted.copy(alpha = 0.5f)),
            shadowElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScrollWithBar(scrollState)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // 顶部快捷行：全选 / 清空 / 完成（每一个操作都有专属的可爱圆角外框包裹，不再是裸文字）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // 全选按钮胶囊框
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(g.canvasSubtle)
                                .border(1.dp, g.borderMuted, RoundedCornerShape(8.dp))
                                .clickable {
                                    tempSelected.clear()
                                    tempSelected.addAll(options.map { it.id })
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                tr("全选"),
                                fontSize = 12.sp,
                                color = g.fg,
                                fontWeight = FontWeight.Medium,
                            )
                        }

                        // 清空按钮胶囊框（仅在有选中项时展示）
                        if (tempSelected.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(g.danger.copy(alpha = 0.08f))
                                    .border(1.dp, g.danger.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                                    .clickable {
                                        tempSelected.clear()
                                    }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    tr("清空"),
                                    fontSize = 12.sp,
                                    color = g.danger,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }

                    // 完成按钮胶囊框（实心强调色背景，清晰高亮表示最终确认提交）
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(g.accent)
                            .clickable {
                                open = false
                                onApply(tempSelected.toSet())
                            }
                            .padding(horizontal = 14.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            tr("完成"),
                            fontSize = 12.sp,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                HDivider()

                options.forEach { opt ->
                    val isSelected = opt.id in tempSelected
                    val cardBg = if (isSelected) g.accent.copy(alpha = 0.12f) else g.canvasSubtle
                    val cardBorder = if (isSelected) g.accent.copy(alpha = 0.35f) else g.borderMuted
                    val textFg = if (isSelected) g.accent else g.fg

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(9.dp))
                            .background(cardBg)
                            .border(1.dp, cardBorder, RoundedCornerShape(9.dp))
                            .clickable(enabled = enabled) {
                                if (opt.id in tempSelected) {
                                    tempSelected.remove(opt.id)
                                } else {
                                    tempSelected.add(opt.id)
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 选项左侧的多选对勾框
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(g.accent),
                                contentAlignment = Alignment.Center,
                            ) {
                                Oc(R.drawable.oc_check, g.canvas, 12.dp)
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .border(1.dp, g.borderMuted, CircleShape)
                            )
                        }

                        Spacer(Modifier.width(10.dp))

                        // 图标（若有）
                        if (opt.icon != null) {
                            Oc(opt.icon, if (isSelected) g.accent else g.fgMuted, 15.dp)
                            Spacer(Modifier.width(8.dp))
                        }

                        Text(
                            text = opt.label,
                            color = textFg,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }) { open = true }
}

/**
 * 窗口式多选弹窗：专为仓库等动态、大量项设计，支持搜索输入框快速筛选，大面积卡片展示，不拥挤。
 */
@Composable
fun MultiSelectDialog(
    title: String,
    options: List<MultiSelectOption>,
    selectedIds: Set<String>,
    onDismiss: () -> Unit,
    onApply: (Set<String>) -> Unit,
) {
    val g = Gh.c
    var query by remember { mutableStateOf("") }
    val tempSelected = remember { mutableStateListOf<String>().apply { addAll(selectedIds) } }
    val filtered = remember(query, options) {
        val q = query.trim()
        if (q.isEmpty()) options
        else options.filter { it.label.contains(q, ignoreCase = true) || it.id.contains(q, ignoreCase = true) }
    }
    val listState = rememberLazyListState()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = g.canvas,
        titleContentColor = g.fg,
        textContentColor = g.fg,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 0.dp,
        modifier = Modifier.dialogFrame().widthIn(min = 280.dp, max = 400.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                if (tempSelected.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .background(g.accent.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        Text(trf("已选 {0} 项", tempSelected.size), fontSize = 12.sp, color = g.accent, fontWeight = FontWeight.Medium)
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 搜索输入框
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(g.canvasSubtle, RoundedCornerShape(8.dp))
                        .border(1.dp, g.borderMuted, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Oc(R.drawable.oc_search, g.fgMuted, 16.dp)
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = TextStyle(color = g.fg, fontSize = 14.sp),
                        decorationBox = { innerTextField ->
                            if (query.isEmpty()) {
                                Text(tr("搜索…"), color = g.fgMuted, fontSize = 14.sp)
                            }
                            innerTextField()
                        },
                    )
                    if (query.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { query = "" }
                                .padding(2.dp),
                        ) {
                            Oc(R.drawable.oc_x, g.fgMuted, 14.dp)
                        }
                    }
                }

                // 快捷操作：全选 / 清空
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(g.canvasSubtle)
                                .border(1.dp, g.borderMuted, RoundedCornerShape(6.dp))
                                .clickable {
                                    val targetIds = filtered.map { it.id }
                                    if (targetIds.isNotEmpty() && targetIds.all { it in tempSelected }) {
                                        tempSelected.removeAll(targetIds)
                                    } else {
                                        targetIds.forEach { if (it !in tempSelected) tempSelected.add(it) }
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            val allSelected = filtered.isNotEmpty() && filtered.all { it.id in tempSelected }
                            Text(if (allSelected) tr("取消全选") else tr("全选"), fontSize = 12.sp, color = g.fg)
                        }
                        if (tempSelected.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(g.danger.copy(alpha = 0.08f))
                                    .border(1.dp, g.danger.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
                                    .clickable { tempSelected.clear() }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Text(tr("清空"), fontSize = 12.sp, color = g.danger)
                            }
                        }
                    }
                    Text(trf("共 {0} 项", filtered.size), fontSize = 12.sp, color = g.fgMuted)
                }

                // 滚动卡片列表
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp, max = 320.dp),
                ) {
                    if (filtered.isEmpty()) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
                            Text(tr("未找到匹配的项"), color = g.fgMuted, fontSize = 13.sp)
                        }
                    } else {
                        ScrollbarLazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(filtered, key = { it.id }) { item ->
                                val selected = item.id in tempSelected
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (selected) g.accent.copy(alpha = 0.08f) else g.canvasSubtle.copy(alpha = 0.5f))
                                        .border(
                                            1.dp,
                                            if (selected) g.accent.copy(alpha = 0.5f) else g.borderMuted.copy(alpha = 0.5f),
                                            RoundedCornerShape(8.dp),
                                        )
                                        .clickable {
                                            if (selected) tempSelected.remove(item.id) else tempSelected.add(item.id)
                                        }
                                        .padding(horizontal = 10.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(18.dp)
                                            .border(
                                                1.5.dp,
                                                if (selected) g.accent else g.fgMuted.copy(alpha = 0.6f),
                                                RoundedCornerShape(4.dp),
                                            )
                                            .background(if (selected) g.accent else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(4.dp)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (selected) {
                                            Icon(
                                                painterResource(R.drawable.oc_check),
                                                contentDescription = null,
                                                tint = androidx.compose.ui.graphics.Color.White,
                                                modifier = Modifier.size(12.dp),
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    if (item.icon != null) {
                                        Oc(item.icon, if (selected) g.accent else g.fgMuted, 16.dp)
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(
                                        text = item.label,
                                        fontSize = 13.sp,
                                        color = if (selected) g.fg else g.fgMuted,
                                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            GhButton(
                tr("完成"),
                primary = true,
                onClick = {
                    onApply(tempSelected.toSet())
                    onDismiss()
                },
            )
        },
        dismissButton = {
            GhButton(tr("取消"), onClick = onDismiss)
        },
    )
}

/** 与 [Dropdown] 外观一致的按钮，点按后由调用方决定打开什么（如选择弹窗） */
@Composable
fun DropdownButton(
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    menu: @Composable () -> Unit = {},
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val g = Gh.c
    Box(modifier) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .border(
                    width = 1.dp,
                    color = if (active) g.accent.copy(alpha = 0.5f) else g.borderMuted,
                    shape = RoundedCornerShape(8.dp),
                )
                .background(if (active) g.accent.copy(alpha = 0.08f) else Color.Transparent)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) g.accent else g.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Oc(
                    R.drawable.oc_chevron_down,
                    if (active) g.accent else g.fgMuted,
                    14.dp,
                )
            }
        }
        menu()
    }
}

data class MenuAction(
    val text: String,
    val danger: Boolean = false,
    @get:DrawableRes val icon: Int? = null,
    val enabled: Boolean = true,
    val checked: Boolean? = null,
    val onClick: () -> Unit,
) {
    constructor(text: String, onClick: () -> Unit) : this(text, false, null, true, null, onClick)
    constructor(text: String, danger: Boolean, onClick: () -> Unit) : this(text, danger, null, true, null, onClick)
    constructor(text: String, danger: Boolean, @DrawableRes icon: Int?, onClick: () -> Unit) : this(text, danger, icon, true, null, onClick)
    constructor(text: String, @DrawableRes icon: Int?, checked: Boolean?, onClick: () -> Unit) : this(text, false, icon, true, checked, onClick)
}

/** 智能推断操作对应的 Octicon 图标 */
@DrawableRes
fun inferActionIcon(text: String, danger: Boolean = false): Int {
    val t = text.lowercase()
    return when {
        danger || t.contains("删除") || t.contains("移除") || t.contains("清空") || t.contains("清除") ||
            t.contains("delete") || t.contains("remove") || t.contains("clear") -> R.drawable.oc_trash
        t.contains("搜索") || t.contains("查找") || t.contains("search") || t.contains("find") -> R.drawable.oc_search
        t.contains("编辑") || t.contains("edit") || t.contains("修改") -> R.drawable.oc_pencil
        t.contains("复制") || t.contains("copy") -> R.drawable.oc_copy
        t.contains("分享") || t.contains("share") -> R.drawable.oc_share_android
        t.contains("浏览器") || t.contains("browser") || t.contains("网页") -> R.drawable.oc_globe
        t.contains("稍后阅读") || t.contains("bookmark") -> R.drawable.oc_bookmark
        t.contains("收藏") || t.contains("star") -> R.drawable.oc_star
        t.contains("刷新") || t.contains("reload") || t.contains("refresh") -> R.drawable.oc_sync
        t.contains("克隆") || t.contains("clone") -> R.drawable.oc_link
        t.contains("下载") || t.contains("download") -> R.drawable.oc_download
        t.contains("回复") || t.contains("reply") -> R.drawable.oc_comment
        t.contains("源码") || t.contains("source") || t.contains("code") -> R.drawable.oc_code
        t.contains("查看") || t.contains("浏览") || t.contains("open") || t.contains("view") -> R.drawable.oc_eye
        t.contains("设置") || t.contains("setting") -> R.drawable.oc_gear
        t.contains("合并") || t.contains("merge") -> R.drawable.oc_git_merge
        t.contains("置顶") || t.contains("pin") -> R.drawable.oc_pin
        t.contains("导出") || t.contains("export") -> R.drawable.oc_upload
        t.contains("模板") || t.contains("template") -> R.drawable.oc_checklist
        t.contains("答案") || t.contains("answer") -> R.drawable.oc_check_circle
        t.contains("历史") || t.contains("history") -> R.drawable.oc_history
        t.contains("自动换行") -> R.drawable.oc_wrap_text
        t.contains("关闭") || t.contains("close") -> R.drawable.oc_x_circle
        t.contains("重新") || t.contains("reopen") -> R.drawable.oc_issue_reopened
        t.contains("上移") || t.contains("up") -> R.drawable.oc_arrow_up
        t.contains("下移") || t.contains("down") -> R.drawable.oc_arrow_down
        t.contains("添加") || t.contains("新建") || t.contains("add") || t.contains("new") -> R.drawable.oc_plus
        else -> R.drawable.oc_dot_fill
    }
}

/** 现代卡片风格的单个菜单选项项：去除了生硬的内部小框黑边，触控反馈自然平滑 */
@Composable
fun MenuActionItem(
    a: MenuAction,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val g = Gh.c
    val iconRes = a.icon ?: inferActionIcon(a.text, a.danger)
    val itemFg = when {
        !a.enabled -> g.fgMuted.copy(alpha = 0.45f)
        a.danger -> g.danger
        else -> g.fg
    }
    val iconFg = when {
        !a.enabled -> g.fgMuted.copy(alpha = 0.45f)
        a.danger -> g.danger
        iconRes == R.drawable.oc_check -> g.success
        else -> g.fgMuted
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .semantics(mergeDescendants = true) {
                if (!a.enabled) disabled()
            }
            .clickable(enabled = a.enabled) {
                onDismiss()
                a.onClick()
            }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Oc(iconRes, iconFg, 16.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            text = a.text,
            color = itemFg,
            fontSize = 14.sp,
            fontWeight = if (a.checked == true) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (a.checked != null) {
            Spacer(Modifier.width(8.dp))
            if (a.checked) {
                Oc(R.drawable.oc_check, g.accent, 16.dp)
            } else {
                Spacer(Modifier.width(16.dp))
            }
        }
    }
}

/**
 * 清晰圆角边框与柔和阴影的现代下拉菜单：消除层层嵌套黑框，视觉轻盈现代
 */
@Composable
fun CardDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    actions: List<MenuAction>,
    modifier: Modifier = Modifier,
) {
    val g = Gh.c
    val scrollState = rememberScrollState()
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier
            .widthIn(min = 200.dp, max = 280.dp)
            .heightIn(max = 380.dp),
        shape = RoundedCornerShape(12.dp),
        containerColor = g.canvas,
        border = BorderStroke(1.dp, g.borderMuted.copy(alpha = 0.5f)),
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 380.dp)
                .verticalScrollWithBar(scrollState)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            actions.forEach { a ->
                MenuActionItem(a = a, onDismiss = onDismissRequest)
            }
        }
    }
}

/**
 * 「更多」菜单里统一的链接操作，顺序固定：复制链接、分享、在浏览器打开。
 * 所有页面的链接菜单都用它，避免有的有「复制链接」、有的没有。
 */
fun MutableList<MenuAction>.addLinkActions(ctx: android.content.Context, url: String) {
    add(MenuAction(com.mobilegh.i18n.tr("复制链接"), icon = R.drawable.oc_copy) { ctx.copy(url, com.mobilegh.i18n.tr("已复制链接")) })
    add(MenuAction(com.mobilegh.i18n.tr("分享"), icon = R.drawable.oc_share_android) { ctx.share(url) })
    add(MenuAction(com.mobilegh.i18n.tr("在浏览器打开"), icon = R.drawable.oc_globe) { ctx.openBrowser(url) })
}

/** 右上角"更多"菜单：每一个菜单选项都有独立的圆角卡片、图标与清晰的边界 */
@Composable
fun MoreMenu(actions: List<MenuAction>, tint: Color = Gh.c.fg) {
    var open by remember { mutableStateOf(false) }
    // 菜单必须和按钮放在同一个 Box 里：DropdownMenu 锚定在父布局上，直接放进 Row 会跑到整行的起点（左边）
    Box {
        OcButton(R.drawable.oc_kebab_horizontal, { open = true }, tint)
        CardDropdownMenu(expanded = open, onDismissRequest = { open = false }, actions = actions)
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
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(10.dp),
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
            shape = shape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = g.accent,
                unfocusedBorderColor = g.borderMuted,
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

/** 分段切换（如 GitHub / 本机、全部 Star / 列表等），无阴影纯扁平设计，未选中项自然融入轨道，选中项高亮清晰 */
@Composable
fun Segmented(
    options: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSelect: (Int) -> Unit,
) {
    val g = Gh.c
    val trackBg = if (g.dark) Color(0xFF161B22) else Color(0xFFEFF1F4)
    val selectedBg = if (g.dark) Color(0xFF28303C) else Color.White
    val selectedBorder = if (g.dark) Color(0xFF38434F) else Color(0xFFD0D7DE)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(trackBg)
            .border(1.dp, g.borderMuted, RoundedCornerShape(8.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { i, label ->
            val isSelected = i == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .then(
                        if (isSelected) {
                            Modifier
                                .background(selectedBg)
                                .border(1.dp, selectedBorder, RoundedCornerShape(6.dp))
                        } else {
                            Modifier.background(Color.Transparent)
                        }
                    )
                    .clickable(
                        enabled = enabled,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(i) }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) (if (g.dark) Color.White else g.accent) else g.fgMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** 顶栏页签导航（对齐 GitHub 官方设计）：扁平下划线指示条，无卡片阴影，无黑底凹槽 */
@Composable
fun GhTopTabBar(
    tabs: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    val g = Gh.c
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            tabs.forEachIndexed { index, title ->
                val isSelected = index == selected
                Column(
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(index) }
                        .padding(top = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = title,
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) g.accent else g.fgMuted,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Box(
                        Modifier
                            .height(2.5.dp)
                            .width(if (isSelected) 36.dp else 0.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(if (isSelected) g.accent else Color.Transparent)
                    )
                }
            }
        }
        HDivider()
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

/** 文件内搜索栏：输入关键字，显示 1/15 匹配计数，支持上下翻查与关闭 */
@Composable
fun InFileSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchCount: Int,
    currentIndex: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val g = Gh.c
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(g.canvasSubtle)
            .border(1.dp, g.borderMuted, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Oc(R.drawable.oc_search, g.fgMuted, 16.dp)
        Spacer(Modifier.width(8.dp))
        androidx.compose.foundation.text.BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = g.fg, fontSize = 14.sp),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(tr("在文件内查找…"), color = g.fgMuted, fontSize = 14.sp)
                }
                inner()
            },
        )
        if (query.isNotEmpty()) {
            Text(
                text = if (matchCount == 0) tr("无匹配") else "${currentIndex + 1}/$matchCount",
                color = if (matchCount == 0) g.danger else g.fgMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            Box(
                Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = matchCount > 0, onClick = onPrev),
                contentAlignment = Alignment.Center,
            ) {
                Oc(R.drawable.oc_arrow_up, if (matchCount > 0) g.fg else g.fgMuted.copy(alpha = 0.5f), 14.dp)
            }
            Spacer(Modifier.width(2.dp))
            Box(
                Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = matchCount > 0, onClick = onNext),
                contentAlignment = Alignment.Center,
            ) {
                Oc(R.drawable.oc_arrow_down, if (matchCount > 0) g.fg else g.fgMuted.copy(alpha = 0.5f), 14.dp)
            }
            Spacer(Modifier.width(4.dp))
        }
        Box(
            Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Oc(R.drawable.oc_x, g.fgMuted, 14.dp)
        }
    }
}

/**
 * 弹窗描边：精致柔和的细描边，圆角 16dp，搭配阴影与遮罩自然突出边界，避免生硬突兀的黑框与内层溢出
 */
@Composable
fun Modifier.dialogFrame(): Modifier {
    val g = Gh.c
    val shape = RoundedCornerShape(16.dp)
    return clip(shape).border(1.dp, g.borderMuted.copy(alpha = 0.5f), shape)
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
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 0.dp,
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
