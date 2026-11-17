package com.mobilegh.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.ActionCache
import com.mobilegh.data.ActionCacheRetentionLimit
import com.mobilegh.data.ActionCacheStorageLimit
import com.mobilegh.data.ActionRunner
import com.mobilegh.data.ActionRunnerDownload
import com.mobilegh.data.ActionRunnerToken
import com.mobilegh.data.GitHub
import com.mobilegh.data.Session
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf
import com.mobilegh.nav.Loader
import com.mobilegh.nav.LocalEntry
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
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private data class RunnerCacheAccount(val login: String?, val generation: Int)

private fun currentRunnerCacheAccount() = RunnerCacheAccount(Session.login, Session.generation)

private fun RunnerCacheAccount.requireCurrent() {
    check(Session.login == login && Session.generation == generation && Session.token != null) {
        tr("账号已切换，请重新打开编辑器")
    }
}

@Composable
internal fun RunnersSettings(owner: String, name: String) {
    var nameInput by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }
    val entry = LocalEntry.current
    val runnerFilter = filter
    val runners = rememberLoader("actions-runners:$runnerFilter") {
        GitHub.actionRunners(owner, name, runnerFilter.takeIf(String::isNotBlank), it)
    }
    var registering by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ActionRunner?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionTitle(tr("Runners"), trailing = {
            SectionActionChip(tr("下载 Runner"), onClick = { downloading = true })
            SectionActionChip(tr("注册 Runner"), onClick = { registering = true })
        })
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GhField(
                nameInput,
                { nameInput = it },
                tr("Runner 名称"),
                Modifier.weight(1f),
            )
            SectionActionChip(tr("筛选"), onClick = { filter = nameInput.trim() })
        }
        RunnerCacheList(runners, tr("暂无 Runners")) { items ->
            items.forEach { runner -> RunnerRow(runner) { deleting = runner } }
        }
    }

    if (registering) {
        RunnerRegistrationDialog(owner, name) { registering = false }
    }
    if (downloading) {
        RunnerDownloadsDialog(owner, name) { downloading = false }
    }
    deleting?.let { runner ->
        RunnerCacheDeleteDialog(
            title = tr("删除 Runner？"),
            itemName = runner.name,
            onDismiss = { deleting = null },
            onDelete = { GitHub.deleteActionRunner(owner, name, runner.id) },
            onDeleted = {
                entry.bag.forEach { (key, value) ->
                    if (key.startsWith("actions-runners:")) (value as? Loader<*>)?.load(true)
                }
            },
        )
    }
}

@Composable
private fun RunnerRow(runner: ActionRunner, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(runner.name, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text("#${runner.id}", color = Gh.c.fgMuted, fontSize = 12.sp)
            Text(
                buildList {
                    add(runner.os)
                    add(runner.status)
                    if (runner.busy) add("busy")
                    if (runner.ephemeral) add("ephemeral")
                }.joinToString(" · "),
                color = Gh.c.fgMuted,
                fontSize = 12.sp,
            )
            if (runner.labels.isNotEmpty()) {
                Text(runner.labels.joinToString(", ") { it.name }, color = Gh.c.fgMuted, fontSize = 12.sp)
            }
        }
        OcButton(
            R.drawable.oc_trash,
            onDelete,
            Gh.c.danger,
            description = trf("删除 Runner {0}", runner.name),
        )
    }
    HDivider()
}

@Composable
private fun RunnerRegistrationDialog(owner: String, name: String, onDismiss: () -> Unit) {
    val account = remember { currentRunnerCacheAccount() }
    val context = LocalContext.current
    // Deliberately not rememberSaveable: registration tokens must never enter saved instance state.
    var token by remember { mutableStateOf<ActionRunnerToken?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(owner, name) {
        try {
            account.requireCurrent()
            val result = GitHub.actionRunnerRegistrationToken(owner, name)
            account.requireCurrent()
            token = result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.friendly()
        }
    }
    GhDialog(title = tr("注册 Runner"), onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                token != null -> {
                    val current = token!!
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(current.token, Modifier.weight(1f))
                        OcButton(
                            R.drawable.oc_copy,
                            { context.copy(current.token) },
                            description = tr("复制注册令牌"),
                        )
                    }
                    Text(trf("过期时间：{0}", current.expiresAt), color = Gh.c.fgMuted, fontSize = 12.sp)
                    Text(tr("关闭后令牌将被丢弃"), color = Gh.c.fgMuted, fontSize = 12.sp)
                }
                error != null -> Text(error!!, color = Gh.c.danger, fontSize = 12.sp)
                else -> Loading(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun RunnerDownloadsDialog(owner: String, name: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var downloads by remember { mutableStateOf<List<ActionRunnerDownload>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(owner, name) {
        try {
            downloads = GitHub.actionRunnerDownloads(owner, name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.friendly()
        }
    }
    GhDialog(title = tr("下载 Runner"), onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                downloads != null -> downloads!!.forEach { download ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(download.filename, fontWeight = FontWeight.Medium)
                        Text("${download.os} / ${download.architecture}", color = Gh.c.fgMuted, fontSize = 12.sp)
                        Text(download.downloadUrl, color = Gh.c.fgMuted, fontSize = 12.sp)
                        download.sha256Checksum?.takeIf(String::isNotBlank)?.let {
                            Text("SHA-256: $it", color = Gh.c.fgMuted, fontSize = 12.sp)
                        }
                        SectionActionChip(
                            trf("复制 Runner 下载地址 {0}", download.filename),
                            onClick = { context.copy(download.downloadUrl) },
                        )
                    }
                }
                error != null -> Text(error!!, color = Gh.c.danger, fontSize = 12.sp)
                else -> Loading(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
internal fun CachesSettings(owner: String, name: String) {
    var keyInput by remember { mutableStateOf("") }
    var refInput by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf<Pair<String, String>?>(null) }
    val cacheKey = filter?.first.orEmpty()
    val cacheRef = filter?.second.orEmpty()
    val entry = LocalEntry.current
    val usage = rememberLoader("actions-cache-usage") { GitHub.actionCacheUsage(owner, name, it) }
    val caches = rememberLoader("actions-caches:$cacheKey:$cacheRef") {
        GitHub.actionCaches(owner, name, cacheKey.takeIf(String::isNotBlank), cacheRef.takeIf(String::isNotBlank), it)
    }
    val retention = rememberLoader("actions-cache-retention") {
        GitHub.actionCacheRetentionLimit(owner, name, it)
    }
    val storage = rememberLoader("actions-cache-storage") {
        GitHub.actionCacheStorageLimit(owner, name, it)
    }
    var deleting by remember { mutableStateOf<ActionCache?>(null) }
    var deletingByKey by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionTitle(tr("Caches"), trailing = {
            SectionActionChip(tr("按 key 删除"), onClick = { deletingByKey = true })
        })
        usage.data?.let {
            Text(
                trf("活跃缓存：{0} · {1}", it.activeCachesCount, formatActionBytes(it.activeCachesSizeInBytes)),
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (usage.error != null && usage.data == null) InlineError(usage.error!!) { usage.load(true) }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GhField(keyInput, { keyInput = it }, tr("Cache key"), Modifier.weight(1f))
            GhField(refInput, { refInput = it }, tr("Git ref"), Modifier.weight(1f))
            SectionActionChip(tr("筛选"), onClick = { filter = keyInput.trim() to refInput.trim() })
        }
        RunnerCacheList(caches, tr("暂无 Caches")) { items ->
            items.forEach { cache -> CacheRow(cache) { deleting = cache } }
        }

        CacheRetentionSetting(owner, name, retention)
        CacheStorageSetting(owner, name, storage)
    }

    val refreshCaches = {
        entry.bag.forEach { (key, value) ->
            if (key.startsWith("actions-caches:")) (value as? Loader<*>)?.load(true)
        }
        usage.load(true)
    }

    deleting?.let { cache ->
        RunnerCacheDeleteDialog(
            title = tr("删除 Cache？"),
            itemName = cache.key,
            onDismiss = { deleting = null },
            onDelete = { GitHub.deleteActionCache(owner, name, cache.id) },
            onDeleted = refreshCaches,
        )
    }
    if (deletingByKey) {
        CacheKeyDeleteDialog(
            owner = owner,
            name = name,
            onDismiss = { deletingByKey = false },
            onDeleted = refreshCaches,
        )
    }
}

@Composable
private fun CacheRow(cache: ActionCache, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(cache.key, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            cache.ref?.let { Text(it, color = Gh.c.fgMuted, fontSize = 12.sp) }
            Text(cache.version, color = Gh.c.fgMuted, fontSize = 12.sp)
            Text(formatActionBytes(cache.sizeInBytes), color = Gh.c.fgMuted, fontSize = 12.sp)
            cache.createdAt?.let { Text(trf("创建：{0}", it), color = Gh.c.fgMuted, fontSize = 12.sp) }
            cache.lastAccessedAt?.let { Text(trf("最后访问：{0}", it), color = Gh.c.fgMuted, fontSize = 12.sp) }
        }
        OcButton(
            R.drawable.oc_trash,
            onDelete,
            Gh.c.danger,
            description = trf("删除 Cache {0}", cache.key),
        )
    }
    HDivider()
}

@Composable
private fun CacheRetentionSetting(
    owner: String,
    name: String,
    loader: Loader<ActionCacheRetentionLimit>,
) {
    var editing by remember { mutableStateOf(false) }
    SectionTitle(tr("缓存保留"), trailing = {
        if (loader.data != null) {
            OcButton(R.drawable.oc_pencil, { editing = true }, description = tr("编辑缓存保留"))
        }
    })
    when {
        loader.data != null -> Text(
            trf("{0} 天", loader.data!!.maxCacheRetentionDays),
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = Gh.c.fgMuted,
        )
        loader.error != null -> InlineError(loader.error!!) { loader.load(true) }
        else -> Loading(Modifier.fillMaxWidth())
    }
    if (editing) {
        loader.data?.let { original ->
            CacheLimitDialog(
                title = tr("缓存保留"),
                initialValue = original.maxCacheRetentionDays,
                onDismiss = { editing = false },
                onSave = {
                    GitHub.updateActionCacheRetentionLimit(owner, name, it)
                    original.copy(maxCacheRetentionDays = it)
                },
                onSaved = loader::accept,
            )
        }
    }
}

@Composable
private fun CacheStorageSetting(
    owner: String,
    name: String,
    loader: Loader<ActionCacheStorageLimit>,
) {
    var editing by remember { mutableStateOf(false) }
    SectionTitle(tr("缓存大小"), trailing = {
        if (loader.data != null) {
            OcButton(R.drawable.oc_pencil, { editing = true }, description = tr("编辑缓存大小"))
        }
    })
    when {
        loader.data != null -> Text(
            trf("{0} GB", loader.data!!.maxCacheSizeGb),
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = Gh.c.fgMuted,
        )
        loader.error != null -> InlineError(loader.error!!) { loader.load(true) }
        else -> Loading(Modifier.fillMaxWidth())
    }
    if (editing) {
        loader.data?.let { original ->
            CacheLimitDialog(
                title = tr("缓存大小"),
                initialValue = original.maxCacheSizeGb,
                onDismiss = { editing = false },
                onSave = {
                    GitHub.updateActionCacheStorageLimit(owner, name, it)
                    original.copy(maxCacheSizeGb = it)
                },
                onSaved = loader::accept,
            )
        }
    }
}

@Composable
private fun <T> CacheLimitDialog(
    title: String,
    initialValue: Int,
    onDismiss: () -> Unit,
    onSave: suspend (Int) -> T,
    onSaved: (T) -> Unit,
) {
    val account = remember { currentRunnerCacheAccount() }
    val scope = rememberCoroutineScope()
    var value by remember(initialValue) { mutableStateOf(initialValue.toString()) }
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
            val parsed = value.toIntOrNull()
            if (parsed == null || parsed <= 0) {
                error = tr("请输入正整数")
                return@GhDialog
            }
            saving = true
            error = null
            scope.launch {
                try {
                    account.requireCurrent()
                    val result = onSave(parsed)
                    account.requireCurrent()
                    onSaved(result)
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
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GhField(value, { value = it }, title, enabled = !saving)
            error?.let { Text(it, color = Gh.c.danger, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun CacheKeyDeleteDialog(
    owner: String,
    name: String,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit,
) {
    val account = remember { currentRunnerCacheAccount() }
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var ref by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    GhDialog(
        title = tr("按 key 删除"),
        onDismiss = onDismiss,
        confirm = tr(if (deleting) "删除中…" else "删除"),
        danger = true,
        confirmEnabled = !deleting && key.isNotBlank(),
        dismissEnabled = !deleting,
        dismissOnClickOutside = !deleting,
        onConfirm = {
            deleting = true
            error = null
            scope.launch {
                try {
                    account.requireCurrent()
                    GitHub.deleteActionCaches(owner, name, key, ref.takeIf(String::isNotBlank))
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
            GhField(key, { key = it }, tr("Cache key"), enabled = !deleting)
            GhField(ref, { ref = it }, tr("Git ref"), enabled = !deleting)
            error?.let { Text(it, color = Gh.c.danger, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun RunnerCacheDeleteDialog(
    title: String,
    itemName: String,
    onDismiss: () -> Unit,
    onDelete: suspend () -> Unit,
    onDeleted: () -> Unit,
) {
    val account = remember { currentRunnerCacheAccount() }
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
            Text(itemName)
            error?.let { Text(it, color = Gh.c.danger, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun <T> RunnerCacheList(
    loader: Loader<List<T>>,
    emptyText: String,
    content: @Composable (List<T>) -> Unit,
) {
    when {
        loader.data != null -> if (loader.data!!.isEmpty()) {
            EmptyState(emptyText, modifier = Modifier.fillMaxWidth())
        } else {
            content(loader.data!!)
        }
        loader.error != null -> InlineError(loader.error!!) { loader.load(true) }
        else -> Loading(Modifier.fillMaxWidth())
    }
}

private fun formatActionBytes(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "$bytes B" else String.format(Locale.US, "%.1f %s", value, units[unit])
}
