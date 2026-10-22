package com.gatekeeper.docker

import java.io.Closeable

interface DockerGateway : Closeable {
    fun listContainers(all: Boolean): List<ContainerInfo> = unsupported("listContainers")
    fun listNetworks(): List<NetworkInfo> = unsupported("listNetworks")
    fun imageExists(imageRef: String): Boolean = unsupported("imageExists")
    fun hostPortsInUse(): Set<Int> = unsupported("hostPortsInUse")
    fun imageDigest(imageRef: String): String? = unsupported("imageDigest")
    fun immutableImageReference(imageRef: String): String? = unsupported("immutableImageReference")
    fun imageExposedTcpPorts(imageRef: String): List<Int> = unsupported("imageExposedTcpPorts")
    fun pullImage(image: String, tag: String = "latest"): Unit = unsupported("pullImage")
    fun pullImageViaCli(imageRef: String, dockerHost: String? = null): Unit = unsupported("pullImageViaCli")
    fun createNetworkIfMissing(name: String): Unit = unsupported("createNetworkIfMissing")
    fun createContainer(request: CreateContainerRequest): ContainerInfo = unsupported("createContainer")
    fun deleteImage(image: String, tag: String = "latest", force: Boolean = true): Unit = unsupported("deleteImage")
    fun deleteContainer(containerNameOrId: String, force: Boolean = true): Unit = unsupported("deleteContainer")
    fun containerHealth(containerNameOrId: String): String
    fun dockerHealthStatus(containerNameOrId: String): String?
    fun getContainer(containerNameOrId: String): ContainerInfo? = null
    fun adoptionDetails(containerIdOrName: String): DockerService.AdoptionDetails? = null
    fun removeContainerIfPresent(containerName: String): Boolean = false
    fun createContainerTracked(request: CreateContainerRequest): CreatedContainer = unsupported("createContainerTracked")
    fun removeContainerByIdIfPresent(containerId: String): Boolean = false
    fun stopContainer(containerNameOrId: String, timeoutSeconds: Int = 10): Unit = unsupported("stopContainer")
    fun containerImageId(containerNameOrId: String): String? = null
    fun ensureRestoredContainer(request: CreateContainerRequest): ContainerInfo = unsupported("ensureRestoredContainer")

    private fun <T> unsupported(operation: String): T =
        throw UnsupportedOperationException("Docker gateway operation is not available: $operation")
}
