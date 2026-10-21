package com.mobilegh.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadAccessTest {
    @Test
    fun outsiderGetsNoWriteEntries() {
        val a = ThreadAccess.of(Permissions(pull = true), archived = false, author = "someone", me = "me")
        assertFalse(a.editTitle)
        assertFalse(a.editMeta)
        assertFalse(a.changeState)
        assertFalse(a.merge)
        assertFalse(a.toggleDraft)
    }

    @Test
    fun authorCanEditAndCloseOwnThreadWithoutRepoAccess() {
        val a = ThreadAccess.of(Permissions(pull = true), archived = false, author = "Me", me = "me")
        assertTrue(a.editTitle)
        assertTrue(a.changeState)
        assertTrue(a.toggleDraft)
        assertFalse(a.editMeta)
        assertFalse(a.merge)
    }

    @Test
    fun triageCanManageMetadataButNotMerge() {
        val a = ThreadAccess.of(Permissions(triage = true, pull = true), archived = false, author = "someone", me = "me")
        assertTrue(a.editMeta)
        assertTrue(a.changeState)
        assertFalse(a.editTitle)
        assertFalse(a.merge)
    }

    @Test
    fun pushGrantsEverything() {
        val a = ThreadAccess.of(Permissions(push = true), archived = false, author = "someone", me = "me")
        assertTrue(a.editTitle && a.editMeta && a.changeState && a.merge && a.toggleDraft)
    }

    @Test
    fun unknownPermissionsOrArchivedRepoHideEverything() {
        assertFalse(ThreadAccess.of(null, archived = false, author = "me", me = "me").editTitle)
        assertFalse(ThreadAccess.of(Permissions(admin = true), archived = true, author = "me", me = "me").changeState)
    }
}
