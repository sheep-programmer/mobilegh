package com.mobilegh.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.mobilegh.R
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Loader
import com.mobilegh.nav.Pager
import com.mobilegh.ui.theme.Gh
import com.mobilegh.ui.theme.parseHex
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ---------------- 图标 ----------------

@Composable
fun Oc(@DrawableRes icon: Int, tint: Color = Gh.c.fgMuted, size: Dp = 16.dp, modifier: Modifier = Modifier) {
    Icon(painterResource(icon), null, modifier.size(size), tint = tint)
}

@Composable
fun OcButton(@DrawableRes icon: Int, onClick: () -> Unit, tint: Color = Gh.c.fg, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled) { Oc(icon, if (enabled) tint else Gh.c.fgMuted, 20.dp) }
}

// ---------------- 页面骨架 ----------------

@Composable
fun Page(
    title: String,
    subtitle: String? = null,
    back: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    floating: @Composable () -> Unit = {},
    contentWindowInsets: WindowInsets? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val nav = LocalNav.current
    Scaffold(
        containerColor = Gh.c.canvas,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                            if (subtitle != null) Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = Gh.c.fgMuted)
                        }
                    },
                    navigationIcon = {
                        if (back) OcButton(R.drawable.oc_arrow_left, { nav.pop() })
                    },
                    actions = actions,
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Gh.c.header, titleContentColor = Gh.c.fg),
                )
                HDivider()
            }
        },
        bottomBar = bottomBar,
        floatingActionButton = floating,
        contentWindowInsets = contentWindowInsets ?: androidx.compose.material3.ScaffoldDefaults.contentWindowInsets,
        content = content,
    )
}

@Composable
fun HDivider(modifier: Modifier = Modifier) = HorizontalDivider(modifier, thickness = 1.dp, color = Gh.c.borderMuted)

@Composable
fun Loading(modifier: Modifier = Modifier.fillMaxSize()) {
    Box(modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp), color = Gh.c.fgMuted, strokeWidth = 2.5.dp)
    }
}

@Composable
fun ErrorState(msg: String, modifier: Modifier = Modifier.fillMaxSize(), retry: (() -> Unit)? = null) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Oc(R.drawable.oc_alert, Gh.c.attention, 32.dp)
        Spacer(Modifier.height(12.dp))
        Text(msg, color = Gh.c.fgMuted, textAlign = TextAlign.Center)
        if (retry != null) {
            Spacer(Modifier.height(16.dp))
            GhButton("重试", onClick = retry)
        }
    }
}

@Composable
fun EmptyState(msg: String, @DrawableRes icon: Int = R.drawable.oc_inbox, modifier: Modifier = Modifier.fillMaxWidth()) {
    Column(modifier.padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Oc(icon, Gh.c.fgMuted, 32.dp)
        Spacer(Modifier.height(12.dp))
        Text(msg, color = Gh.c.fgMuted, textAlign = TextAlign.Center)
    }
}

/** 加载容器：加载中/错误/内容 + 下拉刷新 */
@Composable
fun <T> LoadBox(loader: Loader<T>, modifier: Modifier = Modifier.fillMaxSize(), content: @Composable (T) -> Unit) {
    val d = loader.data
    when {
        d != null -> PullToRefreshBox(loader.refreshing, onRefresh = loader::refresh, modifier) { content(d) }
        loader.error != null -> ErrorState(loader.error!!, modifier) { loader.load(true) }
        else -> Loading(modifier)
    }
}

/** 自动分页的列表 */
@Composable
fun <T> PagedList(
    pager: Pager<T>,
    modifier: Modifier = Modifier.fillMaxSize(),
    state: LazyListState = rememberLazyListState(),
    empty: String = "暂无内容",
    divider: Boolean = true,
    boxed: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(top = 8.dp, bottom = 16.dp),
    onRefresh: () -> Unit = {},
    header: LazyListScope.() -> Unit = {},
    item: @Composable LazyItemScope.(T) -> Unit,
) {
    val near by remember(state) {
        derivedStateOf {
            val li = state.layoutInfo
            (li.visibleItemsInfo.lastOrNull()?.index ?: 0) >= li.totalItemsCount - 6
        }
    }
    LaunchedEffect(pager, state) {
        snapshotFlow { near && !pager.loading && !pager.end && pager.error == null }
            .collect { if (it) pager.loadMore() }
    }
    PullToRefreshBox(pager.refreshing, onRefresh = { pager.refresh(); onRefresh() }, modifier) {
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = contentPadding) {
            header()
            itemsIndexed(pager.items) { i, it ->
                if (boxed) {
                    Column(Modifier.groupItem(i == 0, i == pager.items.lastIndex, Gh.c.border, Gh.c.canvas)) { item(it) }
                } else {
                    item(it)
                    if (divider) HDivider()
                }
            }
            item {
                when {
                    pager.error != null && pager.items.isEmpty() -> ErrorState(pager.error!!, Modifier.fillMaxWidth().padding(top = 40.dp)) { pager.error = null; pager.loadMore() }
                    pager.error != null -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        TextLink("加载失败，点击重试") { pager.error = null; pager.loadMore() }
                    }
                    pager.loading && !pager.refreshing -> Loading(Modifier.fillMaxWidth().padding(24.dp))
                    pager.end && pager.items.isEmpty() -> EmptyState(empty)
                }
            }
        }
    }
}

// ---------------- 小部件 ----------------

@Composable
fun GhButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null,
    onClick: () -> Unit,
) {
    val g = Gh.c
    val content: @Composable RowScope.() -> Unit = {
        if (icon != null) {
            Oc(icon, if (primary) Color.White else if (danger) g.danger else g.fgMuted)
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontWeight = FontWeight.Medium, fontSize = 14.sp)
    }
    val shape = RoundedCornerShape(6.dp)
    val pad = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
    if (primary) {
        Button(
            onClick, modifier.height(36.dp), enabled = enabled, shape = shape, contentPadding = pad,
            colors = ButtonDefaults.buttonColors(containerColor = if (danger) g.danger else g.btnPrimary, contentColor = Color.White),
            content = content,
        )
    } else {
        OutlinedButton(
            onClick, modifier.height(36.dp), enabled = enabled, shape = shape, contentPadding = pad,
            border = BorderStroke(1.dp, g.border),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = g.btnBg, contentColor = if (danger) g.danger else g.fg),
            content = content,
        )
    }
}

@Composable
fun TextLink(text: String, color: Color = Gh.c.accent, onClick: () -> Unit) {
    Text(text, color = color, fontSize = 14.sp, modifier = Modifier.clickable(onClick = onClick).padding(4.dp))
}

@Composable
fun Counter(n: Int, modifier: Modifier = Modifier) {
    Text(
        fmtCount(n),
        modifier.background(Gh.c.neutralMuted, RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 1.dp),
        fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Gh.c.fg,
    )
}

@Composable
fun Pill(text: String, color: Color = Gh.c.fgMuted, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 1.dp),
        fontSize = 11.sp, fontWeight = FontWeight.Medium, color = color,
    )
}

@Composable
fun LabelChip(name: String, hex: String) {
    val base = parseHex(hex) ?: Gh.c.fgMuted
    val dark = Gh.c.dark
    val bg = if (dark) base.copy(alpha = 0.18f) else base
    val fg = if (dark) lighten(base) else if (base.luminance() > 0.5f) Color(0xFF1F2328) else Color.White
    Text(
        name,
        Modifier
            .background(bg, RoundedCornerShape(50))
            .then(if (dark) Modifier.border(1.dp, base.copy(alpha = 0.3f), RoundedCornerShape(50)) else Modifier)
            .padding(horizontal = 8.dp, vertical = 1.dp),
        fontSize = 12.sp, fontWeight = FontWeight.Medium, color = fg, maxLines = 1,
    )
}

private fun lighten(c: Color): Color {
    val f = 0.45f
    return Color(c.red + (1 - c.red) * f, c.green + (1 - c.green) * f, c.blue + (1 - c.blue) * f)
}

@Composable
fun Topic(name: String, onClick: (() -> Unit)? = null) {
    Text(
        name,
        Modifier
            .background(Gh.c.accentSubtle, RoundedCornerShape(50))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 2.dp),
        fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Gh.c.accent,
    )
}

enum class IssueState { Open, Closed, NotPlanned, Merged, Draft, PrOpen, PrClosed }

fun issueState(state: String, reason: String?, isPull: Boolean, merged: Boolean, draft: Boolean): IssueState = when {
    isPull && merged -> IssueState.Merged
    isPull && state == "open" && draft -> IssueState.Draft
    isPull && state == "open" -> IssueState.PrOpen
    isPull -> IssueState.PrClosed
    state == "open" -> IssueState.Open
    reason == "not_planned" -> IssueState.NotPlanned
    else -> IssueState.Closed
}

@Composable
fun stateVisual(s: IssueState): Triple<Int, Color, String> {
    val g = Gh.c
    return when (s) {
        IssueState.Open -> Triple(R.drawable.oc_issue_opened, g.success, "开启")
        IssueState.Closed -> Triple(R.drawable.oc_issue_closed, g.done, "已关闭")
        IssueState.NotPlanned -> Triple(R.drawable.oc_skip, g.fgMuted, "不计划")
        IssueState.Merged -> Triple(R.drawable.oc_git_merge, g.done, "已合并")
        IssueState.Draft -> Triple(R.drawable.oc_git_pull_request_draft, g.fgMuted, "草稿")
        IssueState.PrOpen -> Triple(R.drawable.oc_git_pull_request, g.success, "开启")
        IssueState.PrClosed -> Triple(R.drawable.oc_git_pull_request_closed, g.danger, "已关闭")
    }
}

@Composable
fun StateBadge(s: IssueState) {
    val (icon, color, text) = stateVisual(s)
    Row(
        Modifier.background(color, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Oc(icon, Color.White, 14.dp)
        Spacer(Modifier.width(4.dp))
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Gh.c.fg, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** GitHub 风格的菜单行（带图标、标题、计数和箭头） */
@Composable
fun MenuRow(
    @DrawableRes icon: Int,
    title: String,
    iconBg: Color? = null,
    value: String? = null,
    count: Int? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconBg != null) {
            Box(Modifier.size(30.dp).background(iconBg, RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
                Oc(icon, Color.White, 16.dp)
            }
        } else {
            Oc(icon, Gh.c.fgMuted, 18.dp)
        }
        Spacer(Modifier.width(14.dp))
        Text(title, Modifier.weight(1f), fontSize = 15.sp, color = Gh.c.fg)
        if (count != null) Counter(count)
        if (value != null) Text(value, color = Gh.c.fgMuted, fontSize = 14.sp, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        Oc(R.drawable.oc_chevron_right, Gh.c.fgMuted, 14.dp)
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .border(1.dp, Gh.c.border, RoundedCornerShape(8.dp))
            .background(Gh.c.canvas, RoundedCornerShape(8.dp)),
    ) { content() }
}

@Composable
fun IconText(@DrawableRes icon: Int, text: String, color: Color = Gh.c.fgMuted, modifier: Modifier = Modifier, bold: Boolean = false) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Oc(icon, color, 14.dp)
        Spacer(Modifier.width(4.dp))
        Text(text, color = color, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (bold) FontWeight.SemiBold else null)
    }
}

/** 列表分组框中的一行：首行圆角上边，末行圆角下边，行间只画一条分隔线 */
fun Modifier.groupItem(first: Boolean, last: Boolean, border: Color, bg: Color): Modifier {
    val r = 8.dp
    val shape = RoundedCornerShape(topStart = if (first) r else 0.dp, topEnd = if (first) r else 0.dp, bottomStart = if (last) r else 0.dp, bottomEnd = if (last) r else 0.dp)
    return this
        .padding(horizontal = 12.dp)
        .clip(shape)
        .background(bg)
        .drawWithContent {
            drawContent()
            val sw = 1.dp.toPx()
            val outline = shape.createOutline(size, layoutDirection, this)
            drawOutline(outline, border, style = androidx.compose.ui.graphics.drawscope.Stroke(sw * 2))
            // 非首行：擦掉上边线，由上一行的下边线充当分隔线
            if (!first) drawRect(bg, androidx.compose.ui.geometry.Offset(sw, 0f), androidx.compose.ui.geometry.Size(size.width - sw * 2, sw))
        }
}

/** LazyColumn 中以分组框渲染一组条目 */
fun <T> LazyListScope.boxedItems(list: List<T>, key: ((T) -> Any)? = null, content: @Composable (T) -> Unit) {
    itemsIndexed(list, key = key?.let { k -> { _: Int, t: T -> k(t) } }) { i, t ->
        Column(Modifier.groupItem(i == 0, i == list.lastIndex, Gh.c.border, Gh.c.canvas)) { content(t) }
    }
}

/** 带边框的分组容器，子项之间自动画分隔线 */
@Composable
fun MenuGroup(modifier: Modifier = Modifier.padding(horizontal = 16.dp), content: @Composable () -> Unit) {
    val g = Gh.c
    val ys = remember { ArrayList<Int>() }
    Card(modifier) {
        androidx.compose.ui.layout.Layout(
            content,
            Modifier.clip(RoundedCornerShape(8.dp)).drawWithContent {
                drawContent()
                ys.forEach { y -> drawLine(g.borderMuted, androidx.compose.ui.geometry.Offset(0f, y.toFloat()), androidx.compose.ui.geometry.Offset(size.width, y.toFloat()), 1.dp.toPx()) }
            },
        ) { ms, c ->
            val ps = ms.map { it.measure(c.copy(minHeight = 0)) }
            layout(c.maxWidth, ps.sumOf { it.height }) {
                ys.clear()
                var y = 0
                ps.forEachIndexed { i, p ->
                    if (i > 0) ys.add(y)
                    p.place(0, y)
                    y += p.height
                }
            }
        }
    }
}

// ---------------- 格式化 ----------------

fun fmtCount(n: Int): String = when {
    n >= 1_000_000 -> "%.1fm".format(n / 1_000_000.0).replace(".0m", "m")
    n >= 10_000 -> "${n / 1000}k"
    n >= 1_000 -> "%.1fk".format(n / 1000.0).replace(".0k", "k")
    else -> n.toString()
}

fun fmtSize(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> "%.1f KB".format(b / 1024.0)
    else -> "$b B"
}

fun parseInstant(iso: String?): Instant? = iso?.let { runCatching { Instant.parse(it) }.getOrNull() }

fun relTime(iso: String?): String {
    val t = parseInstant(iso) ?: return ""
    val d = Duration.between(t, Instant.now())
    val s = d.seconds
    return when {
        s < 60 -> "刚刚"
        s < 3600 -> "${s / 60} 分钟前"
        s < 86400 -> "${s / 3600} 小时前"
        s < 86400 * 30 -> "${s / 86400} 天前"
        s < 86400 * 365 -> "${s / (86400 * 30)} 个月前"
        else -> "${s / (86400 * 365)} 年前"
    }
}

private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

fun fmtDate(iso: String?): String = parseInstant(iso)?.atZone(ZoneId.systemDefault())?.format(DATE) ?: ""
fun fmtDateTime(iso: String?): String = parseInstant(iso)?.atZone(ZoneId.systemDefault())?.format(DATETIME) ?: ""

fun fmtDuration(from: String?, to: String?): String {
    val a = parseInstant(from) ?: return ""
    val b = parseInstant(to) ?: Instant.now()
    val s = Duration.between(a, b).seconds.coerceAtLeast(0)
    return when {
        s < 60 -> "${s}秒"
        s < 3600 -> "${s / 60}分${s % 60}秒"
        else -> "${s / 3600}时${s % 3600 / 60}分"
    }
}

// ---------------- 系统交互 ----------------

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

fun Context.copy(text: String, label: String = "已复制") {
    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("text", text))
    toast(label)
}

fun Context.share(text: String) {
    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "分享"))
}

/** 用外部浏览器打开（绕开本应用自身的 github.com 链接拦截） */
fun Context.openBrowser(url: String) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri())
    val browser = packageManager.resolveActivity(Intent(Intent.ACTION_VIEW, "https://example.com".toUri()), PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo?.packageName
    if (browser != null && browser != packageName && browser != "android") intent.setPackage(browser)
    runCatching { startActivity(intent) }.onFailure {
        runCatching { startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, url.toUri()), "打开")) }
    }
}

@Composable
fun rememberCtx(): Context = LocalContext.current
