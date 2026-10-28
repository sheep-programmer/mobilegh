package com.mobilegh.data

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** 仓库写入（编辑文件、分支、Release）的纯逻辑与写入后的刷新通知 */
object RepoWrite {
    /** 手机上可编辑的文件上限：contents 接口超过 1MB 不再返回内容 */
    const val MAX_EDIT_BYTES = 1_000_000L

    /** 草稿只保存不超过此长度的文件，更大的文件不写入本机草稿 */
    const val MAX_DRAFT_CHARS = 60_000

    fun encode(text: String): String = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    /** 解码 contents 接口的 Base64 内容；非 UTF-8 或含 NUL 的文件视为二进制，返回 null */
    fun decode(base64: String): String? {
        val bytes = runCatching { Base64.getMimeDecoder().decode(base64) }.getOrNull() ?: return null
        val text = runCatching {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrNull() ?: return null
        return if ('\u0000' in text) null else text
    }

    /** 编辑框只处理 \n；原文件使用 CRLF 时提交前还原，避免整份文件出现换行差异 */
    fun usesCrlf(text: String): Boolean = text.contains("\r\n")
    fun toEditor(text: String): String = text.replace("\r\n", "\n")
    fun fromEditor(text: String, crlf: Boolean): String = if (crlf) text.replace("\r\n", "\n").replace("\n", "\r\n") else text

    fun lineCount(text: String): Int = if (text.isEmpty()) 1 else text.count { it == '\n' } + 1

    fun commitMessage(summary: String, description: String): String =
        if (description.isBlank()) summary.trim() else summary.trim() + "\n\n" + description.trim()

    /** 与 GitHub 网页一致的默认分支名：<login>-patch-1，已存在则递增 */
    fun patchBranch(login: String?, existing: Collection<String>): String {
        val base = if (login.isNullOrBlank()) "patch" else "$login-patch"
        val taken = existing.map { it.lowercase() }.toSet()
        var i = 1
        while ("$base-$i".lowercase() in taken) i++
        return "$base-$i"
    }

    /** 分支名的基本校验（git check-ref-format 的常见规则） */
    fun validBranch(name: String): Boolean {
        if (name.isBlank() || name.length > 250) return false
        if (name.startsWith('/') || name.endsWith('/') || name.endsWith('.') || name.startsWith('-')) return false
        if (name.endsWith(".lock") || name == "@" || name.contains("..") || name.contains("//") || name.contains("@{")) return false
        return name.none { it.isWhitespace() || it.isISOControl() || it in "~^:?*[\\" }
    }

    /** sha 冲突：409（文件已被别人修改），或 422 且提示与 sha 相关 */
    fun isShaConflict(e: Throwable): Boolean =
        e is ApiException && (e.code == 409 || (e.code == 422 && e.message.contains("sha", ignoreCase = true)))

    fun isAlreadyExists(e: Throwable): Boolean =
        e is ApiException && e.code == 422 && e.message.contains("already exists", ignoreCase = true)

    /** 写入成功后：丢弃该仓库的 HTTP 缓存，并通知已打开的页面刷新 */
    suspend fun changed(owner: String, name: String, kind: String = CODE) {
        withContext(Dispatchers.IO) { Api.invalidate("/repos/$owner/$name") }
        withContext(Dispatchers.Main) { RepoChanges.bump(owner, name, kind) }
    }

    const val CODE = "code"
    const val ACTIONS = "actions"
}

/** 每个仓库的写入版本号：页面记下打开时的版本，返回时版本变了就强制刷新 */
object RepoChanges {
    private val versions = mutableStateMapOf<String, Int>()
    private fun key(owner: String, name: String, kind: String) = "${owner.lowercase()}/${name.lowercase()}:$kind"
    fun version(owner: String, name: String, kind: String = RepoWrite.CODE): Int = versions[key(owner, name, kind)] ?: 0
    fun bump(owner: String, name: String, kind: String = RepoWrite.CODE) {
        val k = key(owner, name, kind)
        versions[k] = (versions[k] ?: 0) + 1
    }
}

/** 写入目标：仓库权限 + 当前查看的 ref 是否为分支（标签和提交不能直接提交） */
data class WriteTarget(val repo: Repo, val baseRef: String, val branch: Branch?, val emptyRepo: Boolean = false) {
    val canPush: Boolean get() = repo.permissions?.push == true && !repo.archived
    /** 发起拉取请求时的目标分支：当前分支，查看标签/提交时退回默认分支 */
    val prBase: String get() = branch?.name ?: repo.defaultBranch

    companion object {
        suspend fun load(owner: String, name: String, ref: String?): WriteTarget {
            val repo = GitHub.repo(owner, name, true)
            val base = ref ?: repo.defaultBranch
            val branch = try {
                GitHub.branch(owner, name, base)
            } catch (e: ApiException) {
                if (e.code == 404) null else throw e
            }
            // 空仓库（一个提交都没有）时连默认分支都读不到；实测提交列表会 409「Git Repository is empty」。
            // 只有分支查不到时才多跑这一项，普通仓库不会多花请求
            val empty = branch == null && try {
                GitHub.commits(owner, name, null, null, 1, true).isEmpty()
            } catch (e: ApiException) {
                e.code == 409 || e.message.contains("empty", ignoreCase = true)
            }
            return WriteTarget(repo, base, branch, empty)
        }
    }
}
