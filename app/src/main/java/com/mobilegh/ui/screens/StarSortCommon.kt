package com.mobilegh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.GitHubStarList
import com.mobilegh.data.Session
import com.mobilegh.ui.components.Dropdown
import com.mobilegh.ui.components.GhField
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.components.OcButton
import com.mobilegh.ui.theme.Gh

/**
 * 排序项：[value] 是 REST/GraphQL 的排序键，[descending] 是选到这一项时的默认方向。
 * 星标页、列表目录、列表详情共用同一套「排序字段 + 正序/倒序」模型。
 */
internal data class SortChoice(val label: String, val value: String = "", val descending: Boolean = false)

/**
 * 「已 Star」页的服务端排序。Star 时间走 GraphQL 星标连接（可无限游标翻页），更新时间走 REST。
 *
 * `/user/starred` 只支持 `created` / `updated`；`stars` 等其它值会被静默忽略并回退成默认顺序，
 * 所以不能列进菜单。
 */
internal val STAR_SORTS = listOf(
    SortChoice("Star 时间", "created", descending = true),
    SortChoice("更新时间", "updated", descending = true),
)

internal fun restoredStarSortIndex(index: Int): Int = index.coerceIn(0, STAR_SORTS.lastIndex)

/**
 * 列表详情不做「Star 时间」排序：列表条目不带进列表时间，`starredRepositories` 也不接受按仓库过滤，
 * 想按 Star 时间排就只能把 Star 全集扫一遍——Star 上万时根本跑不动，所以只保留正序 / 倒序，
 * 作用在官方 `items(first:30, after:)` 返回的原始顺序上。
 */

/**
 * Star 列表（GitHub Lists）自身的排序。
 *
 * 服务端 `User.lists` 只有 after/before/first/last，**没有 orderBy**，schema 里也不存在
 * `UserListOrder` 类型，所以只能在客户端排。列表最多几十个，一次全取回来再排完全可行。
 *
 * 默认「最后更新时间」倒序（新 → 旧）。
 */
internal val LIST_SORTS = listOf(
    SortChoice("最后更新时间", descending = true),
    SortChoice("创建时间", descending = true),
    SortChoice("名称"),
    SortChoice("仓库数", descending = true),
)

/** 默认排在「最后更新时间」（= `lastAddedAt`，最后一次加仓库的时间） */
internal const val LIST_SORT_UPDATED = 0

/**
 * 搜索框：输入只改输入框，按回车或点放大镜才应用关键词。
 * 边打字边应用会让列表反复重建分页、原地跳动，搜完再刷新更稳。
 */
@Composable
internal fun SearchBox(
    value: String,
    applied: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val dirty = value.trim() != applied
    GhField(
        value, onValueChange, label, modifier, placeholder = placeholder,
        leading = { Oc(R.drawable.oc_search, Gh.c.fgMuted, 18.dp) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        // 输入框尾部只放一个按钮：有待生效的关键词时是"搜索"，否则是"清空"，不会挤掉输入区
        trailing = when {
            dirty -> { { OcButton(R.drawable.oc_search, onSearch, Gh.c.accent, description = "应用搜索") } }
            value.isNotEmpty() -> { { OcButton(R.drawable.oc_x, onClear, Gh.c.fgMuted, description = "清空搜索") } }
            else -> null
        },
    )
}

/** 正序 / 倒序切换：和排序字段配合，避免每个字段在菜单里列两遍 */
@Composable
internal fun DirectionButton(descending: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val g = Gh.c
    Row(
        Modifier
            .border(1.dp, g.border, RoundedCornerShape(6.dp))
            .background(g.btnBg, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClickLabel = if (descending) "改为正序" else "改为倒序", onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Oc(if (descending) R.drawable.oc_arrow_down else R.drawable.oc_arrow_up, g.fgMuted, 12.dp)
        Spacer(Modifier.width(4.dp))
        Text(if (descending) "倒序" else "正序", fontSize = 13.sp, color = if (enabled) g.fg else g.fgMuted, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** 排序下拉 + 正序/倒序按钮：星标页、列表目录、列表详情共用一行同样的控件 */
@Composable
internal fun SortBar(
    sorts: List<SortChoice>,
    index: Int,
    descending: Boolean,
    onPick: (Int) -> Unit,
    onFlip: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    FlowRow(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Dropdown(sorts[index].label, sorts.map { it.label }, { onPick(it) }, active = enabled && index != 0, enabled = enabled)
        DirectionButton(descending, enabled, onFlip)
    }
}

/** 列表目录的过滤与排序：搜名称/描述，按默认顺序、名称或仓库数排；目录最多几十个，全在客户端完成 */
/**
 * 列表目录的排序。ISO 8601 时间字符串可以直接按字典序比较（定长、UTC、同格式）。
 *
 * 「最后更新时间」用的是 `lastAddedAt`（最后一次往列表里添加仓库的时间），
 * 不是 `updatedAt`（那个只跟列表名称/描述的修改有关，常年不动）。
 *
 * 没有时间的列表**两个方向都排在最后**，不会因为升序而窜到最前面。
 */
internal fun sortStarLists(lists: List<GitHubStarList>, sort: Int, descending: Boolean): List<GitHubStarList> {
    // 有值的按 key 从大到小/从小到大排，空值的整段留在最后
    fun ordered(key: (GitHubStarList) -> String, emptyWhen: (GitHubStarList) -> Boolean): List<GitHubStarList> {
        val (present, missing) = lists.partition { !emptyWhen(it) }
        val sorted = present.sortedBy(key)
        return (if (descending) sorted.reversed() else sorted) + missing
    }
    return when (sort) {
        LIST_SORT_UPDATED -> ordered({ it.lastAddedAt }, { it.lastAddedAt.isEmpty() })
        1 -> ordered({ it.createdAt }, { it.createdAt.isEmpty() })
        2 -> ordered({ it.name.lowercase() }, { it.name.isEmpty() })
        else -> {
            val sorted = lists.sortedBy { it.count }
            if (descending) sorted.reversed() else sorted
        }
    }
}

/** 先按关键词过滤（名称或描述），再排序 */
internal fun arrangeStarLists(lists: List<GitHubStarList>, query: String, sort: Int, descending: Boolean): List<GitHubStarList> {
    val keyword = query.trim()
    val matched = if (keyword.isEmpty()) lists else lists.filter {
        it.name.contains(keyword, true) || it.description.contains(keyword, true)
    }
    return sortStarLists(matched, sort, descending)
}

/** 列表目录的搜索 + 排序状态，页面和首页卡片共用一份实现，行为不会走偏 */
internal class ListBrowserState {
    var input by mutableStateOf("")
    var query by mutableStateOf("")
    var sort by mutableIntStateOf(0)
    var descending by mutableStateOf(LIST_SORTS[0].descending)

    fun apply() { query = input.trim() }
    fun clear() { input = ""; query = "" }
    fun pick(index: Int) { sort = index; descending = LIST_SORTS[index].descending }

    fun shown(lists: List<GitHubStarList>): List<GitHubStarList> = arrangeStarLists(lists, query, sort, descending)
}

/**
 * 一处改、各处跟着变：首页卡片区和「Star 列表」页的搜索/排序是同一份状态，
 * 在首页搜过的关键词，点进列表页不会莫名其妙丢掉。
 */
private val listBrowsers = HashMap<String, ListBrowserState>()
private var listBrowserGeneration = Session.generation

@Composable
internal fun rememberSharedListBrowser(key: String): ListBrowserState {
    val generation = Session.generation
    return androidx.compose.runtime.remember(key, generation) {
        if (generation != listBrowserGeneration) {
            listBrowsers.clear()
            listBrowserGeneration = generation
        }
        listBrowsers.getOrPut(key) { ListBrowserState() }
    }
}

/** 账号切换时清掉上一个账号的界面状态 */
internal fun resetListBrowsers() {
    listBrowsers.clear()
    listBrowserGeneration = Session.generation
}

/** 只属于当前页面的搜索/排序状态（列表详情之类不需要跨页共享的地方用它） */
@Composable
internal fun rememberListBrowser(key: String): ListBrowserState = androidx.compose.runtime.remember(key) { ListBrowserState() }
