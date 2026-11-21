package com.mobilegh.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class RepoStarStateTest {
    @Test fun pendingStarRequestStartsFromAnActionableUnstarredState() {
        assertEquals(false, effectiveStarState(null))
        assertEquals(false, effectiveStarState(false))
        assertEquals(true, effectiveStarState(true))
    }
}
