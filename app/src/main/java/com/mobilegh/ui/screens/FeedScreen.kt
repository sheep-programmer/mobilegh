package com.mobilegh.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mobilegh.R
import com.mobilegh.data.Event
import com.mobilegh.data.GitHub
import com.mobilegh.data.Session
import com.mobilegh.i18n.tr
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Tab
import com.mobilegh.nav.rememberPager
import com.mobilegh.ui.components.Dropdown
import com.mobilegh.ui.components.EventCat
import com.mobilegh.ui.components.EventItem
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.PagedList
import com.mobilegh.ui.components.eventCat
import com.mobilegh.ui.components.parseInstant

/**
 * 动态流页面：展示关注的开发者与仓库的动态事件流。
 * 原先内嵌在首页，现由「我的工作」入口点开查看，保持首页简洁高效。
 */
@Composable
fun FeedScreen() {
    val nav = LocalNav.current
    val login = Session.login ?: return
    val feed = rememberPager("feed:$login") { p, f -> GitHub.received(login, p, f) }
    var cat by rememberSaveable { mutableStateOf(EventCat.All) }
    val state = rememberLazyListState()

    Page(
        title = tr("动态"),
        actions = {
            Dropdown(
                label = cat.label,
                options = EventCat.filterOptions.map { it.label },
                onSelect = { cat = EventCat.filterOptions[it] },
                active = cat != EventCat.All,
            )
        },
    ) { pad ->
        PagedList(
            feed, Modifier.padding(pad).fillMaxSize(), state,
            empty = tr("关注一些开发者或 Star 一些仓库后，这里会显示动态"),
            emptyIcon = R.drawable.oc_pulse,
            emptyAction = tr("去探索"),
            onEmptyAction = { nav.select(Tab.Explore) },
            autoLoad = cat == EventCat.All,
            itemFilter = { cat == EventCat.All || eventCat(it) == cat },
            itemSort = compareByDescending<Event> { parseInstant(it.createdAt)?.toEpochMilli() ?: Long.MIN_VALUE },
            itemKey = { it.id },
        ) { EventItem(it) }
    }
}
