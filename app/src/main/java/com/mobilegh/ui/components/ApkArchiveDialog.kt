package com.mobilegh.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.data.AppLog
import com.mobilegh.data.ArtifactApks
import com.mobilegh.data.DownloadTask
import com.mobilegh.data.Downloads
import com.mobilegh.i18n.tr
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal object ApkArchiveDialogState {
    var task by mutableStateOf<DownloadTask?>(null)
}

@Composable
internal fun ApkArchiveDialogHost() {
    val task = ApkArchiveDialogState.task ?: return
    key(task.key) { ApkArchiveDialog(task) }
}

@Composable
private fun ApkArchiveDialog(task: DownloadTask) {
    val context = rememberCtx()
    val scope = rememberCoroutineScope()
    var choices by remember { mutableStateOf<List<ArtifactApks.Entry>?>(null) }
    var selected by remember { mutableStateOf<ArtifactApks.Entry?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(task) {
        try {
            choices = Downloads.artifactApks(task)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            AppLog.warn("download", "APK archive: ${failure.javaClass.simpleName}")
            error = tr("无法读取产物 ZIP，请重新下载后再试")
        }
    }
    GhDialog(
        title = tr("从 Actions 产物安装 APK"),
        onDismiss = { ApkArchiveDialogState.task = null },
        confirm = tr("交给系统安装"),
        confirmEnabled = selected != null && !preparing,
        onConfirm = {
            val entry = selected
            if (entry != null && Downloads.canInstallApk(context)) {
                preparing = true
                error = null
                scope.launch {
                    try {
                        val apk = Downloads.prepareArtifactApk(task, entry)
                        ApkArchiveDialogState.task = null
                        Downloads.installApk(context, apk)
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: Exception) {
                        AppLog.warn("download", "APK extraction: ${failure.javaClass.simpleName}")
                        error = tr("无法提取有效的 APK，请检查产物")
                    } finally {
                        preparing = false
                    }
                }
            }
        },
    ) {
        Column {
            val entries = choices
            error?.let { InlineError(it) }
            when {
                preparing -> Loading(Modifier.fillMaxWidth().heightIn(min = 72.dp))
                entries == null && error == null -> Loading(Modifier.fillMaxWidth().heightIn(min = 72.dp))
                entries.isNullOrEmpty() && error == null -> Text(tr("此产物没有可安装的 APK"))
                !entries.isNullOrEmpty() -> {
                    Text(tr("仅安装你信任的项目生成的 APK。"), color = Gh.c.fgMuted, fontSize = 13.sp)
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                        items(entries, key = { it.path }) { entry ->
                            Row(Modifier.fillMaxWidth().clickable { selected = entry }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected == entry, onClick = { selected = entry })
                                Column(Modifier.weight(1f)) {
                                    Text(entry.path, color = Gh.c.fg, fontSize = 13.sp)
                                    Text(fmtSize(entry.size), color = Gh.c.fgMuted, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
