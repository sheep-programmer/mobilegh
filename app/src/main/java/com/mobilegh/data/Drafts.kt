package com.mobilegh.data

import android.content.Context
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

@Serializable
data class DraftValue(val title: String = "", val body: String = "", val category: String? = null) {
    val empty: Boolean get() = title.isBlank() && body.isBlank()
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
        require(raw.length <= 300_000) { "草稿超过读取上限" }
        return json.decodeFromString<DraftValue>(raw)
    }
    fun save(login: String, context: String, value: DraftValue) {
        require(value.title.length <= 1024 && value.body.length <= 65_536) { "草稿太长，无法保存在本机" }
        if (value.empty) clear(login, context) else storage.write(key(login, context), json.encodeToString(value))
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
        })
    }
}

/** Captures the draft owner: closing a page after switching accounts still flushes only its old owner's draft. */
class DraftController internal constructor(private val store: DraftStore, private val login: String?, private val context: String) {
    private val lock = Any()
    var error by mutableStateOf<String?>(null)
        private set
    val initial: DraftValue = login?.let { owner -> runCatching { store.load(owner, context) }.getOrElse {
        error = "草稿读取失败，原数据已保留：${it.message}"
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
        runCatching { store.save(owner, context, current) }.onSuccess { dirty = false; error = null }
            .onFailure { error = "草稿保存失败：${it.message}" }
    }
    fun flush() = synchronized(lock) {
        job?.cancel()
        if (dirty) job = Drafts.scope.launch { persist() }
    }
    fun submitted(value: DraftValue) = synchronized(lock) {
        val owner = login ?: return@synchronized
        if (current != value) return@synchronized
        job?.cancel()
        runCatching { store.clear(owner, context) }.onSuccess { error = null }.onFailure { error = "已提交，但草稿清理失败：${it.message}" }
        current = DraftValue()
        suppressed = value
        dirty = false
    }
}
