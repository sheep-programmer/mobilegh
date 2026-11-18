package com.mobilegh.data

import java.time.Instant

private val failedConclusions = setOf("failure", "timed_out", "startup_failure", "action_required")

internal fun firstFailedJob(jobs: List<Job>): Pair<Job, Step?>? {
    return jobs.asSequence().filter { it.conclusion in failedConclusions }.map { job ->
        job to job.steps.filter { it.number > 0 && it.conclusion in failedConclusions }.minByOrNull(Step::number)
    }.minByOrNull { (job, step) -> (step?.completedAt ?: job.completedAt)?.let(::logInstant) ?: Instant.MAX }
}

internal fun logStepTarget(timestamps: List<String>, step: Step, errors: List<Int>, focusFailure: Boolean): Int? {
    val start = step.startedAt?.let(::logInstant) ?: return null
    val end = step.completedAt?.let(::logInstant)
    fun withinStep(index: Int): Boolean {
        val time = logInstant(timestamps[index]) ?: return false
        return time >= start && (end == null || time <= end)
    }
    if (focusFailure) errors.firstOrNull(::withinStep)?.let { return it }
    return timestamps.indices.firstOrNull(::withinStep)
}

private fun logInstant(value: String): Instant? = if (value.isBlank()) null else runCatching { Instant.parse(value.trim()) }.getOrNull()

internal fun nextLogTarget(targets: List<Int>, anchor: Int, forward: Boolean): Int? {
    if (targets.isEmpty()) return null
    val position = targets.binarySearch(anchor)
    val index = when {
        position >= 0 -> if (forward) position + 1 else position - 1
        forward -> -position - 1
        else -> -position - 2
    }
    return targets[index.mod(targets.size)]
}
