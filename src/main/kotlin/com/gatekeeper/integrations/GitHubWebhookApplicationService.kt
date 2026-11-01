package com.gatekeeper.integrations

import com.gatekeeper.api.HttpApiException
import com.gatekeeper.config.AppSettings
import com.gatekeeper.deployment.CreateDeploymentRequest
import com.gatekeeper.feature.audit.domain.repository.AuditRepository
import com.gatekeeper.feature.deployment.domain.repository.DeploymentJobRepository
import com.gatekeeper.feature.integrations.domain.repository.GitHubCredentialStore
import com.gatekeeper.feature.integrations.domain.repository.GitHubWebhookStore
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class GitHubWebhookApplicationService(
    private val settings: AppSettings,
    private val credentials: GitHubCredentialStore,
    private val webhooks: GitHubWebhookStore,
    private val deployments: DeploymentJobRepository,
    private val audit: AuditRepository
) {
    fun handle(body: String, signature: String, event: String, delivery: String?): GitHubWebhookResponse {
        val secret = runCatching { credentials.webhookSecret() }.getOrNull() ?: settings.github.webhookSecret
        if (secret.isBlank()) throw HttpApiException(HttpStatusCode.ServiceUnavailable, "github_webhook_unconfigured", "GitHub webhook secret is not configured")
        val expected = "sha256=" + hmac(body, secret)
        if (!MessageDigest.isEqual(expected.toByteArray(), signature.toByteArray())) {
            throw HttpApiException(HttpStatusCode.Unauthorized, "invalid_github_signature", "Invalid GitHub webhook signature")
        }
        if (delivery.isNullOrBlank()) throw HttpApiException(HttpStatusCode.BadRequest, "missing_github_delivery", "X-GitHub-Delivery is required")

        val repository = runCatching {
            Json.parseToJsonElement(body).jsonObject["repository"]?.jsonObject?.get("full_name")?.jsonPrimitive?.content
        }.getOrNull()
        if (!webhooks.claim(delivery, event, repository)) return GitHubWebhookResponse("duplicate", event, 0)
        val queued = if (event == "push") queuePushDeployments(body) else 0
        webhooks.setQueuedCount(delivery, queued)
        audit.write(null, "github_webhook_received", "github:$delivery", "event=$event repository=${repository ?: "unknown"} queued=$queued")
        return GitHubWebhookResponse("received", event, queued)
    }

    private fun queuePushDeployments(body: String): Int {
        val root = Json.parseToJsonElement(body).jsonObject
        val repository = root["repository"]?.jsonObject?.get("full_name")?.jsonPrimitive?.content ?: return 0
        val ref = root["ref"]?.jsonPrimitive?.content?.let { rawRef ->
            when {
                rawRef.startsWith("refs/heads/") -> rawRef.removePrefix("refs/heads/")
                rawRef.startsWith("refs/tags/") -> rawRef.removePrefix("refs/tags/")
                else -> rawRef
            }
        } ?: return 0
        return deployments.findAutoDeployTargets(repository, ref).count { target ->
            val saved = deployments.latestForService(target.projectId, target.serviceId, target.environment)
            val request = if (saved != null) {
                CreateDeploymentRequest(
                    repository = target.repository, gitRef = target.gitRef, registry = target.registry,
                    imageName = target.imageName, imageTag = target.imageTag,
                    hostPort = saved.hostPort, containerPort = saved.containerPort, network = saved.network,
                    restartPolicy = saved.restartPolicy, projectId = target.projectId.toString(),
                    serviceId = target.serviceId.toString(), triggerSource = "github_push",
                    env = saved.env, secretEnv = saved.secretEnv, volumes = saved.volumes,
                    createNetworkIfMissing = saved.createNetworkIfMissing, environment = target.environment,
                    autoDeploy = true
                )
            } else {
                CreateDeploymentRequest(
                    repository = target.repository, gitRef = target.gitRef, registry = target.registry,
                    imageName = target.imageName, imageTag = target.imageTag, projectId = target.projectId.toString(),
                    serviceId = target.serviceId.toString(), triggerSource = "github_push",
                    environment = target.environment, autoDeploy = true
                )
            }
            deployments.create(request)
            true
        }
    }

    private fun hmac(value: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
