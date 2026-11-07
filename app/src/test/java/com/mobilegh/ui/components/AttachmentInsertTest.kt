package com.mobilegh.ui.components

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentInsertTest {
    @Test fun imagesUseMarkdownImageSyntax() {
        assertEquals("![a.png](https://github.com/user-attachments/assets/1)", attachmentMarkdown("https://github.com/user-attachments/assets/1", "a.png"))
    }

    @Test fun videosInsertTheBareLinkSoGitHubPlaysThem() {
        assertEquals("https://github.com/user-attachments/assets/2", attachmentMarkdown("https://github.com/user-attachments/assets/2", "clip.MP4"))
    }

    @Test fun insertsAtTheCursorWithLineBreaksAndKeepsTheCursorAfterIt() {
        val v = TextFieldValue("前文后文", TextRange(2))
        val out = insertAtCursor(v, "![x](u)")
        assertEquals("前文\n![x](u)\n后文", out.text)
        assertEquals(TextRange(2 + "\n![x](u)\n".length), out.selection)
    }

    @Test fun noExtraLeadingBreakWhenAlreadyAtALineStart() {
        val out = insertAtCursor(TextFieldValue("", TextRange(0)), "![x](u)")
        assertEquals("![x](u)\n", out.text)
        val afterNewline = insertAtCursor(TextFieldValue("a\n", TextRange(2)), "S")
        assertEquals("a\nS\n", afterNewline.text)
    }
}
