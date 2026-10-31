package com.mobilegh.ui.screens

import com.mobilegh.data.DraftOption
import com.mobilegh.data.DraftValue
import com.mobilegh.data.GitHub
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class IssueActionsTest {
    @Test fun checkoutCommandFetchesPullRefIntoLocalBranch() {
        assertEquals(
            "git fetch origin pull/42/head:pr-42 && git checkout pr-42",
            prCheckoutCommand(42),
        )
    }

    @Test fun multiSelectIsLockedOnlyAtTheLimitAndForUnselectedOptions() {
        assertTrue(optionLocked(single = false, selectedCount = 10, isSelected = false, maxSelect = 10))
        assertFalse(optionLocked(single = false, selectedCount = 10, isSelected = true, maxSelect = 10))
        assertFalse(optionLocked(single = false, selectedCount = 9, isSelected = false, maxSelect = 10))
        assertFalse(optionLocked(single = false, selectedCount = 50, isSelected = false, maxSelect = null))
        assertFalse(optionLocked(single = true, selectedCount = 1, isSelected = false, maxSelect = 1))
    }

    @Test fun onlyNonBlankChangesNeedDiscardConfirmation() {
        assertFalse(isUnsavedChange("a", "a"))
        assertFalse(isUnsavedChange("a", ""))
        assertFalse(isUnsavedChange("a", "   "))
        assertTrue(isUnsavedChange("a", "b"))
        assertTrue(isUnsavedChange("", "new text"))
    }

    @Test fun everyReactionHasItsOwnDescription() {
        // 每个表情都有文字说明，且不回退成 emoji 名字本身
        val labels = REACTIONS.map { (key, _) -> key to reactionLabel(key) }
        labels.forEach { (key, label) -> assertNotEquals(key, label) }
        assertEquals(REACTIONS.size, labels.map { it.second }.toSet().size)
        assertEquals("赞", reactionLabel("+1"))
    }

    @Test fun pagedCandidatesStopAtShortPageOrPageLimit() {
        runBlocking {
            val short = GitHub.collectPages(2, 5) { p ->
                when (p) {
                    1 -> listOf(1, 2)
                    2 -> listOf(3)
                    else -> emptyList()
                }
            }
            assertEquals(listOf(1, 2, 3), short)

            var calls = 0
            val capped = GitHub.collectPages(2, 2) { p ->
                calls++
                listOf(p, p)
            }
            assertEquals(4, capped.size)
            assertEquals(2, calls)
        }
    }

    @Test fun draftMetadataSurvivesSerializationAndCountsAsContent() {
        val draft = DraftValue(
            labels = listOf(DraftOption("bug", "bug", color = "ff0000")),
            assignees = listOf(DraftOption("alice", "alice", avatar = "https://example.test/a.png")),
            milestone = DraftOption("3", "v1"),
        )
        assertFalse(draft.empty)
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(draft, json.decodeFromString<DraftValue>(json.encodeToString(draft)))
        assertTrue(DraftValue().empty)
    }
}
