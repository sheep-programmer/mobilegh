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

    @Test
    fun outsiderCannotReopenOrClose() {
        val a = ThreadAccess.of(Permissions(pull = true), archived = false, author = "someone", me = "me")
        assertFalse(a.canReopen(locked = false))
        assertFalse(a.canReopen(locked = true))
        assertFalse(a.canClose(locked = false))
        assertFalse(a.canClose(locked = true))
    }

    @Test
    fun authorCanReopenAndCloseWhenUnlockedButNotWhenLocked() {
        val a = ThreadAccess.of(Permissions(pull = true), archived = false, author = "me", me = "me")
        assertTrue(a.canReopen(locked = false))
        assertTrue(a.canClose(locked = false))
        // 普通作者无法关闭或重新开启已被 Maintainer 锁定的 Issue
        assertFalse(a.canReopen(locked = true))
        assertFalse(a.canClose(locked = true))
    }

    @Test
    fun triageAndPushCanReopenAndCloseEvenWhenLocked() {
        val triageAccess = ThreadAccess.of(Permissions(triage = true, pull = true), archived = false, author = "someone", me = "me")
        assertTrue(triageAccess.canReopen(locked = true))
        assertTrue(triageAccess.canClose(locked = true))

        val pushAccess = ThreadAccess.of(Permissions(push = true), archived = false, author = "someone", me = "me")
        assertTrue(pushAccess.canReopen(locked = true))
        assertTrue(pushAccess.canClose(locked = true))
    }

    @Test
    fun mergedPrCannotBeReopenedEvenByCollaborators() {
        val a = ThreadAccess.of(Permissions(admin = true, push = true), archived = false, author = "me", me = "me")
        assertFalse(a.canReopen(locked = false, merged = true))
        assertFalse(a.canReopen(locked = true, merged = true))
    }

    @Test
    fun authorCannotReopenWhenClosedAsNotPlannedOrClosedByOther() {
        val authorAccess = ThreadAccess.of(Permissions(pull = true), archived = false, author = "me", me = "me")
        // 普通作者无法重新开启标记为 not_planned 的 issue
        assertFalse(authorAccess.canReopen(locked = false, stateReason = "not_planned"))
        // 普通作者无法重新开启被管理员/他人关闭的 issue
        assertFalse(authorAccess.canReopen(locked = false, closedByLogin = "maintainer", me = "me"))
        // 普通作者自己关闭且正常完成的 issue 可以重新开启
        assertTrue(authorAccess.canReopen(locked = false, stateReason = "completed", closedByLogin = "me", me = "me"))

        // 协作者均可重新开启
        val triageAccess = ThreadAccess.of(Permissions(triage = true, pull = true), archived = false, author = "someone", me = "me")
        assertTrue(triageAccess.canReopen(locked = false, stateReason = "not_planned"))
        assertTrue(triageAccess.canReopen(locked = false, closedByLogin = "other", me = "me"))
    }

    @Test
    fun archivedRepoCannotReopenOrClose() {
        val a = ThreadAccess.of(Permissions(admin = true, push = true), archived = true, author = "me", me = "me")
        assertFalse(a.canReopen(locked = false))
        assertFalse(a.canClose(locked = false))
    }
}
