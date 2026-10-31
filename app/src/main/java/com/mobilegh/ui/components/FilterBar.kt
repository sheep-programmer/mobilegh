package com.mobilegh.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mobilegh.R
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf

/** 搜索栏右侧的筛选按钮：有生效条件时用数量徽标提示 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterEntry(count: Int, onClick: () -> Unit) {
    val active = count > 0
    BadgedBox(badge = { if (active) Badge { Text("$count") } }) {
        FilledTonalIconButton(
            onClick = onClick,
            modifier = Modifier.size(48.dp).semantics {
                contentDescription = if (active) trf("筛选，已选 {0} 项", count) else tr("筛选")
            },
        ) {
            Oc(R.drawable.oc_sliders, LocalContentColor.current, 20.dp)
        }
    }
}

/** 已生效的筛选条件 (key, 描述)：每一项是可以点掉的输入胶囊，末尾提供「全部清除」 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ActiveFilterChips(chips: List<Pair<String, String>>, onRemove: (String) -> Unit, onClear: () -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        chips.forEach { (key, label) ->
            InputChip(
                selected = true,
                onClick = { onRemove(key) },
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingIcon = { Oc(R.drawable.oc_x, LocalContentColor.current, 16.dp) },
                modifier = Modifier.semantics { contentDescription = trf("移除筛选条件 {0}", label) },
            )
        }
        TextButton(onClick = onClear) { Text(tr("全部清除")) }
    }
}

/**
 * 标题 + 单选项：选项少（2–3 个）且文字短时用分段按钮（一眼能看出全部选项），否则用可换行的筛选胶囊。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ChoicePicker(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // 分段按钮最多 3 项：4 项时每段太窄，选中勾号会挤压文字，改用胶囊
        if (options.size in 2..3 && options.all { it.length <= 8 }) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { i, text ->
                    SegmentedButton(
                        selected = i == selected,
                        onClick = { onSelect(i) },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                        label = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
            return@Column
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { i, text ->
                FilterChip(selected = i == selected, onClick = { onSelect(i) }, label = { Text(text) })
            }
        }
    }
}

/**
 * 筛选弹层的统一外壳：标准底部弹层，顶部标题与关闭按钮，中间可滚动的分组内容，底部右对齐的操作按钮。
 * 仓库排序筛选、搜索组合筛选、Star 列表选择共用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(
    title: String,
    subtitle: String,
    closeDescription: String,
    onDismiss: () -> Unit,
    footer: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDismiss, modifier = Modifier.semantics { contentDescription = closeDescription }) {
                    Oc(R.drawable.oc_x, LocalContentColor.current, 20.dp)
                }
            }
            HorizontalDivider()
            // 滚动区用 weight：弹层被键盘或矮屏压缩时，底部按钮仍在屏幕内，只有中间内容滚动
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                content = content,
            )
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                content = footer,
            )
        }
    }
}

/** 弹层里的一个分组：小标题 + 控件，分组之间靠留白区分，不再套白色卡片 */
@Composable
fun FilterCard(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (title != null) Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}
