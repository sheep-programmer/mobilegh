package com.mobilegh.ui.components

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.mobilegh.data.SavedReply
import org.junit.Assert.assertEquals
import org.junit.Test

class SavedReplyInsertTest {
    @Test fun insertsAtCursorAndMovesCursorToTheEnd() {
        val value = TextFieldValue("Before after", TextRange(7))
        val out = insertSavedReply(value, "Thanks!")
        assertEquals("Before Thanks!after", out.text)
        assertEquals(TextRange(14), out.selection)
    }

    @Test fun replacesTheWholeSelection() {
        val value = TextFieldValue("keep old text tail", TextRange(5, 13))
        val out = insertSavedReply(value, "new")
        assertEquals("keep new tail", out.text)
        assertEquals(TextRange(8), out.selection)
    }

    @Test fun emptyReplyReplacesSelectionWithoutMovingPastItsStart() {
        val value = TextFieldValue("abc", TextRange(1, 3))
        val out = insertSavedReply(value, "")
        assertEquals("a", out.text)
        assertEquals(TextRange(1), out.selection)
    }

    @Test fun localSearchMatchesTitleAndBodyWithoutCaseSensitivity() {
        val replies = listOf(
            SavedReply("1", "Thanks", "Appreciate the contribution"),
            SavedReply("2", "Needs info", "Please provide logs"),
            SavedReply("3", "Duplicate", "See the existing report"),
        )
        assertEquals(listOf("1"), filterSavedReplies(replies, "appreciate").map { it.id })
        assertEquals(listOf("2"), filterSavedReplies(replies, "INFO").map { it.id })
        assertEquals(replies, filterSavedReplies(replies, "  "))
    }

    @Test fun managementLinkUsesTheConfiguredEnterpriseWebOrigin() {
        assertEquals(
            "https://git.example.corp/settings/replies",
            savedRepliesSettingsUrl("https://git.example.corp/"),
        )
    }
}
