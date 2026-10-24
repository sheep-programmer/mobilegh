package com.mobilegh.ui.screens

import com.mobilegh.data.GitHubStarList
import org.junit.Assert.assertEquals
import org.junit.Test

class ListSortTest {
    private fun list(
        name: String,
        count: Int = 0,
        created: String = "",
        updated: String = "",
        lastAdded: String = "",
        description: String = "",
    ) = GitHubStarList(
        id = "L_$name", name = name, description = description, isPrivate = false,
        slug = name, owner = "me", count = count,
        createdAt = created, updatedAt = updated, lastAddedAt = lastAdded,
    )

    // 刻意让 updatedAt 与 lastAddedAt 顺序相反，用来证明排的是 lastAddedAt
    private val ai = list("AI", 303, created = "2024-09-25T04:14:14Z", updated = "2025-02-14T15:13:01Z", lastAdded = "2026-10-23T15:59:46Z")
    private val essay = list("文章", 53, created = "2024-11-18T15:46:30Z", updated = "2025-05-16T14:26:46Z", lastAdded = "2026-10-22T05:41:38Z")
    private val win = list("win逆向", 92, created = "2024-10-14T04:55:07Z", updated = "2024-10-14T04:55:07Z", lastAdded = "2026-10-22T05:24:11Z")
    private val android = list("安卓逆向", 94, created = "2024-10-28T04:18:26Z", updated = "2025-11-09T23:54:34Z", lastAdded = "2026-10-22T02:27:45Z")
    private val apps = list("软件集", 513, created = "2024-10-31T04:46:44Z", updated = "2024-11-29T04:21:23Z", lastAdded = "2026-10-18T16:11:58Z")
    private val all = listOf(win, apps, ai, android, essay)

    @Test fun theMenuHasTheFourRequestedFields() {
        assertEquals(listOf("最后更新时间", "创建时间", "名称", "仓库数"), LIST_SORTS.map { it.label })
    }

    @Test fun theDefaultIsLastUpdatedDescending() {
        assertEquals(LIST_SORT_UPDATED, 0)
        assertEquals("最后更新时间", LIST_SORTS[0].label)
        assertEquals("默认按最后更新时间倒序（新 → 旧）", true, LIST_SORTS[0].descending)
    }

    @Test fun lastUpdatedSortsByLastAddedNotByUpdatedAt() {
        val sorted = sortStarLists(all, LIST_SORT_UPDATED, descending = true).map { it.name }
        // 按 lastAddedAt 倒序
        assertEquals(listOf("AI", "文章", "win逆向", "安卓逆向", "软件集"), sorted)
        // 若误用 updatedAt，顺序会变成 安卓逆向(2025-11) 文章(2025-05) AI(2025-02) 软件集(2024-11) win逆向(2024-10)
        assertEquals(false, sorted.first() == "安卓逆向")
    }

    @Test fun lastUpdatedAscendingPutsTheOldestAdditionFirst() {
        assertEquals(
            listOf("软件集", "安卓逆向", "win逆向", "文章", "AI"),
            sortStarLists(all, LIST_SORT_UPDATED, descending = false).map { it.name },
        )
    }

    @Test fun createdTimeSortsEitherWay() {
        val desc = sortStarLists(all, 1, descending = true).map { it.name }
        // 最新创建的在前：文章(11-18) > 软件集(10-31) > 安卓逆向(10-28) > win逆向(10-14) > AI(09-25)
        assertEquals(listOf("文章", "软件集", "安卓逆向", "win逆向", "AI"), desc)
        assertEquals(desc.reversed(), sortStarLists(all, 1, descending = false).map { it.name })
    }

    @Test fun nameSortsAlphabeticallyAndCaseInsensitively() {
        val named = listOf(list("beta"), list("Alpha"), list("gamma"))
        assertEquals(
            listOf("Alpha", "beta", "gamma"),
            sortStarLists(named, 2, descending = false).map { it.name },
        )
    }

    @Test fun starCountSortsByRepositoryCountEitherWay() {
        assertEquals(listOf("软件集", "AI", "安卓逆向", "win逆向", "文章"), sortStarLists(all, 3, descending = true).map { it.name })
        assertEquals(listOf("文章", "win逆向", "安卓逆向", "AI", "软件集"), sortStarLists(all, 3, descending = false).map { it.name })
    }

    @Test fun listsWithoutTimestampsDoNotJumpToTheTop() {
        val missing = list("无时间")
        val asc = sortStarLists(listOf(missing, ai), LIST_SORT_UPDATED, descending = false).map { it.name }
        assertEquals("缺时间的排最后（升序时）", listOf("AI", "无时间"), asc)
    }

    @Test fun searchAndSortComposeTogether() {
        val shown = arrangeStarLists(all, "逆向", LIST_SORT_UPDATED, descending = true).map { it.name }
        // 只有两个命中，按 lastAddedAt 倒序：win逆向(10-22 05:24) 在 安卓逆向(10-22 02:27) 之前
        assertEquals(listOf("win逆向", "安卓逆向"), shown)
    }

    @Test fun searchMatchesDescriptionToo() {
        val tagged = listOf(list("未命名", description = "包含逆向关键词"))
        assertEquals(1, arrangeStarLists(tagged, "逆向", LIST_SORT_UPDATED, true).size)
    }
}
