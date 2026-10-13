package com.gatekeeper.deployment

/** Docker boundary used to remove a candidate left behind by an interrupted attempt. */
internal fun interface CandidateContainerBoundary {
    fun removeIfPresent(name: String)
}

/** Clears any same-deployment candidate before a non-ready job is attempted again. */
internal object CandidateRetryRecovery {
    fun prepareForRetry(
        deterministicName: String,
        recordedName: String?,
        containers: CandidateContainerBoundary,
        clearRecordedRuntime: () -> Boolean
    ) {
        // The deterministic name also finds containers created just before a worker crash,
        // when the runtime name has not yet reached PostgreSQL.
        (listOfNotNull(recordedName, deterministicName).distinct()).forEach(containers::removeIfPresent)

        if (recordedName != null) {
            check(clearRecordedRuntime()) { "Unable to clear the previous candidate runtime record" }
        }
    }
}
