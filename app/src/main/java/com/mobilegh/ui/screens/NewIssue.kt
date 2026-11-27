package com.mobilegh.ui.screens

import androidx.activity.compose.BackHandler
import com.mobilegh.ui.components.MdText
import com.mobilegh.ui.components.verticalScrollWithBar
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.key
import com.mobilegh.ui.components.attachmentMarkdown
import com.mobilegh.ui.components.insertAtCursor
import com.mobilegh.ui.components.rememberCommentAttachments
import com.mobilegh.ui.components.removeAttachmentSnippet
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
import androidx.compose.foundation.layout.consumeWindowInsets
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
import com.mobilegh.data.AppLog
import com.mobilegh.data.DraftAnswer
import com.mobilegh.data.DraftController
import com.mobilegh.data.DraftOption
import com.mobilegh.data.DraftValue
import com.mobilegh.data.GitHub
import com.mobilegh.data.IssueFormField
import com.mobilegh.data.IssueTemplate
import com.mobilegh.data.IssueTemplateSet
import com.mobilegh.data.IssueTemplates
import com.mobilegh.data.IssueHierarchy
import com.mobilegh.data.IssueTypes
import com.mobilegh.data.SharedAttachment
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
    val issueType: MetaOpt? = null,
)

internal fun newIssueDraftOrEmpty(
    title: String,
    body: String,
    meta: NewIssueMeta,
    template: IssueTemplate?,
    answers: Map<Int, List<String>>,
): DraftValue {
    if (template == null) {
        val candidate = newIssueDraft(title, body, meta, template = null, answers = emptyMap())
        return if (
            candidate.title.isBlank() && candidate.body.isBlank() &&
            candidate.labels.isEmpty() && candidate.assignees.isEmpty() &&
            candidate.milestone == null && candidate.issueType == null
        ) DraftValue() else candidate
    }
    val fields = template.fields
    val defaultAnswers = if (fields.isNotEmpty()) IssueTemplates.defaultAnswers(fields) else emptyMap()
    val defaultBody = if (fields.isNotEmpty()) IssueTemplates.renderBody(fields, defaultAnswers) else template.body
    val untouched = title == template.title &&
        body == defaultBody &&
        answers == defaultAnswers &&
        meta.milestone == null &&
        meta.issueType == null &&
        meta.labels.map { it.key.lowercase() } == template.labels.map { it.lowercase() } &&
        meta.assignees.map { it.key.lowercase() } == template.assignees.map { it.lowercase() }
    return if (untouched) DraftValue() else newIssueDraft(title, body, meta, template.file, answers)
}

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
    issueType = meta.issueType?.toDraftOption(),
    template = template,
    answers = answers.filterValues { it.any { v -> v.isNotBlank() } }
        .map { DraftAnswer(it.key, it.value) }.sortedBy { it.index },
)

internal fun canSubmitNewIssue(
    title: String,
    busy: Boolean,
    missing: List<IssueFormField>,
    uploadsInFlight: Int,
    sharedUploadsPending: Boolean,
    sharedContentPending: Boolean = false,
): Boolean = title.isNotBlank() && !busy && missing.isEmpty() && uploadsInFlight == 0 && !sharedUploadsPending && !sharedContentPending

/** 从草稿恢复元数据（颜色、头像随草稿一起保存） */
internal fun DraftValue.toNewIssueMeta(): NewIssueMeta = NewIssueMeta(
    labels = labels.map { it.toMetaOpt() },
    assignees = assignees.map { it.toMetaOpt() },
    milestone = milestone?.toMetaOpt(),
    issueType = issueType?.toMetaOpt(),
)

/** 草稿可以恢复并展示元数据，但只有明确有写权限时才进入创建请求及创建后的类型补写。 */
internal fun newIssueSubmissionMeta(meta: NewIssueMeta, canSetMeta: Boolean?): NewIssueMeta =
    if (canSetMeta == true) meta else NewIssueMeta()

private fun MetaOpt.toDraftOption() = DraftOption(key = key, label = label, color = color, avatar = avatar)
private fun DraftOption.toMetaOpt() = MetaOpt(key = key, label = label, avatar = avatar, color = color)

internal fun initialSharedBody(draftBody: String, sharedBody: String): String = when {
    sharedBody.isEmpty() || draftBody == sharedBody || draftBody.endsWith("\n\n$sharedBody") -> draftBody
    draftBody.isEmpty() -> sharedBody
    else -> "$draftBody\n\n$sharedBody"
}

internal fun sharedContentBlockedByYamlForm(
    yamlForm: Boolean,
    sharedText: String,
    sharedAttachmentsPending: Boolean,
): Boolean = yamlForm && (sharedText.isNotEmpty() || sharedAttachmentsPending)

internal fun sharedContentCanBeConsumed(yamlForm: Boolean, templateResolved: Boolean): Boolean =
    templateResolved && !yamlForm

internal fun sharedTemplateBody(templateBody: String, sharedBody: String, yamlForm: Boolean, includeShared: Boolean = true): String =
    if (yamlForm) "" else listOf(templateBody, if (includeShared) sharedBody else "").filter { it.isNotEmpty() }.joinToString("\n\n")

/** REST 创建已成功就立即确认草稿；可选补写或页面取消不能再暴露可重复提交的旧草稿。 */
internal suspend fun <T> createIssueWithDraftAck(
    draft: DraftController,
    submitted: DraftValue,
    create: suspend () -> T,
    afterAcknowledged: suspend (T) -> Unit = {},
): T {
    val created = create()
    draft.submitted(submitted)
    afterAcknowledged(created)
    return created
}

/**
 * 依次上传分享带来的图片；[startIndex] 之前的附件上次已经上传并写进正文，不再重复处理。
 * 每成功一张就通过 [onProgress] 报告下一次的起始下标；失败时异常继续抛出，下标停在没上传成功的那张，
 * 调用方保存它就能从断点重试。返回全部完成后的下标（等于 attachments.size）。
 */
internal suspend fun uploadShared(
    attachments: List<SharedAttachment>,
    startIndex: Int,
    upload: suspend (SharedAttachment) -> String,
    insert: (SharedAttachment, String) -> Unit,
    onProgress: (Int) -> Unit = {},
): Int {
    var index = startIndex.coerceIn(0, attachments.size)
    while (index < attachments.size) {
        val attachment = attachments[index]
        val url = upload(attachment)
        insert(attachment, url)
        index++
        onProgress(index)
    }
    return index
}

/** 必填字段在标签后加星号，与 GitHub 表单一致 */
private fun IssueFormField.requiredLabel(): String = if (required) "$label *" else label

/** 选择器中「空白 Issue」对应的 key */
private const val BLANK = ""

/**
 * 新建 Issue：仓库有 .github/ISSUE_TEMPLATE/ 时先选模板（遵循 config.yml 的 blank_issues_enabled 与 contact_links），
 * 再填写标题、正文以及标签 / 指派人 / 里程碑。
 */
@Composable
fun NewIssueScreen(owner: String, name: String, parentNodeId: String? = null, parentNumber: Int? = null, shareId: String? = null) {
    val nav = LocalNav.current
    val entry = LocalEntry.current
    val ctx = rememberCtx()
    // peek 不删除元数据：进程回收或重进页面后还能继续上传剩下的图片，全部完成后才 finish
    val shared = remember(shareId) { shareId?.let { com.mobilegh.data.PendingIssueShare.peek(ctx, it) } }
    val draft = rememberDraft("new-issue:${owner.lowercase()}/${name.lowercase()}")
    var title by rememberSaveable { mutableStateOf(draft.initial.title) }
    var body by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(initialSharedBody(draft.initial.body, shared?.text.orEmpty()))) }
    // 分享预填默认从空白 Issue 开始；已有草稿仍恢复其原模板。
    var picked by rememberSaveable { mutableStateOf<String?>(if (!draft.initial.empty) (draft.initial.template ?: BLANK) else if (shared != null) BLANK else null) }
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
    var createMore by rememberSaveable { mutableStateOf(false) }
    val templates = rememberLoader("issue-templates") { GitHub.issueTemplates(owner, name, it) }
    val repo = rememberLoader("repo") { GitHub.repo(owner, name, it) }
    val repoLabels = rememberLoader("repo-labels") { GitHub.repoLabels(owner, name) }
    LaunchedEffect(repoLabels.data, meta.labels) {
        val catalog = repoLabels.data.orEmpty()
        if (catalog.isNotEmpty() && meta.labels.any { it.color.isNullOrBlank() }) {
            val byName = catalog.associateBy { it.name.lowercase() }
            val resolved = meta.labels.map { opt ->
                val matched = byName[opt.key.lowercase()]
                if (matched != null) MetaOpt(matched.name, matched.name, avatar = opt.avatar, color = matched.color) else opt
            }
            if (resolved != meta.labels) meta = meta.copy(labels = resolved)
        }
    }
    val template = templates.data?.templates?.firstOrNull { it.file == picked }
    val formFields = template?.fields.orEmpty()
    val isForm = formFields.isNotEmpty()
    val sharedContentConsumable = sharedContentCanBeConsumed(isForm, templateResolved = picked == BLANK || template != null)
    // 正文的附件（图片 / 视频）上传后插入到光标处，和网页版一样
    var uploadsInFlight by remember { mutableStateOf(0) }
    fun uploadStateChanged(uploading: Boolean) {
        uploadsInFlight = (uploadsInFlight + if (uploading) 1 else -1).coerceAtLeast(0)
    }
    val bodyAttach = rememberCommentAttachments(
        repoId = repo.data?.id,
        onUploaded = { url, alt -> body = insertAtCursor(body, attachmentMarkdown(url, alt)) },
        onUploadingChange = ::uploadStateChanged,
    )
    // 已上传的张数（下次从这一张继续）；[sharedUploadAttempt] 由「重试」按钮递增以重新触发上传
    var sharedUploadIndex by rememberSaveable(shareId) { mutableStateOf(0) }
    var sharedUploadAttempt by rememberSaveable(shareId) { mutableStateOf(0) }
    var sharedUploadError by rememberSaveable(shareId) { mutableStateOf<String?>(null) }
    LaunchedEffect(repo.data?.id, shareId, sharedUploadAttempt, sharedContentConsumable) {
        val attachments = shared?.attachments.orEmpty()
        val repoId = repo.data?.id
        if (!sharedContentConsumable || repoId == null || repoId <= 0 || sharedUploadIndex >= attachments.size) return@LaunchedEffect
        entry.act(
            onError = { sharedUploadError = it; ctx.toast(trf("分享图片上传失败：{0}", it)) },
            label = tr("上传分享图片"),
        ) {
            if (com.mobilegh.data.Session.token == null && !com.mobilegh.ui.components.GitHubWebLogin.hasSession()) {
                error(tr("请先登录 GitHub 账号后重试"))
            }
            uploadShared(
                attachments = attachments,
                startIndex = sharedUploadIndex,
                upload = { attachment ->
                    val file = java.io.File(attachment.path)
                    com.mobilegh.data.CommentAttachments.upload(repoId, attachment.name, file.length()) { file.inputStream() }
                },
                insert = { attachment, url -> body = insertAtCursor(body, attachmentMarkdown(url, attachment.name)) },
                onProgress = { sharedUploadIndex = it },
            )
            sharedUploadError = null
            ctx.toast(tr("分享图片已插入"))
        }
    }
    // 分享内容已经全部进入编辑器（纯文本或图片都已处理），可以清理暂存
    LaunchedEffect(shareId, repo.data?.id, sharedUploadIndex, sharedContentConsumable) {
        val id = shareId ?: return@LaunchedEffect
        val share = shared ?: return@LaunchedEffect
        if (sharedContentConsumable && repo.data?.id != null && sharedUploadIndex >= share.attachments.size) {
            com.mobilegh.data.PendingIssueShare.finish(ctx, id)
        }
    }
    val set = templates.data
    val perms = repo.data?.permissions
    // 读不到仓库信息时不下结论，只给一般说明
    val canSetMeta: Boolean? = perms?.let { it.push || it.maintain || it.admin }

    fun apply(t: IssueTemplate?, keepAnswers: Boolean = true, includeShared: Boolean = true) {
        title = t?.title.orEmpty()
        meta = NewIssueMeta(
            labels = t?.labels.orEmpty().map { MetaOpt(it, it) },
            assignees = t?.assignees.orEmpty().map { MetaOpt(it, it) },
        )
        picked = t?.file ?: BLANK
        val fields = t?.fields.orEmpty()
        if (fields.isNotEmpty()) {
            // 换模板时保留同一模板的已填答案，其它模板的下标没有意义
            val kept = if (keepAnswers && t != null && draft.initial.template == t.file) savedAnswers else emptyMap()
            answers = IssueTemplates.defaultAnswers(fields) +
                kept.filterKeys { key -> fields.any { it.index == key } }
            body = TextFieldValue("")
        } else {
            answers = emptyMap()
            body = TextFieldValue(sharedTemplateBody(t?.body.orEmpty(), shared?.text.orEmpty(), yamlForm = false, includeShared = includeShared))
        }
    }

    // 表单不直接编辑 Markdown：提交时按字段生成正文
    val formBody = if (isForm) IssueTemplates.renderBody(formFields, answers) else body.text
    val missing = if (isForm) IssueTemplates.missingRequired(formFields, answers) else emptyList()
    val sharedUploadsPending = sharedUploadIndex < shared?.attachments.orEmpty().size
    val sharedContentPending = sharedContentBlockedByYamlForm(isForm, shared?.text.orEmpty(), sharedUploadsPending)
    val submissionEnabled = canSubmitNewIssue(title, busy, missing, uploadsInFlight, sharedUploadsPending, sharedContentPending)

    /** 提交新建 Issue。「创建更多」时留在页面里清空成同一模板的空白表单，否则打开新 Issue。 */
    fun submit() {
        if (!canSubmitNewIssue(title, busy, missing, uploadsInFlight, sharedUploadsPending, sharedContentPending)) return
        busy = true
        val submitted = newIssueDraft(title, formBody, meta, template?.file, answers)
        val m = newIssueSubmissionMeta(meta, canSetMeta)
        draft.update(submitted)
        entry.act({ busy = false; ctx.toast(it) }, label = tr("创建 Issue")) {
            var typeFailure: Throwable? = null
            var parentFailure: Throwable? = null
            val i = createIssueWithDraftAck(
                draft = draft,
                submitted = submitted,
                create = {
                    GitHub.createIssue(
                        owner, name, submitted.title.trim(), submitted.body,
                        labels = m.labels.map { it.key },
                        assignees = m.assignees.map { it.key },
                        milestone = m.milestone?.key?.toIntOrNull(),
                    )
                },
                afterAcknowledged = { created ->
                    // 类型和父 Issue 只能在拿到 GraphQL Node ID 后补写，失败不能重试整个创建流程。
                    typeFailure = if (created == null || m.issueType == null) null else {
                        val nodeId = created.nodeId
                        if (nodeId.isNullOrBlank()) IllegalStateException(tr("缺少 Issue 节点 ID"))
                        else IssueTypes.setAfterCreation(nodeId, m.issueType.key).exceptionOrNull()
                    }
                    parentFailure = if (created == null || parentNodeId == null) null else {
                        val nodeId = created.nodeId
                        if (nodeId.isNullOrBlank()) IllegalStateException(tr("缺少 Issue 节点 ID"))
                        else IssueHierarchy.addAfterCreation(parentNodeId, nodeId).exceptionOrNull()
                    }
                },
            )
            AppLog.info("issue", trf("已创建 {0}", "$owner/$name" + (i?.let { " #${it.number}" } ?: "")))
            typeFailure?.let { ctx.toast(trf("Issue 已创建，但类型未设置：{0}", it.message ?: tr("操作失败"))) }
            parentFailure?.let { ctx.toast(trf("Issue 已创建，但未关联父 Issue：{0}", it.message ?: tr("操作失败"))) }
            // 新 Issue 会出现在列表里；子 Issue 创建也要刷新仍处于导航栈中的父详情
            nav.refreshRetained { s ->
                s is Screen.Issues && s.owner.equals(owner, true) && s.name.equals(name, true) ||
                    s is Screen.IssueDetail && parentNumber != null && s.number == parentNumber &&
                    s.owner.equals(owner, true) && s.name.equals(name, true)
            }
            if (i == null) {
                // 已创建，只是响应无法解析：提示并回到列表，不能让用户重新提交（会重复创建）
                busy = false
                ctx.toast(tr("Issue 已提交，请到列表查看"))
                nav.pop()
            } else if (createMore) {
                busy = false
                ctx.toast(trf("已创建 {0}", "#${i.number}"))
                apply(template, keepAnswers = false, includeShared = false)
            } else {
                nav.replace(Screen.IssueDetail(owner, name, i.number, false))
            }
        }
    }

    val chooser = picked == null && set?.hasChooser == true
    // 只有明确的离开动作才放弃暂存；重组、配置变化和进程恢复仍需保留分享。
    fun leave() {
        shareId?.let { com.mobilegh.data.PendingIssueShare.finish(ctx, it) }
        nav.pop()
    }
    BackHandler(enabled = shareId != null) { leave() }
    Page(
        if (chooser) tr("选择 Issue 模板") else tr("新建 Issue"),
        // 与网页版标题一致：「仓库: 模板名」
        subtitle = template?.name?.takeIf { it.isNotBlank() }?.let { "$owner/$name: $it" } ?: "$owner/$name",
        onBack = { leave() },
        actions = {
            if (!chooser && set?.hasChooser == true) MoreMenu(listOf(MenuAction(tr("更换模板")) { lastPicked = picked; picked = null }))
        },
        bottomBar = {
            if (!chooser) {
                // 网页版的底部栏：左边「创建更多」，右边「取消」「创建 Issue」
                Column(Modifier.fillMaxWidth().background(Gh.c.header).navigationBarsPadding().imePadding()) {
                    HDivider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CheckRow(tr("创建更多"), createMore, Modifier.weight(1f)) { createMore = !createMore }
                        GhButton(tr("取消")) { leave() }
                        Spacer(Modifier.width(8.dp))
                        GhButton(tr("创建 Issue"), primary = true, enabled = submissionEnabled) { submit() }
                    }
                }
            }
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
            // 底部栏自己已经垫到键盘上方，pad 里含键盘高度：先扣掉 pad 再补剩余部分，键盘弹出时不会多出一大块空白
            else -> Column(Modifier.padding(pad).consumeWindowInsets(pad).imePadding().verticalScrollWithBar().padding(vertical = 16.dp)) {
                parentNumber?.let {
                    Text(trf("将作为父 Issue #{0} 的子 Issue 创建", it), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 13.sp, color = Gh.c.fgMuted)
                    Spacer(Modifier.height(8.dp))
                }
                GhField(title, { title = it }, tr("标题"), Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(12.dp))
                if (isForm) {
                    FormFields(formFields, answers, "$owner/$name", repo.data?.id, Modifier.padding(horizontal = 16.dp), ::uploadStateChanged) { index, values ->
                        answers = if (values.isEmpty()) answers - index else answers + (index to values)
                    }
                } else {
                    MarkdownEditor(
                        body, { body = it }, placeholder = tr("描述（支持 Markdown）"), contextRepo = "$owner/$name", minLines = 8,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        framed = true, onAttach = { bodyAttach.pick() }, uploading = bodyAttach.uploading,
                        attachments = bodyAttach.items,
                        onRemoveAttachment = { att ->
                            bodyAttach.remove(att)
                            body = removeAttachmentSnippet(body, att)
                        },
                    )
                }
                // 分享图片上传中断时留在页面上，点「重试」从失败的那一张继续
                val sharedAttachments = shared?.attachments.orEmpty()
                if (!isForm && sharedUploadError != null && sharedUploadIndex < sharedAttachments.size) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            trf("分享图片上传失败：{0}", sharedUploadError.orEmpty()),
                            Modifier.weight(1f),
                            fontSize = 12.sp, lineHeight = 16.sp, color = Gh.c.attention,
                        )
                        GhButton(tr("重试")) { sharedUploadAttempt++ }
                    }
                }
                if (sharedContentPending) {
                    Text(
                        tr("YAML 表单暂不能接收分享内容。请改用空白 Issue；分享文本和图片会保留。"),
                        Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        fontSize = 12.sp, lineHeight = 16.sp, color = Gh.c.attention,
                    )
                }
                DraftBinding(draft, newIssueDraftOrEmpty(title, formBody, meta, template, answers))
                Spacer(Modifier.height(12.dp))
                MetaRows(
                    labels = meta.labels.map { it.label to it.color.orEmpty() },
                    assignees = meta.assignees.map { it.label to it.avatar },
                    milestone = meta.milestone?.label,
                    issueType = meta.issueType,
                    showIssueType = true,
                    // 明确没有写入权限时不让打开选择器（只读，不显示铅笔）；权限未知时仍可编辑
                    onEdit = if (canSetMeta == false) null else ({ kind: String -> editing = kind }),
                )
                Text(
                    when (canSetMeta) {
                        false -> tr("你没有该仓库的写入权限：只创建标题和正文，不会设置标签、指派人、里程碑或类型。")
                        true -> tr("标签、指派人、里程碑和类型会在创建时一并设置。")
                        null -> tr("只有对仓库有写入权限时，标签、指派人、里程碑和类型才会生效。")
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
            }
        }
    }

    when (val k = editing) {
        "labels" -> MetaPicker(k, owner, name, tr("标签"), meta.labels.map { it.key }, { editing = null }) { meta = meta.copy(labels = it) }
        "assignees" -> MetaPicker(k, owner, name, tr("指派人"), meta.assignees.map { it.key }, { editing = null }) { meta = meta.copy(assignees = it) }
        "milestone" -> MetaPicker(k, owner, name, tr("里程碑"), listOfNotNull(meta.milestone?.key), { editing = null }, single = true, noneLabel = tr("无里程碑")) {
            meta = meta.copy(milestone = it.firstOrNull())
        }
        "issueType" -> MetaPicker(k, owner, name, tr("类型"), listOfNotNull(meta.issueType?.key), { editing = null }, single = true, noneLabel = tr("无类型")) {
            meta = meta.copy(issueType = it.firstOrNull())
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

/** 模板选择：与网页版一致，一个列表里依次是模板、空白 Issue（仓库允许时）和 config.yml 的外部链接。模板用箭头，外部链接用外链图标。 */
@Composable
private fun TemplateChooser(set: IssueTemplateSet, modifier: Modifier, onPick: (IssueTemplate?) -> Unit) {
    val g = Gh.c
    val ctx = rememberCtx()
    val rows = buildList {
        set.templates.forEach { t -> add(ChooserRow(false, t.name, t.about.ifBlank { null }) { onPick(t) }) }
        if (set.config.blankIssuesEnabled) add(ChooserRow(false, tr("空白 Issue"), tr("不使用模板，从空白开始")) { onPick(null) })
        set.config.contactLinks.forEach { l -> add(ChooserRow(true, l.name, l.about.ifBlank { l.url }) { ctx.openBrowser(l.url) }) }
    }
    // 与网页版一致：一个带描边的圆角列表，上方是「模板与表单」分组标题；外链与模板在同一列表里
    Column(modifier.fillMaxSize().verticalScrollWithBar().padding(horizontal = 16.dp, vertical = 16.dp)) {
        if (rows.isEmpty()) {
            Text(tr("该仓库没有可在应用内使用的模板，请通过网页提交。"), fontSize = 14.sp, color = g.fgMuted)
        } else {
            Text(tr("模板与表单"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = g.fg)
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, g.border, RoundedCornerShape(6.dp))
                    .background(g.canvas),
            ) {
                rows.forEachIndexed { i, row ->
                    if (i > 0) HDivider(Modifier.padding(start = 16.dp))
                    ChooserItem(row)
                }
            }
        }
    }
}

/** 模板选择页的一行：外部链接为 true 时用外链图标，否则用箭头 */
private class ChooserRow(val external: Boolean, val title: String, val desc: String?, val onClick: () -> Unit)

@Composable
private fun ChooserItem(row: ChooserRow) {
    val g = Gh.c
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = row.onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = g.fg, lineHeight = 20.sp)
            row.desc?.let {
                Spacer(Modifier.height(2.dp))
                MdText(it, fontSize = 13.sp, lineHeight = 18.sp, color = g.fgMuted, maxLines = 3)
            }
        }
        Spacer(Modifier.width(12.dp))
        Oc(if (row.external) R.drawable.oc_link_external else R.drawable.oc_chevron_right, g.fgMuted, 16.dp)
    }
}

// ---------- YAML Issue 表单 ----------

/** 按字段类型渲染表单：markdown 说明块用 GitHub 渲染，文本类直接输入，选项类点选 */
@Composable
private fun FormFields(
    fields: List<IssueFormField>,
    answers: Map<Int, List<String>>,
    contextRepo: String?,
    repoId: Long?,
    modifier: Modifier = Modifier,
    onUploadingChange: (Boolean) -> Unit,
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
                "input" -> Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                    FieldLabel(f)
                    OutlinedTextField(
                        value = answer.firstOrNull().orEmpty(),
                        onValueChange = { onAnswer(f.index, listOf(it)) },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        singleLine = true,
                        shape = RoundedCornerShape(6.dp),
                        placeholder = f.placeholder.takeIf { it.isNotBlank() }?.let { { Text(it, color = g.fgMuted, fontSize = 14.sp) } },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                    )
                }
                // key：每个多行字段各自持有附件状态，字段顺序变化时不串用
                "textarea" -> key(f.index) {
                    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                        FieldLabel(f)
                        // 自己持有 TextFieldValue，避免每次重组把光标挪到开头
                        var field by remember(f.index, f.value) { mutableStateOf(TextFieldValue(answer.joinToString("\n"))) }
                        LaunchedEffect(answer) {
                            val text = answer.joinToString("\n")
                            if (field.text != text) field = TextFieldValue(text, TextRange(text.length))
                        }
                        // 附件插入到这个字段的光标处（与网页版表单一致）
                        val attach = rememberCommentAttachments(
                            repoId = repoId,
                            onUploaded = { url, alt ->
                                field = insertAtCursor(field, attachmentMarkdown(url, alt))
                                onAnswer(f.index, listOf(field.text))
                            },
                            onUploadingChange = onUploadingChange,
                        )
                        MarkdownEditor(
                            field, { field = it; onAnswer(f.index, listOf(it.text)) },
                            contextRepo = contextRepo, minLines = 5,
                            placeholder = f.placeholder.ifBlank { tr("支持 Markdown") },
                            modifier = Modifier.padding(top = 6.dp),
                            framed = true, onAttach = { attach.pick() }, uploading = attach.uploading,
                            attachments = attach.items,
                            onRemoveAttachment = { att ->
                                attach.remove(att)
                                field = removeAttachmentSnippet(field, att)
                                onAnswer(f.index, listOf(field.text))
                            },
                        )
                    }
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
        Text(f.label, fontSize = 14.sp, color = g.fg, fontWeight = FontWeight.SemiBold)
        if (f.required) Text(" *", fontSize = 14.sp, color = g.danger, fontWeight = FontWeight.SemiBold)
    }
    if (f.description.isNotBlank()) {
        // 说明里的 [文字](链接) 按 Markdown 显示，与网页版一致
        MdText(f.description, Modifier.padding(top = 2.dp), fontSize = 13.sp, lineHeight = 18.sp, color = g.fgMuted)
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
        Column(Modifier.heightIn(max = 420.dp).verticalScrollWithBar()) {
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
