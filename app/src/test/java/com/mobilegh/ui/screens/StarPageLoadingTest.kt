package com.mobilegh.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StarPageLoadingTest {
    @Test
    fun defaultAllStarsTabDoesNotLoadTheListCatalog() {
        assertFalse(shouldLoadStarListCatalog(signedIn = true, tab = 0))
        assertTrue(shouldLoadStarListCatalog(signedIn = true, tab = 1))
        assertFalse(shouldLoadStarListCatalog(signedIn = false, tab = 1))
    }
}
