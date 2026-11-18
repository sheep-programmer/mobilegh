package com.mobilegh.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.data.ActionAccess
import com.mobilegh.data.ActionArtifactRetention
import com.mobilegh.data.ActionForkApproval
import com.mobilegh.data.ActionPermissions
import com.mobilegh.data.ActionPrivateForks
import com.mobilegh.data.ActionSelectedActions
import com.mobilegh.data.ActionWorkflowPermissions
import com.mobilegh.data.GitHub
import com.mobilegh.data.Repo
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf
import com.mobilegh.nav.ActionsSettingsSection
import com.mobilegh.nav.Loader
import com.mobilegh.nav.friendly
import com.mobilegh.nav.rememberLoader
import com.mobilegh.ui.components.Dropdown
import com.mobilegh.ui.components.GhDialog
import com.mobilegh.ui.components.GhField
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.InlineError
import com.mobilegh.ui.components.Loading
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.SwitchRow
import com.mobilegh.ui.components.SectionActionChip
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ActionsSettingsScreen(
    owner: String,
    name: String,
    initialSection: ActionsSettingsSection = ActionsSettingsSection.General,
) {
    var section by rememberSaveable(owner, name) { mutableStateOf(initialSection) }
    val sectionState = rememberSaveableStateHolder()
    val repo = rememberLoader("actions-settings-repo") { GitHub.repo(owner, name, it) }
    val repository = repo.data
    val sections = ActionsSettingsSection.entries
    Page(tr("Actions 设置"), subtitle = "$owner/$name") { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            when {
                repo.error != null && repo.data == null -> InlineError(repo.error!!) { repo.load(true) }
                repository == null -> Loading()
                repository.permissions?.admin != true || repository.archived -> Text(
                    tr("需要仓库管理员权限，且仓库不能处于归档状态。"),
                    Modifier.padding(16.dp),
                    color = Gh.c.fgMuted,
                )
                else -> {
                    Dropdown(
                        label = section.title(),
                        options = sections.map { it.title() },
                        onSelect = { section = sections[it] },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    sectionState.SaveableStateProvider(section.name) {
                        when (section) {
                            ActionsSettingsSection.General -> GeneralSettings(owner, name, repository)
                            ActionsSettingsSection.Secrets -> SecretsSettings(owner, name, repository.owner.type == "Organization")
                            ActionsSettingsSection.Variables -> VariablesSettings(owner, name)
                            ActionsSettingsSection.Runners -> RunnersSettings(owner, name)
                            ActionsSettingsSection.Caches -> CachesSettings(owner, name)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GeneralSettings(owner: String, name: String, repo: Repo) {
    val permissions = rememberLoader("actions-permissions") { GitHub.actionPermissions(owner, name, it) }
    val retention = rememberLoader("actions-retention") { GitHub.actionArtifactRetention(owner, name, it) }
    val workflow = rememberLoader("actions-workflow") { GitHub.actionWorkflowPermissions(owner, name, it) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PermissionsSetting(owner, name, permissions)
        if (permissions.data?.let { it.enabled && it.allowedActions == "selected" } == true) {
            val selected = rememberLoader("actions-selected") { GitHub.actionSelectedActions(owner, name, it) }
            SelectedActionsSetting(owner, name, selected, repo.isPrivate)
        }
        RetentionSetting(owner, name, retention)
        if (repo.isPrivate) {
            PrivateAccessSetting(owner, name, repo.owner.type == "Organization")
            PrivateForksSetting(owner, name)
        } else {
            val forkApproval = rememberLoader("actions-fork-approval") { GitHub.actionForkApproval(owner, name, it) }
            ForkApprovalSetting(owner, name, forkApproval)
        }
        WorkflowSetting(owner, name, workflow)
    }
}

@Composable
private fun PermissionsSetting(owner: String, name: String, loader: Loader<ActionPermissions>) {
    var editing by remember { mutableStateOf(false) }
    ActionSettingItem(tr("Actions 权限"), loader, tr("编辑 Actions"), { editing = true }) {
        Text(if (it.enabled) it.allowedActions else tr("已禁用"), color = Gh.c.fgMuted)
    }
    loader.data?.let { original ->
        if (editing) {
            var enabled by remember(original) { mutableStateOf(original.enabled) }
            var allowed by remember(original) { mutableStateOf(original.allowedActions) }
            var shaPinning by remember(original) { mutableStateOf(original.shaPinningRequired) }
            ActionEditorDialog(
                title = tr("Actions 权限"),
                onDismiss = { editing = false },
                onSave = {
                    GitHub.updateActionPermissions(owner, name, enabled, allowed, shaPinning)
                    original.copy(enabled = enabled, allowedActions = allowed, shaPinningRequired = shaPinning)
                },
                onSaved = loader::accept,
            ) { saving ->
                SwitchRow(tr("启用 Actions"), checked = enabled, enabled = !saving) { enabled = it }
                Dropdown(allowed, listOf("all", "local_only", "selected"), { allowed = listOf("all", "local_only", "selected")[it] }, enabled = !saving)
                SwitchRow(tr("要求 SHA 固定"), checked = shaPinning, enabled = !saving) { shaPinning = it }
            }
        }
    }
}

@Composable
private fun RetentionSetting(owner: String, name: String, loader: Loader<ActionArtifactRetention>) {
    var editing by remember { mutableStateOf(false) }
    ActionSettingItem(tr("产物和日志保留"), loader, tr("编辑保留"), { editing = true }) {
        Text(trf("保留 {0} 天（上限 {1} 天）", it.days, it.maximumAllowedDays), color = Gh.c.fgMuted)
    }
    loader.data?.let { original ->
        if (editing) {
            var days by remember(original) { mutableStateOf(original.days.toString()) }
            ActionEditorDialog(
                title = tr("产物和日志保留"),
                onDismiss = { editing = false },
                validate = {
                    val value = days.toIntOrNull()
                    when {
                        value == null || value <= 0 -> tr("请输入正整数天数")
                        value > original.maximumAllowedDays -> trf("不能超过 {0} 天", original.maximumAllowedDays)
                        else -> null
                    }
                },
                onSave = {
                    val value = days.toInt()
                    GitHub.updateActionArtifactRetention(owner, name, value)
                    original.copy(days = value)
                },
                onSaved = loader::accept,
            ) { saving ->
                GhField(days, { days = it }, tr("保留天数"), enabled = !saving)
            }
        }
    }
}

@Composable
private fun ForkApprovalSetting(owner: String, name: String, loader: Loader<ActionForkApproval>) {
    var editing by remember { mutableStateOf(false) }
    ActionSettingItem(tr("Fork PR 审批"), loader, tr("编辑 Fork 审批"), { editing = true }) {
        Text(it.approvalPolicy, color = Gh.c.fgMuted)
    }
    loader.data?.let { original ->
        if (editing) {
            val policies = listOf(
                "first_time_contributors_new_to_github",
                "first_time_contributors",
                "all_external_contributors",
            )
            var policy by remember(original) { mutableStateOf(original.approvalPolicy) }
            ActionEditorDialog(
                title = tr("Fork PR 审批"),
                onDismiss = { editing = false },
                onSave = {
                    GitHub.updateActionForkApproval(owner, name, policy)
                    original.copy(approvalPolicy = policy)
                },
                onSaved = loader::accept,
            ) { saving ->
                Dropdown(policy, policies, { policy = policies[it] }, enabled = !saving)
            }
        }
    }
}

@Composable
private fun PrivateAccessSetting(owner: String, name: String, organization: Boolean) {
    val loader = rememberLoader("actions-access") { GitHub.actionAccess(owner, name, it) }
    var editing by remember { mutableStateOf(false) }
    ActionSettingItem(tr("私有仓库 Workflow 访问"), loader, tr("编辑访问"), { editing = true }) {
        Text(it.accessLevel, color = Gh.c.fgMuted)
    }
    loader.data?.let { original ->
        if (editing) {
            val levels = listOf("none", if (organization) "organization" else "user")
            var level by remember(original) { mutableStateOf(original.accessLevel) }
            ActionEditorDialog(
                title = tr("私有仓库 Workflow 访问"),
                onDismiss = { editing = false },
                onSave = {
                    GitHub.updateActionAccess(owner, name, level)
                    original.copy(accessLevel = level)
                },
                onSaved = loader::accept,
            ) { saving ->
                Dropdown(level, levels, { level = levels[it] }, enabled = !saving)
            }
        }
    }
}

@Composable
private fun PrivateForksSetting(owner: String, name: String) {
    val loader = rememberLoader("actions-private-forks") { GitHub.actionPrivateForks(owner, name, it) }
    var editing by remember { mutableStateOf(false) }
    ActionSettingItem(tr("私有 Fork Workflows"), loader, tr("编辑私有 Fork"), { editing = true }) {
        Text(tr(if (it.run) "允许 Fork Pull Request 运行 Workflow" else "禁止 Fork Pull Request 运行 Workflow"), color = Gh.c.fgMuted)
    }
    loader.data?.let { original ->
        if (editing) {
            var run by remember(original) { mutableStateOf(original.run) }
            var writeTokens by remember(original) { mutableStateOf(original.writeTokens) }
            var secrets by remember(original) { mutableStateOf(original.secrets) }
            var approval by remember(original) { mutableStateOf(original.approval) }
            ActionEditorDialog(
                title = tr("私有 Fork Workflows"),
                onDismiss = { editing = false },
                onSave = {
                    val updated = original.copy(run = run, writeTokens = writeTokens, secrets = secrets, approval = approval)
                    GitHub.updateActionPrivateForks(owner, name, updated)
                    updated
                },
                onSaved = loader::accept,
            ) { saving ->
                SwitchRow(tr("允许 Fork Pull Request 运行 Workflow"), checked = run, enabled = !saving) { run = it }
                SwitchRow(tr("向 Fork Workflow 发送写入令牌"), checked = writeTokens, enabled = !saving) { writeTokens = it }
                SwitchRow(tr("向 Fork Workflow 发送 Secrets 和 Variables"), checked = secrets, enabled = !saving) { secrets = it }
                SwitchRow(tr("要求审批 Fork PR Workflows"), checked = approval, enabled = !saving) { approval = it }
            }
        }
    }
}

@Composable
private fun SelectedActionsSetting(
    owner: String,
    name: String,
    loader: Loader<ActionSelectedActions>,
    isPrivate: Boolean,
) {
    var editing by remember { mutableStateOf(false) }
    ActionSettingItem(tr("允许的 Actions"), loader, tr("编辑允许范围"), { editing = true }) {
        Column {
            if (!isPrivate && it.patternsAllowed.isNotEmpty()) {
                Text(it.patternsAllowed.joinToString("\n"), color = Gh.c.fgMuted)
            }
            Text(trf("GitHub 创建：{0}；Verified：{1}", tr(if (it.githubOwnedAllowed) "允许" else "禁止"), tr(if (it.verifiedAllowed) "允许" else "禁止")), color = Gh.c.fgMuted, fontSize = 12.sp)
        }
    }
    loader.data?.let { original ->
        if (editing) {
            var githubOwned by remember(original) { mutableStateOf(original.githubOwnedAllowed) }
            var verified by remember(original) { mutableStateOf(original.verifiedAllowed) }
            var patterns by remember(original) { mutableStateOf(original.patternsAllowed.joinToString("\n")) }
            ActionEditorDialog(
                title = tr("允许的 Actions"),
                onDismiss = { editing = false },
                onSave = {
                    val values = if (isPrivate) null else patterns.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    GitHub.updateActionSelectedActions(owner, name, githubOwned, verified, values)
                    original.copy(githubOwnedAllowed = githubOwned, verifiedAllowed = verified, patternsAllowed = values.orEmpty())
                },
                onSaved = loader::accept,
            ) { saving ->
                SwitchRow(tr("允许 GitHub 创建的 Actions"), checked = githubOwned, enabled = !saving) { githubOwned = it }
                SwitchRow(tr("允许 Verified 创建者的 Actions"), checked = verified, enabled = !saving) { verified = it }
                if (!isPrivate) {
                    GhField(patterns, { patterns = it }, tr("Actions 模式（每行一个）"), singleLine = false, minLines = 3, enabled = !saving)
                }
            }
        }
    }
}

@Composable
private fun WorkflowSetting(owner: String, name: String, loader: Loader<ActionWorkflowPermissions>) {
    var editing by remember { mutableStateOf(false) }
    ActionSettingItem(tr("Workflow 权限"), loader, tr("编辑 Workflow"), { editing = true }) {
        Text(if (it.defaultWorkflowPermissions == "read") tr("读取") else tr("读取和写入"), color = Gh.c.fgMuted)
    }
    loader.data?.let { original ->
        if (editing) {
            var permission by remember(original) { mutableStateOf(original.defaultWorkflowPermissions) }
            var canApprove by remember(original) { mutableStateOf(original.canApprovePullRequestReviews) }
            ActionEditorDialog(
                title = tr("Workflow 权限"),
                onDismiss = { editing = false },
                onSave = {
                    GitHub.updateActionWorkflowPermissions(owner, name, permission, canApprove)
                    original.copy(defaultWorkflowPermissions = permission, canApprovePullRequestReviews = canApprove)
                },
                onSaved = loader::accept,
            ) { saving ->
                Dropdown(permission, listOf("read", "write"), { permission = listOf("read", "write")[it] }, enabled = !saving)
                SwitchRow(tr("允许 Workflow 审批 Pull Request"), checked = canApprove, enabled = !saving) { canApprove = it }
            }
        }
    }
}

@Composable
private fun <T> ActionEditorDialog(
    title: String,
    onDismiss: () -> Unit,
    onSave: suspend () -> T,
    onSaved: (T) -> Unit,
    validate: () -> String? = { null },
    content: @Composable (saving: Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    GhDialog(
        title = title,
        onDismiss = onDismiss,
        confirm = tr(if (saving) "保存中…" else "保存"),
        confirmEnabled = !saving,
        dismissEnabled = !saving,
        dismissOnClickOutside = !saving,
        onConfirm = {
            val validationError = validate()
            if (validationError != null) {
                error = validationError
            } else {
                saving = true
                error = null
                scope.launch {
                    try {
                        val updated = onSave()
                        onSaved(updated)
                        onDismiss()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.friendly()
                    } finally {
                        saving = false
                    }
                }
            }
        },
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content(saving)
            error?.let { Text(it, color = Gh.c.danger, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun <T> ActionSettingItem(
    title: String,
    loader: Loader<T>,
    editLabel: String? = null,
    onEdit: (() -> Unit)? = null,
    content: @Composable (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        FlowRow(
            Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, Modifier.align(Alignment.CenterVertically), fontSize = 14.sp)
            if (editLabel != null && onEdit != null && loader.data != null) {
                SectionActionChip(editLabel, Modifier.align(Alignment.CenterVertically), onClick = onEdit)
            }
        }
        val data = loader.data
        when {
            data != null -> content(data)
            loader.error != null -> InlineError(loader.error!!) { loader.load(true) }
            else -> Loading(Modifier.fillMaxWidth().height(40.dp))
        }
    }
    HDivider()
}

internal fun ActionsSettingsSection.title(): String = when (this) {
    ActionsSettingsSection.General -> tr("通用权限")
    ActionsSettingsSection.Secrets -> "Secrets"
    ActionsSettingsSection.Variables -> "Variables"
    ActionsSettingsSection.Runners -> "Runners"
    ActionsSettingsSection.Caches -> "Caches"
}
