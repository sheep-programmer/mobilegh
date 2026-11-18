package com.mobilegh.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.ActionSecret
import com.mobilegh.data.ActionVariable
import com.mobilegh.data.GitHub
import com.mobilegh.data.LazySodiumActionSecretSealer
import com.mobilegh.data.Session
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf
import com.mobilegh.nav.Loader
import com.mobilegh.nav.friendly
import com.mobilegh.nav.rememberLoader
import com.mobilegh.ui.components.EmptyState
import com.mobilegh.ui.components.GhDialog
import com.mobilegh.ui.components.GhField
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.InlineError
import com.mobilegh.ui.components.Loading
import com.mobilegh.ui.components.OcButton
import com.mobilegh.ui.components.SectionActionChip
import com.mobilegh.ui.components.SectionTitle
import com.mobilegh.ui.components.copy
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private data class ActionWriteAccount(val login: String?, val generation: Int)

private fun currentActionWriteAccount() = ActionWriteAccount(Session.login, Session.generation)

private fun ActionWriteAccount.requireCurrent() {
    check(Session.login == login && Session.generation == generation && Session.token != null) {
        tr("账号已切换，请重新打开编辑器")
    }
}

private data class SecretEditorTarget(val original: ActionSecret?)
private data class VariableEditorTarget(val original: ActionVariable?)

@Composable
internal fun SecretsSettings(owner: String, name: String, organizationOwned: Boolean) {
    val repository = rememberLoader("actions-secrets") { GitHub.actionSecrets(owner, name, it) }
    var editor by remember { mutableStateOf<SecretEditorTarget?>(null) }
    var deleting by remember { mutableStateOf<ActionSecret?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionTitle(tr("仓库 Secrets"), trailing = {
            SectionActionChip(tr("新建 Secret"), onClick = { editor = SecretEditorTarget(null) })
        })
        SettingsList(
            loader = repository,
            emptyText = tr("暂无 Secrets"),
        ) { secrets ->
            secrets.forEach { secret ->
                SecretRow(
                    secret = secret,
                    onOverwrite = { editor = SecretEditorTarget(secret) },
                    onDelete = { deleting = secret },
                )
            }
        }

        if (organizationOwned) {
            val organization = rememberLoader("actions-organization-secrets") { GitHub.actionOrganizationSecrets(owner, name, it) }
            SectionTitle(tr("组织 Secrets"))
            SettingsList(
                loader = organization,
                emptyText = tr("暂无组织 Secrets"),
            ) { secrets ->
                secrets.forEach { secret -> SecretRow(secret = secret) }
            }
        }
    }

    editor?.let { target ->
        SecretEditorDialog(
            owner = owner,
            name = name,
            target = target,
            onDismiss = { editor = null },
            onSaved = { repository.load(true) },
        )
    }
    deleting?.let { secret ->
        DeleteActionDialog(
            title = tr("删除 Secret？"),
            itemName = secret.name,
            onDismiss = { deleting = null },
            onDelete = { GitHub.deleteActionSecret(owner, name, secret.name) },
            onDeleted = {
                repository.accept(repository.data.orEmpty().filterNot { it.name == secret.name })
            },
        )
    }
}

@Composable
private fun SecretRow(
    secret: ActionSecret,
    onOverwrite: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(secret.name, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            ActionMetadata(secret.createdAt, secret.updatedAt)
        }
        if (onOverwrite != null) {
            OcButton(
                R.drawable.oc_pencil,
                onOverwrite,
                Gh.c.fgMuted,
                description = trf("覆盖 Secret {0}", secret.name),
            )
        }
        if (onDelete != null) {
            OcButton(
                R.drawable.oc_trash,
                onDelete,
                Gh.c.danger,
                description = trf("删除 Secret {0}", secret.name),
            )
        }
    }
    HDivider()
}

@Composable
private fun SecretEditorDialog(
    owner: String,
    name: String,
    target: SecretEditorTarget,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val original = target.original
    val account = remember(target) { currentActionWriteAccount() }
    val sealer = remember { LazySodiumActionSecretSealer() }
    val scope = rememberCoroutineScope()
    var secretName by remember(target) { mutableStateOf(original?.name.orEmpty()) }
    // Deliberately not rememberSaveable: a secret value must never enter saved instance state.
    var value by remember(target) { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun dismiss() {
        value = ""
        secretName = ""
        onDismiss()
    }

    GhDialog(
        title = tr(if (original == null) "新建 Secret" else "覆盖 Secret"),
        onDismiss = ::dismiss,
        confirm = tr(if (saving) "保存中…" else "保存"),
        confirmEnabled = !saving && secretName.isNotEmpty() && value.isNotEmpty(),
        dismissEnabled = !saving,
        dismissOnClickOutside = !saving,
        onConfirm = {
            saving = true
            error = null
            scope.launch {
                try {
                    account.requireCurrent()
                    val publicKey = GitHub.actionSecretPublicKey(owner, name)
                    account.requireCurrent()
                    val plaintext = value.toByteArray(Charsets.UTF_8)
                    val encrypted = try {
                        sealer.seal(plaintext, publicKey.key)
                    } finally {
                        plaintext.fill(0)
                    }
                    account.requireCurrent()
                    GitHub.putActionSecret(owner, name, secretName, encrypted, publicKey.keyId)
                    account.requireCurrent()
                    onSaved()
                    dismiss()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e.friendly()
                } finally {
                    saving = false
                }
            }
        },
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (original == null) {
                GhField(secretName, { secretName = it }, tr("Secret 名称"), enabled = !saving)
            }
            GhField(
                value,
                { value = it },
                tr("Secret 值"),
                enabled = !saving,
                singleLine = false,
                minLines = 3,
                visualTransformation = PasswordVisualTransformation(),
            )
            Text(tr("Secret 值只会写入，无法再次读取"), color = Gh.c.fgMuted, fontSize = 12.sp)
            error?.let { Text(it, color = Gh.c.danger, fontSize = 12.sp) }
        }
    }
}

@Composable
internal fun VariablesSettings(owner: String, name: String) {
    val variables = rememberLoader("actions-variables") { GitHub.actionVariables(owner, name, it) }
    val context = LocalContext.current
    var editor by remember { mutableStateOf<VariableEditorTarget?>(null) }
    var deleting by remember { mutableStateOf<ActionVariable?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionTitle("Variables", trailing = {
            SectionActionChip(tr("新建 Variable"), onClick = { editor = VariableEditorTarget(null) })
        })
        SettingsList(
            loader = variables,
            emptyText = tr("暂无 Variables"),
        ) { items ->
            items.forEach { variable ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(variable.name, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text(variable.value, color = Gh.c.fg, fontSize = 13.sp)
                        ActionMetadata(variable.createdAt, variable.updatedAt)
                    }
                    OcButton(
                        R.drawable.oc_copy,
                        { context.copy(variable.value) },
                        Gh.c.fgMuted,
                        description = trf("复制 Variable {0}", variable.name),
                    )
                    OcButton(
                        R.drawable.oc_pencil,
                        { editor = VariableEditorTarget(variable) },
                        Gh.c.fgMuted,
                        description = trf("编辑 Variable {0}", variable.name),
                    )
                    OcButton(
                        R.drawable.oc_trash,
                        { deleting = variable },
                        Gh.c.danger,
                        description = trf("删除 Variable {0}", variable.name),
                    )
                }
                HDivider()
            }
        }
    }

    editor?.let { target ->
        VariableEditorDialog(
            owner = owner,
            name = name,
            target = target,
            onDismiss = { editor = null },
            onSaved = { updated ->
                val original = target.original
                val current = variables.data.orEmpty()
                variables.accept(
                    if (original == null) {
                        current + updated
                    } else {
                        current.map { if (it.name == original.name) updated else it }
                    },
                )
            },
        )
    }
    deleting?.let { variable ->
        DeleteActionDialog(
            title = tr("删除 Variable？"),
            itemName = variable.name,
            onDismiss = { deleting = null },
            onDelete = { GitHub.deleteActionVariable(owner, name, variable.name) },
            onDeleted = {
                variables.accept(variables.data.orEmpty().filterNot { it.name == variable.name })
            },
        )
    }
}

@Composable
private fun VariableEditorDialog(
    owner: String,
    name: String,
    target: VariableEditorTarget,
    onDismiss: () -> Unit,
    onSaved: (ActionVariable) -> Unit,
) {
    val original = target.original
    val account = remember(target) { currentActionWriteAccount() }
    val scope = rememberCoroutineScope()
    var variableName by remember(target) { mutableStateOf(original?.name.orEmpty()) }
    var value by remember(target) { mutableStateOf(original?.value.orEmpty()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    GhDialog(
        title = tr(if (original == null) "新建 Variable" else "编辑 Variable"),
        onDismiss = onDismiss,
        confirm = tr(if (saving) "保存中…" else "保存"),
        confirmEnabled = !saving && variableName.isNotEmpty(),
        dismissEnabled = !saving,
        dismissOnClickOutside = !saving,
        onConfirm = {
            saving = true
            error = null
            scope.launch {
                try {
                    account.requireCurrent()
                    val updated = if (original == null) {
                        GitHub.createActionVariable(owner, name, variableName, value)
                    } else {
                        val renamed = variableName.takeIf { it != original.name }
                        val changedValue = value.takeIf { it != original.value }
                        if (renamed != null || changedValue != null) {
                            GitHub.updateActionVariable(owner, name, original.name, renamed, changedValue)
                        }
                        original.copy(name = variableName, value = value)
                    }
                    account.requireCurrent()
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
        },
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GhField(variableName, { variableName = it }, tr("Variable 名称"), enabled = !saving)
            GhField(
                value,
                { value = it },
                tr("Variable 值"),
                enabled = !saving,
                singleLine = false,
                minLines = 3,
            )
            error?.let { Text(it, color = Gh.c.danger, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun DeleteActionDialog(
    title: String,
    itemName: String,
    onDismiss: () -> Unit,
    onDelete: suspend () -> Unit,
    onDeleted: () -> Unit,
) {
    val account = remember { currentActionWriteAccount() }
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    GhDialog(
        title = title,
        onDismiss = onDismiss,
        confirm = tr(if (deleting) "删除中…" else "删除"),
        danger = true,
        confirmEnabled = !deleting,
        dismissEnabled = !deleting,
        dismissOnClickOutside = !deleting,
        onConfirm = {
            deleting = true
            error = null
            scope.launch {
                try {
                    account.requireCurrent()
                    onDelete()
                    account.requireCurrent()
                    onDeleted()
                    onDismiss()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e.friendly()
                } finally {
                    deleting = false
                }
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(trf("确定删除 {0}？此操作无法撤销。", itemName), color = Gh.c.fgMuted)
            error?.let { Text(it, color = Gh.c.danger, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun <T> SettingsList(
    loader: Loader<List<T>>,
    emptyText: String,
    content: @Composable (List<T>) -> Unit,
) {
    val data = loader.data
    when {
        data != null -> if (data.isEmpty()) {
            EmptyState(emptyText, modifier = Modifier.fillMaxWidth())
        } else {
            content(data)
        }
        loader.error != null -> InlineError(loader.error!!, Modifier.padding(horizontal = 16.dp)) {
            loader.load(true)
        }
        else -> Loading(Modifier.fillMaxWidth().height(72.dp))
    }
}

@Composable
private fun ActionMetadata(createdAt: String?, updatedAt: String?) {
    val metadata = listOfNotNull(createdAt, updatedAt).joinToString(" · ")
    if (metadata.isNotEmpty()) {
        Text(metadata, color = Gh.c.fgMuted, fontSize = 12.sp)
    }
}
