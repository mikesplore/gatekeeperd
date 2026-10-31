package com.gatekeeper.deployment

/** Detects repeated Docker restarts inside a short window while a deployment candidate is stabilizing. */
class CandidateRestartLoopDetector(
    private val restartLimit: Int = RESTART_LIMIT,
    private val windowMillis: Long = WINDOW_MILLIS
) {
    private data class State(var lastCount: Int, val restartTimes: ArrayDeque<Long> = ArrayDeque())

    private val states = mutableMapOf<String, State>()

    @Synchronized
    fun reset(containerName: String) {
        states.remove(containerName)
    }

    @Synchronized
    fun observe(containerName: String, restartCount: Int, nowMillis: Long = System.nanoTime() / 1_000_000): Boolean {
        require(restartCount >= 0) { "Docker restart count cannot be negative" }
        val state = states[containerName]
        if (state == null || restartCount < state.lastCount) {
            val fresh = State(restartCount)
            repeat(restartCount.coerceAtMost(restartLimit)) { fresh.restartTimes.addLast(nowMillis) }
            states[containerName] = fresh
            return restartCount >= restartLimit
        }
        repeat(restartCount - state.lastCount) { state.restartTimes.addLast(nowMillis) }
        state.lastCount = restartCount
        while (state.restartTimes.isNotEmpty() && nowMillis - state.restartTimes.first() > windowMillis) {
            state.restartTimes.removeFirst()
        }
        return state.restartTimes.size >= restartLimit
    }

    companion object {
        const val RESTART_LIMIT = 3
        const val WINDOW_MILLIS = 60_000L
    }
}
