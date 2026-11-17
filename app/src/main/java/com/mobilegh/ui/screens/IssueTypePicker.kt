package com.mobilegh.ui.screens

import androidx.compose.runtime.Composable
import com.mobilegh.data.IssueType
import com.mobilegh.ui.components.LabelChip

/** GraphQL 返回的是枚举名，不是 Label API 的十六进制颜色。 */
fun issueTypeHex(color: String?): String? = when (color?.uppercase()) {
    "GRAY" -> "6e7781"
    "BLUE" -> "0969da"
    "GREEN" -> "1a7f37"
    "YELLOW" -> "9a6700"
    "ORANGE" -> "bc4c00"
    "RED" -> "cf222e"
    "PINK" -> "bf3989"
    "PURPLE" -> "8250df"
    else -> null
}

@Composable
fun IssueTypeChip(type: IssueType) = LabelChip(type.name, issueTypeHex(type.color) ?: "6e7781")

fun IssueType.toMetaOpt(): MetaOpt = MetaOpt(id, name, color = issueTypeHex(color))
