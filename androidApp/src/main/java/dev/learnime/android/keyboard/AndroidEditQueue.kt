package dev.learnime.android

import kotlinx.coroutines.*

/** Cancels the entire editor's queued and suspended work on a host-field change. */
internal class AndroidEditQueue(private val scope: CoroutineScope) {
    var epoch = 0L; private set
    private var parent = SupervisorJob(scope.coroutineContext[Job])
    private var tail: Job? = null
    fun enqueue(action: suspend () -> Unit) {
        val previous = tail
        val expected = epoch
        tail = scope.launch(parent) {
            previous?.join()
            ensureActive()
            if (expected == epoch) action()
        }
    }
    fun reset() {
        epoch++
        parent.cancel()
        parent = SupervisorJob(scope.coroutineContext[Job])
        tail = null
    }
}
