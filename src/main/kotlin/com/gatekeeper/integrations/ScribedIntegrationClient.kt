package com.gatekeeper.integrations

import com.gatekeeper.config.AppConfig
import com.gatekeeper.db.repositories.ProjectRepository
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import kotlinx.coroutines.runBlocking

@Serializable data class ScribedSuspensionPayload(val project_id: String, val project_slug: String, val status: String, val reason: String, val occurred_at: String)

object ScribedIntegrationClient {
    private val logger = LoggerFactory.getLogger("com.gatekeeper.integrations.ScribedIntegrationClient")
    private val http = HttpClient()
    fun notifySuspension(project: ProjectRepository.ProjectRecord, reason: String) {
        runBlocking {
        val base = AppConfig.scribedCallbackUrl.trim().trimEnd('/')
        val secret = AppConfig.scribedIntegrationSecret.trim()
        if (base.isBlank() || secret.isBlank()) return@runBlocking
        repeat(3) { attempt ->
            val delivered = runCatching {
                val response = http.post("$base/integrations/gatekeeper/suspensions") {
                    contentType(ContentType.Application.Json); header("X-Gatekeeper-Secret", secret)
                    setBody(ScribedSuspensionPayload(project.id.toString(), project.slug, project.status, reason, java.time.OffsetDateTime.now().toString()))
                }
                if (!response.status.isSuccess()) logger.warn("Scribed callback failed: status=${response.status} project=${project.slug} attempt=${attempt + 1}")
                response.status.isSuccess()
            }.getOrElse {
                logger.warn("Scribed callback unavailable for project=${project.slug} attempt=${attempt + 1}: ${it.message}")
                false
            }
            if (delivered) return@runBlocking
            if (attempt < 2) Thread.sleep((attempt + 1) * 500L)
        }
        }
    }
}
