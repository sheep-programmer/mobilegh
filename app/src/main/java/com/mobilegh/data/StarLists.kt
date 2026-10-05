package com.mobilegh.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale
import java.util.UUID

/** Only repository identity is persisted, not tokens, private descriptions, or API responses. */
@Serializable
data class ListedRepository(val id: Long, val fullName: String, val isPrivate: Boolean = false) {
    val owner: String get() = fullName.substringBefore('/')
    val name: String get() = fullName.substringAfter('/')

    companion object {
        fun from(repo: Repo) = ListedRepository(repo.id, "${repo.owner.login}/${repo.name}", repo.isPrivate)
    }
}

@Serializable
data class LocalStarList(
    val id: String,
    val name: String,
    val description: String = "",
    val repositories: List<ListedRepository> = emptyList(),
)

/** Captured when opening the screen; old jobs cannot read/write after logout/account switch. */
data class StarListAccount(val login: String, val generation: Int)

internal interface StarListStorage {
    fun read(key: String): String?
    fun write(key: String, value: String): Boolean
}

@Serializable
private data class StoredStarLists(val savedAt: Long = 0, val lists: List<LocalStarList> = emptyList())

/** Account-guarded local CRUD. Run disk operations on Dispatchers.IO. No GitHub writes. */
class LocalStarListsStore internal constructor(
    private val storage: StarListStorage,
    private val activeAccount: () -> StarListAccount?,
) {
    companion object {
        const val MAX_LISTS = 40
        const val MAX_REPOSITORIES = 200
        const val MAX_MEMBERSHIPS = 1000
        const val MAX_NAME = 80
        const val MAX_DESCRIPTION = 280
        internal const val MAX_BYTES = 512 * 1024
        private val lock = Any()
        private val json = Json { ignoreUnknownKeys = true }
        private val LOGIN = Regex("[A-Za-z0-9-]{1,39}")
        private val FULL_NAME = Regex("[A-Za-z0-9-]{1,39}/[A-Za-z0-9_.-]{1,100}")
    }

    fun read(account: StarListAccount): List<LocalStarList> = synchronized(lock) {
        val state = readState(account)
        checkAccount(account)
        state.lists
    }

    fun create(account: StarListAccount, name: String, description: String = ""): List<LocalStarList> = change(account) { lists ->
        require(lists.size < MAX_LISTS) { "每个账号最多创建 $MAX_LISTS 个本机列表" }
        val clean = checkedName(name)
        checkUniqueName(lists, clean)
        lists + LocalStarList(UUID.randomUUID().toString(), clean, checkedDescription(description))
    }

    fun update(account: StarListAccount, id: String, name: String, description: String): List<LocalStarList> = change(account) { lists ->
        require(lists.any { it.id == id }) { "列表已被删除，请返回重试" }
        val clean = checkedName(name)
        checkUniqueName(lists.filterNot { it.id == id }, clean)
        lists.map { if (it.id == id) it.copy(name = clean, description = checkedDescription(description)) else it }
    }

    fun delete(account: StarListAccount, id: String): List<LocalStarList> = change(account) { lists ->
        require(lists.any { it.id == id }) { "列表已被删除，请返回重试" }
        lists.filterNot { it.id == id }
    }

    fun addRepositories(account: StarListAccount, id: String, repositories: List<ListedRepository>): List<LocalStarList> = change(account) { lists ->
        require(lists.any { it.id == id }) { "列表已被删除，请返回重试" }
        repositories.forEach(::checkRepository)
        lists.map { list ->
            if (list.id != id) list else {
                val merged = (list.repositories + repositories).distinctBy { it.id }
                require(merged.size <= MAX_REPOSITORIES) { "每个本机列表最多保存 $MAX_REPOSITORIES 个仓库" }
                list.copy(repositories = merged)
            }
        }
    }

    fun removeRepository(account: StarListAccount, id: String, repositoryId: Long): List<LocalStarList> = change(account) { lists ->
        require(lists.any { it.id == id }) { "列表已被删除，请返回重试" }
        lists.map { if (it.id == id) it.copy(repositories = it.repositories.filterNot { repo -> repo.id == repositoryId }) else it }
    }

    /** Apply only the picker delta to the latest disk state, preserving concurrent memberships. */
    fun setRepositoryLists(
        account: StarListAccount,
        repository: ListedRepository,
        listIds: Set<String>,
        originalListIds: Set<String>,
    ): List<LocalStarList> = change(account) { lists ->
        checkRepository(repository)
        val added = listIds - originalListIds
        val removed = originalListIds - listIds
        require(added.all { id -> lists.any { it.id == id } }) { "列表已被删除，请重新选择" }
        lists.map { list ->
            if (list.id !in added && list.id !in removed) list else {
                val others = list.repositories.filterNot { it.id == repository.id }
                val members = if (list.id in added) others + repository else others
                require(members.size <= MAX_REPOSITORIES) { "「${list.name}」最多保存 $MAX_REPOSITORIES 个仓库" }
                list.copy(repositories = members)
            }
        }
    }

    private fun change(account: StarListAccount, transform: (List<LocalStarList>) -> List<LocalStarList>): List<LocalStarList> = synchronized(lock) {
        val lists = transform(readState(account).lists)
        checkLists(lists)
        val encoded = json.encodeToString(StoredStarLists(System.currentTimeMillis(), lists))
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "本机列表数据超过存储上限" }
        val key = key(account)
        checkAccount(account)
        check(storage.write(key, encoded)) { "本机列表保存失败，请重试" }
        checkAccount(account)
        lists
    }

    private fun readState(account: StarListAccount): StoredStarLists {
        checkAccount(account)
        val raw = storage.read(key(account)) ?: return StoredStarLists()
        require(raw.length <= MAX_BYTES && raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "本机列表数据超过读取上限" }
        val state = try { json.decodeFromString<StoredStarLists>(raw) } catch (_: Exception) {
            throw IllegalStateException("本机列表数据无法读取；原数据已保留")
        }
        checkLists(state.lists)
        return state
    }

    private fun checkAccount(account: StarListAccount) {
        val active = activeAccount()
        check(LOGIN.matches(account.login) && active != null && active.generation == account.generation &&
            active.login.equals(account.login, true)) { "账号已切换或退出，请重新打开本机列表" }
    }

    private fun key(account: StarListAccount) = "account:${account.login.lowercase(Locale.ROOT)}"

    private fun checkedName(value: String): String = value.trim().also {
        require(it.isNotEmpty() && it.length <= MAX_NAME && it.none(Char::isISOControl)) { "列表名称需为 1–$MAX_NAME 个字符" }
    }

    private fun checkedDescription(value: String): String = value.trim().also {
        require(it.length <= MAX_DESCRIPTION) { "列表描述最多 $MAX_DESCRIPTION 个字符" }
    }

    private fun checkUniqueName(lists: List<LocalStarList>, name: String) {
        require(lists.none { it.name.equals(name, true) }) { "已存在同名本机列表" }
    }

    private fun checkRepository(repo: ListedRepository) {
        require(repo.id > 0 && FULL_NAME.matches(repo.fullName) && repo.name !in setOf(".", "..")) { "无效的仓库信息" }
    }

    private fun checkLists(lists: List<LocalStarList>) {
        require(lists.size <= MAX_LISTS && lists.sumOf { it.repositories.size } <= MAX_MEMBERSHIPS) {
            "每个账号最多保存 $MAX_LISTS 个本机列表、$MAX_MEMBERSHIPS 条仓库记录"
        }
        require(lists.distinctBy { it.id }.size == lists.size) { "本机列表数据重复" }
        lists.forEach { list ->
            require(list.id.isNotBlank() && list.id.length <= 64) { "无效的本机列表数据" }
            checkedName(list.name)
            checkedDescription(list.description)
            require(list.repositories.size <= MAX_REPOSITORIES && list.repositories.distinctBy { it.id }.size == list.repositories.size) { "本机列表仓库数据超过上限或重复" }
            list.repositories.forEach(::checkRepository)
        }
    }
}

/** Lazy initialization keeps AppRoot/Session unchanged. Tokens and cookies are never stored here. */
object StarLists {
    fun store(context: Context): LocalStarListsStore {
        val prefs = context.applicationContext.getSharedPreferences("local_star_lists_v1", Context.MODE_PRIVATE)
        return LocalStarListsStore(object : StarListStorage {
            override fun read(key: String) = prefs.getString(key, null)
            override fun write(key: String, value: String) = prefs.edit().putString(key, value).commit()
        }) {
            Session.login?.takeIf { Session.token != null }?.let { StarListAccount(it, Session.generation) }
        }
    }
}
