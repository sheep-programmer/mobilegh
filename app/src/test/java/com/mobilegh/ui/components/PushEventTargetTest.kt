package com.mobilegh.ui.components

import com.mobilegh.data.Event
import com.mobilegh.nav.Screen
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 首页动态里点一条推送：应该打开这次推送前后的对比，而不是整个分支的提交历史。 */
class PushEventTargetTest {
    private fun payload(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test fun normalPushOpensThePushedRange() {
        val s = pushEventTarget("torvalds", "linux", "master", payload(
            """{"ref":"refs/heads/master","before":"aaaa111","head":"bbbb222","size":2,"commits":[]}""",
        ))
        assertEquals(Screen.Compare("torvalds", "linux", base = "aaaa111", head = "bbbb222"), s)
    }

    @Test fun newBranchFallsBackToBranchCommits() {
        val zero = pushEventTarget("o", "r", "main", payload("""{"ref":"refs/heads/main","before":"0000000000000000","head":"c3"}"""))
        assertTrue(zero is Screen.Commits)
        assertEquals("main", (zero as Screen.Commits).ref)
    }

    @Test fun missingHeadFallsBackToBranchCommits() {
        val s = pushEventTarget("o", "r", "dev", payload("""{"ref":"refs/heads/dev","before":"aaaa"}"""))
        assertTrue(s is Screen.Commits)
    }
}
