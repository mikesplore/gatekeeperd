package com.gatekeeper.docker

import kotlinx.serialization.Serializable

@Serializable
data class ContainerInfo(
    val id: String,
    val name: String,
    val image: String,
    val status: String,
    val state: String,
    val ports: String,
    val created: Long,
    val networks: List<String> = emptyList(),
    val volumes: List<VolumeMount> = emptyList(),
    val restartPolicy: String = "unknown",
    val imageId: String? = null,
    val command: String? = null,
    val entrypoint: List<String> = emptyList(),
    val workingDirectory: String? = null,
    val user: String? = null,
    val environmentKeys: List<String> = emptyList(),
    val labels: Map<String, String> = emptyMap(),
    val restartCount: Int = 0,
    val oomKilled: Boolean = false,
    val health: String? = null,
    val ipAddresses: Map<String, String> = emptyMap()
)

@Serializable
data class ContainerLogsResponse(val container: String, val tail: Int, val logs: String)

@Serializable
data class ContainersListResponse(
    val containers: List<ContainerInfo>,
    val total: Int,
    val limit: Int,
    val offset: Int
)

@Serializable
data class NetworkInfo(
    val id: String,
    val name: String,
    val driver: String,
    val scope: String,
    val containers: List<String> = emptyList(),
    val subnet: String? = null,
    val gateway: String? = null,
    val ipRange: String? = null,
    val ipamDriver: String? = null,
    val internal: Boolean = false,
    val attachable: Boolean = false,
    val enableIpv6: Boolean = false,
    val options: Map<String, String> = emptyMap(),
    val labels: Map<String, String> = emptyMap(),
    val endpoints: List<NetworkEndpoint> = emptyList()
)

@Serializable
data class NetworkEndpoint(val container: String, val containerId: String, val ipv4: String?, val ipv6: String?, val macAddress: String?, val endpointId: String?)

@Serializable
data class VolumeInfo(
    val name: String,
    val driver: String,
    val mountpoint: String,
    val scope: String,
    val createdAt: String? = null,
    val labels: Map<String, String> = emptyMap(),
    val options: Map<String, String> = emptyMap(),
    val containers: List<String> = emptyList()
)

@Serializable
data class CreateNetworkRequest(val name: String, val driver: String = "bridge")

@Serializable
data class VolumeMount(
    val hostPath: String,
    val containerPath: String,
    val readOnly: Boolean = false
)

@Serializable
data class CreateContainerRequest(
    val name: String = "",
    val projectSlug: String? = null,
    val image: String,
    val ports: Map<Int, Int> = emptyMap(),
    val env: Map<String, String> = emptyMap(),
    val network: String = "bridge",
    val volumes: List<VolumeMount> = emptyList(),
    val restartPolicy: String? = null,
    val pullImage: Boolean = true,
    val pullViaCli: Boolean = false
)

@Serializable
data class CreateContainerResponse(
    val id: String,
    val name: String,
    val status: String,
    val ports: String
)

@Serializable
data class DeleteImageRequest(
    val image: String,
    val tag: String = "latest",
    val force: Boolean = true
)

@Serializable
data class DeleteImageResponse(
    val status: String,
    val image: String
)

@Serializable
data class ImageStatusRequest(
    val image: String
)

@Serializable
data class ImageStatusResponse(
    val image: String,
    val exists: Boolean
)

@Serializable
data class PortsAvailabilityRequest(
    val hostPorts: List<Int>
)

@Serializable
data class PortsAvailabilityResponse(
    val ok: Boolean,
    val conflicts: List<Int> = emptyList()
)
