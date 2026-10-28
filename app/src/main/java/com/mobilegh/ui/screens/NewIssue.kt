package com.mobilegh.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.DraftValue
import com.mobilegh.data.GitHub
import com.mobilegh.data.IssueTemplate
import com.mobilegh.data.IssueTemplateSet
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Screen
import com.mobilegh.nav.act
import com.mobilegh.nav.rememberLoader
import com.mobilegh.nav.retain
import com.mobilegh.ui.components.DraftBinding
import com.mobilegh.ui.components.GhButton
import com.mobilegh.ui.components.GhDialog
import com.mobilegh.ui.components.GhField
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.Loading
import com.mobilegh.ui.components.MarkdownEditor
import com.mobilegh.ui.components.MenuAction
import com.mobilegh.ui.components.MenuGroup
import com.mobilegh.ui.components.MenuIcon
import com.mobilegh.ui.components.MoreMenu
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.components.SectionTitle
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.openBrowser
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.components.rememberDraft
import com.mobilegh.ui.components.toast
import com.mobilegh.ui.theme.Gh
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf

/** 新建 Issue 时选中的元数据；milestone 的 key 是里程碑编号 */
private data class NewIssueMeta(
    val labels: List<MetaOpt> = emptyList(),
    val assignees: List<MetaOpt> = emptyList(),
    val milestone: MetaOpt? = null,
)

/** 选择器中「空白 Issue」对应的 key */
private const val BLANK = ""

/**
 * 新建 Issue：仓库有 .github/ISSUE_TEMPLATE/ 时先选模板（遵循 config.yml 的 blank_issues_enabled 与 contact_links），
 * 再填写标题、正文以及标签 / 指派人 / 里程碑。
 */
@Composable
fun NewIssueScreen(owner: String, name: String) {
    val nav = LocalNav.current
    val entry = LocalEntry.current
    val ctx = rememberCtx()
    val draft = rememberDraft("new-issue:${owner.lowercase()}/${name.lowercase()}")
    var title by rememberSaveable { mutableStateOf(draft.initial.title) }
    var body by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(draft.initial.body)) }
    // 有未提交的草稿时直接回到编辑，不再弹模板选择
    var picked by rememberSaveable { mutableStateOf<String?>(if (draft.initial.title.isNotBlank() || draft.initial.body.isNotBlank()) BLANK else null) }
    // 「更换模板」前所用的模板，选回同一个时保留已填写的内容
    var lastPicked by rememberSaveable { mutableStateOf<String?>(null) }
    var meta by retain("new-issue-meta") { mutableStateOf(NewIssueMeta()) }
    var editing by remember { mutableStateOf<String?>(null) }
    var confirmTemplate by remember { mutableStateOf<IssueTemplate?>(null) }
    var busy by remember { mutableStateOf(false) }
    val templates = rememberLoader("issue-templates") { GitHub.issueTemplates(owner, name, it) }
    val repo = rememberLoader("repo") { GitHub.repo(owner, name, it) }
    val set = templates.data
    val perms = repo.data?.permissions
    // 读不到仓库信息时不下结论，只给一般说明
    val canSetMeta: Boolean? = perms?.let { it.push || it.maintain || it.admin }

    fun apply(t: IssueTemplate?) {
        title = t?.title.orEmpty()
        body = TextFieldValue(t?.body.orEmpty())
        meta = NewIssueMeta(
            labels = t?.labels.orEmpty().map { MetaOpt(it, it) },
            assignees = t?.assignees.orEmpty().map { MetaOpt(it, it) },
        )
        picked = t?.file ?: BLANK
    }

    val chooser = picked == null && set?.hasChooser == true
    Page(
        if (chooser) tr("选择 Issue 模板") else tr("新建 Issue"),
        subtitle = "$owner/$name",
        actions = {
            if (!chooser && set?.hasChooser == true) MoreMenu(listOf(MenuAction(tr("更换模板")) { lastPicked = picked; picked = null }))
        },
    ) { pad ->
        when {
            // 模板读取失败时不阻塞，直接按空白 Issue 填写
            picked == null && set == null && templates.error == null -> Loading(Modifier.padding(pad).fillMaxSize())
            chooser -> TemplateChooser(set!!, Modifier.padding(pad)) { t ->
                val key = t?.file ?: BLANK
                val dirty = title.isNotBlank() || body.text.isNotBlank()
                when {
                    key == lastPicked -> picked = key
                    dirty -> confirmTemplate = t ?: IssueTemplate(BLANK, "")
                    else -> apply(t)
                }
            }
            else -> Column(Modifier.padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
                val template = set?.templates?.firstOrNull { it.file == picked }
                if (template != null) {
                    Row(Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Oc(R.drawable.oc_file, Gh.c.fgMuted, 14.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(trf("模板：{0}", template.name), fontSize = 13.sp, color = Gh.c.fgMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                GhField(title, { title = it }, tr("标题"), Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(12.dp))
                MarkdownEditor(
                    body, { body = it }, placeholder = tr("描述（支持 Markdown）"), contextRepo = "$owner/$name", minLines = 8,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                DraftBinding(draft, DraftValue(title, body.text))
                Spacer(Modifier.height(12.dp))
                MetaRows(
                    labels = meta.labels.map { it.label to it.color.orEmpty() },
                    assignees = meta.assignees.map { it.label to it.avatar },
                    milestone = meta.milestone?.label,
                ) { editing = it }
                Text(
                    when (canSetMeta) {
                        false -> tr("你没有该仓库的写入权限：GitHub 会静默忽略标签、指派人和里程碑，只创建标题和正文。")
                        true -> tr("标签、指派人和里程碑会在创建时一并设置。")
                        null -> tr("只有对仓库有写入权限时，标签、指派人和里程碑才会生效；否则 GitHub 会静默忽略。")
                    },
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    fontSize = 12.sp, lineHeight = 16.sp, color = if (canSetMeta == false) Gh.c.attention else Gh.c.fgMuted,
                )
                Spacer(Modifier.height(12.dp))
                GhButton(tr("提交"), Modifier.padding(horizontal = 16.dp).fillMaxWidth(), primary = true, enabled = title.isNotBlank() && !busy) {
                    busy = true
                    val submitted = DraftValue(title, body.text)
                    val m = meta
                    draft.update(submitted)
                    entry.act({ busy = false; ctx.toast(it) }) {
                        val i = GitHub.createIssue(
                            owner, name, submitted.title.trim(), submitted.body,
                            labels = m.labels.map { it.key },
                            assignees = m.assignees.map { it.key },
                            milestone = m.milestone?.key?.toIntOrNull(),
                        )
                        draft.submitted(submitted)
                        nav.replace(Screen.IssueDetail(owner, name, i.number, false))
                    }
                }
            }
        }
    }

    when (val k = editing) {
        "labels" -> MetaPicker(k, owner, name, tr("标签"), meta.labels.map { it.key }, { editing = null }) { meta = meta.copy(labels = it) }
        "assignees" -> MetaPicker(k, owner, name, tr("指派人"), meta.assignees.map { it.key }, { editing = null }) { meta = meta.copy(assignees = it) }
        "milestone" -> MetaPicker(k, owner, name, tr("里程碑"), listOfNotNull(meta.milestone?.key), { editing = null }, single = true, noneLabel = tr("无里程碑")) {
            meta = meta.copy(milestone = it.firstOrNull())
        }
        else -> {}
    }
    confirmTemplate?.let { t ->
        GhDialog(tr("替换当前内容？"), { confirmTemplate = null }, confirm = tr("替换"), danger = true, onConfirm = {
            apply(t.takeIf { it.file != BLANK })
            confirmTemplate = null
        }) { Text(trf("使用「{0}」会覆盖已填写的标题、正文和元数据。", t.name.ifEmpty { tr("空白 Issue") }), fontSize = 14.sp, color = Gh.c.fg) }
    }
}

/** 模板选择：各模板、空白 Issue（仓库允许时）和 config.yml 中的外部链接 */
@Composable
private fun TemplateChooser(set: IssueTemplateSet, modifier: Modifier, onPick: (IssueTemplate?) -> Unit) {
    val g = Gh.c
    val ctx = rememberCtx()
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        if (set.templates.isNotEmpty() || set.config.blankIssuesEnabled) {
            Spacer(Modifier.height(8.dp))
            MenuGroup {
                set.templates.forEachIndexed { i, t ->
                    if (i > 0) HDivider()
                    ChooserRow(if (t.form) R.drawable.oc_issue_opened else R.drawable.oc_file, t.name, t.about.ifBlank { null }) { onPick(t) }
                }
                if (set.config.blankIssuesEnabled) {
                    if (set.templates.isNotEmpty()) HDivider()
                    ChooserRow(R.drawable.oc_note, tr("空白 Issue"), tr("不使用模板，从空白开始")) { onPick(null) }
                }
            }
        }
        if (set.templates.any { it.form }) {
            Text(
                tr("表单模板会转换为 Markdown 结构，提交前请按小标题填写。"),
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp), fontSize = 12.sp, lineHeight = 16.sp, color = g.fgMuted,
            )
        }
        if (!set.config.blankIssuesEnabled) {
            Text(
                tr("该仓库已关闭空白 Issue，请选择一个模板。"),
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp), fontSize = 12.sp, lineHeight = 16.sp, color = g.fgMuted,
            )
        }
        if (set.config.contactLinks.isNotEmpty()) {
            SectionTitle(tr("其他途径"))
            MenuGroup {
                set.config.contactLinks.forEachIndexed { i, l ->
                    if (i > 0) HDivider()
                    ChooserRow(R.drawable.oc_link_external, l.name, l.about.ifBlank { l.url }) { ctx.openBrowser(l.url) }
                }
            }
        }
        if (set.templates.isEmpty() && !set.config.blankIssuesEnabled) {
            Text(
                tr("该仓库没有可在应用内使用的模板，请通过上面的链接或网页提交。"),
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp), fontSize = 13.sp, color = g.fgMuted,
            )
        }
    }
}

@Composable
private fun ChooserRow(icon: Int, title: String, desc: String?, onClick: () -> Unit) {
    val g = Gh.c
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MenuIcon(icon, g.fgMuted)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = g.fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (desc != null) Text(desc, fontSize = 12.sp, lineHeight = 16.sp, color = g.fgMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(6.dp))
        Oc(R.drawable.oc_chevron_right, g.fgMuted, 14.dp)
    }
}
