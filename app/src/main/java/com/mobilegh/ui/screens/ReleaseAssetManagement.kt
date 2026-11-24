package com.mobilegh.ui.screens

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.Asset
import com.mobilegh.data.RepoGovernance
import com.mobilegh.data.releaseAssetNameConflicts
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.Entry
import com.mobilegh.nav.act
import com.mobilegh.ui.components.Card
import com.mobilegh.ui.components.GhButton
import com.mobilegh.ui.components.GhDialog
import com.mobilegh.ui.components.GhField
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.InlineError
import com.mobilegh.ui.components.MenuAction
import com.mobilegh.ui.components.MoreMenu
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.components.SectionTitle
import com.mobilegh.ui.components.TypeToConfirmDialog
import com.mobilegh.ui.components.fmtSize
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.components.toast
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf
import com.mobilegh.ui.theme.Gh
import java.io.IOException

@Composable
fun ReleaseAssetManagementBlock(
    owner: String,
    name: String,
    releaseId: Long,
    assets: List<Asset>,
    canUpload: Boolean,
    onUploaded: (Asset) -> Unit,
    onUpdated: (Asset) -> Unit,
    onDeleted: (Long) -> Unit,
) {
    val g = Gh.c
    val ctx = rememberCtx()
    val resolver = ctx.contentResolver
    val entry = LocalEntry.current
    var progress by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Asset?>(null) }
    var deleting by remember { mutableStateOf<Asset?>(null) }
    var uploadError by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        uploadError = null
        entry.act({ progress = null; uploadError = it }) {
            val selected = uris.map { describeReleaseDocument(resolver, it) }
            val conflicts = releaseAssetNameConflicts(assets, selected.map { it.fileName })
            require(conflicts.isEmpty()) { trf("附件名称已存在：{0}", conflicts.joinToString()) }
            selected.forEachIndexed { i, document ->
                val fileName = document.fileName
                val size = document.size
                val uri = document.uri
                progress = trf("正在上传 {0}（{1}/{2}）", fileName, i + 1, selected.size)
                val mime = resolver.getType(uri) ?: "application/octet-stream"
                val asset = RepoGovernance.uploadReleaseAsset(owner, name, releaseId, fileName, mime, size) {
                    resolver.openInputStream(uri) ?: throw IOException(tr("无法读取所选文件"))
                }
                onUploaded(asset)
            }
            progress = null
            ctx.toast(tr("附件已上传"))
        }
    }
    SectionTitle(trf("附件（{0}）", assets.size)) {
        if (canUpload) {
            GhButton(tr("添加附件"), enabled = progress == null) { pick.launch(arrayOf("*/*")) }
        }
    }
    progress?.let { Text(it, Modifier.padding(horizontal = 16.dp), fontSize = 12.sp, color = g.accent) }
    uploadError?.let { InlineError(it, Modifier.padding(horizontal = 16.dp)) }
    Card(Modifier.padding(horizontal = 16.dp)) {
        if (assets.isEmpty()) {
            Text(tr("还没有附件"), Modifier.padding(14.dp), fontSize = 13.sp, color = g.fgMuted)
        } else {
            Column {
                assets.forEachIndexed { i, asset ->
                    if (i > 0) HDivider()
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(asset.name, fontSize = 14.sp, color = g.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(fmtSize(asset.size), fontSize = 12.sp, color = g.fgMuted)
                        }
                        if (canUpload) {
                            MoreMenu(
                                listOf(
                                    MenuAction(tr("重命名或修改说明")) { editing = asset },
                                    MenuAction(tr("删除附件"), danger = true) { deleting = asset },
                                ),
                                g.fgMuted,
                            )
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    editing?.let { asset ->
        ReleaseAssetEditDialog(
            owner, name, assets, asset, entry,
            onDismiss = { editing = null },
            onUpdated = { onUpdated(it); editing = null },
        )
    }
    deleting?.let { asset ->
        ReleaseAssetDeleteDialog(
            owner, name, asset, entry,
            onDismiss = { deleting = null },
            onDeleted = { onDeleted(asset.id); deleting = null },
        )
    }
}

private data class ReleaseDocument(val fileName: String, val size: Long, val uri: Uri)

private fun describeReleaseDocument(resolver: ContentResolver, uri: Uri): ReleaseDocument {
    var displayName: String? = null
    var size = -1L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            displayName = cursor.getString(0)
            if (!cursor.isNull(1)) size = cursor.getLong(1)
        }
    }
    if (size < 0) size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    require(size >= 0) { tr("无法读取文件大小") }
    val fileName = displayName?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')
        ?: "asset"
    return ReleaseDocument(fileName, size, uri)
}

@Composable
private fun ReleaseAssetEditDialog(
    owner: String,
    name: String,
    assets: List<Asset>,
    asset: Asset,
    entry: Entry,
    onDismiss: () -> Unit,
    onUpdated: (Asset) -> Unit,
) {
    var fileName by remember(asset.id) { mutableStateOf(asset.name) }
    var label by remember(asset.id) { mutableStateOf(asset.label.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun submit() {
        val cleanName = fileName.trim()
        if (cleanName.isEmpty()) {
            error = tr("附件名称不能为空")
            return
        }
        val conflicts = releaseAssetNameConflicts(assets, listOf(cleanName), asset.id)
        if (conflicts.isNotEmpty()) {
            error = trf("附件名称已存在：{0}", conflicts.joinToString())
            return
        }
        busy = true
        error = null
        entry.act({ busy = false; error = it }, label = tr("修改附件")) {
            val updated = RepoGovernance.updateReleaseAsset(owner, name, asset.id, cleanName, label.trim().ifEmpty { null })
            busy = false
            onUpdated(updated)
        }
    }
    GhDialog(
        tr("修改附件"), onDismiss = { if (!busy) onDismiss() },
        confirm = if (busy) tr("保存中…") else tr("保存"), confirmEnabled = !busy,
        dismissEnabled = !busy, onConfirm = ::submit,
    ) {
        Column {
            GhField(fileName, { fileName = it }, tr("文件名"))
            Spacer(Modifier.height(8.dp))
            GhField(label, { label = it }, tr("附件说明"), placeholder = tr("可选"))
            error?.let { InlineError(it, retry = ::submit) }
        }
    }
}

@Composable
private fun ReleaseAssetDeleteDialog(
    owner: String,
    name: String,
    asset: Asset,
    entry: Entry,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit,
) {
    val g = Gh.c
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun submit() {
        busy = true
        error = null
        entry.act({ busy = false; error = it }, label = tr("删除附件")) {
            RepoGovernance.deleteReleaseAsset(owner, name, asset.id)
            busy = false
            onDeleted()
        }
    }
    TypeToConfirmDialog(
        title = tr("删除附件"), expected = asset.name,
        hint = tr("输入下面的文件名以确认："), fieldLabel = tr("文件名"), confirm = tr("删除"),
        busy = busy, onDismiss = onDismiss, onConfirm = ::submit,
    ) {
        Column {
            Text(trf("确定删除 {0} 吗？此操作无法恢复。", asset.name), fontSize = 14.sp, color = g.fg)
            error?.let { InlineError(it, retry = ::submit) }
        }
    }
}
