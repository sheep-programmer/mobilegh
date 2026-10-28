package com.mobilegh.ui.screens

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.HomeLayout
import com.mobilegh.data.HomePrefs
import com.mobilegh.data.Involvement
import com.mobilegh.data.IssueQuery
import com.mobilegh.data.IssueStateFilter
import com.mobilegh.nav.LocalNav
import com.mobilegh.ui.components.Chips
import com.mobilegh.ui.components.GhDialog
import com.mobilegh.ui.components.GhField
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.MenuAction
import com.mobilegh.ui.components.MenuGroup
import com.mobilegh.ui.components.MenuIcon
import com.mobilegh.ui.components.MoreMenu
import com.mobilegh.ui.components.OcButton
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.SectionTitle
import com.mobilegh.ui.components.Segmented
import com.mobilegh.ui.components.TextLink
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.components.toast
import com.mobilegh.ui.theme.Gh
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf

/** 自定义首页：「我的工作」入口的显示与顺序、保存的搜索、收藏仓库。只保存在本机，按账号区分。 */
@Composable
fun HomeCustomizeScreen() {
    val g = Gh.c
    val nav = LocalNav.current
    val ctx = rememberCtx()
    val layout = HomePrefs.layout()
    var creating by remember { mutableStateOf(false) }
    // 复用首页的图标、名称与颜色，保证这里看到的和首页一致
    val builtins = homeShortcuts({}, {}, {}, {}, {}, {}, {}, {}).associateBy { it.id }

    Page(
        tr("自定义首页"),
        subtitle = tr("仅保存在本机"),
        actions = {
            MoreMenu(
                listOf(
                    MenuAction(tr("新建保存的搜索")) { creating = true },
                    MenuAction(tr("恢复默认入口")) { HomePrefs.resetShortcuts(); ctx.toast(tr("已恢复默认入口")) },
                ),
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionTitle(tr("我的工作"))
            Hint(tr("用开关显示或隐藏入口，用箭头调整顺序。"))
            Spacer(Modifier.height(8.dp))
            val slots = layout.slots()
            MenuGroup {
                slots.forEachIndexed { i, slot ->
                    if (i > 0) HDivider()
                    val search = layout.search(slot.id)
                    val builtin = builtins[slot.id]
                    val s = search?.let { savedSearchShortcut(it) {} } ?: builtin ?: return@forEachIndexed
                    EditRow(
                        icon = s.icon,
                        iconTint = if (slot.visible) s.color else g.fgMuted,
                        title = s.label,
                        desc = search?.let { tr("保存的搜索 · ") + it.query.summary() },
                        canUp = i > 0,
                        canDown = i < slots.lastIndex,
                        onMove = { d -> HomePrefs.moveShortcut(i, d) },
                    ) {
                        if (search != null) OcButton(R.drawable.oc_trash, {
                            HomePrefs.removeSearch(search.id)
                            ctx.toast(trf("已删除「{0}」", search.name))
                        }, g.fgMuted)
                        Switch(
                            slot.visible, { HomePrefs.setShortcutVisible(slot.id, it) },
                            Modifier.semantics { contentDescription = (if (slot.visible) tr("隐藏") else tr("显示")) + s.label },
                            colors = SwitchDefaults.colors(checkedTrackColor = g.btnPrimary, checkedThumbColor = Color.White, uncheckedTrackColor = g.neutralMuted, uncheckedBorderColor = g.border),
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                TextLink(tr("新建保存的搜索")) { creating = true }
            }
            Hint(tr("在 Issue / PR 列表设置好筛选后，也可以从右上角菜单选择「保存到首页…」。"))

            SectionTitle(tr("收藏仓库"))
            if (layout.favorites.isEmpty()) {
                Hint(tr("在仓库页右上角菜单选择「加入首页收藏」，常用仓库就会出现在首页。"))
            } else {
                Hint(tr("也可以在首页长按收藏的仓库来调整。"))
                Spacer(Modifier.height(8.dp))
                MenuGroup {
                    layout.favorites.forEachIndexed { i, f ->
                        if (i > 0) HDivider()
                        EditRow(
                            icon = R.drawable.oc_repo, iconTint = g.fgMuted, title = "${f.owner} / ${f.name}", desc = null,
                            canUp = i > 0, canDown = i < layout.favorites.lastIndex,
                            onMove = { d -> HomePrefs.moveFavorite(i, d) },
                        ) {
                            OcButton(R.drawable.oc_trash, { HomePrefs.removeFavorite(f) }, g.fgMuted)
                        }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
    if (creating) NewSavedSearchDialog { creating = false }
}

@Composable
private fun Hint(text: String) {
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 2.dp), fontSize = 12.sp, lineHeight = 16.sp, color = Gh.c.fgMuted)
}

/** 可编辑的一行：图标、名称、上移 / 下移，以及末尾的额外操作 */
@Composable
private fun EditRow(
    @DrawableRes icon: Int,
    iconTint: Color,
    title: String,
    desc: String?,
    canUp: Boolean,
    canDown: Boolean,
    onMove: (Int) -> Unit,
    trailing: @Composable () -> Unit,
) {
    val g = Gh.c
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MenuIcon(icon, iconTint)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = g.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (desc != null) Text(desc, fontSize = 12.sp, lineHeight = 16.sp, color = g.fgMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.semantics { contentDescription = trf("上移{0}", title) }) { OcButton(R.drawable.oc_arrow_up, { onMove(-1) }, g.fgMuted, canUp) }
        Row(Modifier.semantics { contentDescription = trf("下移{0}", title) }) { OcButton(R.drawable.oc_arrow_down, { onMove(1) }, g.fgMuted, canDown) }
        trailing()
    }
}

/**
 * 从零新建保存的搜索：范围（Issue / PR）、可选仓库、状态、与我相关的方式和关键词。
 * 不填仓库时按「与我相关」跨仓库搜索；关键词原样交给 GitHub 搜索，可以写 label:bug 之类的限定词。
 */
@Composable
private fun NewSavedSearchDialog(onDismiss: () -> Unit) {
    val g = Gh.c
    val ctx = rememberCtx()
    var name by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf("search") }
    var pulls by remember { mutableStateOf(false) }
    var repo by remember { mutableStateOf("") }
    var state by remember { mutableStateOf(IssueStateFilter.Open) }
    var involves by remember { mutableStateOf(Involvement.Created) }
    var text by remember { mutableStateOf("") }
    val repoText = repo.trim().removePrefix("https://github.com/").trimEnd('/')
    val repoOk = repoText.isEmpty() || IssueQuery(repo = repoText).normalized().repo != null
    val kinds = Involvement.entries.filter { pulls || !it.pullsOnly }
    val query = IssueQuery(
        pulls = pulls,
        repo = repoText.ifEmpty { null },
        state = state,
        text = text,
        involves = if (repoText.isEmpty()) involves.takeIf { it in kinds } ?: Involvement.Created else null,
    ).normalized()
    val valid = name.isNotBlank() && name.trim().length <= HomeLayout.MAX_NAME && repoOk

    GhDialog(tr("新建保存的搜索"), onDismiss, confirm = tr("保存"), confirmEnabled = valid, onConfirm = {
        runCatching { HomePrefs.addSearch(name, icon, query) ?: error(tr("请先登录")) }
            .onSuccess { ctx.toast(tr("已添加到首页「我的工作」")); onDismiss() }
            .onFailure { ctx.toast(it.message ?: tr("保存失败")) }
    }) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            GhField(name, { name = it.take(HomeLayout.MAX_NAME + 8) }, tr("名称"))
            Spacer(Modifier.height(10.dp))
            Segmented(listOf("Issues", "Pull Requests"), if (pulls) 1 else 0) {
                pulls = it == 1
                if (icon == "issue" || icon == "pr") icon = if (pulls) "pr" else "issue"
            }
            Spacer(Modifier.height(10.dp))
            GhField(repo, { repo = it }, tr("仓库（可选）"), placeholder = "owner/name")
            if (!repoOk) Text(tr("格式应为 owner/name"), fontSize = 12.sp, color = g.danger)
            if (repoText.isEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(tr("不填仓库时，搜索与你相关的内容"), fontSize = 12.sp, color = g.fgMuted)
                Spacer(Modifier.height(6.dp))
                Segmented(kinds.map { it.label }, kinds.indexOf(involves).coerceAtLeast(0)) { involves = kinds[it] }
            }
            Spacer(Modifier.height(4.dp))
            Chips(IssueStateFilter.entries.map { it.label }, IssueStateFilter.entries.indexOf(state)) { state = IssueStateFilter.entries[it] }
            GhField(text, { text = it }, tr("关键词（可选）"), placeholder = tr("支持 label:bug 等搜索限定词"))
            Spacer(Modifier.height(4.dp))
            Text(query.summary(), fontSize = 12.sp, color = g.fgMuted, maxLines = 3)
            Spacer(Modifier.height(12.dp))
            SavedSearchIconPicker(icon) { icon = it }
        }
    }
}
