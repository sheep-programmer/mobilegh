package com.mobilegh.data

import kotlinx.serialization.Serializable

@Serializable
data class Comparison(
    val status: String = "", val aheadBy: Int = 0, val behindBy: Int = 0,
    val totalCommits: Int = 0, val commits: List<Commit> = emptyList(), val files: List<CommitFile> = emptyList(),
)

object Compare {
    suspend fun load(owner: String, name: String, base: String, head: String, page: Int = 1, force: Boolean = false): Comparison {
        require(base.isNotBlank() && head.isNotBlank()) { "请选择两个版本" }
        val refs = Api.q(base).replace("+", "%20") + "..." + Api.q(head).replace("+", "%20")
        return Api.get("/repos/${Api.encodePath(owner)}/${Api.encodePath(name)}/compare/$refs?per_page=100&page=$page", force)
    }
}
