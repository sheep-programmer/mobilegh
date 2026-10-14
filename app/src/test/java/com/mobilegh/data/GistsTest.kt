package com.mobilegh.data

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GistsTest {
    private fun existing(name: String, content: String, truncated: Boolean = false) =
        GistDraftFile(original = name, filename = name, content = if (truncated) "" else content, truncated = truncated, originalContent = if (truncated) "" else content)

    private fun JsonObject.files() = this["files"]!!.jsonObject

    @Test fun createPayloadKeepsEveryFileAndVisibility() {
        val body = gistCreatePayload(
            "  demo  ", public = false,
            listOf(GistDraftFile(filename = " a.kt ", content = "fun a() {}"), GistDraftFile(filename = "b.md", content = "# B")),
        )
        assertEquals("demo", body["description"]!!.jsonPrimitive.content)
        assertFalse(body["public"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("a.kt", "b.md"), body.files().keys.toList())
        assertEquals("fun a() {}", body.files()["a.kt"]!!.jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test fun validationRejectsBlankDuplicateAndEmptyFiles() {
        assertNotNull(gistFilesError(emptyList()))
        assertNotNull(gistFilesError(listOf(GistDraftFile(filename = "", content = "x"))))
        assertNotNull(gistFilesError(listOf(GistDraftFile(filename = "a.txt", content = "  "))))
        assertNotNull(gistFilesError(listOf(GistDraftFile(filename = "dir/a.txt", content = "x"))))
        assertNotNull(gistFilesError(listOf(GistDraftFile(filename = "a.txt", content = "x"), GistDraftFile(filename = " a.txt", content = "y"))))
        assertNull(gistFilesError(listOf(GistDraftFile(filename = "a.txt", content = "x"))))
    }

    @Test fun validationAllowsTruncatedFilesWithoutContent() {
        assertNull(gistFilesError(listOf(existing("big.log", "", truncated = true))))
    }

    @Test fun newFileCannotReuseANameBeingRemovedOrRenamed() {
        val renamed = existing("a.txt", "x").copy(filename = "b.txt")
        assertNotNull(gistFilesError(listOf(renamed, GistDraftFile(filename = "a.txt", content = "new"))))
        assertNotNull(gistFilesError(listOf(GistDraftFile(filename = "gone.txt", content = "new")), removed = listOf("gone.txt")))
        // 改回原名不算冲突
        assertNull(gistFilesError(listOf(existing("a.txt", "x"))))
    }

    @Test fun unchangedGistProducesNoPatch() {
        assertNull(gistPatchPayload("desc", " desc ", listOf(existing("a.txt", "x")), emptyList()))
        assertNull(gistPatchPayload(null, "", listOf(existing("a.txt", "x")), emptyList()))
    }

    @Test fun descriptionOnlyChangeOmitsFiles() {
        val patch = gistPatchPayload("old", "new", listOf(existing("a.txt", "x")), emptyList())!!
        assertEquals("new", patch["description"]!!.jsonPrimitive.content)
        assertFalse(patch.containsKey("files"))
        assertFalse(patch.containsKey("public"))
    }

    @Test fun patchDescribesRenameEditAddAndDelete() {
        val files = listOf(
            existing("keep.txt", "same"),
            existing("edit.txt", "old").copy(content = "new"),
            existing("old-name.txt", "body").copy(filename = "new-name.txt"),
            existing("both.txt", "1").copy(filename = "both.md", content = "2"),
            GistDraftFile(filename = "added.py", content = "print(1)"),
        )
        val patch = gistPatchPayload("d", "d", files, removed = listOf("deleted.txt"))!!
        val changes = patch.files()
        assertFalse(changes.containsKey("keep.txt"))
        assertEquals(JsonNull, changes["deleted.txt"])
        assertEquals("new", changes["edit.txt"]!!.jsonObject["content"]!!.jsonPrimitive.content)
        assertFalse(changes["edit.txt"]!!.jsonObject.containsKey("filename"))
        val renamed = changes["old-name.txt"]!!.jsonObject
        assertEquals("new-name.txt", renamed["filename"]!!.jsonPrimitive.content)
        assertFalse("renaming alone must not resend content", renamed.containsKey("content"))
        val both = changes["both.txt"]!!.jsonObject
        assertEquals("both.md", both["filename"]!!.jsonPrimitive.content)
        assertEquals("2", both["content"]!!.jsonPrimitive.content)
        assertEquals("print(1)", changes["added.py"]!!.jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test fun truncatedFilesNeverSendTheirPartialContent() {
        val renamed = existing("big.log", "", truncated = true).copy(filename = "big.txt", content = "partial")
        val patch = gistPatchPayload("d", "d", listOf(renamed), emptyList())!!
        val change = patch.files()["big.log"]!!.jsonObject
        assertEquals("big.txt", change["filename"]!!.jsonPrimitive.content)
        assertFalse(change.containsKey("content"))
        assertNull(gistPatchPayload("d", "d", listOf(existing("big.log", "", truncated = true)), emptyList()))
    }

    @Test fun draftFilesComeFromServerFilesAndBlankTruncatedContent() {
        val drafts = gistDraftFiles(mapOf(
            "a.txt" to GistFile(filename = "a.txt", content = "hello"),
            "big.bin" to GistFile(filename = "big.bin", content = "part", truncated = true),
        ))
        assertEquals(listOf("a.txt", "big.bin"), drafts.map { it.original })
        assertEquals("hello", drafts[0].content)
        assertTrue(drafts[1].truncated)
        assertEquals("", drafts[1].content)
        // 不改动直接保存不会写任何东西
        assertNull(gistPatchPayload(null, "", drafts, emptyList()))
    }
}
