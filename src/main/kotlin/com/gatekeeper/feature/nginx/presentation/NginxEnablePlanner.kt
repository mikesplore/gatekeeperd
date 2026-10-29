package com.gatekeeper.feature.nginx.presentation

import com.gatekeeper.docker.DockerGateway
import com.gatekeeper.feature.nginx.domain.port.NginxSiteStorage
import com.gatekeeper.feature.nginx.domain.port.PortProbe
import com.gatekeeper.feature.nginx.domain.service.NginxValidationService
import com.gatekeeper.feature.nginx.domain.service.UpstreamValidation
import com.gatekeeper.feature.nginx.infrastructure.CertbotCertificateProvisioner
import com.gatekeeper.feature.nginx.model.NginxEnablePlan
import com.gatekeeper.feature.nginx.model.NginxPlanResult
import com.gatekeeper.feature.nginx.infrastructure.DeploymentUpstreamResolver
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory
import kotlin.collections.contains
import kotlin.collections.isNotEmpty
import kotlin.collections.orEmpty
import kotlin.collections.sorted
class PlanNginxEnableRequest(
    private val dockerService: DockerGateway?,
    private val parser: UpstreamValidation,
    private val portProbe: PortProbe,
    private val validator: NginxValidationService,
    private val certificateProvisioner: CertbotCertificateProvisioner
) {
    fun planNginxEnableRequest(
        slug: String,
        projectDomain: String,
        activeRuntimeTarget: DeploymentUpstreamResolver.Target?,
        request: NginxEnableRequest
    ): NginxPlanResult {
        val configuredContainerName = activeRuntimeTarget?.containerName?.takeIf(String::isNotBlank)
        val dockerPublishedPorts = run {
            if (dockerService == null || configuredContainerName == null) return@run null
            val health = dockerService.containerHealth(configuredContainerName)
            if (health != "running") {
                return NginxPlanResult.Err(
                    HttpStatusCode.BadRequest,
                    "port_not_active",
                    "Container '$configuredContainerName' is not running for project $slug (state: $health). Start it and try again."
                )
            }
            val info = dockerService.getContainer(configuredContainerName)
            parser.parsePublishedPorts(info?.ports.orEmpty())
        }
        val dockerPublishedHostPorts = dockerPublishedPorts?.values?.toSet()

        val selectedContainerPort = activeRuntimeTarget?.containerPort
        val actualRuntimeHostPort = selectedContainerPort?.let { dockerPublishedPorts?.get(it) }
            ?: dockerPublishedHostPorts?.singleOrNull()
        // Docker's current binding is authoritative when the recorded host port has gone stale,
        // which can happen if an adopted/recreated container receives a new dynamic host port.
        val appPort = when {
            actualRuntimeHostPort != null -> actualRuntimeHostPort
            else -> activeRuntimeTarget?.port?.takeIf { it in 1..65535 } ?: actualRuntimeHostPort
        }

        if (appPort == null) {
            if (dockerPublishedHostPorts != null) {
                return NginxPlanResult.Err(
                    HttpStatusCode.BadRequest,
                    "missing_port",
                    when {
                        dockerPublishedHostPorts.isEmpty() ->
                            "Could not infer the active deployment upstream port from the active runtime. Ensure the deployment exposes and publishes its application port."

                        else ->
                            "Multiple published host ports were found, but the active deployment does not identify which one serves the application. Redeploy it so Gatekeeperd can record the application port."
                    },
                    buildJsonObject {
                        put("resolvedUpstream", JsonPrimitive(configuredContainerName.orEmpty()))
                        putJsonArray("publishedHostPorts") {
                            dockerPublishedHostPorts.sorted().forEach { add(JsonPrimitive(it)) }
                        }
                    }
                )
            }

            return NginxPlanResult.Err(
                HttpStatusCode.BadRequest,
                "missing_port",
                "Could not resolve a published application port from the active deployment. Deploy or attach a service with a published application port first."
            )
        }

        // Prefer Docker-based validation: works even when gatekeeperd runs in a container (127.0.0.1 differs).
        if (dockerPublishedHostPorts != null) {
            if (selectedContainerPort != null && selectedContainerPort !in dockerPublishedPorts.orEmpty()) {
                return NginxPlanResult.Err(
                    HttpStatusCode.BadRequest,
                    "container_port_not_published",
                    "Container '$configuredContainerName' does not publish container port $selectedContainerPort (published: ${
                        dockerPublishedPorts.keys.sorted().joinToString(", ")
                    })."
                )
            }
            if (dockerPublishedHostPorts.isNotEmpty() && appPort !in dockerPublishedHostPorts) {
                return NginxPlanResult.Err(
                    HttpStatusCode.BadRequest,
                    "port_not_active",
                    "Container '$configuredContainerName' is running but does not publish host port $appPort (published: ${
                        dockerPublishedHostPorts.sorted().joinToString(", ")
                    }). Nginx proxies to 127.0.0.1:$appPort."
                )
            }
        } else {
            if (!portProbe.isPortActive(appPort)) {
                return NginxPlanResult.Err(
                    HttpStatusCode.BadRequest,
                    "port_not_active",
                    "Port $appPort is not reachable on 127.0.0.1 for project $slug. Ensure the active deployment upstream is listening on the host."
                )
            }
        }

        val upstreamScheme = "http"

        val domain = try {
            validator.requireValidHostname(projectDomain)
        } catch (e: IllegalArgumentException) {
            return NginxPlanResult.Err(
                HttpStatusCode.BadRequest,
                "invalid_domain",
                e.message ?: "Project domain is invalid"
            )
        }
        if (domain.isBlank()) {
            return NginxPlanResult.Err(HttpStatusCode.BadRequest, "no_domain", "Project has no domain configured")
        }

        val requireSsl = request.requireSsl
        val resolvedCertificate = certificateProvisioner.resolveCertificateForDomain(domain)

        if (requireSsl && resolvedCertificate == null) {
            val installed = certificateProvisioner.listInstalledCertificates().map { it.certificateDomain }
            return NginxPlanResult.Err(
                HttpStatusCode.BadRequest,
                "certificate_not_found",
                "No SSL certificate found for '$domain' (or its parent domains).",
                buildJsonObject {
                    put("domain", JsonPrimitive(domain))
                    putJsonArray("installedCertificates") { installed.forEach { add(JsonPrimitive(it)) } }
                }
            )
        }

        return NginxPlanResult.Ok(
            NginxEnablePlan(
                slug = slug,
                domain = domain,
                appPort = appPort,
                containerPort = selectedContainerPort,
                upstreamScheme = upstreamScheme,
                resolvedCertificate = resolvedCertificate
            )
        )
    }

}