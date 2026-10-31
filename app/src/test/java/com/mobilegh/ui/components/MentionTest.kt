package com.mobilegh.ui.components

import org.junit.Assert.*
import org.junit.Test

class MentionTest {
    @Test fun atAfterSpaceOrStartIsAMentionFragment() {
        assertEquals(MentionFragment(3, "ali"), mentionFragment("hi @ali", 7))
        assertEquals(MentionFragment(0, ""), mentionFragment("@", 1))
        assertEquals(MentionFragment(2, "bob"), mentionFragment("x @bob more", 6))
    }

    @Test fun emailAddressesAndPlainTextAreNotMentions() {
        assertNull(mentionFragment("a@b", 3))
        assertNull(mentionFragment("text", 4))
    }

    @Test fun spaceInsideTheFragmentEndsTheMention() {
        // 光标在 "more" 上，@ 之后已经有空格，不再是提及
        assertNull(mentionFragment("x @bob more", 11))
    }

    @Test fun invalidCursorIsIgnored() {
        assertNull(mentionFragment("@bob", -1))
        assertNull(mentionFragment("@bob", 9))
    }

    @Test fun candidatesArePrefixMatchesFirstAndLimited() {
        assertEquals(
            listOf("Albert", "ali", "alice"),
            mentionCandidates(listOf("bob", "alice", "Albert", "ali"), "al"),
        )
        assertEquals(8, mentionCandidates(List(20) { "x$it" }, "x").size)
    }

    @Test fun replacingFragmentInsertsLoginAndMovesCursorAfterIt() {
        val (text, cursor) = replaceMentionText("hi @al", 6, MentionFragment(3, "al"), "alice")
        assertEquals("hi @alice ", text)
        assertEquals(10, cursor)
    }
}
