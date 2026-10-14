package com.mobilegh.data

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Gist 写入用到的纯逻辑（网络请求在 [GitHub] 里），便于离线单元测试：
 * 编辑行校验、新建请求体、以及「原文件 → 当前编辑状态」的 PATCH 差异。
 */

/** 编辑界面里的一行文件 */
data class GistDraftFile(
    /** 服务端原有的文件名；null 表示这是新加的文件行 */
    val original: String? = null,
    val filename: String = "",
    val content: String = "",
    /** 原内容超过 API 上限被截断：只能改名或删除，内容保持不动 */
    val truncated: Boolean = false,
    /** 原有内容，用来判断内容是否改过 */
    val originalContent: String? = null,
)

/**
 * 校验编辑中的文件列表，返回错误提示；null 表示可以提交。
 * removed 是已移除的原文件名（编辑已有 Gist 时）。
 */
fun gistFilesError(files: List<GistDraftFile>, removed: Collection<String> = emptyList()): String? {
    if (files.isEmpty()) return "至少要保留一个文件"
    files.forEach { f ->
        if (f.filename.isBlank()) return "文件名不能为空"
        if (f.filename.contains('/')) return "文件名不能包含 /"
        if (!f.truncated && f.content.isBlank()) return "文件「${f.filename.trim()}」的内容不能为空"
    }
    val names = files.map { it.filename.trim() }
    if (names.size != names.toSet().size) return "文件名不能重复"
    // 新文件名不能占用另一行改名前 / 已删除的文件名，否则 PATCH 里的键会互相覆盖
    val taken = files.mapNotNull { it.original } + removed
    files.forEach { f ->
        val name = f.filename.trim()
        if (name != f.original && name in taken) return "文件名「$name」与被改名或删除的文件冲突，请先保存一次再改"
    }
    return null
}

/** POST /gists 请求体 */
fun gistCreatePayload(description: String, public: Boolean, files: List<GistDraftFile>): JsonObject {
    require(gistFilesError(files) == null) { gistFilesError(files)!! }
    return buildJsonObject {
        put("description", description.trim())
        put("public", public)
        put("files", buildJsonObject {
            files.forEach { f -> put(f.filename.trim(), buildJsonObject { put("content", f.content) }) }
        })
    }
}

/**
 * PATCH /gists/{id} 请求体，返回 null 表示没有任何变化。
 * - 删除的原文件 → `"原名": null`
 * - 改名 → `"原名": {"filename": "新名"[, "content": …]}`
 * - 改内容 → `"原名": {"content": …}`
 * - 新文件 → `"新名": {"content": …}`
 * 公开 / 私密在 API 上不可修改，因此不出现在请求体里。
 */
fun gistPatchPayload(
    originalDescription: String?,
    description: String,
    files: List<GistDraftFile>,
    removed: Collection<String>,
): JsonObject? {
    val changes = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
    removed.forEach { changes[it] = JsonNull }
    files.forEach { f ->
        val name = f.filename.trim()
        val original = f.original
        if (original == null) {
            changes[name] = buildJsonObject { put("content", f.content) }
        } else {
            val renamed = name != original
            val edited = !f.truncated && f.content != f.originalContent
            if (renamed || edited) changes[original] = buildJsonObject {
                if (renamed) put("filename", name)
                if (edited) put("content", f.content)
            }
        }
    }
    val descChanged = description.trim() != originalDescription.orEmpty().trim()
    if (changes.isEmpty() && !descChanged) return null
    return buildJsonObject {
        put("description", JsonPrimitive(description.trim()))
        if (changes.isNotEmpty()) put("files", JsonObject(changes))
    }
}

/** 把服务端的文件转成编辑行 */
fun gistDraftFiles(files: Map<String, GistFile>): List<GistDraftFile> =
    files.entries.map { (key, f) ->
        val name = f.filename.ifBlank { key }
        val content = if (f.truncated) "" else f.content.orEmpty()
        GistDraftFile(original = name, filename = name, content = content, truncated = f.truncated, originalContent = content)
    }
