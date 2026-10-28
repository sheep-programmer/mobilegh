package com.mobilegh.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SplitDiffTest {
    private val patch = """
        @@ -1,4 +1,5 @@
         keep
        -old one
        -old two
        +new one
        +new two
        +new three
         tail
        \ No newline at end of file
    """.trimIndent()

    @Test fun contextLinesAppearOnBothSidesAndHunkHeadersSpanBoth() {
        val rows = splitRows(parsePatch(patch))
        assertEquals('@', rows[0].note?.kind)
        assertNull(rows[0].left)
        assertSame(rows[1].left, rows[1].right)
        assertEquals("keep", rows[1].left?.text)
        assertEquals("tail", rows[5].left?.text)
        assertEquals('\\', rows.last().note?.kind)
    }

    @Test fun changedBlocksPairOldAndNewLinesSideBySide() {
        val rows = splitRows(parsePatch(patch))
        assertEquals("old one" to "new one", rows[2].left?.text to rows[2].right?.text)
        assertEquals("old two" to "new two", rows[3].left?.text to rows[3].right?.text)
        // 新增比删除多时，多出来的一行左侧留空
        assertNull(rows[4].left)
        assertEquals("new three", rows[4].right?.text)
        assertEquals(2, rows[2].left?.oldNo)
        assertEquals(2, rows[2].right?.newNo)
    }

    @Test fun pureAdditionsAndDeletionsKeepTheOtherSideEmpty() {
        val added = splitRows(parsePatch("@@ -0,0 +1,2 @@\n+a\n+b"))
        assertEquals(listOf(null, null), added.drop(1).map { it.left })
        assertEquals(listOf("a", "b"), added.drop(1).map { it.right?.text })
        val removed = splitRows(parsePatch("@@ -1,2 +0,0 @@\n-a\n-b"))
        assertEquals(listOf("a", "b"), removed.drop(1).map { it.left?.text })
        assertEquals(listOf(null, null), removed.drop(1).map { it.right })
    }
}
