package com.mobilegh.data

import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 一次推送的摘要：推到了哪个分支或标签、推了几个提交、最新提交的说明和 SHA。 */
data class PushSummary(
    val ref: String,
    val isTag: Boolean,
    /** 推送的提交数；GitHub 事件里没有 size 且 commits 为空时未知，为 null（不能显示成 0）。 */
    val commitCount: Int?,
    val headSha: String?,
    val headMessage: String,
    /** 推送前的提交：对比页的基准。新建分支时 GitHub 给出全零，改用第一个推送提交的父提交。 */
    val baseSha: String?,
)

private val ZERO_SHA = Regex("^0+$")

/**
 * 仓库的 pushed_at（最后一次推送）比事件流里最新的推送晚一分钟以上：事件流没有覆盖最近的推送，列表会比仓库头部的时间旧。
 * 大仓库的事件记录常常不完整。没有 pushed_at 或时间无法解析时不提示；事件流为空时视为落后。
 */
fun pushFeedIsBehind(repoPushedAt: String?, newestPushAt: String?): Boolean {
    val repo = repoPushedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return false
    val newest = newestPushAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return true
    return repo.isAfter(newest.plusSeconds(60))
}

/**
 * 从 PushEvent 的 payload 里取出摘要；不是推送事件或缺少分支信息时返回 null。
 * 分支名去掉 refs/heads/，标签保留 refs/tags/ 的判断，提交数优先用 size（GitHub 给出的真实数量，commits 数组可能被截断）。
 */
fun pushSummary(event: Event): PushSummary? {
    if (event.type != "PushEvent") return null
    val ref = event.payload["ref"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
    val commits: JsonArray = event.payload["commits"] as? JsonArray ?: JsonArray(emptyList())
    val count = event.payload["size"]?.jsonPrimitive?.intOrNull ?: commits.takeIf { it.isNotEmpty() }?.size
    val head = event.payload["head"]?.jsonPrimitive?.contentOrNull
    val headCommit = commits.firstOrNull { (it as? JsonObject)?.get("sha")?.jsonPrimitive?.contentOrNull == head }
        ?: commits.lastOrNull()
    val message = (headCommit as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull.orEmpty()
    val before = event.payload["before"]?.jsonPrimitive?.contentOrNull?.takeIf { !ZERO_SHA.matches(it) }
    val firstSha = (commits.firstOrNull() as? JsonObject)?.get("sha")?.jsonPrimitive?.contentOrNull
    return PushSummary(
        ref = ref.removePrefix("refs/heads/").removePrefix("refs/tags/"),
        isTag = ref.startsWith("refs/tags/"),
        commitCount = count,
        headSha = head,
        headMessage = message.lineSequence().firstOrNull().orEmpty(),
        baseSha = before ?: firstSha?.let { "$it^" },
    )
}
