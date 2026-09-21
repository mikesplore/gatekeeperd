package com.gatekeeper.integrations

import com.gatekeeper.api.respondError
import com.gatekeeper.config.AppConfig
import com.gatekeeper.db.repositories.DeploymentJobRepository
import com.gatekeeper.db.repositories.ProjectRepository
import com.gatekeeper.db.repositories.GitHubWebhookRepository
import com.gatekeeper.db.repositories.AuditRepository
import com.gatekeeper.deployment.CreateDeploymentRequest
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.Serializable

@Serializable
private data class GitHubWebhookResponse(val status: String, val event: String, val deploymentsQueued: Int)

fun Application.configureGitHubWebhookRoutes() {
    routing {
        post("/api/integrations/github/webhook") {
            val secret = AppConfig.githubWebhookSecret
            if (secret.isBlank()) {
                call.respondError(HttpStatusCode.ServiceUnavailable, "github_webhook_unconfigured", "GitHub webhook secret is not configured")
                return@post
            }
            val body = call.receiveText()
            val signature = call.request.headers["X-Hub-Signature-256"] ?: ""
            val expected = "sha256=" + hmac(body, secret)
            if (!MessageDigest.isEqual(expected.toByteArray(), signature.toByteArray())) {
                call.respondError(HttpStatusCode.Unauthorized, "invalid_github_signature", "Invalid GitHub webhook signature")
                return@post
            }
            val event = call.request.headers["X-GitHub-Event"] ?: "unknown"
            val delivery = call.request.headers["X-GitHub-Delivery"]
            if (delivery.isNullOrBlank()) {
                call.respondError(HttpStatusCode.BadRequest, "missing_github_delivery", "X-GitHub-Delivery is required")
                return@post
            }
            val repository = runCatching { Json.parseToJsonElement(body).jsonObject["repository"]?.jsonObject?.get("full_name")?.jsonPrimitive?.content }.getOrNull()
            if (!GitHubWebhookRepository.claim(delivery, event, repository)) {
                call.respond(HttpStatusCode.Accepted, GitHubWebhookResponse("duplicate", event, 0))
                return@post
            }
            val queued = if (event == "push") queuePushDeployments(body) else 0
            GitHubWebhookRepository.setQueuedCount(delivery, queued)
            AuditRepository.write(null, "github_webhook_received", "github:$delivery", "event=$event repository=${repository ?: "unknown"} queued=$queued")
            call.respond(HttpStatusCode.Accepted, GitHubWebhookResponse("received", event, queued))
        }
    }
}

private fun queuePushDeployments(body: String): Int {
    val root = Json.parseToJsonElement(body).jsonObject
    val repository = root["repository"]?.jsonObject?.get("full_name")?.jsonPrimitive?.content ?: return 0
    val ref = root["ref"]?.jsonPrimitive?.content?.removePrefix("refs/heads/") ?: return 0
    return ProjectRepository.findAutoDeployTargets(repository, ref).count { target ->
        val saved = DeploymentJobRepository.latestForProject(target.slug)
        val request = if (saved != null) {
            CreateDeploymentRequest(saved.repository, ref, saved.registry, saved.imageName, saved.imageTag, saved.containerName,
                saved.hostPort, saved.containerPort, saved.network, saved.restartPolicy, saved.projectSlug, "github_push",
                saved.env, saved.secretEnv, saved.volumes, saved.createNetworkIfMissing)
        } else {
            CreateDeploymentRequest(target.repository, target.gitRef, imageName = target.imageName, imageTag = target.imageTag,
                containerName = target.containerName, projectSlug = target.slug, triggerSource = "github_push")
        }
        DeploymentJobRepository.create(request)
        true
    }
}

private fun hmac(value: String, secret: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
    return mac.doFinal(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
