package com.mobilegh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommitPushesTest {
    private fun c(sha: String, login: String?, at: String, message: String = "m", parent: String? = null) = Commit(
        sha = sha,
        commit = CommitInfo(message = message, author = GitActor(name = login, date = at)),
        author = login?.let { User(login = it) },
        parents = parent?.let { listOf(ShaRef(it)) } ?: emptyList(),
    )

    @Test fun commitsBySameAuthorWithinWindowFormOnePush() {
        val list = listOf(
            c("c3", "mona", "2026-11-07T10:05:00Z", "3"),
            c("c2", "mona", "2026-11-07T10:03:00Z", "2"),
            c("c1", "mona", "2026-11-07T10:00:00Z", "1", parent = "base"),
        )
        val p = splitPushes("refs/heads/main", list)
        assertEquals(1, p.size)
        assertEquals("main", p[0].ref)
        assertEquals(3, p[0].commitCount)
        assertEquals("c3", p[0].headSha)
        assertEquals("3", p[0].headMessage)
        assertEquals("base", p[0].baseSha)
        assertEquals("mona", p[0].authorLogin)
    }

    @Test fun gapOrDifferentAuthorStartsNewPush() {
        val gap = listOf(
            c("b", "mona", "2026-11-07T12:00:00Z"),
            c("a", "mona", "2026-11-07T09:00:00Z"), // 3 小时间隔
        )
        assertEquals(listOf(1, 1), splitPushes("refs/heads/main", gap).map { it.commitCount })

        val other = listOf(
            c("b", "hacker", "2026-11-07T10:00:30Z"),
            c("a", "mona", "2026-11-07T10:00:00Z"),
        )
        assertEquals(listOf(1, 1), splitPushes("refs/heads/main", other).map { it.commitCount })
    }

    @Test fun tagRefAndMissingParentAreHandled() {
        val p = splitPushes("refs/tags/v1.0", listOf(c("a", "mona", "2026-11-07T10:00:00Z"))).single()
        assertEquals("v1.0", p.ref)
        assertEquals(true, p.isTag)
        assertNull(p.baseSha)
    }

    @Test fun emptyAndUnknownTimesStayUsable() {
        assertEquals(emptyList<CommitPush>(), splitPushes("refs/heads/main", emptyList()))
        val noTime = listOf(Commit(sha = "x"), Commit(sha = "y"))
        assertEquals(2, splitPushes("refs/heads/main", noTime).size)
    }
}
