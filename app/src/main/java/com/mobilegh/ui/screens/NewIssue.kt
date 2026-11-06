package com.mobilegh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.DraftAnswer
import com.mobilegh.data.DraftOption
import com.mobilegh.data.DraftValue
import com.mobilegh.data.GitHub
import com.mobilegh.data.IssueFormField
import com.mobilegh.data.IssueTemplate
import com.mobilegh.data.IssueTemplateSet
import com.mobilegh.data.IssueTemplates
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Screen
import com.mobilegh.nav.act
import com.mobilegh.nav.rememberLoader
import com.mobilegh.nav.refreshRetained
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
import com.mobilegh.ui.components.MarkdownPreview
import com.mobilegh.ui.components.MenuIcon
import com.mobilegh.ui.components.MoreMenu
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.components.RadioRow
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
internal data class NewIssueMeta(
    val labels: List<MetaOpt> = emptyList(),
    val assignees: List<MetaOpt> = emptyList(),
    val milestone: MetaOpt? = null,
)

/** 标题、正文与元数据组成的草稿：既是持久化内容，也用于判断是否有未提交的输入 */
internal fun newIssueDraft(
    title: String,
    body: String,
    meta: NewIssueMeta,
    template: String? = null,
    answers: Map<Int, List<String>> = emptyMap(),
): DraftValue = DraftValue(
    title = title,
    body = body,
    labels = meta.labels.map { it.toDraftOption() },
    assignees = meta.assignees.map { it.toDraftOption() },
    milestone = meta.milestone?.toDraftOption(),
    template = template,
    answers = answers.filterValues { it.any { v -> v.isNotBlank() } }
        .map { DraftAnswer(it.key, it.value) }.sortedBy { it.index },
)

/** 从草稿恢复元数据（颜色、头像随草稿一起保存） */
internal fun DraftValue.toNewIssueMeta(): NewIssueMeta = NewIssueMeta(
    labels = labels.map { it.toMetaOpt() },
    assignees = assignees.map { it.toMetaOpt() },
    milestone = milestone?.toMetaOpt(),
)

private fun MetaOpt.toDraftOption() = DraftOption(key = key, label = label, color = color, avatar = avatar)
private fun DraftOption.toMetaOpt() = MetaOpt(key = key, label = label, avatar = avatar, color = color)

/** 必填字段在标签后加星号，与 GitHub 表单一致 */
private fun IssueFormField.requiredLabel(): String = if (required) "$label *" else label

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
    // 有未提交的草稿时直接回到编辑，不再弹模板选择；表单草稿还要带回模板
    var picked by rememberSaveable { mutableStateOf<String?>(if (!draft.initial.empty) (draft.initial.template ?: BLANK) else null) }
    // YAML 表单的答案：键是字段在 body 列表中的下标
    val savedAnswers = remember(draft) { draft.initial.answers.associate { it.index to it.values } }
    var answers by retain("new-issue-answers") { mutableStateOf(savedAnswers) }
    // 「更换模板」前所用的模板，选回同一个时保留已填写的内容
    var lastPicked by rememberSaveable { mutableStateOf<String?>(null) }
    // 元数据随草稿保存：页面重开或进程被回收后仍能恢复标签、指派人与里程碑
    var meta by retain("new-issue-meta") { mutableStateOf(draft.initial.toNewIssueMeta()) }
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
        meta = NewIssueMeta(
            labels = t?.labels.orEmpty().map { MetaOpt(it, it) },
            assignees = t?.assignees.orEmpty().map { MetaOpt(it, it) },
        )
        picked = t?.file ?: BLANK
        val fields = t?.fields.orEmpty()
        if (fields.isNotEmpty()) {
            // 换模板时保留同一模板的已填答案，其它模板的下标没有意义
            val kept = if (t != null && draft.initial.template == t.file) savedAnswers else emptyMap()
            answers = IssueTemplates.defaultAnswers(fields) +
                kept.filterKeys { key -> fields.any { it.index == key } }
            body = TextFieldValue("")
        } else {
            answers = emptyMap()
            body = TextFieldValue(t?.body.orEmpty())
        }
    }

    val template = set?.templates?.firstOrNull { it.file == picked }
    val formFields = template?.fields.orEmpty()
    val isForm = formFields.isNotEmpty()
    // 表单不直接编辑 Markdown：提交时按字段生成正文
    val formBody = if (isForm) IssueTemplates.renderBody(formFields, answers) else body.text
    val missing = if (isForm) IssueTemplates.missingRequired(formFields, answers) else emptyList()

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
            chooser -> TemplateChooser(set, Modifier.padding(pad)) { t ->
                val key = t?.file ?: BLANK
                // 标题、正文或任一元数据有内容都算已填写，套用模板前需要确认
                val dirty = !newIssueDraft(title, formBody, meta).empty
                when {
                    key == lastPicked -> picked = key
                    dirty -> confirmTemplate = t ?: IssueTemplate(BLANK, "")
                    else -> apply(t)
                }
            }
            else -> Column(Modifier.padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
                if (template != null) {
                    Row(Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Oc(R.drawable.oc_file, Gh.c.fgMuted, 14.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(trf("模板：{0}", template.name), fontSize = 13.sp, color = Gh.c.fgMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                GhField(title, { title = it }, tr("标题"), Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(12.dp))
                if (isForm) {
                    FormFields(formFields, answers, "$owner/$name", Modifier.padding(horizontal = 16.dp)) { index, values ->
                        answers = if (values.isEmpty()) answers - index else answers + (index to values)
                    }
                } else {
                    MarkdownEditor(
                        body, { body = it }, placeholder = tr("描述（支持 Markdown）"), contextRepo = "$owner/$name", minLines = 8,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                DraftBinding(draft, newIssueDraft(title, formBody, meta, template?.file, answers))
                Spacer(Modifier.height(12.dp))
                MetaRows(
                    labels = meta.labels.map { it.label to it.color.orEmpty() },
                    assignees = meta.assignees.map { it.label to it.avatar },
                    milestone = meta.milestone?.label,
                    // 明确没有写入权限时不让打开选择器（只读，不显示铅笔）；权限未知时仍可编辑
                    onEdit = if (canSetMeta == false) null else ({ kind: String -> editing = kind }),
                )
                Text(
                    when (canSetMeta) {
                        false -> tr("你没有该仓库的写入权限：GitHub 会静默忽略标签、指派人和里程碑，只创建标题和正文。")
                        true -> tr("标签、指派人和里程碑会在创建时一并设置。")
                        null -> tr("只有对仓库有写入权限时，标签、指派人和里程碑才会生效；否则 GitHub 会静默忽略。")
                    },
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    fontSize = 12.sp, lineHeight = 16.sp, color = if (canSetMeta == false) Gh.c.attention else Gh.c.fgMuted,
                )
                if (missing.isNotEmpty()) {
                    Text(
                        trf("请填写：{0}", missing.joinToString("、") { it.label }),
                        Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                        fontSize = 12.sp, lineHeight = 16.sp, color = Gh.c.attention,
                    )
                }
                Spacer(Modifier.height(12.dp))
                GhButton(
                    tr("提交"), Modifier.padding(horizontal = 16.dp).fillMaxWidth(), primary = true,
                    enabled = title.isNotBlank() && !busy && missing.isEmpty(),
                ) {
                    busy = true
                    val submitted = newIssueDraft(title, formBody, meta, template?.file, answers)
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
                        // 新 Issue 会出现在列表里：已打开的列表页要刷新
                        nav.refreshRetained { s -> s is Screen.Issues && s.owner.equals(owner, true) && s.name.equals(name, true) }
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
                tr("表单模板会显示为可填写的字段，提交时自动整理成 Issue 正文。"),
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

// ---------- YAML Issue 表单 ----------

/** 按字段类型渲染表单：markdown 说明块用 GitHub 渲染，文本类直接输入，选项类点选 */
@Composable
private fun FormFields(
    fields: List<IssueFormField>,
    answers: Map<Int, List<String>>,
    contextRepo: String?,
    modifier: Modifier = Modifier,
    onAnswer: (Int, List<String>) -> Unit,
) {
    val g = Gh.c
    Column(modifier.fillMaxWidth()) {
        fields.forEach { f ->
            val answer = answers[f.index].orEmpty()
            when (f.type) {
                "markdown" -> if (f.value.isNotBlank()) {
                    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) { MarkdownPreview(f.value, contextRepo) }
                }
                "input" -> GhField(
                    value = answer.firstOrNull().orEmpty(),
                    onValueChange = { onAnswer(f.index, listOf(it)) },
                    label = f.requiredLabel(),
                    labelAbove = true,
                    description = f.description.ifBlank { null },
                    placeholder = f.placeholder.ifBlank { null },
                    modifier = Modifier.padding(bottom = 14.dp),
                )
                "textarea" -> Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                    FieldLabel(f)
                    // 自己持有 TextFieldValue，避免每次重组把光标挪到开头
                    var field by remember(f.index, f.value) { mutableStateOf(TextFieldValue(answer.joinToString("\n"))) }
                    LaunchedEffect(answer) {
                        val text = answer.joinToString("\n")
                        if (field.text != text) field = TextFieldValue(text, TextRange(text.length))
                    }
                    MarkdownEditor(
                        field, { field = it; onAnswer(f.index, listOf(it.text)) },
                        contextRepo = contextRepo, minLines = 5,
                        placeholder = f.placeholder.ifBlank { tr("支持 Markdown") },
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                "dropdown" -> Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                    FieldLabel(f)
                    DropdownAnswer(f, answer, Modifier.padding(top = 6.dp)) { onAnswer(f.index, it) }
                }
                "checkboxes" -> Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                    FieldLabel(f)
                    f.options.forEach { o ->
                        val checked = o.label in answer
                        CheckRow(o.label, checked, Modifier.padding(top = 4.dp)) {
                            onAnswer(f.index, if (checked) answer - o.label else answer + o.label)
                        }
                    }
                }
            }
        }
        if (fields.none { it.type != "markdown" }) {
            Text(tr("该模板没有需要填写的字段。"), fontSize = 12.sp, color = g.fgMuted)
        }
    }
}

@Composable
private fun FieldLabel(f: IssueFormField) {
    val g = Gh.c
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(f.label, fontSize = 13.sp, color = g.fg, fontWeight = FontWeight.SemiBold)
        if (f.required) Text(" *", fontSize = 13.sp, color = g.danger)
    }
    if (f.description.isNotBlank()) {
        Text(f.description, Modifier.padding(top = 2.dp), fontSize = 12.sp, lineHeight = 16.sp, color = g.fgMuted)
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    val g = Gh.c
    Row(
        modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked, null,
            colors = CheckboxDefaults.colors(checkedColor = g.accent, uncheckedColor = g.fgMuted, checkmarkColor = g.canvas),
        )
        Text(label, Modifier.padding(start = 6.dp), fontSize = 14.sp, color = g.fg)
    }
}

@Composable
private fun DropdownAnswer(field: IssueFormField, answer: List<String>, modifier: Modifier = Modifier, onChange: (List<String>) -> Unit) {
    val g = Gh.c
    var open by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(g.canvasSubtle)
            .border(1.dp, g.border, RoundedCornerShape(6.dp)).clickable { open = true }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            answer.joinToString(", ").ifBlank { tr("请选择") },
            Modifier.weight(1f), fontSize = 14.sp,
            color = if (answer.isEmpty()) g.fgMuted else g.fg,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Oc(R.drawable.oc_chevron_down, g.fgMuted, 14.dp)
    }
    if (!open) return
    GhDialog(
        field.label,
        onDismiss = { open = false },
        confirm = tr("完成"),
        onConfirm = if (field.multiple) ({ open = false }) else null,
    ) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            field.options.forEach { o ->
                if (field.multiple) {
                    val checked = o.label in answer
                    CheckRow(o.label, checked) { onChange(if (checked) answer - o.label else answer + o.label) }
                } else {
                    RadioRow(o.label in answer, o.label) {
                        onChange(listOf(o.label))
                        open = false
                    }
                }
            }
        }
    }
}
