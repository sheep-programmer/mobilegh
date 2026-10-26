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
}
