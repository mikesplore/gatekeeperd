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
    val created: Long
)

@Serializable
data class NetworkInfo(
    val id: String,
    val name: String,
    val driver: String,
    val scope: String
)

@Serializable
data class VolumeMount(
    val hostPath: String,
    val containerPath: String,
    val readOnly: Boolean = false
)

@Serializable
data class CreateContainerRequest(
    val name: String,
    val image: String,
    val ports: Map<Int, Int> = emptyMap(),
    val env: Map<String, String> = emptyMap(),
    val network: String = "bridge",
    val volumes: List<VolumeMount> = emptyList(),
    val restartPolicy: String? = null,
    val pullImage: Boolean = true
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
