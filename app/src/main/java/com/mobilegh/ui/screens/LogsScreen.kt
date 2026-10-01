package com.mobilegh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.width
import com.mobilegh.data.AppLog
import com.mobilegh.data.AppLogEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.ui.components.Card
import com.mobilegh.ui.components.Chips
import com.mobilegh.ui.components.GhDialog
import com.mobilegh.ui.components.GhField
import com.mobilegh.ui.components.MenuAction
import com.mobilegh.ui.components.MoreMenu
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.Pill
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.components.share
import com.mobilegh.ui.theme.Gh

private val LOG_CATEGORIES = listOf(
    "all" to "全部",
    "network" to "网络",
    "auth" to "认证",
    "download" to "下载",
    "actions" to "Actions",
    "webview" to "网页渲染",
    "crash" to "崩溃",
    "app" to "应用",
)

@Composable
fun LogsScreen() {
    val g = Gh.c
    val ctx = rememberCtx()
    var category by rememberSaveable { mutableStateOf("all") }
    var query by rememberSaveable { mutableStateOf("") }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val _version = AppLog.version.intValue
    val all = AppLog.entries.toList()
    val filtered = all.filter { entry ->
        (category == "all" || entry.category == category) &&
            (query.isBlank() || entry.message.contains(query, true) || entry.detail?.contains(query, true) == true)
    }
    Page(
        "日志与诊断",
        subtitle = filtered.size.toString() + " 条",
        actions = {
            MoreMenu(listOf(
                MenuAction("导出当前筛选") { ctx.share(AppLog.export(filtered)) },
                MenuAction("导出全部日志") { ctx.share(AppLog.export(all)) },
                MenuAction("清除全部日志", danger = true) { confirmClear = true },
            ))
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Chips(LOG_CATEGORIES.map { it.second }, LOG_CATEGORIES.indexOfFirst { it.first == category }.coerceAtLeast(0)) {
                category = LOG_CATEGORIES[it].first
            }
            GhField(
                query,
                { query = it },
                "筛选日志",
                Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp),
                placeholder = "搜索错误、请求路径或文件名",
            )
            if (filtered.isEmpty()) {
                Text("没有匹配的日志", Modifier.padding(24.dp), color = g.fgMuted)
            } else {
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
                    items(filtered, key = { it.id }) { entry ->
                        LogRow(entry, expanded = expanded == entry.id) {
                            expanded = if (expanded == entry.id) null else entry.id
                        }
                    }
                }
            }
        }
    }
    if (confirmClear) {
        GhDialog(
            "清除日志",
            onDismiss = { confirmClear = false },
            confirm = "清除",
            danger = true,
            onConfirm = {
                AppLog.clear()
                confirmClear = false
                expanded = null
            },
        ) {
            Text("会删除本机保存的所有 MobileGH 诊断日志，无法恢复。", color = g.fg)
        }
    }
}

@Composable
private fun LogRow(entry: AppLogEntry, expanded: Boolean, onClick: () -> Unit) {
    val g = Gh.c
    val color = when (entry.level) {
        "ERROR" -> g.danger
        "WARN" -> g.attention
        else -> g.fgMuted
    }
    Card(Modifier.padding(horizontal = 12.dp).clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp)) {
            Row {
                Text(entry.level, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(52.dp))
                Pill(entry.category, color)
                Spacer(Modifier.width(8.dp))
                Text(entry.time.replace('T', ' ').substringBefore('.'), color = g.fgMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(5.dp))
            Text(entry.message, color = g.fg, fontSize = 13.sp, maxLines = if (expanded) 20 else 2, overflow = TextOverflow.Ellipsis)
            if (expanded && !entry.detail.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    entry.detail,
                    Modifier.fillMaxWidth().background(g.canvasInset).padding(8.dp).verticalScroll(rememberScrollState()),
                    color = g.fgMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 16.sp,
                )
            }
            if (expanded && entry.thread != null) {
                Spacer(Modifier.height(5.dp))
                Text("线程：" + entry.thread, color = g.fgMuted, fontSize = 11.sp)
            }
        }
    }
}
