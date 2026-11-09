package com.mobilegh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.DrawableRes
import com.mobilegh.R
import com.mobilegh.ui.theme.Gh

/**
 * 列表顶部的紧凑搜索栏（44dp）：放大镜 + 单行输入 + 可选尾部按钮。
 * 比带浮动标签的 Material 输入框矮一截，也不会出现标签缺口，和 GitHub 应用里的列表搜索一致。
 * [label] 只作无障碍名称，界面上显示的是 [hint] 或 [label] 作为占位文字。
 */
@Composable
fun CompactSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    onSearch: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val g = Gh.c
    var focused by remember { mutableStateOf(false) }
    // String 重载在重新组合时会把光标放到 0；这里自己持有 TextFieldValue，首次显示和外部重置都落在末尾
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    LaunchedEffect(value) {
        if (field.text != value) field = TextFieldValue(value, TextRange(value.length))
    }
    // 与搜索页的搜索框一致：全圆角胶囊
    val shape = RoundedCornerShape(28.dp)
    Row(
        modifier.fillMaxWidth().heightIn(min = 44.dp).clip(shape)
            .background(g.canvas, shape)
            .border(if (focused) 1.5.dp else 1.dp, if (focused) g.accent else g.borderMuted, shape)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Oc(R.drawable.oc_search, g.fgMuted, 18.dp)
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            field,
            {
                field = it
                onValueChange(it.text)
            },
            Modifier.weight(1f).onFocusChanged { focused = it.isFocused }.semantics { contentDescription = label },
            singleLine = true,
            textStyle = TextStyle(fontSize = 14.sp, color = g.fg),
            cursorBrush = SolidColor(g.accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
            decorationBox = { inner ->
                Box(Modifier.padding(vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
                    if (field.text.isEmpty()) Text(
                        hint?.let { "$label · $it" } ?: label,
                        color = g.fgMuted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    inner()
                }
            },
        )
        trailing?.invoke()
    }
}

/** 搜索栏尾部的小图标按钮（圆形波纹 40dp，点击区至少 48dp） */
@Composable
fun FieldAction(@DrawableRes icon: Int, description: String, tint: Color = Gh.c.fgMuted, onClick: () -> Unit) {
    Box(
        Modifier.minimumInteractiveComponentSize().size(40.dp).clip(RoundedCornerShape(50))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Oc(icon, tint, 18.dp) }
}
