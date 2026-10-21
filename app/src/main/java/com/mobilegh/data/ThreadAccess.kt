package com.mobilegh.data

/**
 * Issue / PR 详情页可用的写操作，按 GitHub 的角色规则从仓库权限推算。
 * 仓库权限还没读到或仓库已归档时一律不给入口，避免出现点了必然 403 的按钮。
 */
data class ThreadAccess(val write: Boolean, val triage: Boolean, val author: Boolean) {
    /** 作者可以改自己的标题；改别人的需要写权限 */
    val editTitle: Boolean get() = author || write

    /** 标签、指派人、里程碑、请求审查需要 triage 及以上 */
    val editMeta: Boolean get() = triage

    /** 作者可以关闭 / 重开自己的 Issue 与 PR */
    val changeState: Boolean get() = author || triage

    val merge: Boolean get() = write

    val toggleDraft: Boolean get() = author || write

    companion object {
        val None = ThreadAccess(write = false, triage = false, author = false)

        fun of(perms: Permissions?, archived: Boolean, author: String?, me: String?): ThreadAccess {
            if (perms == null || archived) return None
            val write = perms.push || perms.maintain || perms.admin
            return ThreadAccess(
                write = write,
                triage = write || perms.triage,
                author = author != null && me != null && author.equals(me, ignoreCase = true),
            )
        }
    }
}
