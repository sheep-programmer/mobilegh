package com.mobilegh.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.mobilegh.R
import com.mobilegh.data.PullRequestSnapshot
import com.mobilegh.data.WidgetStore
import com.mobilegh.i18n.tr
import com.mobilegh.nav.Tab

class PullRequestWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val v0 = Widgets.version(WidgetStore.Kind.PullRequests).value
        val first = load(tr("Pull Requests")) { WidgetStore.loadPullRequests() }
        provideContent {
            PullRequestContent(context, rememberLoad(first, v0, WidgetStore.Kind.PullRequests) {
                load(tr("Pull Requests")) { WidgetStore.loadPullRequests() }
            })
        }
    }
}

class PullRequestWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PullRequestWidget()
}

@Composable
private fun PullRequestContent(ctx: Context, state: Load<PullRequestSnapshot>) {
    WidgetFrame(actionStartActivity(WidgetIntents.open(ctx, Tab.Home, "mobilegh://shortcut/pulls"))) {
        Column(GlanceModifier.fillMaxSize()) {
            Header(R.drawable.oc_git_pull_request, tr("Pull Requests"), null)
            when (state) {
                Load.SignedOut -> Message(tr("请先登录 MobileGH"))
                Load.Failed -> Message(tr("暂时无法加载，点按打开 MobileGH"))
                is Load.Ready -> {
                    if (state.value.items.isEmpty()) {
                        Message(tr("没有分配给你的开放 Pull Request"))
                    } else {
                        val rows = ((LocalSize.current.height.value - 48) / 44).toInt()
                            .coerceIn(1, WidgetStore.PULL_REQUEST_ITEMS)
                        state.value.items.take(rows).forEach { item ->
                            Spacer(GlanceModifier.height(8.dp))
                            val open = WidgetIntents.open(ctx, Tab.Home, item.url)
                            Column(GlanceModifier.fillMaxWidth().clickable(actionStartActivity(open))) {
                                Text(
                                    "${item.repo} #${item.number}",
                                    style = TextStyle(color = Accent, fontSize = 11.sp),
                                    maxLines = 1,
                                )
                                Text(
                                    item.title,
                                    style = TextStyle(color = Fg, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
