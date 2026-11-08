package com.mobilegh.data

import android.content.Context
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf

/** 草稿里的一个元数据选项（标签 / 指派人 / 里程碑），保存显示所需的信息，避免恢复后颜色或头像丢失 */
@Serializable
data class DraftOption(val key: String, val label: String, val color: String? = null, val avatar: String? = null)

/**
 * 草稿的来源：编辑时所在的页面（序列化的 Screen）和上下文键，以及保存时间。
 * 只在存储里使用：读出给编辑页面时会去掉，草稿列表靠它显示位置、跳回原页面。
 */
@Serializable
data class DraftMeta(val context: String, val origin: String? = null, val savedAt: Long = 0)

/** 草稿列表里的一项：存储键、内容与来源（旧版本保存的草稿没有来源，[meta] 为 null） */
data class DraftEntry(val key: String, val value: DraftValue, val meta: DraftMeta?)

/** 表单草稿里某个字段的答案；用列表而不是 Map，JSON 的键只能是字符串 */
@Serializable
data class DraftAnswer(val index: Int, val values: List<String> = emptyList())

@Serializable
data class DraftValue(
    val title: String = "",
    val body: String = "",
    val category: String? = null,
    /** 仅新建 Issue 使用：标签、指派人、里程碑 */
    val labels: List<DraftOption> = emptyList(),
    val assignees: List<DraftOption> = emptyList(),
    val milestone: DraftOption? = null,
    /** 新建 Issue 使用：所选模板的文件名与 YAML 表单的答案 */
    val template: String? = null,
    val answers: List<DraftAnswer> = emptyList(),
    /** 草稿来源，只在存储里出现，见 [DraftMeta] */
    val meta: DraftMeta? = null,
) {
    val empty: Boolean get() = title.isBlank() && body.isBlank() && labels.isEmpty() && assignees.isEmpty() &&
        milestone == null && template == null && answers.isEmpty()
}

class DraftStore internal constructor(private val storage: LocalRecordStorage) {
    private val json = Json { ignoreUnknownKeys = true }
    private fun key(login: String, context: String): String {
        require(login.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}")) && context.length in 1..4096)
        val digest = MessageDigest.getInstance("SHA-256").digest(context.toByteArray()).joinToString("") { "%02x".format(it) }
        return "draft:${login.lowercase()}:$digest"
    }
    fun load(login: String, context: String): DraftValue {
        val raw = storage.read(key(login, context)) ?: return DraftValue()
        require(raw.length <= 300_000) { tr("草稿超过读取上限") }
        return json.decodeFromString<DraftValue>(raw).copy(meta = null)
    }

    /** 该账号的全部草稿，最近保存的在前；读不出的记录跳过 */
    fun list(login: String): List<DraftEntry> {
        val prefix = "draft:${login.lowercase()}:"
        return storage.keys().filter { it.startsWith(prefix) }.mapNotNull { k ->
            runCatching {
                val raw = storage.read(k) ?: return@mapNotNull null
                if (raw.length > 300_000) return@mapNotNull null
                val v = json.decodeFromString<DraftValue>(raw)
                if (v.empty) null else DraftEntry(k, v.copy(meta = null), v.meta)
            }.getOrNull()
        }.sortedByDescending { it.meta?.savedAt ?: 0L }
    }

    /** 删除列表里的一条草稿（按存储键） */
    fun remove(key: String) {
        if (key.startsWith("draft:")) storage.remove(key)
    }

    fun save(login: String, context: String, value: DraftValue, origin: String? = null, now: Long = System.currentTimeMillis()) {
        require(value.title.length <= 1024 && value.body.length <= 65_536) { tr("草稿太长，无法保存在本机") }
        require(value.labels.size <= 100 && value.assignees.size <= 100) { tr("草稿太长，无法保存在本机") }
        require((value.template?.length ?: 0) <= 1024 && value.answers.size <= 500) { tr("草稿太长，无法保存在本机") }
        require(value.answers.all { it.values.size <= 100 && it.values.all { v -> v.length <= 65_536 } }) { tr("草稿太长，无法保存在本机") }
        if (value.empty) clear(login, context)
        else storage.write(key(login, context), json.encodeToString(value.copy(meta = DraftMeta(context, origin, now))))
    }
    fun clear(login: String, context: String) = storage.remove(key(login, context))
}

object Drafts {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun store(context: Context): DraftStore {
        val prefs = context.applicationContext.getSharedPreferences("editor_drafts_v1", Context.MODE_PRIVATE)
        return DraftStore(object : LocalRecordStorage {
            override fun read(key: String): String? = prefs.getString(key, null)?.let(Crypto::decrypt)
            override fun write(key: String, value: String) { prefs.edit().putString(key, Crypto.encrypt(value)).apply() }
            override fun remove(key: String) { prefs.edit().remove(key).apply() }
            override fun keys(): Collection<String> = prefs.all.keys
        })
    }
}

/** Captures the draft owner: closing a page after switching accounts still flushes only its old owner's draft. */
class DraftController internal constructor(
    private val store: DraftStore,
    private val login: String?,
    private val context: String,
    /** 编辑所在页面（序列化的 Screen），草稿列表据此跳回 */
    private val origin: String? = null,
) {
    private val lock = Any()
    var error by mutableStateOf<String?>(null)
        private set
    val initial: DraftValue = login?.let { owner -> runCatching { store.load(owner, context) }.getOrElse {
        error = trf("草稿读取失败，原数据已保留：{0}", it.message)
        DraftValue()
    } } ?: DraftValue()
    private var current = initial
    private var dirty = false
    private var suppressed: DraftValue? = null
    private var job: kotlinx.coroutines.Job? = null
    fun update(value: DraftValue) = synchronized(lock) {
        if (login == null) return@synchronized
        if (value == suppressed) return@synchronized
        suppressed = null
        if (current == value) return@synchronized
        current = value
        dirty = true
        job?.cancel()
        job = Drafts.scope.launch { delay(350); persist() }
    }
    private fun persist() = synchronized(lock) {
        val owner = login ?: return@synchronized
        if (!dirty) return@synchronized
        runCatching { store.save(owner, context, current, origin) }.onSuccess { dirty = false; error = null }
            .onFailure { error = trf("草稿保存失败：{0}", it.message) }
    }
    /** 当前内容不为空：离开页面时会作为草稿保留 */
    val hasContent: Boolean get() = synchronized(lock) { !current.empty }

    fun flush() = synchronized(lock) {
        job?.cancel()
        if (dirty) job = Drafts.scope.launch { persist() }
    }
    fun submitted(value: DraftValue) = synchronized(lock) {
        val owner = login ?: return@synchronized
        if (current != value) return@synchronized
        job?.cancel()
        runCatching { store.clear(owner, context) }.onSuccess { error = null }.onFailure { error = trf("已提交，但草稿清理失败：{0}", it.message) }
        current = DraftValue()
        suppressed = value
        dirty = false
    }
}
