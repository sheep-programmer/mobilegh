package com.mobilegh.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf

/** 首页「我的工作」里的内置入口；顺序即默认布局 */
enum class BuiltinShortcut(val id: String) {
    Issues("issues"), Pulls("pulls"), Repos("repos"), Orgs("orgs"),
    Stars("stars"), Lists("lists"), Gists("gists"), Recent("recent");

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id }
    }
}

/** 保存的搜索可选的图标（固定的一组 Octicon，界面层负责映射到图标资源） */
val SAVED_SEARCH_ICONS = listOf("search", "issue", "pr", "bug", "tag", "milestone", "mention", "eye", "flame", "zap", "star", "bookmark")

@Serializable
data class FavoriteRepo(val owner: String, val name: String) {
    val fullName: String get() = "$owner/$name"
}

@Serializable
data class SavedSearch(val id: String, val name: String, val icon: String = "search", val query: IssueQuery = IssueQuery())

@Serializable
data class ShortcutSlot(val id: String, val visible: Boolean = true)

/** 首页布局；shortcuts 为 null 表示从未编辑过，使用默认布局 */
@Serializable
data class HomeLayout(
    val favorites: List<FavoriteRepo> = emptyList(),
    val shortcuts: List<ShortcutSlot>? = null,
    val searches: List<SavedSearch> = emptyList(),
) {
    /** 实际生效的入口顺序：未知 id 丢弃，新增的内置入口与保存的搜索追加在末尾 */
    fun slots(): List<ShortcutSlot> {
        val known = BuiltinShortcut.entries.map { it.id } + searches.map { searchSlotId(it.id) }
        val stored = (shortcuts ?: BuiltinShortcut.entries.map { ShortcutSlot(it.id) }).filter { it.id in known }.distinctBy { it.id }
        val missing = known.filter { id -> stored.none { it.id == id } }.map { ShortcutSlot(it) }
        return stored + missing
    }

    fun visibleSlots(): List<ShortcutSlot> = slots().filter { it.visible }

    fun isFavorite(owner: String, name: String) = favorites.any { it.fullName.equals("$owner/$name", true) }

    fun search(slotId: String): SavedSearch? = searches.firstOrNull { searchSlotId(it.id) == slotId }

    companion object {
        const val MAX_FAVORITES = 30
        const val MAX_SEARCHES = 12
        const val MAX_NAME = 24
        fun searchSlotId(id: String) = "search:$id"
    }
}

/** 把 list[from] 移动 delta 位，越界时原样返回 */
fun <T> List<T>.moved(from: Int, delta: Int): List<T> {
    val to = from + delta
    if (from !in indices || to !in indices || delta == 0) return this
    val m = toMutableList()
    m.add(to, m.removeAt(from))
    return m
}

/** 按账号保存在本机；只保存仓库名与查询条件，不保存任何 API 数据 */
class HomeLayoutStore internal constructor(private val storage: LocalRecordStorage) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val repoName = Regex("[A-Za-z0-9-]{1,39}/[A-Za-z0-9_.-]{1,100}")

    private fun key(login: String): String {
        require(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}").matches(login))
        return "home:${login.lowercase()}"
    }

    fun read(login: String): HomeLayout {
        val raw = storage.read(key(login)) ?: return HomeLayout()
        if (raw.length > 65_536) return HomeLayout()
        val data = runCatching { json.decodeFromString<HomeLayout>(raw) }.getOrDefault(HomeLayout())
        return data.copy(
            favorites = data.favorites.filter { repoName.matches(it.fullName) }.distinctBy { it.fullName.lowercase() }.take(HomeLayout.MAX_FAVORITES),
            searches = data.searches.filter { it.id.isNotBlank() && it.name.isNotBlank() }.distinctBy { it.id }.take(HomeLayout.MAX_SEARCHES)
                .map { it.copy(icon = it.icon.takeIf { i -> i in SAVED_SEARCH_ICONS } ?: "search", query = it.query.normalized()) },
        )
    }

    fun encode(layout: HomeLayout): String = json.encodeToString(layout)

    private fun change(login: String, block: (HomeLayout) -> HomeLayout): HomeLayout {
        val next = block(read(login))
        storage.write(key(login), encode(next))
        return next
    }

    fun toggleFavorite(login: String, owner: String, name: String): Boolean {
        var added = false
        change(login) { l ->
            if (l.isFavorite(owner, name)) l.copy(favorites = l.favorites.filterNot { it.fullName.equals("$owner/$name", true) })
            else {
                val f = FavoriteRepo(owner, name)
                require(repoName.matches(f.fullName)) { tr("无效的仓库名") }
                require(l.favorites.size < HomeLayout.MAX_FAVORITES) { trf("首页最多收藏 {0} 个仓库", HomeLayout.MAX_FAVORITES) }
                added = true
                l.copy(favorites = l.favorites + f)
            }
        }
        return added
    }

    fun moveFavorite(login: String, index: Int, delta: Int) = change(login) { it.copy(favorites = it.favorites.moved(index, delta)) }

    fun removeFavorite(login: String, repo: FavoriteRepo) =
        change(login) { l -> l.copy(favorites = l.favorites.filterNot { it.fullName.equals(repo.fullName, true) }) }

    fun moveShortcut(login: String, index: Int, delta: Int) = change(login) { it.copy(shortcuts = it.slots().moved(index, delta)) }

    fun setShortcutVisible(login: String, id: String, visible: Boolean) =
        change(login) { l -> l.copy(shortcuts = l.slots().map { if (it.id == id) it.copy(visible = visible) else it }) }

    /** 恢复默认：内置入口回到默认顺序并全部显示；保存的搜索保留并排在后面 */
    fun resetShortcuts(login: String) = change(login) { it.copy(shortcuts = null) }

    fun addSearch(login: String, name: String, icon: String, query: IssueQuery): SavedSearch {
        val clean = name.trim()
        require(clean.isNotEmpty() && clean.length <= HomeLayout.MAX_NAME && clean.none(Char::isISOControl)) { trf("名称需为 1–{0} 个字符", HomeLayout.MAX_NAME) }
        val s = SavedSearch(UUID.randomUUID().toString(), clean, icon.takeIf { it in SAVED_SEARCH_ICONS } ?: "search", query.normalized())
        change(login) { l ->
            require(l.searches.size < HomeLayout.MAX_SEARCHES) { trf("最多保存 {0} 个搜索", HomeLayout.MAX_SEARCHES) }
            val next = l.copy(searches = l.searches + s)
            // 已编辑过布局时，新的搜索会由 slots() 自动追加到末尾
            next.copy(shortcuts = l.shortcuts?.let { next.slots() })
        }
        return s
    }

    fun removeSearch(login: String, id: String) = change(login) { l ->
        l.copy(searches = l.searches.filterNot { it.id == id }, shortcuts = l.shortcuts?.filterNot { it.id == HomeLayout.searchSlotId(id) })
    }
}

object HomePrefs {
    private var store: HomeLayoutStore? = null
    var revision by mutableIntStateOf(0)
        private set

    fun init(context: Context) {
        val prefs = context.getSharedPreferences("home_layout_v1", Context.MODE_PRIVATE)
        store = HomeLayoutStore(object : LocalRecordStorage {
            override fun read(key: String) = prefs.getString(key, null)
            override fun write(key: String, value: String) { prefs.edit().putString(key, value).apply() }
            override fun remove(key: String) { prefs.edit().remove(key).apply() }
        })
    }

    /** 读取当前账号的布局；在 Composable 中调用会随修改自动刷新 */
    fun layout(): HomeLayout {
        revision
        return Session.login?.let { store?.read(it) } ?: HomeLayout()
    }

    private fun <T> change(block: HomeLayoutStore.(String) -> T): T? {
        val login = Session.login ?: return null
        val s = store ?: return null
        return s.block(login).also { revision++ }
    }

    fun toggleFavorite(owner: String, name: String): Boolean = change { toggleFavorite(it, owner, name) } ?: false
    fun moveFavorite(index: Int, delta: Int) = change { moveFavorite(it, index, delta) }
    fun removeFavorite(repo: FavoriteRepo) = change { removeFavorite(it, repo) }
    fun moveShortcut(index: Int, delta: Int) = change { moveShortcut(it, index, delta) }
    fun setShortcutVisible(id: String, visible: Boolean) = change { setShortcutVisible(it, id, visible) }
    fun resetShortcuts() = change { resetShortcuts(it) }
    fun addSearch(name: String, icon: String, query: IssueQuery) = change { addSearch(it, name, icon, query) }
    fun removeSearch(id: String) = change { removeSearch(it, id) }
}
