package com.mobilegh.widget

import com.mobilegh.data.WidgetStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 同类刷新只保留正在执行的一次与最后一次尾随请求；不同 Kind 互不阻塞。 */
internal class WidgetRefresh(
    private val scope: CoroutineScope,
    private val update: suspend (WidgetStore.Kind?) -> Unit,
    private val onFailure: (Exception) -> Unit = {},
) {
    private data class State(var running: Boolean = false, var pending: Boolean = false)

    private val lock = Mutex()
    private val states = mutableMapOf<WidgetStore.Kind?, State>()

    fun request(kind: WidgetStore.Kind?) {
        scope.launch {
            val start = lock.withLock {
                val state = states.getOrPut(kind) { State() }
                if (state.running) {
                    state.pending = true
                    false
                } else {
                    state.running = true
                    true
                }
            }
            if (!start) return@launch

            var completed = false
            try {
                while (true) {
                    try {
                        update(kind)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        onFailure(e)
                    }
                    val trailing = lock.withLock {
                        val state = states.getValue(kind)
                        if (state.pending) {
                            state.pending = false
                            true
                        } else {
                            states.remove(kind)
                            false
                        }
                    }
                    if (!trailing) break
                }
                completed = true
            } finally {
                if (!completed) withContext(NonCancellable) {
                    lock.withLock { states.remove(kind) }
                }
            }
        }
    }
}
