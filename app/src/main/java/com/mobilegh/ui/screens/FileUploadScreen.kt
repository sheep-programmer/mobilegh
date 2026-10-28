package com.mobilegh.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
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
import com.mobilegh.data.GitUpload
import com.mobilegh.data.RepoWrite
import com.mobilegh.data.Session
import com.mobilegh.data.UploadItem
import com.mobilegh.data.UploadStage
import com.mobilegh.data.UploadProgress
import com.mobilegh.data.UploadSource
import com.mobilegh.data.WriteTarget
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Screen
import com.mobilegh.nav.guarded
import com.mobilegh.nav.rememberLoader
import com.mobilegh.nav.retain
import com.mobilegh.ui.components.Card
import com.mobilegh.ui.components.EmptyState
import com.mobilegh.ui.components.ErrorState
import com.mobilegh.ui.components.GhButton
import com.mobilegh.ui.components.GhDialog
import com.mobilegh.ui.components.GhField
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.InlineError
import com.mobilegh.ui.components.Loading
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.components.OcButton
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.RadioRow
import com.mobilegh.ui.components.SwitchRow
import com.mobilegh.ui.components.fmtSize
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.components.toast
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 上传页的本机状态。文件清单可能有几千项，只挂在页面条目上（不进 saved state）；
 * [UploadState.raw] 是用户实际选中的内容，界面上显示的清单由 [GitUpload.triage] 派生，
 * 移除一项后原本被判定「重复」的那一项会重新可用。
 */
private class UploadState {
    val raw = mutableStateListOf<UploadItem>()
    var version by mutableIntStateOf(0)
    val items = mutableStateListOf<UploadItem>()
    var dropped by mutableStateOf(0)
    var note by mutableStateOf<String?>(null)
    var progress by mutableStateOf<UploadProgress?>(null)
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var reading by mutableStateOf(false)
    var job: Job? = null
    /** 已经生成的提交：分支没跟上时用它告诉用户内容在哪，绝不假装「失败 = 什么都没发生」 */
    var commit by mutableStateOf<String?>(null)
    var doneMsg by mutableStateOf<String?>(null)
    var confirmClear by mutableStateOf(false)
    var confirmLeave by mutableStateOf(false)
    /** null = 跟随 ref：分支可以直接提交，标签/提交只能新建分支 */
    var useNewBranch by mutableStateOf<Boolean?>(null)

    val usable get() = GitUpload.usable(items)
    val skipped get() = items.size - usable.size
    val bytes get() = usable.sumOf { it.size }

    fun add(list: List<UploadItem>) {
        if (list.isEmpty()) return
        raw.addAll(list)
        sync()
    }

    fun remove(item: UploadItem) {
        val idx = raw.indexOfFirst { it.uri == item.uri && it.path == item.path }
        if (idx >= 0) raw.removeAt(idx)
        sync()
    }

    fun clearAll() {
        raw.clear()
        sync()
    }

    fun sync() {
        val r = GitUpload.triage(raw.toList())
        items.clear()
        items.addAll(r.items)
        dropped = r.dropped
        version++
    }

    /** 用户输入过提交信息就别覆盖它 */
    fun suggestedMessage(current: String) = if (current.isNotBlank()) current else GitUpload.defaultMessage(items)

    /** 底部一行：文件数 + 合计大小 + 被排除的数量 */
    fun lineSummary(): String {
        val size = if (usable.isEmpty()) "" else " · " + fmtSize(bytes)
        return "${usable.size} 个文件$size" + (if (skipped > 0) " · $skipped 项不上传" else "")
    }
}

/**
 * 上传文件（夹）：文件对象用 git 自己的传输（packfile）推进仓库，再用 Data API 在基准树上
 * 生成新树与**一个**提交，最后快进分支，等同于电脑上的 `git add` + `commit` + `push`。
 *
 * 文件不限数量，也可以直接选文件夹，文件夹会连同里面的层级一起上传；
 * 上限就是 GitHub 仓库的单文件硬上限 100 MiB（实测 101 MiB 会被 GH001 拒），
 * 超出的项会留在列表里并写明原因，不会偷偷进提交。不拿 REST 写接口当上限：
 * 那三条路径在 40 MiB 就拒收，会把本来能传的文件拦住。
 */
@Composable
fun UploadFilesScreen(owner: String, name: String, path: String, ref: String?) {
    val nav = LocalNav.current
    val ctx = rememberCtx()
    val entry = LocalEntry.current
    val g = Gh.c
    val target = rememberLoader("upload-target") { WriteTarget.load(owner, name, ref) }
    val branches = rememberLoader("upload-branches") { GitHub.branches(owner, name, 1, it).map { b -> b.name } }
    val st = retain("upload") { UploadState() }
    val t = target.data

    var summary by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var branchInput by rememberSaveable { mutableStateOf("") }
    var openPr by rememberSaveable { mutableStateOf(true) }

    // 空仓库：没有提交可当基准，也没有既有分支可提，就把选中的内容直接建成默认分支的第一个提交；
    // 也不能挑新分支去建 PR（目标分支还不存在），所以这里固定不新建分支
    val emptyRepo = t?.emptyRepo == true
    val newBranch = !emptyRepo && (st.useNewBranch ?: (t?.branch == null))
    // 新建分支时留空就用和网页一致的默认名（<login>-patch-1）
    val suggestedBranch = RepoWrite.patchBranch(Session.login, branches.data ?: emptyList())
    val branch = if (newBranch) branchInput.trim().ifBlank { suggestedBranch } else t?.baseRef.orEmpty()
    val branchTaken = newBranch && branches.data?.any { it.equals(branch, ignoreCase = true) } == true
    val canSubmit = t?.canPush == true && st.usable.isNotEmpty() && summary.isNotBlank() && !st.busy && !st.reading &&
        (!newBranch || (RepoWrite.validBranch(branch) && !branchTaken && branch.isNotEmpty()))
    LaunchedEffect(st.version) { summary = st.suggestedMessage(summary) }
    // 系统返回键不经过 Page 的 onBack，必须自己拦下：选好的清单和进行中的上传不能一划就没
    androidx.activity.compose.BackHandler(enabled = st.busy || st.raw.isNotEmpty()) { st.confirmLeave = true }

    /** 选择文件与选择文件夹共用一套：相对路径拼到当前目录下面，非法路径由 triage 去掉 */
    fun accept(group: UploadSource.Group) {
        st.add(group.entries.mapNotNull { e ->
            val segs = e.relative.split('/')
            GitUpload.pathOf(path, segs.dropLast(1), segs.last())?.let { UploadItem(e.uri, segs.last(), it, e.size) }
        })
        st.note = group.note
        if (group.entries.isEmpty()) ctx.toast(group.note ?: "没有读到可上传的文件")
    }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) { st.reading = false; return@rememberLauncherForActivityResult }
        uris.forEach { UploadSource.persistRead(ctx, it) }
        entry.scope.launch {
            // 递归统计可能很慢（几千个文件），统计完前不让重复点
            val read = runCatching { UploadSource.readFiles(ctx, uris) }
            st.reading = false
            if (read.isFailure) st.error = "读取所选文件失败：" + read.exceptionOrNull()?.friendlyText()
            else accept(read.getOrThrow())
        }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) { st.reading = false; return@rememberLauncherForActivityResult }
        UploadSource.persistRead(ctx, tree)
        entry.scope.launch {
            val read = runCatching { UploadSource.readFolder(ctx, tree) }
            st.reading = false
            if (read.isFailure) st.error = "读取文件夹失败：" + read.exceptionOrNull()?.friendlyText()
            else accept(read.getOrThrow())
        }
    }

    fun upload() {
        val files = st.usable.toList()
        val base = t?.baseRef ?: return
        if (files.isEmpty() || branch.isEmpty()) return
        st.busy = true
        st.error = null
        st.commit = null
        st.note = null
        st.progress = UploadProgress(0, files.size, 0, files.sumOf { it.size }, "")
        val message = RepoWrite.commitMessage(summary, description)
        val asNew = newBranch
        st.job = entry.scope.launch {
            try {
                val r = entry.guarded {
                    GitUpload.run(
                        owner, name, branch, base, files, message, asNew,
                        open = { UploadSource.open(ctx, it.uri) },
                        onProgress = { st.progress = it },
                        onCommit = { st.commit = it },
                    )
                }
                RepoWrite.changed(owner, name)
                st.busy = false
                st.progress = null
                st.doneMsg = "${r.written} 个文件已提交到 ${r.branch}（${r.sha.take(7)}）" +
                    (if (r.unchanged > 0) "；另有 ${r.unchanged} 个与仓库内容相同，没有重复写入" else "") +
                    "。仓库里没选中的文件都没有动。"
                st.clearAll()
                if (asNew && openPr) nav.replace(Screen.NewPull(owner, name, r.branch, t.prBase, summary.trim(), description.trim()))
            } catch (e: CancellationException) {
                st.busy = false
                st.progress = null
                st.error = "已取消上传。" + (st.commit?.let { "提交 ${it.take(7)} 已经生成，但分支没有更新，可以在提交历史里看到它。" } ?: "GitHub 上还没有产生提交。")
                throw e
            } catch (e: Throwable) {
                st.busy = false
                st.progress = null
                st.error = GitUpload.explain(e, st.commit)
            }
        }
    }

    Page(
        "上传文件（夹）",
        subtitle = "${t?.baseRef ?: ref ?: "默认分支"} · $owner/$name" + (if (path.isEmpty()) "" else " · $path/"),
        onBack = { if (st.busy || st.raw.isNotEmpty()) st.confirmLeave = true else nav.pop() },
        actions = {
            if (st.raw.isNotEmpty() && !st.busy) OcButton(R.drawable.oc_trash, { st.confirmClear = true }, g.fgMuted, description = "清空已选")
        },
        bottomBar = {
            Column(Modifier.background(g.header).navigationBarsPadding().imePadding()) {
                HDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(st.lineSummary(), fontSize = 12.sp, color = g.fgMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (st.skipped > 0 || st.dropped > 0) Text(
                            (if (st.skipped > 0) "不上传的项在列表里标了原因" else "") +
                                (if (st.dropped > 0) (if (st.skipped > 0) "；" else "") + "${st.dropped} 项路径重复或无效，已去掉" else ""),
                            fontSize = 11.sp, color = g.attention, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    GhButton(if (st.busy) "上传中…" else "上传并提交", primary = true, enabled = canSubmit, onClick = ::upload)
                }
            }
        },
    ) { pad ->
        when {
            target.error != null && t == null -> ErrorState(target.error!!, Modifier.padding(pad).fillMaxSize()) { target.load(true) }
            t == null -> Loading(Modifier.padding(pad).fillMaxSize())
            else -> LazyColumn(Modifier.padding(pad).fillMaxSize()) {
                // 上面的表单固定在首项，下面的清单可能几千项，只能懒加载
                item {
                    Column {
                        if (!t.canPush) NoPushNotice(t.repo.archived, "https://github.com/$owner/$name/upload/${t.prBase}")
                        if (emptyRepo) Text(
                            "这是一个空仓库：上传会把选中的内容直接建成 ${t.baseRef} 的第一个提交。",
                            Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 12.sp, lineHeight = 17.sp, color = g.fgMuted,
                        )
                        if (t.canPush) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GhButton("选择文件", Modifier.weight(1f), icon = R.drawable.oc_file, enabled = !st.busy && !st.reading) {
                                st.reading = true
                                runCatching { pickFiles.launch(arrayOf("*/*")) }.onFailure { st.reading = false; ctx.toast("这台设备没有可用的文件选择器") }
                            }
                            GhButton("选择文件夹", Modifier.weight(1f), icon = R.drawable.oc_file_directory, enabled = !st.busy && !st.reading) {
                                st.reading = true
                                runCatching { pickFolder.launch(null) }.onFailure { st.reading = false; ctx.toast("这台设备没有可用的目录选择器") }
                            }
                        }
                        if (st.reading) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Oc(R.drawable.oc_sync, g.accent, 14.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("正在统计选中的文件…", fontSize = 13.sp, color = g.fgMuted)
                        }
                        st.note?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 12.sp, lineHeight = 17.sp, color = g.attention) }
                        Text(
                            "文件可以多选（数量不限），也可以选文件夹：文件夹按名字在仓库里建目录并递归带上里面的内容。" +
                                "单个文件最大 ${GitUpload.MAX_FILE_BYTES / 1024 / 1024} MB（GitHub 仓库的硬上限，超过的项不会进入提交）；" +
                                "一次最多带 ${GitUpload.MAX_FOLDER_FILES} 个文件。" +
                                "走的是和 git push 相同的传输，一次上传只产生一个提交，仓库里没选中的文件不受影响。",
                            Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 11.sp, lineHeight = 16.sp, color = g.fgMuted,
                        )

                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            GhField(summary, { summary = it }, "提交信息", labelAbove = true, placeholder = "例如 Add logo.png")
                            Spacer(Modifier.height(8.dp))
                            GhField(description, { description = it }, "扩展描述（可选）", singleLine = false, minLines = 2, labelAbove = true)
                            Spacer(Modifier.height(10.dp))
                            RadioRow(!newBranch, "直接提交到 ${t.baseRef}",
                                when {
                                    emptyRepo -> "空仓库：这会成为它的第一个提交"
                                    t.branch == null -> "${t.baseRef} 不是分支，不能直接提交"
                                    t.branch.protected -> "该分支受保护，推送可能被 GitHub 拒绝"
                                    else -> "先把文件写完，最后才把分支移动过去（相当于 push）"
                                },
                                enabled = (t.branch != null || emptyRepo) && !st.busy,
                            ) { st.useNewBranch = false }
                            RadioRow(newBranch, "新建分支并推送", "从 ${t.baseRef} 创建", enabled = !st.busy && !emptyRepo) { st.useNewBranch = true }
                            if (newBranch) {
                                Spacer(Modifier.height(4.dp))
                                GhField(branchInput, { branchInput = it }, "新分支名称", placeholder = suggestedBranch)
                                when {
                                    branchInput.isNotBlank() && !RepoWrite.validBranch(branchInput.trim()) -> Text("分支名称无效", fontSize = 12.sp, color = g.danger)
                                    branchTaken -> Text("已存在同名分支", fontSize = 12.sp, color = g.danger)
                                }
                                if (branchInput.isBlank()) Text("留空则使用 $branch", fontSize = 11.sp, color = g.fgMuted)
                                Spacer(Modifier.height(6.dp))
                                SwitchRow("推送后创建拉取请求", "合并到 ${t.prBase}", openPr, enabled = !st.busy) { openPr = it }
                            }
                        }

                        if (st.busy) Card(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                val p = st.progress
                                LinearProgressIndicator(
                                    progress = { if (p != null && p.totalBytes > 0) (p.bytesDone.toFloat() / p.totalBytes).coerceIn(0f, 1f) else 0f },
                                    modifier = Modifier.fillMaxWidth().height(6.dp),
                                    color = g.accent, trackColor = g.neutralMuted,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    (p?.let { "${it.done}/${it.total} 个文件 · ${fmtSize(it.bytesDone)} / ${fmtSize(it.totalBytes)}" } ?: "准备中…") +
                                        (if (p != null && p.current.isNotEmpty()) "\n${p.current}" else ""),
                                    fontSize = 12.sp, lineHeight = 17.sp, color = g.fgMuted,
                                )
                            }
                        }
                        st.error?.let { InlineError(it, Modifier.padding(horizontal = 16.dp)) }

                        if (st.items.isEmpty()) {
                            if (!st.reading) EmptyState(if (t.canPush) "还没有选择文件" else "没有推送权限", R.drawable.oc_upload, Modifier.fillMaxWidth())
                        } else {
                            HDivider()
                            Text(
                                "已选 ${st.items.size} 项" + (if (st.skipped > 0) "（${st.skipped} 项不上传）" else ""),
                                Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = g.fg,
                            )
                        }
                    }
                }
                // 清单可能几千项，必须懒加载，不能在一次组合里展开
                itemsIndexed(st.items, key = { _, row -> row.uri + "@" + row.path }) { _, row ->
                    UploadRow(row, st.busy) { st.remove(row) }
                }
                if (st.items.isNotEmpty()) item { HDivider() }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (st.confirmClear) GhDialog("清空已选文件？", onDismiss = { st.confirmClear = false }, confirm = "清空", danger = true, onConfirm = {
        st.confirmClear = false
        st.clearAll()
        st.note = null
    }) { Text("只清空本机这份清单，GitHub 上的文件不受影响。", fontSize = 14.sp, color = g.fg) }

    if (st.confirmLeave) GhDialog(
        if (st.busy) "上传正在进行" else "离开上传页？",
        { st.confirmLeave = false },
        confirm = if (st.busy) "取消上传并离开" else "离开并丢弃",
        danger = st.busy,
        onConfirm = {
            st.confirmLeave = false
            if (st.busy) st.job?.cancel()
            nav.pop()
        },
    ) {
        Text(
            if (st.busy) "取消后已经写到 GitHub 的文件对象不会清理，但不会产生半个提交：分支只有在全部成功后才会移动。"
            else "上传还没开始。离开会丢掉已选的 ${st.items.size} 项（GitHub 上什么都没有改动）。",
            fontSize = 14.sp, color = g.fg,
        )
        if (!st.busy) Spacer(Modifier.height(8.dp))
        if (!st.busy) Text("想继续上传就点「取消」，清单会留在本页。", fontSize = 12.sp, color = g.fgMuted)
    }

    st.doneMsg?.let { msg ->
        val sha = st.commit
        GhDialog("上传完成", onDismiss = { st.doneMsg = null }, confirm = if (sha != null) "查看提交" else "关闭", onConfirm = {
            st.doneMsg = null
            if (sha != null) nav.push(Screen.CommitDetail(owner, name, sha))
        }) { Text(msg, fontSize = 14.sp, color = g.fg) }
    }
}

@Composable
private fun UploadRow(item: UploadItem, busy: Boolean, onRemove: () -> Unit) {
    val g = Gh.c
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Oc(if (item.skip != null) R.drawable.oc_circle_slash else R.drawable.oc_file, if (item.skip != null) g.attention else g.fgMuted, 14.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, fontSize = 13.sp, color = g.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.path, fontSize = 11.sp, color = g.fgMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            item.skip?.let { Text(it, fontSize = 11.sp, lineHeight = 15.sp, color = g.attention) }
        }
        Spacer(Modifier.width(8.dp))
        if (item.skip == null && item.size > 0) Text(fmtSize(item.size), fontSize = 11.sp, color = g.fgMuted)
        if (!busy) Text("移除", fontSize = 12.sp, color = g.fgMuted, modifier = Modifier.padding(start = 10.dp).clickable(onClick = onRemove))
    }
}

/**
 * 能不能显示真实百分比：只有在「读文件算校验值」这一步，本机才知道确切进度。
 * 打包上传阶段拿不到已发送字节数（边压边发，OkHttp 还有自己的缓冲），
 * 若继续沿用读阶段的比例，条会停在 100% 而上传其实才刚开始。
 */
internal fun determinateProgress(p: UploadProgress?): Boolean =
    p != null && p.stage == UploadStage.Reading && p.totalBytes > 0

/** 读取阶段的异常没有 friendly() 的语义，单独给一句人能看懂的话 */
private fun Throwable?.friendlyText(): String = this?.message?.takeIf { it.isNotBlank() } ?: "无法读取该文件"
