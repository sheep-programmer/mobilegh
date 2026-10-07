package com.mobilegh.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ClipboardLinkTrackerTest {
    private val text = "https://github.com/octocat/Hello-World"

    @Test fun unchangedClipboardIsNotSuggestedAgainWhenFocusReturns() {
        val tracker = ClipboardLinkTracker()
        assertEquals("octocat/Hello-World", tracker.inspect(100, listOf(text))?.link?.label)
        assertNull(tracker.inspect(100, listOf(text)))
    }

    @Test fun copyingTheSameLinkAgainCountsAsANewClipboardEvent() {
        val tracker = ClipboardLinkTracker()
        tracker.inspect(100, listOf(text))
        assertNotNull(tracker.inspect(101, listOf(text))?.link)
    }

    @Test fun ordinaryTextOrClearedClipboardRemovesThePreviousSuggestion() {
        val tracker = ClipboardLinkTracker()
        tracker.inspect(100, listOf(text))
        assertNull(tracker.inspect(101, listOf("hello"))?.link)
        assertNull(tracker.inspect(101, listOf("hello")))
        assertNotNull(tracker.inspect(0, emptyList()))
    }

    @Test fun allClipboardItemsAreCheckedAndContentChangeWithoutTimestampIsHandled() {
        val tracker = ClipboardLinkTracker()
        assertNotNull(tracker.inspect(0, listOf("hello", text))?.link)
        assertEquals("octocat/Other", tracker.inspect(0, listOf("https://github.com/octocat/Other"))?.link?.label)
    }
}
