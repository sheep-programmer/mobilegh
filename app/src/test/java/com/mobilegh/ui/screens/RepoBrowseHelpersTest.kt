package com.mobilegh.ui.screens

import com.mobilegh.data.CommitFile
import com.mobilegh.data.Commit
import com.mobilegh.data.CommitInfo
import com.mobilegh.data.GitActor
import com.mobilegh.data.Content
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RepoBrowseHelpersTest {
    @Test fun fileEntryDetailsDescribeFilesAndFolders() {
        assertEquals("文件", fileEntryKind(Content(name = "main.kt", type = "file")))
        assertEquals("文件夹", fileEntryKind(Content(name = "src", type = "dir")))
        assertEquals("12 B", fileEntrySize(Content(name = "a.txt", type = "file", size = 12)))
    }

    @Test fun fileRowsUseTheFirstCommitLineAndKeepTheCommitDateSeparate() {
        val commit = Commit(
            commit = CommitInfo(
                message = "Fix parser\n\nLonger explanation",
                author = GitActor(date = "2026-11-24T08:30:00Z"),
            ),
        )

        assertEquals("Fix parser", fileCommitMessage(commit))
        assertEquals("2026-11-24T08:30:00Z", fileCommitDate(commit))
    }

    @Test fun readmeIndexFollowsTheLanguageBarPosition() {
        // 0 头部、1 按钮、2 分支行、3 快捷入口，有语言条时它占第 4 项
        assertEquals(4, repoReadmeIndex(hasLanguages = false))
        assertEquals(5, repoReadmeIndex(hasLanguages = true))
    }

    @Test fun jumpLineAcceptsOnlyLinesInsideTheFile() {
        assertEquals(42, parseJumpLine(" 42 ", 100))
        assertEquals(1, parseJumpLine("1", 100))
        assertEquals(100, parseJumpLine("100", 100))
        assertNull(parseJumpLine("0", 100))
        assertNull(parseJumpLine("101", 100))
        assertNull(parseJumpLine("", 100))
        assertNull(parseJumpLine("4a", 100))
    }

    @Test fun branchFilterIsCaseInsensitiveAndIgnoresBlankQueries() {
        assertTrue(refMatches("feature/Login", "login"))
        assertTrue(refMatches("main", ""))
        assertTrue(refMatches("main", "   "))
        assertEquals(false, refMatches("main", "dev"))
    }

    @Test fun patchIsParsedOnceAndTheResultIsCached() {
        val file = CommitFile(filename = "a.kt", status = "modified", patch = "@@ -1,2 +1,2 @@\n-old\n+new\n ctx")
        val pf = ParsedFile(file)
        val first = pf.lines
        assertEquals(4, first.size)
        assertSame(first, pf.lines)
        assertTrue(ParsedFile(CommitFile(filename = "b.bin", status = "modified")).lines.isEmpty())
    }
}
