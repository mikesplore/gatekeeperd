package com.gatekeeper.docker

import java.io.Closeable

data class AdoptionDetails(
    val id: String,
    val name: String,
    val image: String,
    val imageDigest: String?,
    val imageId: String,
    val state: String,
    val ports: Map<Int, Int>,
    val portHostIps: Map<Int, String>,
    val networks: List<String>,
    val restartPolicy: String,
    val volumes: List<VolumeMount>,
    val environment: Map<String, String>,
    val runtimeOptions: ContainerRuntimeOptions
)

data class DockerImageInfo(val id: String, val tags: List<String>, val sizeBytes: Long)

interface DockerGateway : Closeable {
    val available: Boolean get() = true
    fun listContainers(all: Boolean): List<ContainerInfo> = unsupported("listContainers")
    fun listNetworks(): List<NetworkInfo> = unsupported("listNetworks")
    fun listVolumes(): List<VolumeInfo> = unsupported("listVolumes")
    fun imageExists(imageRef: String): Boolean = unsupported("imageExists")
    fun listImages(): List<DockerImageInfo> = unsupported("listImages")
    fun referencedImageNames(): Set<String> = unsupported("referencedImageNames")
    fun deleteImageReference(reference: String): Unit = unsupported("deleteImageReference")
    fun hostPortsInUse(): Set<Int> = unsupported("hostPortsInUse")
    fun imageDigest(imageRef: String): String? = unsupported("imageDigest")
    fun immutableImageReference(imageRef: String): String? = unsupported("immutableImageReference")
    fun imageExposedTcpPorts(imageRef: String): List<Int> = unsupported("imageExposedTcpPorts")
    fun pullImage(image: String, tag: String = "latest"): Unit = unsupported("pullImage")
    fun pullImageViaCli(imageRef: String, dockerHost: String? = null): Unit = unsupported("pullImageViaCli")
    fun createNetworkIfMissing(name: String): Unit = unsupported("createNetworkIfMissing")
    fun createNetwork(name: String, driver: String = "bridge"): Unit = unsupported("createNetwork")
    fun deleteNetwork(name: String): Unit = unsupported("deleteNetwork")
    fun createVolume(name: String, driver: String = "local"): Unit = unsupported("createVolume")
    fun deleteVolume(name: String): Unit = unsupported("deleteVolume")
    fun containerLogs(containerNameOrId: String, tail: Int = 100): String = unsupported("containerLogs")
    fun getContainerPresetCredentials(containerNameOrId: String): ContainerPresetCredentialsResponse? = unsupported("getContainerPresetCredentials")
    fun startContainer(containerNameOrId: String): Unit = unsupported("startContainer")
    fun connectContainerToNetwork(containerNameOrId: String, networkName: String): Unit = unsupported("connectContainerToNetwork")
    fun disconnectContainerFromNetwork(containerNameOrId: String, networkName: String): Unit = unsupported("disconnectContainerFromNetwork")
    fun restartContainer(containerNameOrId: String): Unit = unsupported("restartContainer")
    fun createContainer(request: CreateContainerRequest): ContainerInfo = unsupported("createContainer")
    fun deleteImage(image: String, tag: String = "latest", force: Boolean = true): Unit = unsupported("deleteImage")
    fun deleteContainer(containerNameOrId: String, force: Boolean = true): Unit = unsupported("deleteContainer")
    fun containerHealth(containerNameOrId: String): String
    fun dockerHealthStatus(containerNameOrId: String): String?
    fun getContainer(containerNameOrId: String): ContainerInfo? = null
    fun containerDiagnostics(containerNameOrId: String): DockerContainerDiagnostics? = null
    fun adoptionDetails(containerIdOrName: String): AdoptionDetails? = null
    fun removeContainerIfPresent(containerName: String): Boolean = false
    fun createContainerTracked(request: CreateContainerRequest): CreatedContainer = unsupported("createContainerTracked")
    fun removeContainerByIdIfPresent(containerId: String): Boolean = false
    fun stopContainer(containerNameOrId: String, timeoutSeconds: Int = 10): Unit = unsupported("stopContainer")
    fun containerImageId(containerNameOrId: String): String? = null
    fun ensureRestoredContainer(request: CreateContainerRequest): ContainerInfo = unsupported("ensureRestoredContainer")

    private fun <T> unsupported(operation: String): T =
        throw UnsupportedOperationException("Docker gateway operation is not available: $operation")
}
