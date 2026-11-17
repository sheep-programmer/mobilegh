package com.mobilegh.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.mobilegh.R
import com.mobilegh.data.ShortcutSnapshot
import com.mobilegh.data.ShortcutWidgetItem
import com.mobilegh.data.WidgetStore
import com.mobilegh.i18n.tr
import com.mobilegh.nav.Tab

internal const val NEW_SHORTCUT_URL = "mobilegh://shortcut/new"

internal fun shortcutLabel(item: ShortcutWidgetItem, translate: (String) -> String = ::tr): String =
    item.labelKey?.let(translate) ?: item.label

class ShortcutWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val v0 = Widgets.version(WidgetStore.Kind.Shortcuts).value
        val first = load(tr("我的工作")) { WidgetStore.loadShortcuts() }
        provideContent {
            ShortcutContent(context, rememberLoad(first, v0, WidgetStore.Kind.Shortcuts) {
                load(tr("我的工作")) { WidgetStore.loadShortcuts() }
            })
        }
    }
}

class ShortcutWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ShortcutWidget()
}

@Composable
private fun ShortcutContent(ctx: Context, state: Load<ShortcutSnapshot>) {
    val fallback = if (state is Load.Ready && state.value.items.isEmpty()) NEW_SHORTCUT_URL else null
    WidgetFrame(actionStartActivity(WidgetIntents.open(ctx, Tab.Home, fallback))) {
        Column(GlanceModifier.fillMaxSize()) {
            Header(R.drawable.oc_zap, tr("我的工作"), null)
            when (state) {
                Load.SignedOut -> Message(tr("请先登录 MobileGH"))
                Load.Failed -> Message(tr("暂时无法加载，点按打开 MobileGH"))
                is Load.Ready -> if (state.value.items.isEmpty()) {
                    Message(tr("添加快捷键"))
                } else {
                    Spacer(GlanceModifier.height(6.dp))
                    state.value.items.chunked(2).forEach { pair ->
                        Row(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                            pair.forEach { item ->
                                Row(
                                    GlanceModifier.defaultWeight().padding(4.dp)
                                        .clickable(actionStartActivity(WidgetIntents.open(ctx, Tab.Home, item.url))),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("•", style = TextStyle(color = Accent, fontSize = 16.sp))
                                    Spacer(GlanceModifier.width(6.dp))
                                    Text(
                                        shortcutLabel(item),
                                        style = TextStyle(color = Fg, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                                        maxLines = 1,
                                    )
                                }
                            }
                            if (pair.size == 1) Spacer(GlanceModifier.defaultWeight())
                        }
                    }
                }
            }
        }
    }
}
