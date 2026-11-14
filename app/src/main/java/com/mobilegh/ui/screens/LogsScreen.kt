package com.mobilegh.ui.screens

import androidx.compose.foundation.background
import com.mobilegh.ui.components.verticalScrollWithBar
import com.mobilegh.ui.components.ScrollbarLazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.width
import com.mobilegh.R
import com.mobilegh.data.AppLog
import com.mobilegh.data.AppLogEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.ui.components.Card
import com.mobilegh.ui.components.Chips
import com.mobilegh.ui.components.Dropdown
import com.mobilegh.ui.components.EmptyState
import com.mobilegh.ui.components.GhButton
import com.mobilegh.ui.components.GhDialog
import com.mobilegh.ui.components.GhField
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.MenuAction
import com.mobilegh.ui.components.MoreMenu
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.Pill
import com.mobilegh.ui.components.copy
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.components.share
import com.mobilegh.ui.theme.Gh
import com.mobilegh.i18n.tr

private val LOG_CATEGORIES = listOf(
    "all" to tr("全部"),
    "auth" to tr("认证"),
    "2fa_setup" to tr("审批启用"),
    "2fa" to tr("数字审批"),
    "2fa_push" to tr("审批推送"),
    "network" to tr("网络"),
    "update" to tr("更新"),
    "download" to tr("下载"),
    "actions" to "Actions",
    "webview" to tr("网页渲染"),
    "crash" to tr("崩溃"),
    "exit" to tr("退出诊断"),
    "stall" to tr("卡顿"),
    "app" to tr("应用"),
)

@Composable
fun LogsScreen(onBack: (() -> Unit)? = null) {
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
    // 日志以 id 作为 LazyColumn 的 key，任何来源的重复 id 都会让整页抛异常，渲染前再去重一次
    val unique = AppLog.dedupeById(filtered)
    Page(
        tr("日志与诊断"),
        subtitle = unique.size.toString() + tr(" 条"),
        onBack = onBack,
        actions = {
            MoreMenu(listOf(
                MenuAction(tr("复制全部日志")) { ctx.copy(AppLog.export(all)) },
                MenuAction(tr("复制当前筛选")) { ctx.copy(AppLog.export(unique)) },
                MenuAction(tr("导出当前筛选")) { ctx.share(AppLog.export(unique)) },
                MenuAction(tr("导出全部日志")) { ctx.share(AppLog.export(all)) },
                MenuAction(tr("导出崩溃历史")) {
                    val history = AppLog.crashHistoryText()
                    if (history.isBlank()) ctx.copy(tr("暂无历史崩溃记录")) else ctx.share(history)
                },
                MenuAction(tr("清除全部日志"), danger = true) { confirmClear = true },
            ))
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            val options = LOG_CATEGORIES.map { it.second }
            val selIdx = LOG_CATEGORIES.indexOfFirst { it.first == category }.coerceAtLeast(0)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tr("分类"), color = g.fgMuted, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                Dropdown(
                    label = options.getOrElse(selIdx) { tr("全部") },
                    options = options,
                    onSelect = { category = LOG_CATEGORIES[it].first },
                    active = category != "all",
                )
            }
            HDivider(Modifier.padding(bottom = 6.dp))
            GhField(
                query,
                { query = it },
                tr("筛选日志"),
                Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                placeholder = tr("搜索错误、请求路径或文件名"),
            )
            if (unique.isEmpty()) {
                EmptyState(if (query.isBlank()) tr("暂无日志") else tr("没有匹配的日志"), R.drawable.oc_log, action = if (query.isBlank()) null else tr("清除搜索"), onAction = { query = "" })
            } else {
                ScrollbarLazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
                    items(unique, key = { it.id }) { entry ->
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
            tr("清除日志"),
            onDismiss = { confirmClear = false },
            confirm = tr("清除"),
            danger = true,
            onConfirm = {
                AppLog.clear()
                confirmClear = false
                expanded = null
            },
        ) {
            Text(tr("会删除本机保存的所有 MobileGH 诊断日志，无法恢复。"), color = g.fg)
        }
    }
}

@Composable
private fun LogRow(entry: AppLogEntry, expanded: Boolean, onClick: () -> Unit) {
    val g = Gh.c
    val ctx = rememberCtx()
    val color = when (entry.level) {
        "ERROR" -> g.danger
        "WARN" -> g.attention
        else -> g.fgMuted
    }
    Card(Modifier.padding(horizontal = 16.dp).clickable(onClick = onClick)) {
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
                    // LazyColumn 的 item 高度是无界的，直接套 verticalScroll 会抛
                    // 「measured with an infinity maximum height」；heightIn 先给出上限再滚动
                    Modifier.fillMaxWidth().heightIn(max = 320.dp).background(g.canvasInset).verticalScrollWithBar().padding(8.dp),
                    color = g.fgMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 16.sp,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    GhButton(tr("复制诊断详情"), icon = R.drawable.oc_copy) {
                        ctx.copy(entry.detail)
                    }
                    Spacer(Modifier.width(8.dp))
                    GhButton(tr("分享"), icon = R.drawable.oc_share_android) {
                        ctx.share(entry.detail)
                    }
                }
            }
            if (expanded && entry.thread != null) {
                Spacer(Modifier.height(5.dp))
                Text(tr("线程：") + entry.thread, color = g.fgMuted, fontSize = 11.sp)
            }
        }
    }
}
