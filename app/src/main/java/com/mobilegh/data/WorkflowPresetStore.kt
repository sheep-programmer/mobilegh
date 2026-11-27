package com.mobilegh.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class WorkflowPreset(
    val ref: String? = null,
    val inputs: Map<String, String> = emptyMap(),
)

/**
 * Remembers the most recently dispatched ref and input values per repository + workflow,
 * so re-running parameterized workflows on mobile doesn't require retyping everything.
 */
object WorkflowPresetStore {
    private const val PREFS = "mobilegh_workflow_presets"

    private fun key(owner: String, name: String, workflowId: Long) =
        "${owner.lowercase()}/${name.lowercase()}:$workflowId"

    fun load(ctx: Context, owner: String, name: String, workflowId: Long): WorkflowPreset? {
        val raw = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(owner, name, workflowId), null) ?: return null
        return runCatching { Api.plain.decodeFromString<WorkflowPreset>(raw) }.getOrNull()
    }

    fun save(ctx: Context, owner: String, name: String, workflowId: Long, ref: String, inputs: Map<String, String>) {
        val raw = Api.plain.encodeToString(WorkflowPreset(ref = ref, inputs = inputs))
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key(owner, name, workflowId), raw)
            .apply()
    }

    fun clear(ctx: Context, owner: String, name: String, workflowId: Long) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(key(owner, name, workflowId))
            .apply()
    }
}
