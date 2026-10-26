package com.mobilegh.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private val FORK_REPOSITORY_NAME = Regex("[A-Za-z0-9_.-]{1,100}")
private const val FORK_READINESS_ATTEMPTS = 5

/** 仓库名不是 Git 分支名：允许连续点号，但不允许路径分隔符、空白或单独的 . / ..。 */
internal fun validForkName(name: String): Boolean =
    FORK_REPOSITORY_NAME.matches(name) && name != "." && name != ".."

/**
 * Fork 的 POST 返回不代表仓库已经可读。只在读取遇到 404 时做有限等待；
 * 就绪后按需补写描述，描述接口自己的错误直接交给调用方，不用重试掩盖。
 */
internal suspend fun finishFork(
    description: String?,
    read: suspend () -> Unit,
    update: suspend (String) -> Unit,
    pause: suspend (Long) -> Unit = { delay(it) },
) {
    awaitForkReady(read, pause)
    if (description != null) update(description)
}

private suspend fun awaitForkReady(read: suspend () -> Unit, pause: suspend (Long) -> Unit) {
    repeat(FORK_READINESS_ATTEMPTS) { attempt ->
        try {
            read()
            return
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ApiException) {
            if (failure.code != 404 || attempt == FORK_READINESS_ATTEMPTS - 1) throw failure
            pause((attempt + 1) * 1_000L)
        }
    }
}
