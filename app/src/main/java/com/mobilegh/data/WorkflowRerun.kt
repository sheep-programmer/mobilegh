package com.mobilegh.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ApiRequest(val path: String, val body: JsonObject? = null)

/** GitHub 重跑接口只在启用调试日志时接收请求体；普通重跑保持原有空 body。 */
object WorkflowRerun {
    fun request(runId: Long, failedOnly: Boolean, debug: Boolean): ApiRequest = ApiRequest(
        path = "/actions/runs/$runId/" + if (failedOnly) "rerun-failed-jobs" else "rerun",
        body = if (debug) buildJsonObject { put("enable_debug_logging", true) } else null,
    )
}
