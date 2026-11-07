package com.mobilegh.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ClipboardLinkTrackerTest {
    private val text = "https://github.com/octocat/Hello-World"

    @Test fun returningFromAnotherAppPromptsAgainEvenForTheSameLink() {
        val tracker = ClipboardLinkTracker()
        assertEquals("octocat/Hello-World", tracker.inspect(listOf(text))?.link?.label)
        // 切到别的应用再切回来：同一条链接也要再提示
        tracker.onBackground()
        assertEquals("octocat/Hello-World", tracker.inspect(listOf(text))?.link?.label)
    }

    @Test fun oneForegroundSessionPromptsOnlyOnce() {
        val tracker = ClipboardLinkTracker()
        assertNotNull(tracker.inspect(listOf(text))?.link)
        // 弹窗拿走焦点又回来，同一次前台会话不重复提示
        assertNull(tracker.inspect(listOf(text)))
        assertNull(tracker.inspect(listOf("https://github.com/octocat/Other")))
    }

    @Test fun ordinaryTextDoesNotPromptAndDoesNotUseUpTheSession() {
        val tracker = ClipboardLinkTracker()
        assertNull(tracker.inspect(listOf("hello")))
        assertNull(tracker.inspect(emptyList()))
        // 普通文字之后复制的链接仍然可以提示
        assertNotNull(tracker.inspect(listOf(text))?.link)
    }

    @Test fun inAppCopyIsNotPromptedUntilTheAppReturnsFromBackground() {
        val tracker = ClipboardLinkTracker()
        tracker.note(listOf(text))
        assertNull(tracker.inspect(listOf(text)))
        tracker.onBackground()
        assertEquals("octocat/Hello-World", tracker.inspect(listOf(text))?.link?.label)
    }

    @Test fun aLinkCopiedInAnotherAppIsOfferedOnReturn() {
        val tracker = ClipboardLinkTracker()
        assertNotNull(tracker.inspect(listOf(text))?.link)
        tracker.onBackground()
        assertEquals(
            "octocat/Hello-World#31",
            tracker.inspect(listOf("https://github.com/octocat/Hello-World/issues/31"))?.link?.targetLabel,
        )
    }

    @Test fun onlyCompleteOrShortGithubLinksAreSuggested() {
        listOf(
            "https://github.com/octocat/Hello-World" to "octocat/Hello-World",
            "github.com/octocat/Hello-World" to "octocat/Hello-World",
            "octocat/Hello-World" to "octocat/Hello-World",
            "git@github.com:octocat/Hello-World.git" to "octocat/Hello-World",
            "https://github.com/octocat/Hello-World/issues/42" to "octocat/Hello-World",
        ).forEach { (input, expected) ->
            assertEquals(expected, ClipboardLinkTracker().inspect(listOf(input))?.link?.label)
        }
        listOf("hello", "看看这个仓库", "Hello-World", "github.com/octocat", "https://gitlab.com/octocat/Hello-World")
            .forEach { assertNull(it, ClipboardLinkTracker().inspect(listOf(it))?.link) }
    }
}
