package com.mobilegh.widget

import com.mobilegh.data.WidgetStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 每类小组件只在自身数据变化时重新加载；全量更新覆盖所有类别。 */
internal class WidgetVersions {
    private val flows = WidgetStore.Kind.entries.associateWith { MutableStateFlow(0) }

    fun flow(kind: WidgetStore.Kind): StateFlow<Int> = flows.getValue(kind).asStateFlow()

    fun value(kind: WidgetStore.Kind): Int = flows.getValue(kind).value

    fun update(kind: WidgetStore.Kind?) {
        val targets = kind?.let(::listOf) ?: WidgetStore.Kind.entries
        targets.forEach { flows.getValue(it).update { version -> version + 1 } }
    }
}
