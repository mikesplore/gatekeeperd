package com.gatekeeper.deployment

import java.time.LocalDateTime

/** Keeps ready candidates retryable without hammering nginx when a cutover prerequisite is unavailable. */
object DeploymentCutoverRetryPolicy {
    const val RETRY_DELAY_SECONDS = 30L

    fun isEligible(currentStep: String, updatedAt: LocalDateTime, now: LocalDateTime): Boolean {
        if (currentStep !in setOf("readiness_succeeded", "cutover_in_progress")) return true
        return !updatedAt.isAfter(now.minusSeconds(RETRY_DELAY_SECONDS))
    }
}
