package com.gatekeeper.docker

import java.io.Closeable

interface DockerGateway : Closeable {
    fun containerHealth(containerNameOrId: String): String
    fun dockerHealthStatus(containerNameOrId: String): String?
    fun getContainer(containerNameOrId: String): ContainerInfo? = null
    fun adoptionDetails(containerIdOrName: String): DockerService.AdoptionDetails? = null
    fun removeContainerIfPresent(containerName: String): Boolean = false
}
