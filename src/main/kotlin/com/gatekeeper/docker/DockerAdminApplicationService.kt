package com.gatekeeper.docker

import com.gatekeeper.api.HttpApiException
import com.gatekeeper.api.InputValidators
import com.gatekeeper.config.AppSettings
import io.ktor.http.HttpStatusCode

class DockerAdminApplicationService(
    private val docker: DockerGateway,
    private val settings: AppSettings.DockerSettings
) {
    fun containers(search: String?, state: String?, offset: Int, limit: Int): ContainersListResponse {
        requireAvailable()
        val all = docker.listContainers(all = true).asSequence()
            .filter { search.isNullOrBlank() || it.name.contains(search, true) || it.image.contains(search, true) }
            .filter { state.isNullOrBlank() || it.state.equals(state, ignoreCase = true) }
            .toList()
        return ContainersListResponse(all.drop(offset.coerceAtLeast(0)).take(limit.coerceIn(1, 500)), all.size, limit, offset)
    }

    fun container(name: String): ContainerInfo {
        requireContainerName(name)
        requireAvailable()
        return docker.getContainer(name)
            ?: throw HttpApiException(HttpStatusCode.NotFound, "container_not_found", "Container not found")
    }

    fun credentials(name: String): ContainerPresetCredentialsResponse {
        requireContainerName(name)
        requireAvailable()
        return docker.getContainerPresetCredentials(name)
            ?: throw HttpApiException(HttpStatusCode.NotFound, "preset_credentials_not_found", "No supported database or Redis preset credentials were found for this container")
    }

    fun logs(name: String, tail: Int): ContainerLogsResponse {
        requireContainerName(name)
        requireAvailable()
        return try { ContainerLogsResponse(name, tail, docker.containerLogs(name, tail)) }
        catch (error: Exception) { throw HttpApiException(HttpStatusCode.Conflict, "container_logs_failed", error.message ?: "Unable to read container logs") }
    }

    fun start(name: String) = performContainerAction(name, "started", "container_start_failed") { docker.startContainer(name) }
    fun stop(name: String) = performContainerAction(name, "stopped", "container_stop_failed") { docker.stopContainer(name) }
    fun restart(name: String) = performContainerAction(name, "restarted", "container_restart_failed") { docker.restartContainer(name) }
    fun delete(name: String) = performContainerAction(name, "deleted", "container_delete_failed") { docker.deleteContainer(name, force = true) }

    fun health(name: String): Map<String, String> {
        requireContainerName(name); requireAvailable()
        return mapOf("container" to name, "health" to docker.containerHealth(name))
    }

    fun networks(search: String?, offset: Int, limit: Int): NetworkPage {
        requireAvailable()
        val all = docker.listNetworks().filter { search.isNullOrBlank() || it.name.contains(search, true) }
        val page = all.drop(offset).take(limit)
        return NetworkPage(page, all.size, limit, offset, offset + page.size < all.size)
    }

    fun createNetwork(body: CreateNetworkRequest) = conflict("network_create_failed", "Unable to create network") { docker.createNetwork(body.name, body.driver) }
    fun deleteNetwork(name: String) = conflict("network_delete_failed", "Unable to delete network") { docker.deleteNetwork(name) }

    fun volumes(search: String?, offset: Int, limit: Int): VolumePage {
        requireAvailable()
        val all = docker.listVolumes().filter { search.isNullOrBlank() || it.name.contains(search, true) }
        val page = all.drop(offset).take(limit)
        return VolumePage(page, all.size, limit, offset, offset + page.size < all.size)
    }

    fun createVolume(body: CreateNetworkRequest): String {
        val driver = body.driver.trim().takeIf { it.isNotBlank() && !it.equals("bridge", true) } ?: "local"
        conflict("volume_create_failed", "Unable to create volume") { docker.createVolume(body.name, driver) }
        return driver
    }

    fun deleteVolume(name: String) = conflict("volume_delete_failed", "Unable to delete volume") { docker.deleteVolume(name) }

    fun pullImage(image: String, tag: String, pullViaCli: Boolean) {
        requireAvailable()
        val normalizedImage = image.trim()
        if (!InputValidators.isValidImageName(normalizedImage)) throw HttpApiException(HttpStatusCode.BadRequest, "invalid_request", "A valid image name is required")
        val normalizedTag = tag.trim().ifBlank { "latest" }
        try {
            if (pullViaCli || settings.pullViaCli) docker.pullImageViaCli("$normalizedImage:$normalizedTag", settings.socket)
            else docker.pullImage(normalizedImage, normalizedTag)
        } catch (error: Exception) {
            throw HttpApiException(HttpStatusCode.InternalServerError, "image_pull_failed", error.message ?: "Failed to pull image")
        }
    }

    private fun performContainerAction(name: String, status: String, code: String, action: () -> Unit): Map<String, String> {
        requireContainerName(name); requireAvailable()
        try { action() } catch (error: Exception) {
            throw HttpApiException(HttpStatusCode.InternalServerError, code, error.message ?: "Container operation failed")
        }
        return mapOf("status" to status, "container" to name)
    }

    private fun conflict(code: String, message: String, operation: () -> Unit) {
        requireAvailable()
        try { operation() } catch (error: Exception) {
            throw HttpApiException(HttpStatusCode.Conflict, code, error.message ?: message)
        }
    }

    private fun requireAvailable() {
        if (!docker.available) throw HttpApiException(HttpStatusCode.ServiceUnavailable, "docker_unavailable", "Docker is not available")
    }

    private fun requireContainerName(name: String) {
        if (!InputValidators.isValidContainerName(name)) throw HttpApiException(HttpStatusCode.BadRequest, "invalid_container_name", "Invalid container name")
    }
}
