package com.gatekeeper.integrations

import com.gatekeeper.config.AppConfig
import com.gatekeeper.db.repositories.GitHubAppInstallationRepository
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import java.nio.file.Files
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64

@Serializable private data class InstallationTokenResponse(val token: String, val expires_at: String)
@Serializable private data class InstallationRepositoriesResponse(val repositories: List<GitHubRepository>)
@Serializable data class GitHubRepository(val full_name: String, @SerialName("private") val isPrivate: Boolean)

object GitHubAppClient {
    private val http = HttpClient { install(ContentNegotiation) { json() } }

    fun isConfigured(): Boolean = AppConfig.githubAppId != null &&
        (AppConfig.githubAppInstallationId != null || runCatching { GitHubAppInstallationRepository.find() }.getOrNull() != null) &&
        AppConfig.githubAppPrivateKeyPath.isNotBlank()

    suspend fun installationToken(): String {
        val appId = AppConfig.githubAppId ?: error("GITHUB_APP_ID is not configured")
        // Prefer the installation selected through the GitHub connection flow.
        // The environment value is only a bootstrap fallback and may be stale.
        val installationId = GitHubAppInstallationRepository.find()?.installationId
            ?: AppConfig.githubAppInstallationId
            ?: error("No GitHub App installation is configured")
        val key = loadPrivateKey()
        val now = Instant.now().epochSecond
        val header = base64Url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}")
        val payload = base64Url("{\"iat\":${now - 60},\"exp\":${now + 540},\"iss\":$appId}")
        val unsigned = "$header.$payload"
        val signature = Signature.getInstance("SHA256withRSA").apply { initSign(key); update(unsigned.toByteArray()) }.sign()
        val jwt = "$unsigned.${Base64.getUrlEncoder().withoutPadding().encodeToString(signature)}"
        val response = http.post("https://api.github.com/app/installations/$installationId/access_tokens") {
            header(HttpHeaders.Authorization, "Bearer $jwt")
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
        }
        if (!response.status.isSuccess()) error("GitHub installation token request failed for installation $installationId: ${response.status} ${response.bodyAsText().take(300)}")
        return response.body<InstallationTokenResponse>().token
    }

    suspend fun repositories(query: String): List<GitHubRepository> {
        val token = installationToken()
        val response = http.get("https://api.github.com/installation/repositories") {
            parameter("per_page", 100)
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
        }
        if (!response.status.isSuccess()) error("GitHub repository lookup failed: ${response.status} ${response.bodyAsText().take(300)}")
        val normalized = query.trim().lowercase()
        return response.body<InstallationRepositoriesResponse>().repositories
            .filter { normalized.isBlank() || it.full_name.lowercase().contains(normalized) }
            .take(20)
    }

    private fun loadPrivateKey(): PrivateKey {
        val pem = Files.readString(java.nio.file.Path.of(AppConfig.githubAppPrivateKeyPath))
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace(Regex("\\s"), "")
        val raw = Base64.getDecoder().decode(pem)
        val pkcs8 = if (Files.readString(java.nio.file.Path.of(AppConfig.githubAppPrivateKeyPath)).contains("BEGIN RSA PRIVATE KEY")) wrapPkcs1(raw) else raw
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
    }

    private fun wrapPkcs1(key: ByteArray): ByteArray {
        val algorithm = byteArrayOf(0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00)
        val body = byteArrayOf(0x02, 0x01, 0x00) + algorithm + der(0x04, key)
        return der(0x30, body)
    }

    private fun der(tag: Int, value: ByteArray): ByteArray {
        val length = when {
            value.size < 128 -> byteArrayOf(value.size.toByte())
            value.size < 256 -> byteArrayOf(0x81.toByte(), value.size.toByte())
            else -> byteArrayOf(0x82.toByte(), (value.size shr 8).toByte(), value.size.toByte())
        }
        return byteArrayOf(tag.toByte()) + length + value
    }

    private fun base64Url(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
}
