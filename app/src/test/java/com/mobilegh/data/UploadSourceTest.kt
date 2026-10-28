package com.mobilegh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadSourceTest {
    @Test fun hiddenDirectoriesAreIncludedExceptGitInternals() {
        assertTrue(UploadSource.shouldRecurseDirectory(".github"))
        assertTrue(UploadSource.shouldRecurseDirectory(".config"))
        assertFalse(UploadSource.shouldRecurseDirectory(".git"))
    }

    @Test fun exactFileLimitStillReportsObservedTruncation() {
        val entries = (1..UploadSource.MAX_FOLDER_FILES).map {
            UploadSource.Entry(it.toString(), "file-$it.txt", 1L)
        }
        val notes = mutableListOf<String>()
        val kept = with(UploadSource) { entries.limit(notes, truncated = true) }
        assertEquals(UploadSource.MAX_FOLDER_FILES, kept.size)
        assertTrue(notes.single(), notes.single().contains("超过"))
    }
}
