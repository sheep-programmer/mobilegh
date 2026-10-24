package com.mobilegh.data

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun nullOrganizationAndNameAreOmitted() {
        val body = GitHub.forkBody(organization = null, newName = null, defaultBranchOnly = false)
        assertEquals(setOf("default_branch_only"), body.keys)
    }
}
