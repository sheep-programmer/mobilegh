package com.mobilegh.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 待提交审查（PENDING）：当前用户在某个 PR 上尚未提交的审查，内含尚未发布的行内评论数量 */
data class PendingReview(val id: Long, val commentCount: Int)

/**
 * 待提交审查的请求体与选择规则，纯函数便于单测；网络调用在 [GitHub] 中。
 *
 * - 创建：POST /pulls/{n}/reviews，不带 event 即得到 PENDING 审查
 * - 加行内评论：POST /pulls/{n}/reviews/{id}/comments
 * - 提交：POST /pulls/{n}/reviews/{id}/events（event 必填，body 可选）
 * - 放弃：DELETE /pulls/{n}/reviews/{id}
 */
object ReviewApi {
    /** GitHub 接受的审查结论 */
    val EVENTS = setOf("APPROVE", "REQUEST_CHANGES", "COMMENT")

    /** 创建待提交审查：不写 event；commit_id 固定在正在查看的 head 提交上 */
    fun createBody(commitId: String?): JsonObject = buildJsonObject {
        if (!commitId.isNullOrBlank()) put("commit_id", commitId)
    }

    /**
     * 加入待提交审查的行内评论。line 为 null 时是文件级评论（subject_type=file）；
     * side：RIGHT=新文件的行，LEFT=旧文件的行。
     */
    fun commentBody(
        body: String,
        commitId: String,
        path: String,
        line: Int?,
        side: String = "RIGHT",
        startLine: Int? = null,
        startSide: String? = null,
    ): JsonObject = buildJsonObject {
        put("body", body)
        put("commit_id", commitId)
        put("path", path)
        if (line == null) {
            put("subject_type", "file")
        } else {
            put("line", line)
            put("side", side)
            if (startLine != null && startLine < line) {
                put("start_line", startLine)
                put("start_side", startSide ?: side)
            }
        }
    }

    /** 提交审查的请求体；空白正文不写入。未知的 event 直接拒绝，避免发出 GitHub 必然拒绝的请求 */
    fun submitBody(event: String, body: String): JsonObject {
        require(event in EVENTS) { "unknown review event: $event" }
        return buildJsonObject {
            put("event", event)
            if (body.isNotBlank()) put("body", body)
        }
    }

    /** 在审查列表中找当前用户的 PENDING 审查（login 不区分大小写）；别人的待提交审查与已提交的审查都不算 */
    fun findPending(reviews: List<Review>, login: String?): Review? {
        if (login.isNullOrBlank()) return null
        return reviews.lastOrNull { it.state == "PENDING" && it.user?.login.equals(login, ignoreCase = true) }
    }
}
