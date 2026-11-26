package com.mobilegh.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessRefreshClaimsTest {
    @Test fun eachKeyIsClaimedOnlyOncePerProcess() {
        val key = "test-claim:${System.nanoTime()}"
        assertTrue(ProcessRefreshClaims.claim(key))
        assertFalse(ProcessRefreshClaims.claim(key))
    }

    @Test fun differentKeysAreClaimedIndependently() {
        val stamp = System.nanoTime()
        assertTrue(ProcessRefreshClaims.claim("test-claim-a:$stamp"))
        assertTrue(ProcessRefreshClaims.claim("test-claim-b:$stamp"))
    }
}
