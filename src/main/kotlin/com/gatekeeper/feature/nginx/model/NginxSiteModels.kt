package com.gatekeeper.feature.nginx.model

import io.ktor.http.HttpStatusCode
import io.ktor.util.logging.Logger
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory

data class NginxEnablePlan(
    val slug: String,
    val domain: String,
    val appPort: Int,
    val containerPort: Int?,
    val upstreamScheme: String,
    val resolvedCertificate: ResolvedCertificate?
) {
    val sslEnabled: Boolean get() = resolvedCertificate != null
}

sealed interface NginxPlanResult {
    data class Ok(val plan: NginxEnablePlan) : NginxPlanResult
    data class Err(
        val status: HttpStatusCode,
        val code: String,
        val message: String,
        val data: JsonObject? = null
    ) : NginxPlanResult
}

data class NginxSiteActivationResult(val success: Boolean, val message: String? = null)

data class ResolvedCertificate(
    val certificateDomain: String,
    val certificatePath: String,
    val privateKeyPath: String
)

enum class InterruptedCutoverResult { TARGET_RELOADED, PREVIOUS_RESTORED, CONFLICT }

val nginxServiceLogger: Logger = LoggerFactory.getLogger("com.gatekeeper.nginx.NginxService")
