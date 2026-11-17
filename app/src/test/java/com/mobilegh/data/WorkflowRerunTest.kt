package com.mobilegh.data

import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowRerunTest {
    @Test fun ordinaryRerunKeepsEmptyBodyWhenDebugIsOff() {
        val request = WorkflowRerun.request(42, failedOnly = false, debug = false)
        assertEquals("/actions/runs/42/rerun", request.path)
        assertNull(request.body)
    }

    @Test fun failedJobsRerunKeepsEmptyBodyWhenDebugIsOff() {
        val request = WorkflowRerun.request(42, failedOnly = true, debug = false)
        assertEquals("/actions/runs/42/rerun-failed-jobs", request.path)
        assertNull(request.body)
    }

    @Test fun ordinaryRerunCanEnableDebugLogging() {
        val request = WorkflowRerun.request(42, failedOnly = false, debug = true)
        assertEquals("/actions/runs/42/rerun", request.path)
        assertTrue(request.body?.get("enable_debug_logging")?.jsonPrimitive?.booleanOrNull == true)
    }

    @Test fun failedJobsRerunCanEnableDebugLogging() {
        val request = WorkflowRerun.request(42, failedOnly = true, debug = true)
        assertEquals("/actions/runs/42/rerun-failed-jobs", request.path)
        assertTrue(request.body?.get("enable_debug_logging")?.jsonPrimitive?.booleanOrNull == true)
    }
}
