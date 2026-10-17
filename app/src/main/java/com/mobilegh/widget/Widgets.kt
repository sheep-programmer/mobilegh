package com.mobilegh.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.mobilegh.MainActivity
import com.mobilegh.R
import com.mobilegh.data.AppLog
import com.mobilegh.data.ContribSnapshot
import com.mobilegh.data.InboxSnapshot
import com.mobilegh.data.WidgetStore
import com.mobilegh.nav.Tab
import com.mobilegh.ui.components.fmtCount
import com.mobilegh.ui.components.levelOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 小组件点按后打开应用的意图：切到指定 Tab，可选再打开一个 GitHub 链接。 */
object WidgetIntents {
    const val ACTION = "com.mobilegh.action.WIDGET"
    const val EXTRA_TAB = "widget_tab"
    const val EXTRA_URL = "widget_url"

    fun open(ctx: Context, tab: Tab, url: String? = null): Intent =
        Intent(ctx, MainActivity::class.java)
            .setAction(ACTION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_TAB, tab.name)
            .apply { if (url != null) putExtra(EXTRA_URL, url) }
}

/** 应用内数据变化、切换账号或退出登录时刷新桌面小组件。 */
object Widgets {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 正在运行的小组件会话据此重新读取数据（会话存活期间 updateAll 只会触发重组）。 */
    internal val version = MutableStateFlow(0)

    fun update(ctx: Context, kind: WidgetStore.Kind? = null) {
        val app = ctx.applicationContext
        version.update { it + 1 }
        scope.launch {
            runCatching {
                if (kind != WidgetStore.Kind.Inbox) ContributionWidget().updateAll(app)
                if (kind != WidgetStore.Kind.Contributions) InboxWidget().updateAll(app)
            }.onFailure { AppLog.warn("widget", "小组件刷新失败", it) }
        }
    }
}

private val Fg = ColorProvider(R.color.widget_fg)
private val Muted = ColorProvider(R.color.widget_fg_muted)
private val Accent = ColorProvider(R.color.widget_accent)
private val Heat = listOf(R.color.widget_heat_0, R.color.widget_heat_1, R.color.widget_heat_2, R.color.widget_heat_3, R.color.widget_heat_4)

private sealed interface Load<out T> {
    data object SignedOut : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data object Failed : Load<Nothing>
}

private suspend fun <T> load(tag: String, block: suspend () -> T?): Load<T> = try {
    block()?.let { Load.Ready(it) } ?: Load.SignedOut
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Exception) {
    AppLog.warn("widget", "$tag 小组件加载失败", e)
    Load.Failed
}

/** 首次显示用 provideGlance 中加载好的数据；之后每次刷新请求都重新读取（缓存足够新时不发请求）。 */
@Composable
private fun <T> rememberLoad(initial: Load<T>, startVersion: Int, loader: suspend () -> Load<T>): Load<T> {
    val v by Widgets.version.collectAsState()
    val state by produceState(initial, v) { if (v != startVersion) value = loader() }
    return state
}

@Composable
private fun WidgetFrame(onClick: Action, content: @Composable () -> Unit) {
    Box(
        GlanceModifier.fillMaxSize().appWidgetBackground().background(ImageProvider(R.drawable.widget_background))
            .clickable(onClick).padding(12.dp),
    ) { content() }
}

@Composable
private fun Header(icon: Int, title: String, trailing: String?, trailingColor: ColorProvider = Muted) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Image(ImageProvider(icon), null, GlanceModifier.size(16.dp), colorFilter = ColorFilter.tint(Fg))
        Spacer(GlanceModifier.width(8.dp))
        Text(title, GlanceModifier.defaultWeight(), style = TextStyle(color = Fg, fontSize = 14.sp, fontWeight = FontWeight.Bold), maxLines = 1)
        if (trailing != null) Text(trailing, style = TextStyle(color = trailingColor, fontSize = 12.sp, fontWeight = FontWeight.Medium), maxLines = 1)
    }
}

@Composable
private fun Message(text: String) {
    Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = TextStyle(color = Muted, fontSize = 13.sp), maxLines = 2)
    }
}

// ======================= 贡献图 =======================

class ContributionWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val v0 = Widgets.version.value
        val first = load("贡献图") { WidgetStore.loadContributions() }
        provideContent {
            val state = rememberLoad(first, v0) { load("贡献图") { WidgetStore.loadContributions() } }
            val masks = remember(state) { (state as? Load.Ready)?.value?.let(::heatMasks) }
            ContributionContent(context, state, masks)
        }
    }
}

class ContributionWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ContributionWidget()
}

@Composable
private fun ContributionContent(ctx: Context, state: Load<ContribSnapshot>, masks: List<Bitmap>?) {
    WidgetFrame(actionStartActivity(WidgetIntents.open(ctx, Tab.Me))) {
        Column(GlanceModifier.fillMaxSize()) {
            when (state) {
                Load.SignedOut -> {
                    Header(R.drawable.oc_mark_github, "贡献", null)
                    Message("请先登录 MobileGH")
                }
                Load.Failed -> {
                    Header(R.drawable.oc_mark_github, "贡献", null)
                    Message("暂时无法加载，点按打开 MobileGH")
                }
                is Load.Ready -> {
                    val s = state.value
                    Header(R.drawable.oc_mark_github, "${fmtCount(s.total)} 次贡献", "近 ${WidgetStore.WEEKS} 周 · @${s.login}")
                    Spacer(GlanceModifier.height(8.dp))
                    // 每个等级一张遮罩图，按 Primer 颜色着色后叠放；颜色来自资源，随系统深浅色切换。
                    Box(GlanceModifier.fillMaxWidth().defaultWeight(), contentAlignment = Alignment.Center) {
                        masks?.forEachIndexed { level, bmp ->
                            Image(
                                ImageProvider(bmp), if (level == 0) "最近 ${WidgetStore.WEEKS} 周贡献热力图" else null,
                                GlanceModifier.fillMaxSize(), contentScale = ContentScale.Fit,
                                colorFilter = ColorFilter.tint(ColorProvider(Heat[level])),
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val CELL = 24f
private const val GAP = 5f
private const val RADIUS = 5f

/** 按贡献等级各画一张 Alpha 遮罩（只渲染一次），空位（未来的日期）不画。 */
private fun heatMasks(s: ContribSnapshot): List<Bitmap> {
    val weeks = s.weeks.takeLast(WidgetStore.WEEKS)
    val cols = weeks.size.coerceAtLeast(1)
    val w = (cols * (CELL + GAP) - GAP).toInt()
    val h = (7 * (CELL + GAP) - GAP).toInt()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val masks = List(Heat.size) { Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8) }
    val canvases = masks.map(::Canvas)
    val rect = RectF()
    weeks.forEachIndexed { wi, week ->
        week.contributionDays.forEach { d ->
            val x = wi * (CELL + GAP)
            val y = d.weekday.coerceIn(0, 6) * (CELL + GAP)
            rect.set(x, y, x + CELL, y + CELL)
            canvases[levelOf(d).coerceIn(0, Heat.lastIndex)].drawRoundRect(rect, RADIUS, RADIUS, paint)
        }
    }
    return masks
}

// ======================= 通知收件箱 =======================

class InboxWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val v0 = Widgets.version.value
        val first = load("收件箱") { WidgetStore.loadInbox() }
        provideContent {
            InboxContent(context, rememberLoad(first, v0) { load("收件箱") { WidgetStore.loadInbox() } })
        }
    }
}

class InboxWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = InboxWidget()
}

@Composable
private fun InboxContent(ctx: Context, state: Load<InboxSnapshot>) {
    WidgetFrame(actionStartActivity(WidgetIntents.open(ctx, Tab.Notifications))) {
        Column(GlanceModifier.fillMaxSize()) {
            when (state) {
                Load.SignedOut -> {
                    Header(R.drawable.oc_bell, "通知", null)
                    Message("请先登录 MobileGH")
                }
                Load.Failed -> {
                    Header(R.drawable.oc_bell, "通知", null)
                    Message("暂时无法加载，点按打开 MobileGH")
                }
                is Load.Ready -> {
                    val s = state.value
                    val count = if (s.more) "${s.unread}+" else "${s.unread}"
                    Header(R.drawable.oc_bell, "通知", if (s.unread > 0) "$count 条未读" else null, Accent)
                    if (WidgetStore.hideInboxDetails) {
                        Message(if (s.unread == 0) "没有未读通知" else "已隐藏通知详情，点按打开应用")
                    } else if (s.items.isEmpty()) {
                        Message("没有未读通知")
                    } else {
                        // 标题区约 24dp，每条约 36dp：按实际高度决定显示几条
                        val rows = ((LocalSize.current.height.value - 24 - 24) / 36).toInt().coerceIn(1, WidgetStore.INBOX_ITEMS)
                        s.items.take(rows).forEach { item ->
                            Spacer(GlanceModifier.height(8.dp))
                            val open = WidgetIntents.open(ctx, Tab.Notifications, item.url)
                            Column(GlanceModifier.fillMaxWidth().clickable(actionStartActivity(open))) {
                                Text(item.repo, style = TextStyle(color = Muted, fontSize = 11.sp), maxLines = 1)
                                Text(item.title, style = TextStyle(color = Fg, fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}
