package com.mobilegh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.ui.theme.Gh

/** 与输入框同高的筛选入口，有生效条件时显示数量并高亮 */
@Composable
fun FilterEntry(count: Int, onClick: () -> Unit) {
    val g = Gh.c
    val active = count > 0
    Row(
        Modifier
            .height(56.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, if (active) g.accent.copy(alpha = 0.6f) else g.border, RoundedCornerShape(6.dp))
            .background(if (active) g.accentSubtle else g.canvasSubtle, RoundedCornerShape(6.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (active) "筛选，已选 $count 项" else "筛选" }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Oc(R.drawable.oc_sliders, if (active) g.accent else g.fgMuted, 18.dp)
        if (active) {
            Spacer(Modifier.width(6.dp))
            Text(
                "$count",
                Modifier.background(g.accent, RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 1.dp),
                fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** 已生效的筛选条件 (key, 描述)，每个都能单独撤销 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActiveFilterChips(chips: List<Pair<String, String>>, onRemove: (String) -> Unit, onClear: () -> Unit) {
    val g = Gh.c
    FlowRow(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        chips.forEach { (key, label) ->
            Row(
                Modifier
                    .heightIn(min = 30.dp)
                    .clip(RoundedCornerShape(50))
                    .background(g.accentSubtle)
                    .border(1.dp, g.accent.copy(alpha = 0.5f), RoundedCornerShape(50))
                    .clickable(role = Role.Button) { onRemove(key) }
                    .semantics { contentDescription = "移除筛选条件 $label" }
                    .padding(start = 10.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, fontSize = 12.sp, color = g.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(4.dp))
                Oc(R.drawable.oc_x, g.accent, 12.dp)
            }
        }
        Text(
            "全部清除",
            Modifier.heightIn(min = 30.dp).clip(RoundedCornerShape(50)).clickable { onClear() }.padding(horizontal = 10.dp, vertical = 6.dp),
            fontSize = 12.sp, color = g.fgMuted,
        )
    }
}

/** 标题 + 单选胶囊，用于筛选弹层里选项不多的条件 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChoicePicker(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val g = Gh.c
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 14.sp, color = g.fg)
        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEachIndexed { i, text ->
                val sel = i == selected
                Text(
                    text,
                    Modifier
                        .background(if (sel) g.accentSubtle else g.canvasSubtle, RoundedCornerShape(50))
                        .border(1.dp, if (sel) g.accent.copy(alpha = 0.6f) else g.border, RoundedCornerShape(50))
                        .clip(RoundedCornerShape(50))
                        .selectable(selected = sel, role = Role.RadioButton) { onSelect(i) }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    fontSize = 13.sp, color = if (sel) g.accent else g.fg,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}
