package com.mobilegh.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ForkOptionsTest {
    /**
     * GitHub 的 fork 接口只认 organization / name / default_branch_only 三个字段，
     * 多发的字段会被静默忽略、少发的会退回服务端默认值，所以必须逐字确认。
     */
    @Test fun forkBodySendsOnlyTheThreeSupportedFields() {
        val body = GitHub.forkBody(organization = "mobilegh-org", newName = "mirror", defaultBranchOnly = false)
        assertEquals(setOf("organization", "name", "default_branch_only"), body.keys)
        assertEquals("mobilegh-org", body["organization"]!!.jsonPrimitive.content)
        assertEquals("mirror", body["name"]!!.jsonPrimitive.content)
        assertFalse(body["default_branch_only"]!!.jsonPrimitive.boolean)
    }

    /** 留空表示「不改名 / 不进组织」，此时必须整字段省略，不能发空字符串覆盖对方的默认行为。 */
    @Test fun blankOrganizationAndNameAreOmitted() {
        val body = GitHub.forkBody(organization = "   ", newName = "", defaultBranchOnly = true)
        assertNull(body["organization"])
        assertNull(body["name"])
        assertTrue(body["default_branch_only"]!!.jsonPrimitive.boolean)
        assertEquals(setOf("default_branch_only"), body.keys)
    }

    /** fork 接口本身不接受 description，必须确保不会被误加到请求体里。 */
    @Test fun forkBodyNeverCarriesDescription() {
        val body = GitHub.forkBody(organization = null, newName = null, defaultBranchOnly = true)
        assertNull(body["description"])
    }

    @Test fun repositoryNamesUseRepositoryRulesInsteadOfGitBranchRules() {
        listOf("repo..name", ".github", "a_b-c.d", "a".repeat(100)).forEach {
            assertTrue("合法仓库名被拒绝：$it", validForkName(it))
        }
        listOf("", ".", "..", "a/b", "a b", "a@b", "a".repeat(101)).forEach {
            assertFalse("非法仓库名被接受：$it", validForkName(it))
        }
    }

    @Test fun descriptionWaitsUntilAnAsynchronouslyCreatedForkIsReadable() = runBlocking {
        val events = mutableListOf<String>()
        var reads = 0
        finishFork(
            "new description",
            read = {
                events += "read"
                if (++reads < 3) throw ApiException(404, "not ready")
            },
            update = { events += "write:$it" },
            pause = { events += "wait" },
        )
        assertEquals(listOf("read", "wait", "read", "wait", "read", "write:new description"), events)
    }

    @Test fun descriptionTimeoutDoesNotPretendTheDescriptionWasWritten() = runBlocking {
        var reads = 0
        val writes = mutableListOf<String>()
        assertThrows(ApiException::class.java) {
            runBlocking {
                finishFork(
                    "new description",
                    read = { reads++; throw ApiException(404, "not ready") },
                    update = { writes += it },
                    pause = {},
                )
            }
        }
        assertTrue(reads > 1)
        assertTrue(writes.isEmpty())
    }

    @Test fun descriptionDoesNotRetryPermissionOrNetworkFailures() = runBlocking {
        var reads = 0
        val failure = assertThrows(ApiException::class.java) {
            runBlocking {
                finishFork(
                    "new description",
                    read = { reads++; throw ApiException(403, "forbidden") },
                    update = { error("must not write after a failed readiness check") },
                    pause = {},
                )
            }
        }
        assertEquals(403, failure.code)
        assertEquals(1, reads)
    }

    @Test fun unchangedDescriptionStillWaitsForTheForkToBecomeReadable() = runBlocking {
        val events = mutableListOf<String>()
        var reads = 0
        finishFork(
            null,
            read = {
                events += "read"
                if (++reads < 2) throw ApiException(404, "not ready")
            },
            update = { events += "unexpected write:$it" },
            pause = { events += "wait" },
        )
        assertEquals(listOf("read", "wait", "read"), events)
    }

    @Test fun descriptionUpdate404IsNotRetriedAsAReadinessFailure() = runBlocking {
        var reads = 0
        var writes = 0
        val failure = assertThrows(ApiException::class.java) {
            runBlocking {
                finishFork(
                    "new description",
                    read = { reads++ },
                    update = { writes++; throw ApiException(404, "description update failed") },
                    pause = {},
                )
            }
        }
        assertEquals("description update failed", failure.message)
        assertEquals(1, reads)
        assertEquals(1, writes)
    }

    @Test fun nullOrganizationAndNameAreOmitted() {
        val body = GitHub.forkBody(organization = null, newName = null, defaultBranchOnly = false)
        assertEquals(setOf("default_branch_only"), body.keys)
    }
}
