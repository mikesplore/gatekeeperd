package com.gatekeeper.deployment

/**
 * Canonical lifecycle states for a deployment release.
 *
 * The transition graph is deliberately kept in the domain layer so persistence
 * and workers cannot each invent a different lifecycle.
 */
enum class DeploymentStatus(val value: String) {
    QUEUED("queued"),
    BUILDING("building"),
    STARTING("starting"),
    HEALTH_CHECKING("health-checking"),
    READY("ready"),
    ACTIVE("active"),
    SUPERSEDED("superseded"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    ROLLED_BACK("rolled-back");

    fun canTransition(target: DeploymentStatus): Boolean =
        target in transitionTargets.getValue(this)

    fun canTransitionTo(target: DeploymentStatus): Boolean = canTransition(target)

    companion object {
        private val transitionTargets: Map<DeploymentStatus, Set<DeploymentStatus>> = mapOf(
            QUEUED to setOf(BUILDING, CANCELLED),
            BUILDING to setOf(STARTING, FAILED, CANCELLED),
            STARTING to setOf(HEALTH_CHECKING, FAILED, CANCELLED),
            HEALTH_CHECKING to setOf(QUEUED, READY, ACTIVE, FAILED, CANCELLED),
            READY to setOf(ACTIVE, FAILED),
            ACTIVE to setOf(SUPERSEDED, ROLLED_BACK),
            SUPERSEDED to setOf(ACTIVE, ROLLED_BACK),
            FAILED to setOf(QUEUED),
            CANCELLED to setOf(QUEUED),
            ROLLED_BACK to emptySet()
        )

        fun canTransition(from: DeploymentStatus, to: DeploymentStatus): Boolean =
            from.canTransition(to)
    }
}
