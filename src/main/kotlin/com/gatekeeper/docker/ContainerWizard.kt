package com.gatekeeper.docker

import com.gatekeeper.api.InputValidators
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

internal data class ParsedImageRef(
    val repository: String,
    val tag: String
) {
    val normalized: String get() = "$repository:$tag"
}

internal fun parseImageRef(raw: String): ParsedImageRef? {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return null
    if (trimmed.contains("@")) return null // digests not supported in admin wizard yet

    val lastSlash = trimmed.lastIndexOf('/')
    val lastColon = trimmed.lastIndexOf(':')
    val hasTag = lastColon > lastSlash

    val repository = if (hasTag) trimmed.substring(0, lastColon) else trimmed
    val tag = if (hasTag) trimmed.substring(lastColon + 1) else "latest"

    if (!InputValidators.isValidImageName(repository)) return null
    if (tag.isBlank() || !Regex("^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$").matches(tag)) return null

    return ParsedImageRef(repository = repository, tag = tag)
}

@Serializable
data class ContainerWizardContextResponse(
    val internalNetwork: String,
    val internalNetworkExists: Boolean,
    val networks: List<String>
)

@Serializable
data class ContainerWizardValidateResponse(
    val success: Boolean,
    val message: String,
    val normalizedRequest: CreateContainerRequest,
    val imageExists: Boolean,
    val willPullImage: Boolean,
    val networkExists: Boolean,
    val willCreateInternalNetworkIfMissing: Boolean,
    val portConflicts: List<Int> = emptyList(),
    val warnings: List<String> = emptyList()
)

internal interface DockerWizardInspect {
    fun imageExists(imageRef: String): Boolean
    fun containerExists(name: String): Boolean
    fun listNetworkNames(): Set<String>
    fun hostPortsInUse(): Set<Int>
}

internal data class ContainerCreatePlan(
    val parsedImage: ParsedImageRef,
    val normalizedRequest: CreateContainerRequest,
    val imageExists: Boolean,
    val willPullImage: Boolean,
    val networkExists: Boolean,
    val willCreateInternalNetworkIfMissing: Boolean,
    val warnings: List<String> = emptyList()
)

internal sealed interface ContainerCreatePlanResult {
    data class Ok(val plan: ContainerCreatePlan) : ContainerCreatePlanResult
    data class Err(
        val status: HttpStatusCode,
        val code: String,
        val message: String,
        val data: JsonObject? = null
    ) : ContainerCreatePlanResult
}

internal fun computeContainerCreatePlan(
    request: CreateContainerRequest,
    internalNetwork: String,
    docker: DockerWizardInspect
): ContainerCreatePlanResult {
    val name = run {
        val explicit = request.name.trim()
        if (explicit.isNotBlank()) explicit
        else request.projectSlug?.let { InputValidators.normalizeSlug(it) }.orEmpty()
    }
    if (name.isBlank() || !InputValidators.isValidContainerName(name)) {
        return ContainerCreatePlanResult.Err(
            HttpStatusCode.BadRequest,
            "invalid_request",
            "A valid container name is required (or provide projectSlug to auto-name the container)."
        )
    }

    val containerExists = runCatching { docker.containerExists(name) }.getOrElse {
        return ContainerCreatePlanResult.Err(
            HttpStatusCode.InternalServerError,
            "docker_error",
            it.message ?: "Failed to check if container exists"
        )
    }
    if (containerExists) {
        return ContainerCreatePlanResult.Err(
            HttpStatusCode.Conflict,
            "container_exists",
            "Container '$name' already exists"
        )
    }

    val parsedImage = parseImageRef(request.image)
        ?: return ContainerCreatePlanResult.Err(
            HttpStatusCode.BadRequest,
            "invalid_request",
            "A valid image reference is required (e.g. 'nginx:latest' or 'nginx')"
        )

    val imageRef = parsedImage.normalized
    val imageExists = runCatching { docker.imageExists(imageRef) }.getOrElse {
        return ContainerCreatePlanResult.Err(
            HttpStatusCode.InternalServerError,
            "docker_error",
            it.message ?: "Failed to check if image exists"
        )
    }
    val willPullImage = !imageExists && request.pullImage
    if (!imageExists && !request.pullImage) {
        return ContainerCreatePlanResult.Err(
            HttpStatusCode.NotFound,
            "image_not_found",
            "Docker image '$imageRef' not found locally. Pull it first or set pullImage=true."
        )
    }

    val requestedNetwork = request.network.trim().ifBlank { "bridge" }
    val networks = runCatching { docker.listNetworkNames() }.getOrElse {
        return ContainerCreatePlanResult.Err(
            HttpStatusCode.InternalServerError,
            "docker_error",
            it.message ?: "Failed to list Docker networks"
        )
    }
    val networkExists = requestedNetwork in networks
    val willCreateInternalNetworkIfMissing = requestedNetwork == internalNetwork && !networkExists
    if (requestedNetwork != internalNetwork && !networkExists) {
        return ContainerCreatePlanResult.Err(
            HttpStatusCode.BadRequest,
            "network_not_found",
            "Docker network '$requestedNetwork' not found. Use GET /api/admin/networks (or wizard context) to populate the dropdown.",
            buildJsonObject {
                put("requestedNetwork", JsonPrimitive(requestedNetwork))
                put("internalNetwork", JsonPrimitive(internalNetwork))
                putJsonArray("networks") { networks.sorted().forEach { add(JsonPrimitive(it)) } }
            }
        )
    }

    val restartPolicy = request.restartPolicy?.trim()?.takeIf { it.isNotBlank() }
    if (restartPolicy != null) {
        val ok = restartPolicy in listOf("no", "always", "unless-stopped") ||
            restartPolicy == "on-failure" ||
            Regex("^on-failure:\\d{1,5}$").matches(restartPolicy)
        if (!ok) {
            return ContainerCreatePlanResult.Err(
                HttpStatusCode.BadRequest,
                "invalid_request",
                "restartPolicy must be one of: no, always, unless-stopped, on-failure, on-failure:<max-retries>"
            )
        }
    }

    val invalidEnvKey = request.env.keys.firstOrNull { it.isBlank() || it.contains('=') || it.contains('\u0000') }
    if (invalidEnvKey != null) {
        return ContainerCreatePlanResult.Err(
            HttpStatusCode.BadRequest,
            "invalid_request",
            "Invalid env var name '$invalidEnvKey' (must be non-blank and must not contain '=')."
        )
    }

    val hostPorts = request.ports.keys.toSet()
    val invalidPorts = hostPorts.filter { it !in 1..65535 } +
        request.ports.values.filter { it !in 1..65535 }
    if (invalidPorts.isNotEmpty()) {
        return ContainerCreatePlanResult.Err(
            HttpStatusCode.BadRequest,
            "invalid_request",
            "Ports must be between 1 and 65535"
        )
    }

    if (hostPorts.isNotEmpty()) {
        val inUse = runCatching { docker.hostPortsInUse() }.getOrElse {
            return ContainerCreatePlanResult.Err(
                HttpStatusCode.InternalServerError,
                "docker_error",
                it.message ?: "Failed to check host ports"
            )
        }
        val conflicts = hostPorts.intersect(inUse).sorted()
        if (conflicts.isNotEmpty()) {
            return ContainerCreatePlanResult.Err(
                HttpStatusCode.Conflict,
                "port_conflict",
                "Port(s) already in use: ${conflicts.joinToString(", ")}",
                buildJsonObject {
                    putJsonArray("conflicts") { conflicts.forEach { add(JsonPrimitive(it)) } }
                }
            )
        }
    }

    val invalidVolume = request.volumes.firstOrNull { it.hostPath.isBlank() || it.containerPath.isBlank() }
    if (invalidVolume != null) {
        return ContainerCreatePlanResult.Err(
            HttpStatusCode.BadRequest,
            "invalid_request",
            "volumes require both hostPath and containerPath"
        )
    }

    val invalidContainerPath = request.volumes.firstOrNull { !it.containerPath.trim().startsWith("/") }
    if (invalidContainerPath != null) {
        return ContainerCreatePlanResult.Err(
            HttpStatusCode.BadRequest,
            "invalid_request",
            "containerPath must be an absolute path (start with '/')"
        )
    }

    val warnings = buildList {
        request.volumes.forEach { mount ->
            val hp = mount.hostPath.trim()
            if (!File(hp).isAbsolute) {
                add("Volume hostPath '$hp' is not an absolute path; bind mounts typically require absolute paths on Linux hosts.")
            }
            val f = File(hp)
            if (!f.exists()) {
                add("Volume hostPath '$hp' does not exist on the Docker host (Docker may create a directory, but file binds can fail).")
            }
        }
        if (willPullImage) add("Image '$imageRef' is not present locally and will be pulled during create.")
        if (willCreateInternalNetworkIfMissing) add("Internal network '$internalNetwork' is missing and will be created during create.")
    }

    return ContainerCreatePlanResult.Ok(
        ContainerCreatePlan(
            parsedImage = parsedImage,
            normalizedRequest = request.copy(
                name = name,
                image = imageRef,
                network = requestedNetwork,
                restartPolicy = restartPolicy
            ),
            imageExists = imageExists,
            willPullImage = willPullImage,
            networkExists = networkExists,
            willCreateInternalNetworkIfMissing = willCreateInternalNetworkIfMissing,
            warnings = warnings
        )
    )
}
