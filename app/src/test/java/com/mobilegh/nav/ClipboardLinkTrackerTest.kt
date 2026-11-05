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

    @Test fun copyingALinkAgainDoesNotPromptTwiceInOneSession() {
        val tracker = ClipboardLinkTracker()
        tracker.inspect(100, listOf(text))
        assertNull(tracker.inspect(101, listOf(text))?.link)
    }

    @Test fun ordinaryTextOrClearedClipboardDoesNotSuggest() {
        val tracker = ClipboardLinkTracker()
        assertNull(tracker.inspect(100, listOf("hello"))?.link)
        assertNull(tracker.inspect(100, listOf("hello")))
        assertEquals(ClipboardLinkTracker.Update(null), tracker.inspect(0, emptyList()))
        // 普通文字之后复制链接仍然可以提示
        assertNotNull(tracker.inspect(101, listOf(text))?.link)
    }

    @Test fun allClipboardItemsAreCheckedAndContentChangeWithoutTimestampIsHandled() {
        val tracker = ClipboardLinkTracker()
        assertNotNull(tracker.inspect(0, listOf("hello", text))?.link)
        // 本次启动已经提示过，后续内容变化不再提示
        assertNull(tracker.inspect(0, listOf("https://github.com/octocat/Other"))?.link)
    }

    @Test fun noteSuppressesSuggestionForContentCopiedInsideTheApp() {
        val tracker = ClipboardLinkTracker()
        tracker.note(100, listOf(text))
        // 应用内「复制链接」的当下不弹提示
        assertNull(tracker.inspect(100, listOf(text))?.link)
        assertNull(tracker.inspect(101, listOf(text))?.link)
        // 之后从外部复制的新链接仍然可以提示
        assertNotNull(tracker.inspect(102, listOf("https://github.com/octocat/Other"))?.link)
    }

    @Test fun anInAppCopyIsOfferedAfterTheAppReturnsFromBackground() {
        val tracker = ClipboardLinkTracker()
        tracker.note(100, listOf(text))
        assertNull(tracker.inspect(100, listOf(text))?.link)
        // 退到后台再回来：应用内复制的链接应该像从外部复制的一样被识别，只提示一次
        tracker.onBackground()
        assertEquals("octocat/Hello-World", tracker.inspect(100, listOf(text))?.link?.label)
        tracker.onBackground()
        assertNull(tracker.inspect(100, listOf(text))?.link)
    }

    @Test fun aLinkCopiedInAnotherAppWhileInBackgroundIsOfferedOnReturn() {
        val tracker = ClipboardLinkTracker()
        assertNotNull(tracker.inspect(100, listOf(text))?.link)
        // 本次前台会话已经提示过，不因焦点变化再弹
        assertNull(tracker.inspect(101, listOf("https://github.com/octocat/Other"))?.link)
        // 用户在别的应用复制了新链接后回来
        tracker.onBackground()
        assertEquals(
            "octocat/Hello-World#31",
            tracker.inspect(200, listOf("https://github.com/octocat/Hello-World/issues/31"))?.link?.targetLabel,
        )
        // 同一条链接不会在切换前后台时反复提示
        tracker.onBackground()
        assertNull(tracker.inspect(200, listOf("https://github.com/octocat/Hello-World/issues/31"))?.link)
    }

    @Test fun onlyCompleteOrShortGithubLinksAreSuggested() {
        val tracker = ClipboardLinkTracker()
        // 完整链接、无协议地址、owner/repo、克隆地址都可以
        listOf(
            (0 to listOf("https://github.com/octocat/Hello-World")) to "octocat/Hello-World",
            (1 to listOf("github.com/octocat/Hello-World")) to "octocat/Hello-World",
            (2 to listOf("octocat/Hello-World")) to "octocat/Hello-World",
            (3 to listOf("git@github.com:octocat/Hello-World.git")) to "octocat/Hello-World",
            (4 to listOf("https://github.com/octocat/Hello-World/issues/42")) to "octocat/Hello-World",
        ).forEach { (input, expected) ->
            assertEquals(expected, ClipboardLinkTracker().inspect(input.first.toLong(), input.second)?.link?.label)
        }
        // 普通文字、半截链接、非 GitHub 地址都不提示
        listOf("hello", "看看这个仓库", "Hello-World", "github.com/octocat", "https://gitlab.com/octocat/Hello-World")
            .forEach { assertNull(it, ClipboardLinkTracker().inspect(100, listOf(it))?.link) }
    }

    @Test fun aSuggestionIsShownOnlyOncePerAppSession() {
        val first = ClipboardLinkTracker()
        assertNotNull(first.inspect(100, listOf(text))?.link)
        // 新复制的同一个链接在本次启动内不再提示
        assertNull(first.inspect(101, listOf(text))?.link)
        assertNull(first.inspect(102, listOf("https://github.com/octocat/Other"))?.link)
        // 下次启动是新的 tracker：仍然可以提示一次
        val nextLaunch = ClipboardLinkTracker()
        assertNotNull(nextLaunch.inspect(101, listOf(text))?.link)
    }
}
