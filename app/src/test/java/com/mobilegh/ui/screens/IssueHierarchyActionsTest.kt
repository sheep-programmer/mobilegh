package com.mobilegh.ui.screens

import com.mobilegh.data.IssueRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IssueHierarchyActionsTest {
    private fun ref(id: String, number: Int = 1) = IssueRef(id, number, "Issue $number", "open", "mona", "repo")

    @Test fun pickerExcludesCurrentAndAlreadyLinkedIssues() {
        val available = hierarchyCandidates(
            currentNodeId = "I_current",
            children = listOf(ref("I_child", 2)),
            issues = listOf(ref("I_current", 1), ref("I_child", 2), ref("I_other", 3)),
        )
        assertEquals(listOf("I_other"), available.map { it.nodeId })
    }

    @Test fun replacingParentUsesSingleReplaceMutationWithoutUnlinkingFirst() = runBlocking {
        val calls = mutableListOf<Triple<String, String, Boolean>>()
        val result = IssueHierarchyActions.replaceParent("I_current", "I_new") { parent, child, replace ->
            calls += Triple(parent, child, replace)
        }
        assertTrue(result.isSuccess)
        assertEquals(listOf(Triple("I_new", "I_current", true)), calls)
    }

    @Test fun removingChildAndParentKeepTheirOppositeIdDirections() = runBlocking {
        val removals = mutableListOf<Pair<String, String>>()
        IssueHierarchyActions.removeChild("I_current", "I_child") { issue, child -> removals += issue to child }
        IssueHierarchyActions.removeParent("I_parent", "I_current") { parent, current -> removals += parent to current }
        assertEquals(listOf("I_current" to "I_child", "I_parent" to "I_current"), removals)
    }

    @Test fun relationshipFailureIsReturnedForInlineErrorWithoutLosingIssueContent() = runBlocking {
        val result = IssueHierarchyActions.replaceParent("I_current", "I_new") { _, _, _ -> error("read-only token") }
        assertEquals("read-only token", result.exceptionOrNull()?.message)
    }
}
