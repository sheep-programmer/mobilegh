package com.mobilegh.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.GitHub
import com.mobilegh.data.Net
import com.mobilegh.data.Release
import com.mobilegh.data.Repo
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Loader
import com.mobilegh.nav.Screen
import com.mobilegh.nav.act
import com.mobilegh.nav.rememberLoader
import com.mobilegh.nav.rememberPager
import com.mobilegh.nav.retain
import com.mobilegh.nav.OnRepoChanged
import com.mobilegh.data.ReleaseEdit
import com.mobilegh.data.RepoWrite
import com.mobilegh.ui.components.DraftBinding
import com.mobilegh.ui.components.MarkdownEditor
import com.mobilegh.ui.components.MenuAction
import com.mobilegh.ui.components.MoreMenu
import com.mobilegh.ui.components.rememberDraft
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.text.input.TextFieldValue
import com.mobilegh.ui.components.Avatar
import com.mobilegh.ui.components.BarChart
import com.mobilegh.ui.components.Card
import com.mobilegh.ui.components.Dropdown
import com.mobilegh.ui.components.ErrorState
import com.mobilegh.ui.components.InlineError
import com.mobilegh.ui.components.GhButton
import com.mobilegh.ui.components.GhDialog
import com.mobilegh.ui.components.GhField
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.HtmlView
import com.mobilegh.ui.components.LanguageBar
import com.mobilegh.ui.components.Legend
import com.mobilegh.ui.components.LineChart
import com.mobilegh.ui.components.LoadBox
import com.mobilegh.ui.components.Loading
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.components.OcButton
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.PagedList
import com.mobilegh.ui.components.Pill
import com.mobilegh.ui.components.SectionTitle
import com.mobilegh.ui.components.StatCell
import com.mobilegh.ui.components.SwitchRow
import com.mobilegh.ui.components.TextLink
import com.mobilegh.ui.components.boxedItems
import com.mobilegh.ui.components.fmtCount
import com.mobilegh.ui.components.fmtDate
import com.mobilegh.ui.components.fmtSize
import com.mobilegh.ui.components.openBrowser
import com.mobilegh.ui.components.FileInfo
import com.mobilegh.ui.components.FileInfoDialog
import com.mobilegh.ui.components.fmtDateTime
import com.mobilegh.ui.components.relTime
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.components.toast
import com.mobilegh.ui.theme.Gh
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

// ======================= Releases =======================

@Composable
fun ReleasesScreen(owner: String, name: String) {
    val nav = LocalNav.current
    val ctx = rememberCtx()
    val pager = rememberPager("releases", 10) { p, f -> GitHub.releases(owner, name, p, f) }
    val repo = rememberLoader("repo") { GitHub.repo(owner, name, it) }
    val latest = rememberLoader("latest") { GitHub.latestRelease(owner, name, it)?.id }
    val expanded = retain("expanded") { mutableStateMapOf<Long, Boolean>() }
    OnRepoChanged(owner, name) { pager.refresh(); latest.refresh() }
    val canPush = repo.data?.let { it.permissions?.push == true && !it.archived } == true
    var deleting by remember { mutableStateOf<Release?>(null) }
    Page(
        "Releases", subtitle = "$owner/$name",
        actions = { if (canPush) OcButton(R.drawable.oc_plus, { nav.push(Screen.EditRelease(owner, name)) }) },
    ) { pad ->
        PagedList(
            pager, Modifier.padding(pad), empty = "还没有发布版本", divider = false, boxed = false,
            emptyAction = if (canPush) "新建 Release" else "刷新",
            onEmptyAction = if (canPush) ({ nav.push(Screen.EditRelease(owner, name)) }) else null,
        ) { r ->
            val isLatest = latest.data?.let { it == r.id } ?: (pager.items.firstOrNull() == r)
            ReleaseCard(
                owner, name, r, isLatest, expanded[r.id] == true,
                menu = buildList {
                    if (canPush) add(MenuAction("编辑") { nav.push(Screen.EditRelease(owner, name, r.id)) })
                    if (r.htmlUrl.isNotEmpty()) add(MenuAction("在浏览器打开") { ctx.openBrowser(r.htmlUrl) })
                    if (canPush) add(MenuAction("删除", danger = true) { deleting = r })
                },
            ) { expanded[r.id] = !(expanded[r.id] ?: false) }
        }
    }
    deleting?.let { r -> DeleteReleaseDialog(owner, name, r, { deleting = null }) { deleting = null; pager.update { l -> l.removeAll { it.id == r.id } } } }
}

@Composable
private fun DeleteReleaseDialog(owner: String, name: String, r: Release, onDismiss: () -> Unit, onDeleted: () -> Unit) {
    val g = Gh.c
    val ctx = rememberCtx()
    val entry = LocalEntry.current
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    GhDialog(
        "删除 Release", onDismiss = { if (!busy) onDismiss() },
        confirm = if (busy) "删除中…" else "删除", danger = true, confirmEnabled = !busy,
        onConfirm = {
            busy = true
            error = null
            entry.act({ busy = false; error = it }) {
                GitHub.deleteRelease(owner, name, r.id)
                RepoWrite.changed(owner, name)
                ctx.toast("已删除 ${r.name?.takeIf { it.isNotBlank() } ?: r.tagName}")
                onDeleted()
            }
        },
    ) {
        Column {
            Text("确定删除 ${r.name?.takeIf { it.isNotBlank() } ?: r.tagName} 吗？发布说明和上传的资源文件会一并删除，且无法恢复。", fontSize = 14.sp, color = g.fg)
            Spacer(Modifier.height(6.dp))
            Text("标签 ${r.tagName} 不会被删除。", fontSize = 13.sp, color = g.fgMuted)
            error?.let { InlineError(it) }
        }
    }
}

@Composable
private fun ReleaseCard(owner: String, name: String, r: Release, first: Boolean, expanded: Boolean, menu: List<MenuAction>, onToggle: () -> Unit) {
    val priv = com.mobilegh.data.Net.isPrivateRepo("$owner/$name")
    val g = Gh.c
    var info by remember { mutableStateOf<FileInfo?>(null) }
    info?.let { FileInfoDialog(it) { info = null } }
    Card(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(top = if (menu.isEmpty()) 12.dp else 2.dp, bottom = 12.dp)) {
            Row(Modifier.padding(start = 12.dp, end = if (menu.isEmpty()) 12.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(r.name?.takeIf { it.isNotBlank() } ?: r.tagName, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = g.fg, modifier = Modifier.weight(1f, false))
                Spacer(Modifier.width(8.dp))
                when {
                    r.draft -> Pill("草稿", g.fgMuted)
                    r.prerelease -> Pill("预发布", g.attention)
                    first -> Pill("最新", g.success)
                }
                if (menu.isNotEmpty()) {
                    Spacer(Modifier.weight(1f))
                    MoreMenu(menu, g.fgMuted)
                }
            }
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Oc(R.drawable.oc_tag, g.fgMuted, 14.dp)
                Spacer(Modifier.width(4.dp))
                Text(r.tagName, fontSize = 12.sp, color = g.fgMuted)
                Spacer(Modifier.width(10.dp))
                Avatar(r.author?.avatarUrl, 16.dp)
                Spacer(Modifier.width(4.dp))
                Text("${r.author?.login ?: ""} · ${fmtDate(r.publishedAt ?: r.createdAt)}", fontSize = 12.sp, color = g.fgMuted)
            }
            val body = r.bodyHtml
            if (!body.isNullOrBlank()) {
                if (expanded) {
                    HtmlView(body, Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
                    Row(Modifier.padding(horizontal = 8.dp)) { TextLink("收起", onClick = onToggle) }
                } else {
                    Text(
                        r.body.orEmpty().replace(Regex("[#*`>_]"), "").trim(), Modifier.padding(horizontal = 12.dp),
                        fontSize = 13.sp, color = g.fgMuted, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    )
                    Row(Modifier.padding(horizontal = 8.dp)) { TextLink("展开说明", onClick = onToggle) }
                }
            }
            run {
                HDivider(Modifier.padding(vertical = 6.dp))
                Text("资源 (${r.assets.size + 2})", Modifier.padding(horizontal = 12.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = g.fg)
                r.assets.forEach { a ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            info = FileInfo(
                                name = a.name,
                                url = a.browserDownloadUrl,
                                isPrivate = priv,
                                mimeType = a.contentType,
                                details = listOf(
                                    "说明" to (a.label ?: ""),
                                    "大小" to "${fmtSize(a.size)}（${"%,d".format(a.size)} 字节）",
                                    "类型" to (a.contentType ?: ""),
                                    "下载次数" to "${a.downloadCount} 次",
                                    "上传者" to (a.uploader?.login ?: ""),
                                    "上传时间" to fmtDateTime(a.createdAt),
                                    "更新时间" to fmtDateTime(a.updatedAt),
                                    "版本" to "${r.name?.takeIf { it.isNotBlank() } ?: r.tagName}（${r.tagName}）",
                                    "校验值" to (a.digest ?: ""),
                                ),
                            )
                        }.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Oc(R.drawable.oc_package, g.fgMuted)
                        Spacer(Modifier.width(8.dp))
                        Text(a.name, fontSize = 13.sp, color = g.accent, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${fmtSize(a.size)} · ${fmtCount(a.downloadCount)} 次下载", fontSize = 11.sp, color = g.fgMuted)
                    }
                }
                val repoPath = r.htmlUrl.substringBefore("/releases/")
                listOf("zip", "tar.gz").forEach { ext ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            info = FileInfo(
                                name = "$name-${r.tagName}.$ext",
                                url = "$repoPath/archive/refs/tags/${r.tagName}.$ext",
                                isPrivate = priv,
                                details = listOf(
                                    "内容" to "标签 ${r.tagName} 的完整源代码（$ext 压缩包）",
                                    "仓库" to "$owner/$name",
                                    "发布时间" to fmtDateTime(r.publishedAt ?: r.createdAt),
                                    "大小" to "下载时由 GitHub 实时打包，大小未知",
                                ),
                            )
                        }.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Oc(R.drawable.oc_file_directory, g.fgMuted)
                        Spacer(Modifier.width(8.dp))
                        Text("源代码 ($ext)", fontSize = 13.sp, color = g.accent, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

// ======================= 新建 / 编辑 Release =======================

private class ReleaseSeed(val repo: Repo, val release: Release?, val latestId: Long?, val tags: List<String>)

@Composable
fun EditReleaseScreen(owner: String, name: String, releaseId: Long?) {
    val seed = rememberLoader("seed") { f ->
        val repo = GitHub.repo(owner, name, f)
        val release = releaseId?.let { GitHub.release(owner, name, it) }
        val latest = runCatching { GitHub.latestRelease(owner, name, true)?.id }.getOrNull()
        val tags = runCatching { GitHub.tags(owner, name, 1, f).map { it.name } }.getOrDefault(emptyList())
        ReleaseSeed(repo, release, latest, tags)
    }
    Page(if (releaseId == null) "新建 Release" else "编辑 Release", subtitle = "$owner/$name") { pad ->
        val s = seed.data
        when {
            s != null -> ReleaseForm(owner, name, s, Modifier.padding(pad))
            seed.error != null -> ErrorState(seed.error!!, Modifier.padding(pad)) { seed.load(true) }
            else -> Loading(Modifier.padding(pad))
        }
    }
}

@Composable
private fun ReleaseForm(owner: String, name: String, seed: ReleaseSeed, modifier: Modifier) {
    val g = Gh.c
    val nav = LocalNav.current
    val ctx = rememberCtx()
    val entry = LocalEntry.current
    val rel = seed.release
    val editing = rel != null
    val draft = rememberDraft("release:${owner.lowercase()}/${name.lowercase()}:${rel?.id ?: "new"}")
    val seedTitle = rel?.name.orEmpty()
    val seedNotes = rel?.body.orEmpty()
    var tag by rememberSaveable { mutableStateOf(rel?.tagName.orEmpty()) }
    var target by rememberSaveable { mutableStateOf(rel?.targetCommitish ?: seed.repo.defaultBranch) }
    var title by rememberSaveable { mutableStateOf(if (draft.initial.empty) seedTitle else draft.initial.title) }
    var notes by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(if (draft.initial.empty) seedNotes else draft.initial.body)) }
    var prerelease by rememberSaveable { mutableStateOf(rel?.prerelease ?: false) }
    var isDraft by rememberSaveable { mutableStateOf(rel?.draft ?: false) }
    var latest by rememberSaveable { mutableStateOf(rel == null || seed.latestId == rel.id) }
    var pickTag by remember { mutableStateOf(false) }
    var pickTarget by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var generating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val canPush = seed.repo.permissions?.push == true && !seed.repo.archived
    val tagName = tag.trim()
    val existingTag = tagName in seed.tags
    val tagOk = tagName.isNotEmpty() && tagName.none { it.isWhitespace() }

    Column(modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
        if (!canPush) NoPushNotice(seed.repo.archived, "https://github.com/$owner/$name/releases/new")
        if (!draft.initial.empty && editing) {
            Text("已恢复本机草稿中的标题和说明", Modifier.padding(horizontal = 16.dp), fontSize = 12.sp, color = g.accent)
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            GhField(
                tag, { tag = it }, "标签", placeholder = "例如 v1.0.0",
                trailing = { OcButton(R.drawable.oc_tag, { pickTag = true }, g.fgMuted, enabled = seed.tags.isNotEmpty()) },
            )
            Spacer(Modifier.height(4.dp))
            when {
                tagName.isEmpty() -> Text("选择已有标签，或输入新标签名", fontSize = 12.sp, color = g.fgMuted)
                !tagOk -> Text("标签名不能包含空格", fontSize = 12.sp, color = g.danger)
                existingTag -> Text("使用已有标签 $tagName", fontSize = 12.sp, color = g.fgMuted)
                else -> Text("发布时将从目标分支创建新标签 $tagName", fontSize = 12.sp, color = g.success)
            }
        }
        if (!existingTag) {
            Row(
                Modifier.fillMaxWidth().clickable(enabled = !busy) { pickTarget = true }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Oc(R.drawable.oc_git_branch, g.fgMuted)
                Spacer(Modifier.width(10.dp))
                Text("目标分支", Modifier.weight(1f), fontSize = 15.sp, color = g.fg)
                Text(target, fontSize = 14.sp, color = g.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(4.dp))
                Oc(R.drawable.oc_chevron_down, g.fgMuted, 12.dp)
            }
        } else Spacer(Modifier.height(12.dp))
        Column(Modifier.padding(horizontal = 16.dp)) {
            GhField(title, { title = it }, "标题", placeholder = tagName.ifEmpty { null })
            Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("发布说明", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = g.fg)
                GhButton(if (generating) "生成中…" else "自动生成说明", enabled = tagOk && !generating && !busy) {
                    generating = true
                    error = null
                    entry.act({ generating = false; error = "生成失败：$it" }) {
                        val n = GitHub.generateNotes(owner, name, tagName, if (existingTag) null else target, null)
                        val text = notes.text
                        val merged = if (text.isBlank()) n.body else text.trimEnd() + "\n\n" + n.body
                        notes = TextFieldValue(merged, androidx.compose.ui.text.TextRange(merged.length))
                        if (title.isBlank()) title = n.name
                        generating = false
                    }
                }
            }
            MarkdownEditor(notes, { notes = it }, placeholder = "这个版本有哪些变化（支持 Markdown）", contextRepo = "$owner/$name", minLines = 8)
            DraftBinding(draft, if (title == seedTitle && notes.text == seedNotes) com.mobilegh.data.DraftValue() else com.mobilegh.data.DraftValue(title, notes.text))
        }
        Spacer(Modifier.height(8.dp))
        SwitchRow("设为预发布", "标记为非正式版本，不会成为最新版本", prerelease, enabled = !busy) { prerelease = it }
        SwitchRow("保存为草稿", "草稿只对有写入权限的人可见，暂不创建标签", isDraft, enabled = !busy) { isDraft = it }
        SwitchRow(
            "设为最新", if (prerelease || isDraft) "预发布和草稿不能设为最新" else "在仓库首页显示为最新版本",
            latest && !prerelease && !isDraft, enabled = !busy && !prerelease && !isDraft,
        ) { latest = it }
        Column(Modifier.padding(horizontal = 16.dp)) {
            error?.let { InlineError(it) }
            Spacer(Modifier.height(12.dp))
            GhButton(
                when {
                    busy -> if (editing) "保存中…" else if (isDraft) "保存中…" else "发布中…"
                    editing -> "保存修改"
                    isDraft -> "保存草稿"
                    else -> "发布 Release"
                },
                Modifier.fillMaxWidth(), primary = true, enabled = canPush && tagOk && !busy && !generating,
            ) {
                busy = true
                error = null
                val form = ReleaseEdit(tagName, if (existingTag) null else target, title.trim(), notes.text, isDraft, prerelease, latest)
                val submitted = com.mobilegh.data.DraftValue(title, notes.text)
                entry.act({
                    busy = false
                    error = if (it.contains("already_exists", true) || it.contains("already exists", true)) "标签 $tagName 已有对应的 Release" else it
                }) {
                    val saved = if (rel == null) GitHub.createRelease(owner, name, form) else GitHub.updateRelease(owner, name, rel.id, form)
                    RepoWrite.changed(owner, name)
                    draft.submitted(submitted)
                    ctx.toast(if (rel != null) "已保存" else if (saved.draft) "已保存草稿" else "已发布 ${saved.name?.takeIf { n -> n.isNotBlank() } ?: saved.tagName}")
                    nav.pop()
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (pickTag) {
        var q by remember { mutableStateOf("") }
        GhDialog("选择标签", onDismiss = { pickTag = false }) {
            Column {
                GhField(q, { q = it }, "筛选")
                Spacer(Modifier.height(8.dp))
                val list = seed.tags.filter { it.contains(q, true) }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    boxedItems(list) { t ->
                        Row(Modifier.fillMaxWidth().clickable { tag = t; pickTag = false }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Oc(if (t == tagName) R.drawable.oc_check else R.drawable.oc_tag, if (t == tagName) g.accent else g.fgMuted)
                            Spacer(Modifier.width(10.dp))
                            Text(t, color = g.fg, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (list.isEmpty()) item { Text("无匹配结果", Modifier.padding(24.dp), color = g.fgMuted) }
                }
            }
        }
    }
    if (pickTarget) BranchPicker(owner, name, target, onDismiss = { pickTarget = false }) { target = it; pickTarget = false }
}

// ======================= Insights（官方 App 没有） =======================

@Composable
fun InsightsScreen(owner: String, name: String) {
    val g = Gh.c
    val nav = LocalNav.current
    val views = rememberLoader("views") { GitHub.views(owner, name, it) }
    val clones = rememberLoader("clones") { GitHub.clones(owner, name, it) }
    val refs = rememberLoader("refs") { GitHub.referrers(owner, name, it) }
    val paths = rememberLoader("paths") { GitHub.popularPaths(owner, name, it) }
    val activity = rememberLoader("activity") { GitHub.commitActivity(owner, name, it) }
    val langs = rememberLoader("langs") { GitHub.languages(owner, name) }
    val contributors = rememberLoader("contrib") { GitHub.contributors(owner, name, 1, it).take(10) }
    val all = listOf<Loader<*>>(views, clones, refs, paths, activity, langs, contributors)
    Page("洞察", subtitle = "$owner/$name") { pad ->
        PullToRefreshBox(all.any { it.refreshing }, { all.forEach { it.refresh() } }, Modifier.padding(pad)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                SectionTitle("访问量（最近 14 天）")
                TrafficCard(views, "浏览", "独立访客") { it.views.orEmpty() }
                SectionTitle("克隆（最近 14 天）")
                TrafficCard(clones, "克隆", "独立克隆者") { it.clones.orEmpty() }

                SectionTitle("来源网站")
                InsightBody(refs) { list ->
                    if (list.isEmpty()) Text("暂无数据", Modifier.padding(16.dp), color = g.fgMuted, fontSize = 13.sp)
                    list.forEach { r ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                            Text(r.referrer, Modifier.weight(1f), fontSize = 14.sp, color = g.fg)
                            Text("${r.count} 次 / ${r.uniques} 人", fontSize = 13.sp, color = g.fgMuted)
                        }
                    }
                }
                SectionTitle("热门内容")
                InsightBody(paths) { list ->
                    if (list.isEmpty()) Text("暂无数据", Modifier.padding(16.dp), color = g.fgMuted, fontSize = 13.sp)
                    list.forEach { p ->
                        Column(Modifier.fillMaxWidth().clickable { com.mobilegh.nav.Links.route("https://github.com${p.path}")?.let(nav::push) }.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            Text(p.path, fontSize = 13.sp, color = g.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${p.count} 次浏览 · ${p.uniques} 位访客", fontSize = 12.sp, color = g.fgMuted)
                        }
                    }
                }

                SectionTitle("提交活跃度（最近一年）")
                InsightBody(activity) { weeks ->
                    if (weeks.isEmpty()) {
                        Text("GitHub 正在计算统计数据，请稍后下拉刷新", Modifier.padding(16.dp), color = g.fgMuted, fontSize = 13.sp)
                    } else {
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            Text("共 ${weeks.sumOf { it.total }} 次提交，平均每周 ${"%.1f".format(weeks.sumOf { it.total } / weeks.size.toFloat())} 次", fontSize = 13.sp, color = g.fgMuted)
                            Spacer(Modifier.height(8.dp))
                            BarChart(weeks.map { it.total }, g.success, height = 110.dp)
                        }
                    }
                }

                langs.data?.takeIf { it.isNotEmpty() }?.let {
                    SectionTitle("语言")
                    LanguageBar(it, Modifier.padding(horizontal = 16.dp))
                }

                SectionTitle("主要贡献者") { TextLink("全部") { nav.push(Screen.Users(com.mobilegh.nav.UserKind.Contributors, owner, name)) } }
                InsightBody(contributors) { list ->
                    val max = list.maxOfOrNull { it.contributions ?: 0 } ?: 1
                    list.forEach { u ->
                        Row(
                            Modifier.fillMaxWidth().clickable { nav.push(Screen.Profile(u.login)) }.padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(u.avatarUrl, 24.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                com.mobilegh.ui.components.RatioRow(u.login, u.contributions ?: 0, max, g.success)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun <T> InsightBody(l: Loader<T>, content: @Composable (T) -> Unit) {
    val d = l.data
    when {
        d != null -> Column { content(d) }
        l.error != null -> {
            val denied = l.error!!.contains("未找到") || l.error!!.contains("403") || l.error!!.contains("push access", true)
            InlineError(if (denied) "需要该仓库的写入权限才能查看" else l.error!!, Modifier.padding(horizontal = 16.dp), retry = if (denied) null else ({ l.refresh() }))
        }
        else -> Loading(Modifier.fillMaxWidth().height(80.dp))
    }
}

@Composable
private fun TrafficCard(l: Loader<com.mobilegh.data.Traffic>, a: String, b: String, points: (com.mobilegh.data.Traffic) -> List<com.mobilegh.data.TrafficPoint>) {
    val g = Gh.c
    Card(Modifier.padding(horizontal = 16.dp)) {
        InsightBody(l) { t ->
            val ps = points(t)
            Column(Modifier.padding(12.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    StatCell(fmtCount(t.count), a, Modifier.weight(1f))
                    StatCell(fmtCount(t.uniques), b, Modifier.weight(1f))
                }
                if (ps.isNotEmpty()) {
                    LineChart(ps.map { it.timestamp.substring(5, 10) }, ps.map { it.count }, ps.map { it.uniques }, g.success, g.accent)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Legend(g.success, a)
                        Legend(g.accent, b)
                    }
                }
            }
        }
    }
}

// ======================= 仓库设置 =======================

@Composable
fun RepoSettingsScreen(owner: String, name: String) {
    val repo = rememberLoader("repo") { GitHub.repo(owner, name, true) }
    Page("仓库设置", subtitle = "$owner/$name") { pad ->
        LoadBox(repo, Modifier.padding(pad).fillMaxSize()) { r -> SettingsForm(r) { repo.load(true) } }
    }
}

@Composable
private fun SettingsForm(r: Repo, reload: () -> Unit) {
    val g = Gh.c
    val nav = LocalNav.current
    val entry = LocalEntry.current
    val ctx = rememberCtx()
    var newName by rememberSaveable(r.id) { mutableStateOf(r.name) }
    var desc by rememberSaveable(r.id) { mutableStateOf(r.description.orEmpty()) }
    var home by rememberSaveable(r.id) { mutableStateOf(r.homepage.orEmpty()) }
    var topics by rememberSaveable(r.id) { mutableStateOf(r.topics.joinToString(", ")) }
    var branch by rememberSaveable(r.id) { mutableStateOf(r.defaultBranch) }
    var saving by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val o = r.owner.login

    fun patch(msg: String, vararg kv: Pair<String, Any>) {
        entry.act({ ctx.toast(it) }, reload) {
            GitHub.updateRepo(o, r.name, buildJsonObject {
                kv.forEach { (k, v) -> put(k, if (v is Boolean) JsonPrimitive(v) else JsonPrimitive(v.toString())) }
            })
            ctx.toast(msg)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 40.dp)) {
        SectionTitle("基本信息")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GhField(newName, { newName = it.replace(' ', '-') }, "仓库名称")
            GhField(desc, { desc = it }, "描述", singleLine = false)
            GhField(home, { home = it }, "主页网址")
            GhField(topics, { topics = it }, "Topics（逗号分隔）")
            GhField(branch, { branch = it }, "默认分支")
            GhButton("保存修改", Modifier.fillMaxWidth(), primary = true, enabled = !saving) {
                saving = true
                entry.act({ saving = false; ctx.toast(it) }) {
                    val updated = GitHub.updateRepo(o, r.name, buildJsonObject {
                        put("name", JsonPrimitive(newName.trim()))
                        put("description", JsonPrimitive(desc))
                        put("homepage", JsonPrimitive(home))
                        put("default_branch", JsonPrimitive(branch.trim()))
                    })
                    val list = topics.split(',', '，', ' ').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                    if (list != r.topics) GitHub.setTopics(o, updated.name, list)
                    saving = false
                    ctx.toast("已保存")
                    if (updated.name != r.name) nav.popToRoot().also { nav.push(Screen.Repo(o, updated.name)) } else reload()
                }
            }
        }
        SectionTitle("功能")
        SwitchRow("Issues", null, r.hasIssues) { patch("已更新", "has_issues" to it) }
        SwitchRow("Wiki", null, r.hasWiki) { patch("已更新", "has_wiki" to it) }
        SwitchRow("Projects", null, r.hasProjects) { patch("已更新", "has_projects" to it) }
        SwitchRow("Discussions", null, r.hasDiscussions) { patch("已更新", "has_discussions" to it) }
        SwitchRow("模板仓库", "允许他人以此仓库为模板创建新仓库", r.isTemplate) { patch("已更新", "is_template" to it) }
        SwitchRow("允许 Fork", "仅对私有仓库/组织仓库有效", r.allowForking) { patch("已更新", "allow_forking" to it) }

        SectionTitle("危险操作")
        Card(Modifier.padding(horizontal = 16.dp)) {
            Column {
                DangerRow("更改可见性", "当前：${if (r.isPrivate) "私有" else "公开"}", if (r.isPrivate) "设为公开" else "设为私有") { confirm = "visibility" }
                HDivider()
                DangerRow(if (r.archived) "取消归档" else "归档仓库", "归档后仓库变为只读", if (r.archived) "取消归档" else "归档") { confirm = "archive" }
                HDivider()
                DangerRow("删除仓库", "此操作不可撤销（需要 delete_repo 权限）", "删除") { confirm = "delete" }
            }
        }
    }

    when (confirm) {
        "visibility" -> GhDialog("更改可见性", { confirm = null }, confirm = "确认", danger = true, onConfirm = {
            confirm = null
            patch("可见性已更改", "private" to !r.isPrivate)
        }) { Text("确定要把 ${r.fullName} 设为${if (r.isPrivate) "公开" else "私有"}吗？", color = g.fg) }
        "archive" -> GhDialog(if (r.archived) "取消归档" else "归档仓库", { confirm = null }, confirm = "确认", danger = true, onConfirm = {
            confirm = null
            patch(if (r.archived) "已取消归档" else "已归档", "archived" to !r.archived)
        }) { Text(if (r.archived) "取消归档后仓库将恢复可写。" else "归档后，Issues、PR、代码等都将变为只读。", color = g.fg) }
        "delete" -> {
            var typed by remember { mutableStateOf("") }
            GhDialog("删除仓库", { confirm = null }, confirm = "我了解后果，删除此仓库", danger = true, confirmEnabled = typed == r.fullName, onConfirm = {
                confirm = null
                entry.act({ ctx.toast(if (it.contains("403") || it.contains("admin", true)) "删除失败：Token 需要 delete_repo 权限" else it) }) {
                    GitHub.deleteRepo(o, r.name)
                    ctx.toast("已删除 ${r.fullName}")
                    nav.popToRoot()
                }
            }) {
                Column {
                    Text("这将永久删除 ${r.fullName} 的代码、Issues、PR、Wiki 等全部内容。", color = g.fg, fontSize = 14.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("请输入 ${r.fullName} 以确认：", color = g.fgMuted, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    GhField(typed, { typed = it }, "仓库全名")
                }
            }
        }
    }
}

@Composable
private fun DangerRow(title: String, desc: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Gh.c.fg)
            Text(desc, fontSize = 12.sp, color = Gh.c.fgMuted)
        }
        GhButton(action, danger = true, onClick = onClick)
    }
}

// ======================= 协作者 =======================

@Composable
fun CollaboratorsScreen(owner: String, name: String) {
    val g = Gh.c
    val nav = LocalNav.current
    val entry = LocalEntry.current
    val ctx = rememberCtx()
    val collabs = rememberLoader("collabs") { GitHub.collaborators(owner, name, it) }
    val pending = rememberLoader("pending") { runCatching { GitHub.pendingInvites(owner, name, it) }.getOrDefault(emptyList()) }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }
    fun reload() { collabs.load(true); pending.load(true) }
    Page("协作者", subtitle = "$owner/$name", actions = { OcButton(R.drawable.oc_plus, { adding = true }) }) { pad ->
        LoadBox(collabs, Modifier.padding(pad).fillMaxSize()) { list ->
            LazyColumn(Modifier.fillMaxSize()) {
                val inv = pending.data.orEmpty()
                if (inv.isNotEmpty()) {
                    item { SectionTitle("待接受的邀请") }
                    boxedItems(inv) { i ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (i.invitee != null) Avatar(i.invitee.avatarUrl, 32.dp) else Oc(R.drawable.oc_mail, g.fgMuted)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(i.invitee?.login ?: "邮箱邀请", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = g.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("权限：${permZh(i.permissions)} · ${if (i.expired) "已过期" else relTime(i.createdAt)}", fontSize = 12.sp, color = if (i.expired) g.attention else g.fgMuted)
                            }
                            GhButton("撤销", danger = true) { entry.act({ ctx.toast(it) }, ::reload) { GitHub.cancelInvite(owner, name, i.id) } }
                        }
                    }
                }
                item { SectionTitle("拥有访问权限的人 (${list.size})") }
                boxedItems(list) { u ->
                    Row(
                        Modifier.fillMaxWidth().clickable { nav.push(Screen.Profile(u.login)) }.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(u.avatarUrl, 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(u.login, fontWeight = FontWeight.SemiBold, color = g.fg, fontSize = 15.sp)
                            Text(permZh(u.roleName ?: ""), fontSize = 12.sp, color = g.fgMuted)
                        }
                        if (!u.login.equals(owner, true)) OcButton(R.drawable.oc_trash, { removing = u.login }, g.danger)
                    }
                }
            }
        }
    }
    if (adding) {
        var login by remember { mutableStateOf("") }
        var perm by remember { mutableStateOf(2) }
        val perms = listOf("pull", "triage", "push", "maintain", "admin")
        GhDialog("邀请协作者", { adding = false }, confirm = "发送邀请", confirmEnabled = login.isNotBlank(), onConfirm = {
            adding = false
            entry.act({ ctx.toast(it) }, ::reload) { GitHub.addCollaborator(owner, name, login.trim().removePrefix("@"), perms[perm]); ctx.toast("邀请已发送") }
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GhField(login, { login = it }, "GitHub 用户名")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("权限：", color = g.fgMuted, fontSize = 14.sp)
                    Dropdown(permZh(perms[perm]), perms.map(::permZh), { perm = it })
                }
            }
        }
    }
    removing?.let { u ->
        GhDialog("移除协作者", { removing = null }, confirm = "移除", danger = true, onConfirm = {
            removing = null
            entry.act({ ctx.toast(it) }, ::reload) { GitHub.removeCollaborator(owner, name, u) }
        }) { Text("确定将 $u 从 $owner/$name 移除吗？", color = g.fg) }
    }
}
