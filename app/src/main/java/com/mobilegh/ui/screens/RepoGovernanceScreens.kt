package com.mobilegh.ui.screens

import androidx.compose.foundation.clickable
import com.mobilegh.ui.components.verticalScrollWithBar
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.mobilegh.R
import com.mobilegh.data.AUTO_MERGE_METHODS
import com.mobilegh.data.Asset
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.act
import com.mobilegh.ui.components.GhButton
import com.mobilegh.ui.components.fmtSize
import com.mobilegh.ui.components.openBrowser
import com.mobilegh.ui.components.toast
import java.io.IOException
import com.mobilegh.data.AlertLevel
import com.mobilegh.data.BranchProtection
import com.mobilegh.data.CodeScanningAlert
import com.mobilegh.data.DependabotAlert
import com.mobilegh.data.GitHub
import com.mobilegh.data.RepoGovernance
import com.mobilegh.data.Ruleset
import com.mobilegh.data.RulesetEnforcement
import com.mobilegh.data.SecretAlert
import com.mobilegh.data.codeScanLevel
import com.mobilegh.data.dependabotLevel
import com.mobilegh.data.rulesetEnforcement
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf
import com.mobilegh.nav.Loader
import com.mobilegh.nav.rememberLoader
import com.mobilegh.ui.components.Card
import com.mobilegh.ui.components.GhDialog
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.RadioRow
import com.mobilegh.ui.components.Loading
import com.mobilegh.ui.components.MenuGroup
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.Pill
import com.mobilegh.ui.components.SectionTitle
import com.mobilegh.ui.components.relTime
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.theme.Gh

/** 安全告警：依赖漏洞、代码扫描、密钥泄露三类开放告警。每类独立加载，一类没权限不影响其他类。 */
@Composable
fun SecurityAlertsScreen(owner: String, name: String) {
    val dependabot = rememberLoader("dependabot") { RepoGovernance.dependabotAlerts(owner, name, it) }
    val code = rememberLoader("code-scanning") { RepoGovernance.codeScanningAlerts(owner, name, it) }
    val secret = rememberLoader("secret-scanning") { RepoGovernance.secretScanningAlerts(owner, name, it) }
    val all = listOf<Loader<*>>(dependabot, code, secret)
    val ctx = rememberCtx()
    Page(tr("安全告警"), subtitle = "$owner/$name") { pad ->
        PullToRefreshBox(all.any { it.refreshing }, { all.forEach { it.refresh() } }, Modifier.padding(pad)) {
            Column(Modifier.fillMaxSize().verticalScrollWithBar().padding(bottom = 24.dp)) {
                AlertSection(tr("依赖漏洞（Dependabot）"), dependabot, tr("没有开放的依赖漏洞")) { a: DependabotAlert ->
                    val pkg = a.dependency?.pkg
                    val title = a.securityAdvisory?.summary?.takeIf { it.isNotBlank() } ?: pkg?.name.orEmpty()
                    val subtitle = listOfNotNull(
                        pkg?.let { "${it.ecosystem} · ${it.name}" },
                        a.dependency?.manifestPath,
                        a.securityAdvisory?.cveId,
                    ).joinToString(" · ")
                    AlertRow(dependabotLevel(a), title, subtitle, a.htmlUrl) { ctx.openBrowser(it) }
                }
                AlertSection(tr("代码扫描（Code scanning）"), code, tr("没有开放的代码扫描告警")) { a: CodeScanningAlert ->
                    val loc = a.mostRecentInstance?.location
                    val where = loc?.path?.takeIf { it.isNotBlank() }?.let { p -> loc.startLine?.let { "$p:$it" } ?: p }
                    val subtitle = listOfNotNull(a.tool?.name?.takeIf { it.isNotBlank() }, where, a.createdAt?.let { relTime(it) })
                        .joinToString(" · ")
                    AlertRow(codeScanLevel(a), a.rule?.description?.takeIf { it.isNotBlank() } ?: tr("代码扫描告警"), subtitle, a.htmlUrl) { ctx.openBrowser(it) }
                }
                AlertSection(tr("密钥泄露（Secret scanning）"), secret, tr("没有开放的密钥泄露告警")) { a: SecretAlert ->
                    AlertRow(
                        AlertLevel.High,
                        a.secretTypeDisplayName?.takeIf { it.isNotBlank() } ?: tr("疑似泄露的密钥"),
                        listOfNotNull("#${a.number}", a.createdAt?.let { relTime(it) }).joinToString(" · "),
                        a.htmlUrl,
                    ) { ctx.openBrowser(it) }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    tr("告警的处理（关闭、忽略、查看修复建议）请在网页上完成。"),
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 12.sp, color = Gh.c.fgMuted,
                )
            }
        }
    }
}

/** 分支规则：默认分支的经典保护规则，以及仓库的规则集。 */
@Composable
fun BranchRulesScreen(owner: String, name: String) {
    val g = Gh.c
    val ctx = rememberCtx()
    val protection = rememberLoader("protection") { f ->
        val branch = GitHub.repo(owner, name, f).defaultBranch
        ProtectionState(branch, RepoGovernance.branchProtection(owner, name, branch, f))
    }
    val rulesets = rememberLoader("rulesets") { RepoGovernance.rulesets(owner, name, it) }
    val all = listOf<Loader<*>>(protection, rulesets)
    Page(tr("分支规则"), subtitle = "$owner/$name") { pad ->
        PullToRefreshBox(all.any { it.refreshing }, { all.forEach { it.refresh() } }, Modifier.padding(pad)) {
            Column(Modifier.fillMaxSize().verticalScrollWithBar().padding(bottom = 24.dp)) {
                SectionTitle(tr("默认分支保护"))
                val state = protection.data
                val error = protection.error
                when {
                    state != null -> ProtectionCard(state.branch, state.rules)
                    error != null -> Text(error, Modifier.padding(horizontal = 16.dp), fontSize = 13.sp, color = g.danger)
                    else -> Loading(Modifier.fillMaxWidth().heightIn(min = 80.dp))
                }
                SectionTitle(tr("规则集"))
                val sets = rulesets.data
                val setsError = rulesets.error
                when {
                    sets != null -> Card(Modifier.padding(horizontal = 16.dp)) {
                        if (sets.isEmpty()) {
                            Text(tr("这个仓库没有规则集"), Modifier.padding(14.dp), fontSize = 13.sp, color = g.fgMuted)
                        } else {
                            Column {
                                sets.forEachIndexed { i, s ->
                                    if (i > 0) HDivider()
                                    RulesetRow(s) { ctx.openBrowser("https://github.com/$owner/$name/rules/${s.id}") }
                                }
                            }
                        }
                    }
                    setsError != null -> Text(setsError, Modifier.padding(horizontal = 16.dp), fontSize = 13.sp, color = g.danger)
                    else -> Loading(Modifier.fillMaxWidth().heightIn(min = 80.dp))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    tr("规则只读展示。修改规则请在网页的仓库设置中完成。"),
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 12.sp, color = g.fgMuted,
                )
            }
        }
    }
}

private class ProtectionState(val branch: String, val rules: BranchProtection?)

/** 一类告警的加载状态：有数据显示列表，失败显示原因，加载中显示转圈 */
@Composable
private fun <T> AlertSection(title: String, loader: Loader<List<T>>, emptyText: String, row: @Composable (T) -> Unit) {
    val g = Gh.c
    SectionTitle(title)
    val list = loader.data
    val error = loader.error
    // 与设置页一致：整组放在一个圆角分组里；空状态和错误都是组内的一行说明，不再单独套描边卡片
    MenuGroup {
        when {
            list != null && list.isNotEmpty() -> Column {
                list.forEachIndexed { i, a ->
                    if (i > 0) HDivider()
                    row(a)
                }
            }
            list != null -> GroupNote(emptyText, g.fgMuted)
            error != null -> GroupNote(error, g.fgMuted)
            else -> Loading(Modifier.fillMaxWidth().heightIn(min = 60.dp))
        }
    }
}

/** 分组里的一行说明文字（空状态、不可用原因） */
@Composable
private fun GroupNote(text: String, color: Color) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        fontSize = 13.sp, lineHeight = 18.sp, color = color,
    )
}

@Composable
private fun AlertRow(level: AlertLevel, title: String, subtitle: String, url: String, onOpen: (String) -> Unit) {
    val g = Gh.c
    Row(
        Modifier.fillMaxWidth()
            .let { if (url.isNotBlank()) it.clickable { onOpen(url) } else it }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = g.fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, fontSize = 12.sp, color = g.fgMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(10.dp))
        Pill(levelText(level), levelColor(level))
    }
}

private fun levelText(level: AlertLevel): String = when (level) {
    AlertLevel.Critical -> tr("严重")
    AlertLevel.High -> tr("高危")
    AlertLevel.Medium -> tr("中危")
    AlertLevel.Low -> tr("低危")
    AlertLevel.Unknown -> tr("未知")
}

@Composable
private fun levelColor(level: AlertLevel): Color {
    val g = Gh.c
    return when (level) {
        AlertLevel.Critical, AlertLevel.High -> g.danger
        AlertLevel.Medium -> g.attention
        AlertLevel.Low, AlertLevel.Unknown -> g.fgMuted
    }
}

@Composable
private fun RulesetRow(s: Ruleset, onOpen: () -> Unit) {
    val g = Gh.c
    val enforcement = rulesetEnforcement(s.enforcement)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Oc(R.drawable.oc_git_branch, g.fgMuted, 16.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(s.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = g.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(targetText(s.target), fontSize = 12.sp, color = g.fgMuted)
        }
        Spacer(Modifier.width(10.dp))
        when (enforcement) {
            RulesetEnforcement.Active -> Pill(tr("生效中"), g.success)
            RulesetEnforcement.Evaluate -> Pill(tr("仅评估"), g.attention)
            RulesetEnforcement.Disabled -> Pill(tr("已停用"), g.fgMuted)
            RulesetEnforcement.Unknown -> Pill(s.enforcement.ifBlank { tr("未知") }, g.fgMuted)
        }
    }
}

private fun targetText(target: String?): String = when (target?.lowercase()) {
    "branch" -> tr("作用于分支")
    "tag" -> tr("作用于标签")
    "push" -> tr("作用于推送")
    else -> tr("作用于仓库")
}

@Composable
private fun ProtectionCard(branch: String, rules: BranchProtection?) {
    val g = Gh.c
    Card(Modifier.padding(horizontal = 16.dp)) {
        if (rules == null) {
            Text(
                trf("分支 {0} 没有经典保护规则（或当前账号无权查看）", branch),
                Modifier.padding(14.dp), fontSize = 13.sp, color = g.fgMuted,
            )
        } else {
            Column {
                RuleLine(tr("默认分支"), branch)
                HDivider()
                val reviews = rules.requiredPullRequestReviews
                val reviewExtras = listOfNotNull(
                    reviews?.takeIf { it.dismissStaleReviews }?.let { tr("新提交后撤销批准") },
                    reviews?.takeIf { it.requireCodeOwnerReviews }?.let { tr("需要 Code Owner") },
                )
                val reviewBase = when {
                    reviews == null -> tr("未开启")
                    reviews.requiredApprovingReviewCount == 0 -> tr("需要审查，不限人数")
                    else -> trf("需要 {0} 人批准", reviews.requiredApprovingReviewCount)
                }
                RuleLine(
                    tr("合并前需要审查"),
                    if (reviewExtras.isEmpty()) reviewBase else reviewBase + " · " + reviewExtras.joinToString(" · "),
                )
                HDivider()
                val checks = rules.requiredStatusChecks
                RuleLine(
                    tr("要求状态检查通过"),
                    when {
                        checks == null -> tr("未开启")
                        checks.contexts.isEmpty() -> tr("已开启")
                        else -> checks.contexts.joinToString("、")
                    } + if (checks?.strict == true) tr("（需先更新到最新分支）") else "",
                )
                HDivider()
                RuleLine(tr("要求对话已解决"), toggleText(rules.requiredConversationResolution?.enabled))
                HDivider()
                RuleLine(tr("要求线性提交历史"), toggleText(rules.requiredLinearHistory?.enabled))
                HDivider()
                RuleLine(tr("管理员同样受约束"), toggleText(rules.enforceAdmins?.enabled))
                HDivider()
                RuleLine(tr("允许强制推送"), yesNo(rules.allowForcePushes?.enabled))
                HDivider()
                RuleLine(tr("允许删除分支"), yesNo(rules.allowDeletions?.enabled))
            }
        }
    }
}

@Composable
private fun RuleLine(label: String, value: String) {
    val g = Gh.c
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Text(label, Modifier.weight(0.42f), fontSize = 14.sp, color = g.fgMuted)
        Spacer(Modifier.width(8.dp))
        Text(value, Modifier.weight(0.58f), fontSize = 14.sp, color = g.fg)
    }
}

private fun toggleText(enabled: Boolean?): String = when (enabled) {
    true -> tr("已开启")
    false -> tr("未开启")
    null -> tr("未知")
}

/** 开启自动合并前选择合并方式；检查通过且获得审查后，GitHub 按此方式自动合并 */
/**
 * Release 的附件区：列出已有附件，并可从系统文件选择器添加。只在编辑已存在的 Release 时显示（附件挂在 Release 上）。
 * 每个文件单独上传，上传成功一个就加入列表；中途失败时前面成功的保留，并提示失败原因。
 */
@Composable
fun ReleaseAssetsBlock(
    owner: String,
    name: String,
    releaseId: Long,
    assets: List<Asset>,
    canUpload: Boolean,
    onUploaded: (Asset) -> Unit,
) {
    val g = Gh.c
    val ctx = rememberCtx()
    val resolver = ctx.contentResolver
    val entry = LocalEntry.current
    var progress by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        entry.act({
            progress = null
            ctx.toast(trf("附件上传失败：{0}", it))
        }) {
            uris.forEachIndexed { i, uri ->
                val (fileName, size) = describeDocument(resolver, uri)
                progress = trf("正在上传 {0}（{1}/{2}）", fileName, i + 1, uris.size)
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
    Card(Modifier.padding(horizontal = 16.dp)) {
        if (assets.isEmpty()) {
            Text(tr("还没有附件"), Modifier.padding(14.dp), fontSize = 13.sp, color = g.fgMuted)
        } else {
            Column {
                assets.forEachIndexed { i, a ->
                    if (i > 0) HDivider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(a.name, Modifier.weight(1f), fontSize = 14.sp, color = g.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.width(10.dp))
                        Text(fmtSize(a.size), fontSize = 12.sp, color = g.fgMuted)
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

/** 读取所选文件的显示名与大小；大小未知时无法按 Content-Length 上传，直接报错 */
private fun describeDocument(resolver: ContentResolver, uri: Uri): Pair<String, Long> {
    var displayName: String? = null
    var size = -1L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            displayName = c.getString(0)
            if (!c.isNull(1)) size = c.getLong(1)
        }
    }
    if (size < 0) {
        size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    }
    require(size >= 0) { tr("无法读取文件大小") }
    val fileName = displayName?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "asset"
    return fileName to size
}

@Composable
fun AutoMergeDialog(busy: Boolean, onDismiss: () -> Unit, onEnable: (String) -> Unit) {
    val g = Gh.c
    var method by remember { mutableStateOf(AUTO_MERGE_METHODS.first()) }
    val labels = mapOf(
        "MERGE" to tr("合并提交"),
        "SQUASH" to tr("压缩合并"),
        "REBASE" to tr("变基合并"),
    )
    GhDialog(tr("开启自动合并？"), onDismiss, confirm = tr("开启"), confirmEnabled = !busy, onConfirm = { onEnable(method) }) {
        Column {
            Text(
                tr("必需的检查通过并获得所需审查后，GitHub 会自动按所选方式合并。"),
                fontSize = 13.sp, color = g.fgMuted,
            )
            Spacer(Modifier.height(8.dp))
            AUTO_MERGE_METHODS.forEach { m ->
                RadioRow(method == m, labels[m].orEmpty()) { method = m }
            }
        }
    }
}

private fun yesNo(enabled: Boolean?): String = when (enabled) {
    true -> tr("是")
    false -> tr("否")
    null -> tr("未知")
}
