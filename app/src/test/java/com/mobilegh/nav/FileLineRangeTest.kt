package com.mobilegh.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileLineRangeTest {
    @Test fun singleLineAndRangeAnchorsAreParsed() {
        assertEquals(42..42, Links.lineRange("https://github.com/octocat/Hello-World/blob/main/src/A.kt#L42"))
        assertEquals(42..50, Links.lineRange("https://github.com/octocat/Hello-World/blob/main/src/A.kt#L42-L50"))
        assertEquals(42..50, Links.lineRange("https://github.com/octocat/Hello-World/blob/main/src/A.kt#L42-50"))
    }

    @Test fun missingOrInvalidAnchorsAreIgnored() {
        assertNull(Links.lineRange("https://github.com/octocat/Hello-World/blob/main/src/A.kt"))
        assertNull(Links.lineRange("https://github.com/octocat/Hello-World/blob/main/src/A.kt#readme"))
        assertNull(Links.lineRange("https://github.com/octocat/Hello-World/blob/main/src/A.kt#L0"))
        assertNull(Links.lineRange("https://github.com/octocat/Hello-World/blob/main/src/A.kt#L50-L42"))
    }
}
