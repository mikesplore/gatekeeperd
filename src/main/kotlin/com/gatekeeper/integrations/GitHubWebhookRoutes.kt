package com.gatekeeper.integrations

import com.gatekeeper.api.respondError
import com.gatekeeper.config.AppConfig
import com.gatekeeper.db.repositories.DeploymentJobRepository
import com.gatekeeper.db.repositories.ProjectRepository
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
            val queued = if (event == "push") queuePushDeployments(body) else 0
            call.respond(HttpStatusCode.Accepted, mapOf("status" to "received", "event" to event, "deploymentsQueued" to queued))
        }
    }
}

private fun queuePushDeployments(body: String): Int {
    val root = Json.parseToJsonElement(body).jsonObject
    val repository = root["repository"]?.jsonObject?.get("full_name")?.jsonPrimitive?.content ?: return 0
    val ref = root["ref"]?.jsonPrimitive?.content?.removePrefix("refs/heads/") ?: return 0
    return ProjectRepository.findAutoDeployTargets(repository, ref).count { target ->
        DeploymentJobRepository.create(CreateDeploymentRequest(
            repository = target.repository,
            gitRef = target.gitRef,
            imageName = target.imageName,
            imageTag = target.imageTag,
            containerName = target.containerName
        ))
        true
    }
}

private fun hmac(value: String, secret: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
    return mac.doFinal(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
