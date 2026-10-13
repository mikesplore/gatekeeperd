package com.gatekeeper.deployment

import com.gatekeeper.docker.VolumeMount
import com.gatekeeper.docker.ContainerRuntimeOptions
import com.gatekeeper.security.SecretValueCipher

/** Runtime facts required to recreate an already-deployed container. Never log or stringify this type. */
internal class RuntimeRestorePlan(
    val containerName: String,
    val imageReference: String,
    /** Container port to host port. */
    val publishedPorts: Map<Int, Int>,
    val portHostIps: Map<Int, String>,
    val networkMode: String,
    val networkAttachments: List<String>,
    val restartPolicy: String,
    val environment: Map<String, String>,
    val volumes: List<VolumeMount>,
    val runtimeOptions: ContainerRuntimeOptions = ContainerRuntimeOptions(),
    val portHostIp: String = "127.0.0.1"
)

internal fun interface RuntimeRestoreDockerBoundary {
    /** Must be idempotent: reuse a matching container or create it if absent. */
    fun ensureRuntime(plan: RuntimeRestorePlan)
}

internal fun interface RuntimeRestoreRouteBoundary {
    fun restore(previousNginxConfig: String): Boolean
}

internal fun interface RuntimeRestoreStateBoundary {
    fun update(state: String, error: String?)
}

internal object PreviousRuntimeRecovery {
    fun restore(
        plan: RuntimeRestorePlan?,
        previousNginxConfig: String?,
        docker: RuntimeRestoreDockerBoundary,
        route: RuntimeRestoreRouteBoundary,
        state: RuntimeRestoreStateBoundary,
        secrets: Collection<String>
    ) {
        try {
            // Best effort checkpoint first; never let a transient persistence failure prevent Docker recovery.
            runCatching { state.update("restoring", null) }
            if (plan != null) docker.ensureRuntime(plan)
            // Persist that runtime reconstruction completed before changing the route. If the database is down,
            // the candidate route stays in place and the durable recovery marker can be retried safely.
            state.update("runtime_restored", null)
            if (previousNginxConfig != null) {
                check(route.restore(previousNginxConfig)) { "Previous Nginx configuration could not be restored" }
            }
            state.update("restored", null)
        } catch (error: Exception) {
            val safeMessage = SecretValueCipher.redact(error.message ?: "Runtime restoration failed", secrets)
                .take(1000)
            runCatching { state.update("restore_failed", safeMessage) }
            throw PreviousRuntimeRestorationException()
        }
    }

    /** On canonical activation failure, restore the old route/runtime before propagating the failure. */
    fun activateOrRestore(
        activate: () -> Unit,
        restore: () -> Unit
    ) {
        try {
            activate()
        } catch (activationError: Exception) {
            restore()
            throw activationError
        }
    }
}

internal class PreviousRuntimeRestorationException :
    IllegalStateException("Previous runtime restoration failed; durable cutover recovery is required")
