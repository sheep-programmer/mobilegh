package com.mobilegh.ui.screens

import com.mobilegh.data.RepositoryListMemberships
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StarListPickerStateTest {
    @Test fun confirmedMembershipReplacesSelectionWhenThereIsNoNewerEdit() {
        val picked = starListPickedAfterWrite(
            wanted = setOf("A", "B"),
            picked = setOf("A", "B"),
            confirmed = setOf("B"),
        )
        assertEquals(setOf("B"), picked)
    }

    @Test fun aNewerUserEditIsNotOverwrittenByTheWriteResponse() {
        val picked = starListPickedAfterWrite(
            wanted = setOf("A", "B"),
            picked = setOf("B", "D"),
            confirmed = setOf("A", "B", "C"),
        )
        assertEquals(setOf("B", "D"), picked)
    }

    @Test fun creatingAListWithARepositoryRequiresConfirmedAddableMembership() {
        val starred = RepositoryListMemberships("R_1", true, emptyList(), emptySet())
        val unstarred = starred.copy(starred = false)
        assertFalse(canCreateListWithRepository(null, mine = false))
        assertTrue(canCreateListWithRepository(starred, mine = false))
        assertFalse(canCreateListWithRepository(unstarred, mine = false))
        assertTrue(canCreateListWithRepository(unstarred, mine = true))
    }

    @Test fun listChangeFeedbackNamesTheListsThatWereAddedOrRemoved() {
        val lists = listOf(
            com.mobilegh.data.GitHubStarList(id = "a", name = "前端", description = "", isPrivate = false, slug = "a", owner = "me", count = 1),
            com.mobilegh.data.GitHubStarList(id = "b", name = "工具", description = "", isPrivate = false, slug = "b", owner = "me", count = 1),
        )
        assertEquals(null, starListChangeMessage(setOf("a"), setOf("a"), lists))
        assertTrue(starListChangeMessage(emptySet(), setOf("a"), lists)!!.contains("前端"))
        assertTrue(starListChangeMessage(setOf("a"), emptySet(), lists)!!.contains("前端"))
        val both = starListChangeMessage(setOf("a"), setOf("b"), lists)!!
        assertTrue(both.contains("工具") && both.contains("前端"))
    }
}
