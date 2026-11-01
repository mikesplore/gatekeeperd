package com.gatekeeper.integrations

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import org.koin.ktor.ext.get

fun Application.configureGitHubWebhookRoutes() {
    val webhookService = get<GitHubWebhookApplicationService>()
    routing {
        post("/api/integrations/github/webhook") {
            val response = webhookService.handle(
                body = call.receiveText(),
                signature = call.request.headers["X-Hub-Signature-256"].orEmpty(),
                event = call.request.headers["X-GitHub-Event"] ?: "unknown",
                delivery = call.request.headers["X-GitHub-Delivery"]
            )
            call.respond(HttpStatusCode.Accepted, response)
        }
    }
}
