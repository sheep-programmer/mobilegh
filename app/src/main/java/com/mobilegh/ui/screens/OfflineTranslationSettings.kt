package com.mobilegh.ui.screens

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import com.mobilegh.R
import com.mobilegh.i18n.trf
import com.mobilegh.ui.components.Oc
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties
import com.mobilegh.data.Downloads
import com.mobilegh.data.OfflineTranslation
import com.mobilegh.data.Session
import com.mobilegh.i18n.tr
import com.mobilegh.ui.components.GhDialog
import com.mobilegh.ui.components.GhButton
import com.mobilegh.ui.components.SwitchRow
import com.mobilegh.ui.components.dialogFrame
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OfflineTranslationSettings() {
    val ctx = rememberCtx()
    val scope = rememberCoroutineScope()
    var prompt by rememberSaveable { mutableStateOf(false) }
    var installing by rememberSaveable { mutableStateOf(false) }
    var progress by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var languages by remember { mutableStateOf<List<String>>(emptyList()) }
    var language by rememberSaveable { mutableStateOf<String?>(null) }
    var modelPicker by remember { mutableStateOf(false) }
    // 已下载的语言；null 表示读不到（旧版翻译组件不支持这个查询），不能显示成「一个都没有」
    var downloaded by remember { mutableStateOf<List<String>?>(null) }
    var downloadedLoaded by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(Session.translateDocuments, refresh) {
        if (!Session.translateDocuments) return@LaunchedEffect
        downloaded = runCatching { OfflineTranslation.downloaded() }.getOrNull()
        downloadedLoaded = true
    }

    fun cancel() {
        job?.cancel()
        job = null
        progress = null
    }

    fun run(message: String, work: suspend () -> Unit) {
        if (job?.isActive == true) return
        error = null
        progress = message
        job = scope.launch {
            try {
                work()
                refresh++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                com.mobilegh.data.AppLog.warn("translation", failure.javaClass.simpleName + ": " + failure.message.orEmpty())
                error = failure.message?.takeIf { it == tr("当前版本尚未提供离线翻译组件") }
                    ?: tr("下载或校验失败，请检查网络和存储空间后重试")
            } finally {
                if (job === currentCoroutineContext()[Job]) progress = null
            }
        }
    }
    fun enable() = run(tr("正在下载并校验中英互译模型…")) { Session.enableDocumentTranslation() }
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        installing = false
        if (result.resultCode == Activity.RESULT_OK && OfflineTranslation.installed()) enable()
        else error = tr("翻译组件未安装，离线翻译仍关闭")
    }
    SwitchRow(
        tr("离线翻译"),
        tr("下载并校验模型后开启。正文在设备上翻译，可从页面菜单切换原文和译文"),
        Session.translateDocuments,
        enabled = progress == null && !installing,
    ) { enabled ->
        if (enabled) prompt = true else Session.disableDocumentTranslation()
    }
    if (Session.translateDocuments) {
        DownloadedLanguages(downloaded, downloadedLoaded)
        com.mobilegh.ui.components.MenuRow(com.mobilegh.R.drawable.oc_download, tr("下载其他翻译语言")) {
            run(tr("正在读取语言列表…")) {
                languages = OfflineTranslation.languages().filterNot { it == "zh" }
                modelPicker = true
            }
        }
    }
    if (prompt) GhDialog(
        tr("下载离线翻译模型"), onDismiss = { prompt = false }, confirm = tr("下载"),
        onConfirm = {
            prompt = false
            if (OfflineTranslation.installed()) enable()
            else if (Downloads.canInstallApk(ctx)) run(tr("正在下载并校验翻译组件…")) {
                val intent = OfflineTranslation.installerIntent()
                installing = true
                try { installer.launch(intent) } catch (failure: Exception) { installing = false; throw failure }
            }
        },
    ) {
        Text(tr("首次使用需安装独立翻译组件并联网下载中英互译模型。下载和校验通过后，离线翻译会自动开启。"))
    }
    if (modelPicker) {
        val names = languages.map { code ->
            val name = OfflineTranslation.languageName(code)
            if (code in downloaded.orEmpty()) trf("{0} · 已下载", name) else name
        }
        GhDialog(tr("下载其他翻译语言"), onDismiss = { modelPicker = false }, confirm = tr("下载"),
            confirmEnabled = language in languages,
            onConfirm = {
                modelPicker = false
                val selected = checkNotNull(language)
                run(tr("正在下载并校验翻译模型…")) { OfflineTranslation.prepare(selected, download = true) }
            },
        ) {
            val index = languages.indexOf(language)
            com.mobilegh.ui.components.Dropdown(names.getOrNull(index) ?: tr("选择语言"), names, { language = languages[it] })
        }
    }
    progress?.let { message ->
        AlertDialog(
            onDismissRequest = ::cancel,
            properties = DialogProperties(dismissOnClickOutside = false),
            containerColor = Gh.c.canvas,
            titleContentColor = Gh.c.fg,
            textContentColor = Gh.c.fg,
            modifier = Modifier.dialogFrame(),
            title = { Text(tr("离线翻译")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(message)
                    LinearProgressIndicator()
                }
            },
            confirmButton = { GhButton(tr("取消"), onClick = ::cancel) },
        )
    }
    error?.let { message ->
        GhDialog(tr("离线翻译"), onDismiss = { error = null }) { Text(message) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadedLanguages(downloaded: List<String>?, loaded: Boolean) {
    val g = Gh.c
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(shape).background(g.canvasSubtle)
            .border(1.dp, g.borderMuted, shape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(tr("已下载的语言"), color = g.fg, fontSize = 14.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LanguageChip(trf("{0} · 内置", OfflineTranslation.languageName("en")), ready = true)
            downloaded?.forEach { code ->
                LanguageChip(OfflineTranslation.languageName(code), ready = true)
            }
        }
        when {
            !loaded -> Unit
            downloaded == null -> Text(tr("更新翻译组件后，可在这里查看已下载的语言"), color = g.fgMuted, fontSize = 12.sp, lineHeight = 18.sp)
            downloaded.isEmpty() -> Text(tr("还没有下载语言模型"), color = g.fgMuted, fontSize = 12.sp, lineHeight = 18.sp)
            else -> Text(tr("英语已内置；其他语言之间会经英语中转翻译"), color = g.fgMuted, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun LanguageChip(label: String, ready: Boolean) {
    val g = Gh.c
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.clip(shape).border(1.dp, if (ready) g.success.copy(alpha = 0.5f) else g.border, shape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Oc(R.drawable.oc_check, if (ready) g.success else g.fgMuted, 14.dp)
        Spacer(Modifier.width(6.dp))
        Text(label, color = g.fg, fontSize = 13.sp)
    }
}
