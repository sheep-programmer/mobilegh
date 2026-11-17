package com.mobilegh.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.data.ImportDestinationRequest
import com.mobilegh.data.ImportPhase
import com.mobilegh.data.ImportSource
import com.mobilegh.data.ImportSourceCredentials
import com.mobilegh.data.ImportTask
import com.mobilegh.data.ImportTasks
import com.mobilegh.data.InsecureImportSourceException
import com.mobilegh.data.RepositoryImportCoordinator
import com.mobilegh.data.Session
import com.mobilegh.i18n.tr
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Screen
import com.mobilegh.nav.act
import com.mobilegh.service.AndroidImportUiActions
import com.mobilegh.service.ImportServices
import com.mobilegh.service.ImportUiActions
import com.mobilegh.ui.components.EmptyState
import com.mobilegh.ui.components.GhButton
import com.mobilegh.ui.components.GhDialog
import com.mobilegh.ui.components.GhField
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.SectionTitle
import com.mobilegh.ui.components.SwitchRow
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.components.toast
import com.mobilegh.ui.components.verticalScrollWithBar
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.delay

@Composable
fun ImportRepoScreen(
    tasks: ImportTasks = requireNotNull(ImportServices.tasks),
    actions: ImportUiActions = AndroidImportUiActions,
) {
    val nav = LocalNav.current
    val entry = LocalEntry.current
    val ctx = rememberCtx()
    // 凭据只存于 UI remember，绝不进入 savedInstanceState、Intent 或任务文件。
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var privateSource by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var insecure by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<ImportTask?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val login = Session.login

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            refresh++
        }
    }
    val items = remember(login, refresh) { tasks.list(login ?: "") }

    fun beginImport(allowInsecure: Boolean) {
        val raw = url.trim()
        val source = try {
            ImportSource.parse(raw, allowInsecureHttp = allowInsecure)
        } catch (_: InsecureImportSourceException) {
            insecure = raw
            return
        } catch (e: Exception) {
            ctx.toast(e.message ?: e.javaClass.simpleName)
            return
        }
        val account = login ?: return
        busy = true
        entry.act(onError = { busy = false; ctx.toast(it) }) {
            val task = RepositoryImportCoordinator(tasks).create(
                accountLogin = account,
                source = source,
                destination = ImportDestinationRequest(
                    owner = account,
                    name = source.defaultName,
                    description = "",
                    isPrivate = false,
                ),
                sourceRequiresCredentials = privateSource,
            )
            actions.start(
                ctx,
                task.id,
                if (privateSource) ImportSourceCredentials(username, password) else null,
            )
            busy = false
            refresh++
        }
    }

    insecure?.let {
        GhDialog(
            title = tr("HTTP 连接不安全"),
            onDismiss = { insecure = null },
            confirm = tr("继续导入"),
            onConfirm = {
                insecure = null
                beginImport(allowInsecure = true)
            },
        ) {
            Text(tr("HTTP 会以明文传输 Git 内容和凭据。确定继续吗？"), color = Gh.c.fg, fontSize = 14.sp)
        }
    }

    deleteTarget?.let { target ->
        GhDialog(
            title = tr("删除导入任务？"),
            onDismiss = { deleteTarget = null },
            confirm = tr("删除"),
            danger = true,
            onConfirm = {
                deleteTarget = null
                tasks.delete(target.id)
                refresh++
            },
        )
    }

    Page(tr("导入仓库")) { pad ->
        Column(Modifier.padding(pad).verticalScrollWithBar().padding(vertical = 12.dp)) {
            Text(
                tr("仅迁移 Git 提交历史、分支和标签；不迁移 Issues、Pull Requests、Releases、Wiki、LFS 对象、仓库设置或 Submodule 内容。"),
                Modifier.padding(horizontal = 16.dp),
                fontSize = 12.sp,
                color = Gh.c.fgMuted,
            )
            Spacer(Modifier.height(8.dp))
            GhField(
                url,
                { url = it },
                tr("源 Git URL"),
                Modifier.padding(horizontal = 16.dp),
                labelAbove = true,
                placeholder = "https://github.com/owner/repo.git",
            )
            Spacer(Modifier.height(8.dp))
            GhField(
                username,
                { username = it },
                tr("源用户名"),
                Modifier.padding(horizontal = 16.dp),
                labelAbove = true,
            )
            Spacer(Modifier.height(8.dp))
            GhField(
                password,
                { password = it },
                tr("源密码或访问令牌"),
                Modifier.padding(horizontal = 16.dp),
                labelAbove = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            SwitchRow(tr("私有源仓库"), checked = privateSource) { privateSource = it }
            Spacer(Modifier.height(4.dp))
            GhButton(
                tr("开始导入"),
                Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                primary = true,
                enabled = url.isNotBlank() && !busy,
            ) { beginImport(false) }
            Spacer(Modifier.height(16.dp))
            HDivider()
            SectionTitle(tr("导入任务"))
            if (items.isEmpty()) {
                EmptyState(tr("没有导入任务"), modifier = Modifier.fillMaxWidth())
            } else {
                items.forEach { task ->
                    val active = actions.isActive(task.id)
                    val needsCredentials = task.sourceRequiresCredentials &&
                        !active && task.phase != ImportPhase.Completed
                    ImportTaskRow(
                        task = task,
                        active = active,
                        retryText = if (needsCredentials && password.isNotBlank()) tr("继续导入") else tr("重试"),
                        onCancel = { actions.cancel(ctx, task.id); refresh++ },
                        onDelete = { deleteTarget = task },
                        onOpen = { nav.push(Screen.Repo(task.owner, task.name)) },
                        onRetry = {
                            if (needsCredentials && password.isBlank()) {
                                ctx.toast(tr("请先输入源仓库密码或令牌"))
                            } else {
                                actions.start(
                                    ctx,
                                    task.id,
                                    if (needsCredentials) ImportSourceCredentials(username, password) else null,
                                )
                            }
                        },
                    )
                    HDivider()
                }
            }
        }
    }
}

@Composable
private fun ImportTaskRow(
    task: ImportTask,
    active: Boolean,
    retryText: String,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text("${task.owner}/${task.name}", color = Gh.c.fg, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(task.sourceUrl, color = Gh.c.fgMuted, fontSize = 12.sp)
        Text(phaseLabel(task.phase), color = Gh.c.fgMuted, fontSize = 12.sp)
        task.message?.let { Text(it, color = Gh.c.fgMuted, fontSize = 12.sp) }
        val retryable = !active && task.phase != ImportPhase.Completed
        if (task.sourceRequiresCredentials && retryable) {
            Text(tr("需要重新输入源仓库凭据"), color = Gh.c.fgMuted, fontSize = 12.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                active -> {
                    GhButton(tr("取消导入"), onClick = onCancel)
                }
                task.phase == ImportPhase.Completed -> GhButton(tr("打开仓库"), onClick = onOpen)
                else -> {
                    if (retryable) GhButton(retryText, onClick = onRetry)
                    GhButton(tr("删除任务"), onClick = onDelete)
                }
            }
        }
    }
}

private fun phaseLabel(phase: ImportPhase): String = when (phase) {
    ImportPhase.Creating -> tr("创建目标仓库")
    ImportPhase.CreationUnknown -> tr("目标仓库创建状态未知")
    ImportPhase.Cloning -> tr("正在克隆源仓库")
    ImportPhase.Pushing -> tr("正在推送到目标仓库")
    ImportPhase.UpdatingDefaultBranch -> tr("正在更新默认分支")
    ImportPhase.Completed -> tr("已完成")
    ImportPhase.Failed -> tr("失败")
    ImportPhase.Cancelled -> tr("已取消")
    ImportPhase.Interrupted -> tr("已中断")
}
