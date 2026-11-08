package com.mobilegh.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class SavedKind {
    Issue, PullRequest, Repo, Discussion
}

@Serializable
data class SavedItem(
    val id: String,
    val kind: SavedKind,
    val owner: String,
    val name: String,
    val number: Int? = null,
    val title: String,
    val state: String? = null,
    val savedAt: Long = System.currentTimeMillis(),
)

object SavedItems {
    private var prefs: android.content.SharedPreferences? = null
    var revision by mutableIntStateOf(0)
        private set
    private val json = Json { ignoreUnknownKeys = true }

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("saved_items_v1", Context.MODE_PRIVATE)
    }

    private fun key(): String = Session.login?.let { "saved:$it" } ?: "saved:anonymous"

    fun all(): List<SavedItem> {
        revision
        val p = prefs ?: return emptyList()
        val raw = p.getString(key(), null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<SavedItem>>(raw) }.getOrDefault(emptyList())
    }

    fun isSaved(id: String): Boolean = all().any { it.id == id }

    fun count(): Int = all().size

    fun save(item: SavedItem) {
        val p = prefs ?: return
        val current = all().filter { it.id != item.id }.toMutableList()
        current.add(0, item)
        p.edit().putString(key(), json.encodeToString(current)).apply()
        revision++
    }

    fun remove(id: String) {
        val p = prefs ?: return
        val current = all().filter { it.id != id }
        p.edit().putString(key(), json.encodeToString(current)).apply()
        revision++
    }

    fun toggle(item: SavedItem): Boolean {
        return if (isSaved(item.id)) {
            remove(item.id)
            false
        } else {
            save(item)
            true
        }
    }
}
