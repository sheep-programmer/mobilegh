package com.mobilegh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 本轮 Issue 修复里的纯逻辑：错误说明、草稿列表 */
class IssueBatchFixesTest {
    @Test fun validationErrorIncludesFieldReason() {
        val body = """{"message":"Validation Failed","errors":[{"message":"Cannot update default branch for an empty repository.","field":"default_branch","code":"invalid"}]}"""
        assertEquals("Validation Failed：Cannot update default branch for an empty repository.", Api.errorMessage(body))
    }

    @Test fun errorMessageWithoutDetailsKeepsTopMessage() {
        assertEquals("Not Found", Api.errorMessage("""{"message":"Not Found"}"""))
        assertEquals("a；b", Api.errorMessage("""{"errors":["a","b"]}"""))
    }

    @Test fun draftListKeepsOriginAndNewestFirst() {
        val storage = MemoryRecords()
        val store = DraftStore(storage)
        store.save("mona", "comment:o/r:1", DraftValue(body = "old"), origin = "{\"type\":\"x\"}", now = 100)
        store.save("mona", "new-issue:o/r", DraftValue(title = "new"), origin = null, now = 200)
        store.save("hubot", "comment:o/r:9", DraftValue(body = "other account"), now = 300)
        val list = store.list("mona")
        assertEquals(listOf("new-issue:o/r", "comment:o/r:1"), list.map { it.meta?.context })
        assertEquals("{\"type\":\"x\"}", list[1].meta?.origin)
        // 编辑页面读到的草稿不带来源信息，和写入时一致
        assertNull(store.load("mona", "comment:o/r:1").meta)
        assertEquals("old", store.load("mona", "comment:o/r:1").body)
    }

    @Test fun removingDraftFromListDeletesIt() {
        val storage = MemoryRecords()
        val store = DraftStore(storage)
        store.save("mona", "comment:o/r:1", DraftValue(body = "x"), now = 1)
        val key = store.list("mona").single().key
        store.remove(key)
        assertTrue(store.list("mona").isEmpty())
        // 非草稿键不会被误删
        storage.write("other:key", "v")
        store.remove("other:key")
        assertEquals("v", storage.read("other:key"))
    }
}
