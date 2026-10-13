package com.gatekeeper.deployment

/** Small polling boundary so readiness timeout and cancellation behavior can use a controlled clock. */
internal object DeploymentReadiness {
    fun <T : Any> await(
        timeoutNanos: Long,
        intervalMillis: Long,
        nowNanos: () -> Long = System::nanoTime,
        checkCancelled: () -> Unit,
        probe: () -> T?,
        pause: (Long) -> Unit
    ): T? {
        val deadline = nowNanos() + timeoutNanos
        while (nowNanos() < deadline) {
            checkCancelled()
            runCatching(probe).getOrNull()?.let { return it }
            pause(intervalMillis)
        }
        return null
    }
}
