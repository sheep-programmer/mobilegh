package com.mobilegh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitLinksTest {
    @Test fun candidatesTryTheShortestRefFirstAndStripRefPrefixes() {
        assertEquals(
            listOf("main" to "src/a.kt", "main/src" to "a.kt", "main/src/a.kt" to ""),
            GitLinks.candidates("main/src/a.kt"),
        )
        assertEquals(
            listOf("feature" to "x/a.kt", "feature/x" to "a.kt", "feature/x/a.kt" to ""),
            GitLinks.candidates("refs/heads/feature/x/a.kt"),
        )
        assertEquals(listOf("v1.0" to ""), GitLinks.candidates("v1.0"))
        assertEquals(emptyList<Pair<String, String>>(), GitLinks.candidates(""))
    }

    @Test fun directoryDetectionUsesTheObjectTypeOrAListing() {
        assertTrue(GitLinks.isDirectory("""{"type":"dir"}"""))
        assertFalse(GitLinks.isDirectory("""{"type":"file","size":6}"""))
        assertTrue(GitLinks.isDirectory("""[{"name":"a.md","type":"file"}]"""))
    }
}
