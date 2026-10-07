package com.mobilegh.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.mobilegh.nav.Links
import com.mobilegh.nav.Screen
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal interface LocalRecordStorage {
    fun read(key: String): String?
    fun write(key: String, value: String)
    fun remove(key: String)
}

@Serializable
data class SearchHistoryEntry(val query: String, val type: Int = 0)

@Serializable
data class RecentRepository(val owner: String, val name: String) {
    val fullName: String get() = "$owner/$name"
}

@Serializable
data class HistoryData(
    val searches: List<SearchHistoryEntry> = emptyList(),
    val repositories: List<RecentRepository> = emptyList(),
)

class HistoryStore internal constructor(private val storage: LocalRecordStorage) {
    private val json = Json { ignoreUnknownKeys = true }
    private fun key(login: String): String {
        require(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}").matches(login))
        return "history:${login.lowercase()}"
    }
    fun read(login: String): HistoryData {
        val raw = storage.read(key(login)) ?: return HistoryData()
        if (raw.length > 131_072) return HistoryData()
        val data = runCatching { json.decodeFromString<HistoryData>(raw) }.getOrDefault(HistoryData())
        return HistoryData(
            data.searches.filter { validSearch(it) }.distinct().take(20),
            data.repositories.filter { validRepo(it) }.distinctBy { it.fullName.lowercase() }.take(50),
        )
    }
    fun search(login: String, query: String, type: Int) {
        val entry = SearchHistoryEntry(query.trim(), type)
        if (!validSearch(entry)) return
        val old = read(login)
        save(login, old.copy(searches = (listOf(entry) + old.searches.filterNot { it == entry }).take(20)))
    }
    fun repository(login: String, owner: String, name: String) {
        val entry = RecentRepository(owner, name)
        if (!validRepo(entry)) return
        val old = read(login)
        save(login, old.copy(repositories = (listOf(entry) + old.repositories.filterNot { it.fullName.equals(entry.fullName, true) }).take(50)))
    }
    fun removeSearch(login: String, entry: SearchHistoryEntry) = read(login).let { save(login, it.copy(searches = it.searches - entry)) }
    fun clearSearches(login: String) = read(login).let { save(login, it.copy(searches = emptyList())) }
    fun removeRepository(login: String, entry: RecentRepository) = read(login).let { save(login, it.copy(repositories = it.repositories.filterNot { r -> r.fullName.equals(entry.fullName, true) })) }
    fun clearRepositories(login: String) = read(login).let { save(login, it.copy(repositories = emptyList())) }
    private fun save(login: String, data: HistoryData) = storage.write(key(login), json.encodeToString(data))
    private fun validSearch(entry: SearchHistoryEntry) = entry.query.length in 1..512 && entry.query.isNotBlank() && entry.query.none(Char::isISOControl) && entry.type in 0..4
    private fun validRepo(entry: RecentRepository) = Links.fromInput(entry.fullName) is Screen.Repo
}

object History {
    private var store: HistoryStore? = null
    var revision by mutableIntStateOf(0)
        private set
    fun init(context: Context) {
        val prefs = context.getSharedPreferences("navigation_history_v1", Context.MODE_PRIVATE)
        store = HistoryStore(object : LocalRecordStorage {
            override fun read(key: String) = prefs.getString(key, null)
            override fun write(key: String, value: String) { prefs.edit().putString(key, value).apply() }
            override fun remove(key: String) { prefs.edit().remove(key).apply() }
        })
    }
    fun data(): HistoryData {
        revision
        return Session.login?.let { store?.read(it) } ?: HistoryData()
    }
    private fun change(block: HistoryStore.(String) -> Unit) {
        val login = Session.login ?: return
        store?.block(login)
        revision++
    }
    fun search(query: String, type: Int) = change { login -> search(login, query, type) }
    fun removeSearch(entry: SearchHistoryEntry) = change { removeSearch(it, entry) }
    fun clearSearches() = change { clearSearches(it) }
    fun removeRepository(entry: RecentRepository) = change { removeRepository(it, entry) }
    fun clearRepositories() = change { clearRepositories(it) }
    fun navigation(screen: Screen) {
        val repo = when (screen) {
            is Screen.Repo -> screen.owner to screen.name
            is Screen.Files -> screen.owner to screen.name
            is Screen.FileView -> screen.owner to screen.name
            is Screen.IssueDetail -> screen.owner to screen.name
            is Screen.DiscussionDetail -> screen.owner to screen.name
            else -> null
        } ?: return
        change { repository(it, repo.first, repo.second) }
    }
}
