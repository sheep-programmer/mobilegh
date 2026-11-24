package com.mobilegh.ui.screens

import com.mobilegh.data.ThreadAccess
import kotlinx.coroutines.CancellationException

/** Review threads whose resolution state can be acted on by this screen. */
data class PullReviewResolveTarget(val thread: ReviewThread, val nodeId: String)

/** The small, confirmed summary used by the PR files screen. */
class PullReviewDiscussions(private val threads: List<ReviewThread>) {
    val unresolvedByPath: Map<String, List<ReviewThread>> = threads
        .asSequence()
        .filter { !it.resolved && it.resolutionId() != null && it.path.isNotBlank() }
        .groupBy { it.path }

    val unresolvedCount: Int get() = unresolvedByPath.values.sumOf { it.size }

    /** Threads from the REST fallback have no GraphQL id, so their state is not confirmed. */
    val unknownCount: Int get() = threads.count { !it.resolved && it.resolutionId() == null }

    fun fileIndex(path: String, filePaths: List<String>): Int? =
        filePaths.indexOf(path).takeIf { it >= 0 }
}

fun ReviewThread.resolutionId(): String? = root.threadId?.trim()?.takeIf { it.isNotEmpty() }

/** Reuses the same permission check as the individual resolve link. */
fun ReviewThread.resolutionId(access: ThreadAccess): String? =
    resolutionId().takeIf { access.write || access.author }

fun pullReviewResolveTargets(
    threads: List<ReviewThread>,
    access: ThreadAccess,
    limit: Int = 20,
): List<PullReviewResolveTarget> {
    if (limit <= 0 || (!access.write && !access.author)) return emptyList()
    return threads.asSequence()
        .filter { !it.resolved }
        .mapNotNull { thread -> thread.resolutionId(access)?.let { PullReviewResolveTarget(thread, it) } }
        .distinctBy { it.nodeId }
        .take(limit)
        .toList()
}

sealed interface PullReviewResolveOutcome {
    data object Resolved : PullReviewResolveOutcome
    data class Failed(val error: Throwable) : PullReviewResolveOutcome
    data object Skipped : PullReviewResolveOutcome
}

/** Executes one GitHub mutation per thread, preserving each outcome and cancellation. */
suspend fun runPullReviewResolveBatch(
    targets: List<PullReviewResolveTarget>,
    shouldStop: () -> Boolean,
    resolve: suspend (String) -> Unit,
    report: (PullReviewResolveTarget, PullReviewResolveOutcome) -> Unit,
) {
    for ((index, target) in targets.withIndex()) {
        if (shouldStop()) {
            for (remaining in targets.drop(index)) report(remaining, PullReviewResolveOutcome.Skipped)
            return
        }
        try {
            resolve(target.nodeId)
            report(target, PullReviewResolveOutcome.Resolved)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            report(target, PullReviewResolveOutcome.Failed(e))
        }
    }
}
