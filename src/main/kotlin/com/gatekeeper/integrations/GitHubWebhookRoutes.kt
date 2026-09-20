package com.gatekeeper.integrations

import com.gatekeeper.api.respondError
import com.gatekeeper.config.AppConfig
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

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
            call.respond(HttpStatusCode.Accepted, mapOf("status" to "received", "event" to (call.request.headers["X-GitHub-Event"] ?: "unknown")))
        }
    }
}

private fun hmac(value: String, secret: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
    return mac.doFinal(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
