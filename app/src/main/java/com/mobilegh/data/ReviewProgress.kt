package com.mobilegh.data

import com.mobilegh.i18n.tr
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal enum class FileReviewState { VIEWED, UNVIEWED, DISMISSED }

internal data class ReviewProgress(val id: String, val head: String, val base: String, val states: Map<String, FileReviewState>) {
    fun matches(head: String, base: String) = this.head == head && this.base == base
    fun viewedFiles(files: List<CommitFile>) = files.mapNotNull { file ->
        file.sha?.takeIf { states[file.filename] == FileReviewState.VIEWED }?.let { file.filename to it }
    }.toMap()
}

@Serializable
internal data class ReviewPosition(val path: String, val sha: String?, val row: Int?, val offset: Int, val split: Boolean) {
    fun rowFor(file: CommitFile, splitMode: Boolean) = row?.takeIf { sha != null && sha == file.sha && split == splitMode }
}

internal object ReviewProgressApi {
    private suspend fun request(query: String, variables: Map<String, Any?>) = Api.graphql(query, variables, force = true)

    suspend fun read(
        owner: String, name: String, number: Int, generation: Int = Session.generation,
        send: suspend (String, Map<String, Any?>) -> JsonObject = ::request,
    ): ReviewProgress {
        var cursor: String? = null
        var initial: ReviewProgress? = null
        val states = linkedMapOf<String, FileReviewState>()
        val seenCursors = mutableSetOf<String>()
        do {
            requireSession(generation)
            val pull = send(
                """query ReviewProgress(${'$'}owner: String!, ${'$'}name: String!, ${'$'}number: Int!, ${'$'}cursor: String) {
                    repository(owner: ${'$'}owner, name: ${'$'}name) {
                        pullRequest(number: ${'$'}number) {
                            id headRefOid baseRefOid
                            files(first: 100, after: ${'$'}cursor) {
                                nodes { path viewerViewedState }
                                pageInfo { hasNextPage endCursor }
                            }
                        }
                    }
                }""".trimIndent(),
                mapOf("owner" to owner, "name" to name, "number" to number, "cursor" to cursor),
            ).obj("repository").obj("pullRequest") ?: unavailable()
            val page = ReviewProgress(pull.str("id") ?: unavailable(), pull.str("headRefOid") ?: unavailable(), pull.str("baseRefOid") ?: unavailable(), emptyMap())
            if (initial == null) initial = page
            else if (initial != page) throw ApiException(409, tr("PR 已更新，请刷新文件变更"))
            val files = pull.obj("files") ?: unavailable()
            for (file in files.arr("nodes") ?: unavailable()) {
                val path = file.str("path") ?: unavailable()
                val state = FileReviewState.entries.firstOrNull { it.name == file.str("viewerViewedState") } ?: unavailable()
                states[path] = state
            }
            val pageInfo = files.obj("pageInfo") ?: unavailable()
            cursor = when (pageInfo.str("hasNextPage")) {
                "true" -> pageInfo.str("endCursor")?.takeIf { it.isNotBlank() && seenCursors.add(it) } ?: unavailable()
                "false" -> null
                else -> unavailable()
            }
        } while (cursor != null)
        requireSession(generation)
        return initial.copy(states = states)
    }

    suspend fun setViewed(
        progress: ReviewProgress, path: String, viewed: Boolean, generation: Int,
        send: suspend (String, Map<String, Any?>) -> JsonObject = ::request,
    ) {
        requireSession(generation)
        val operation = if (viewed) "markFileAsViewed" else "unmarkFileAsViewed"
        val input = if (viewed) "MarkFileAsViewedInput" else "UnmarkFileAsViewedInput"
        val data = send(
            "mutation SetFileViewed(${'$'}input: $input!) { $operation(input: ${'$'}input) { pullRequest { id headRefOid baseRefOid } } }",
            mapOf("input" to buildJsonObject {
                put("pullRequestId", progress.id)
                put("path", path)
            }),
        )
        requireSession(generation)
        val pull = data.obj(operation).obj("pullRequest") ?: unavailable()
        if (pull.str("id") != progress.id) unavailable()
        if (!progress.matches(pull.str("headRefOid").orEmpty(), pull.str("baseRefOid").orEmpty())) {
            throw ApiException(409, tr("PR 已更新，请刷新文件变更"))
        }
    }

    private fun requireSession(generation: Int) {
        if (Session.generation != generation) throw CancellationException("Account changed")
    }

    private fun unavailable(): Nothing = throw ApiException(403, tr("GitHub 已阅状态暂不可用"))
}
