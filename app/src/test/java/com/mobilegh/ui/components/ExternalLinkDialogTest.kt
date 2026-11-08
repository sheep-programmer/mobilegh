package com.mobilegh.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalLinkDialogTest {

    @Test
    fun externalLinkDialogShowsAndDismisses() {
        ExternalLinkDialog.dismiss()
        assertNull(ExternalLinkDialog.current)

        ExternalLinkDialog.show("https://keepachangelog.com/zh-CN/1.1.0/", "Keep a Changelog")
        val req = ExternalLinkDialog.current
        assertNotNull(req)
        assertEquals("https://keepachangelog.com/zh-CN/1.1.0/", req!!.url)
        assertEquals("Keep a Changelog", req.label)

        ExternalLinkDialog.dismiss()
        assertNull(ExternalLinkDialog.current)
    }

    @Test
    fun ignoresEmptyOrSameLabel() {
        ExternalLinkDialog.show("https://example.com", "https://example.com")
        val req = ExternalLinkDialog.current
        assertNotNull(req)
        assertEquals("https://example.com", req!!.url)
        assertNull(req.label)

        ExternalLinkDialog.dismiss()
    }
}
