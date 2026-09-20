package com.gatekeeper.docker

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.command.CreateContainerResponse
import com.github.dockerjava.api.command.InspectContainerResponse
import com.github.dockerjava.api.command.RemoveContainerCmd
import com.github.dockerjava.api.command.RemoveImageCmd
import com.github.dockerjava.api.model.Bind
import com.github.dockerjava.api.model.Container
import com.github.dockerjava.api.model.Frame
import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.HostConfig
import com.github.dockerjava.api.model.Ports
import com.github.dockerjava.api.model.RestartPolicy
import com.github.dockerjava.api.model.Image
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientBuilder
import com.github.dockerjava.core.DockerClientConfig
import org.slf4j.LoggerFactory
import com.gatekeeper.config.AppConfig
import java.io.File
import java.util.stream.Collectors
import com.github.dockerjava.api.async.ResultCallback

private val logger = LoggerFactory.getLogger("com.gatekeeper.docker.DockerService")

class DockerService(dockerSocketPath: String) {
    private val client: DockerClient

    init {
        val config: DockerClientConfig = DefaultDockerClientConfig.createDefaultConfigBuilder()
            .withDockerHost(dockerSocketPath)
            .build()

        client = DockerClientBuilder.getInstance(config).build()
        logger.info("Docker client initialized (socket: $dockerSocketPath)")
    }

    fun listContainers(all: Boolean): List<ContainerInfo> {
        return client.listContainersCmd()
            .withShowAll(all)
            .exec()
            .stream()
            .map { container -> toContainerInfo(container) }
            .collect(Collectors.toList())
    }

    fun getContainer(containerNameOrId: String): ContainerInfo? {
        return try {
            val containers = client.listContainersCmd()
                .withShowAll(true)
                .exec()

            containers.firstOrNull { container ->
                container.id.startsWith(containerNameOrId) ||
                        container.names.any { name -> name.removePrefix("/") == containerNameOrId }
            }?.let { container ->
                val base = toContainerInfo(container)
                val inspected = client.inspectContainerCmd(container.id).exec()
                base.copy(
                    networks = inspected.networkSettings?.networks?.keys?.sorted().orEmpty(),
                    volumes = inspected.mounts?.mapNotNull { mount ->
                        val source = mount.getSource() ?: return@mapNotNull null
                        val destination = mount.getDestination()?.path ?: return@mapNotNull null
                        VolumeMount(source, destination, mount.getMode()?.contains("ro") == true)
                    }.orEmpty(),
                    restartPolicy = inspected.hostConfig?.restartPolicy?.name ?: "unknown",
                    imageId = inspected.imageId,
                    command = inspected.path,
                    entrypoint = inspected.config?.entrypoint?.toList().orEmpty(),
                    workingDirectory = inspected.config?.workingDir,
                    user = inspected.config?.user,
                    environmentKeys = inspected.config?.env?.mapNotNull { it.substringBefore('=', "").takeIf(String::isNotBlank) }?.sorted().orEmpty(),
                    labels = inspected.config?.labels.orEmpty(),
                    restartCount = inspected.restartCount ?: 0,
                    oomKilled = inspected.state?.oomKilled == true,
                    health = inspected.state?.health?.status,
                    ipAddresses = inspected.networkSettings?.networks?.mapNotNull { (network, details) ->
                        details.ipAddress?.takeIf(String::isNotBlank)?.let { network to it }
                    }?.toMap().orEmpty()
                )
            }
        } catch (e: Exception) {
            logger.error("Error getting container $containerNameOrId", e)
            null
        }
    }

    fun containerLogs(containerNameOrId: String, tail: Int = 100): String {
        val output = StringBuilder()
        client.logContainerCmd(containerNameOrId)
            .withStdOut(true)
            .withStdErr(true)
            .withTail(tail)
            .exec(object : ResultCallback.Adapter<Frame>() {
                override fun onNext(frame: Frame) {
                    output.append(String(frame.payload ?: ByteArray(0), Charsets.UTF_8))
                }
            }).awaitCompletion()
        return output.toString()
    }

    fun startContainer(containerNameOrId: String) {
        client.startContainerCmd(containerNameOrId).exec()
        logger.info("Started container: $containerNameOrId")
    }

    fun stopContainer(containerNameOrId: String, timeoutSeconds: Int = 10) {
        client.stopContainerCmd(containerNameOrId).withTimeout(timeoutSeconds).exec()
        logger.info("Stopped container: $containerNameOrId")
    }

    fun restartContainer(containerNameOrId: String) {
        client.restartContainerCmd(containerNameOrId).exec()
        logger.info("Restarted container: $containerNameOrId")
    }

    fun pullImage(image: String, tag: String = "latest") {
        val fullName = if (tag.isNotEmpty()) "$image:$tag" else image
        logger.info("Pulling image: $fullName")
        client.pullImageCmd(fullName).start().awaitCompletion()
        logger.info("Image pulled: $fullName")
    }

    fun pullImageViaCli(imageRef: String, dockerHost: String? = null) {
        val ref = imageRef.trim()
        require(ref.isNotBlank()) { "imageRef must be non-blank" }

        val cmd = listOf("docker", "pull", ref)
        logger.info("Pulling image via docker CLI: $ref")

        val process = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .apply {
                if (!dockerHost.isNullOrBlank()) {
                    environment()["DOCKER_HOST"] = dockerHost
                }
            }
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        if (exit != 0) {
            throw RuntimeException("docker pull failed (exit=$exit): ${output.trim().ifBlank { "no output" }}")
        }
        logger.info("Image pulled via docker CLI: $ref")
    }

    fun imageExists(imageRef: String): Boolean {
        val ref = imageRef.trim()
        if (ref.isBlank()) return false

        val candidates = buildList {
            add(ref)
            if (!ref.contains(":") && !ref.contains("@")) {
                add("$ref:latest")
            }
        }

        return candidates.any { candidate ->
            try {
                client.inspectImageCmd(candidate).exec()
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    fun imageDigest(imageRef: String): String? = runCatching {
        client.inspectImageCmd(imageRef).exec().repoDigests?.firstOrNull()
    }.getOrNull()

    data class ImageInfo(val id: String, val tags: List<String>, val sizeBytes: Long)

    fun listImages(): List<ImageInfo> = client.listImagesCmd().withShowAll(true).exec().map { image ->
        ImageInfo(image.id ?: "", image.repoTags?.toList().orEmpty(), image.size ?: 0L)
    }

    fun referencedImageNames(): Set<String> = listContainers(true).map { it.image }.toSet()

    fun hostPortsInUse(): Set<Int> {
        return client.listContainersCmd()
            .withShowAll(true)
            .exec()
            .flatMap { c -> c.ports?.toList().orEmpty() }
            .mapNotNull { p -> p.publicPort }
            .toSet()
    }

    fun listNetworks(): List<NetworkInfo> {
        val containersByNetwork = client.listContainersCmd()
            .withShowAll(true)
            .exec()
            .flatMap { container ->
                val containerName = container.names?.firstOrNull()?.removePrefix("/") ?: container.id
                container.networkSettings?.networks?.keys.orEmpty().map { networkName -> networkName to containerName }
            }
            .groupBy({ it.first }, { it.second })
        return client.listNetworksCmd()
            .exec()
            .map { net ->
                val ipam = net.ipam
                val ipamConfig = ipam?.config?.firstOrNull()
                NetworkInfo(
                    id = net.id ?: "",
                    name = net.name ?: "",
                    driver = net.driver ?: "",
                    scope = net.scope ?: "",
                    containers = containersByNetwork[net.name].orEmpty().distinct().sorted(),
                    subnet = ipamConfig?.subnet,
                    gateway = ipamConfig?.gateway,
                    ipRange = ipamConfig?.ipRange,
                    ipamDriver = ipam?.driver,
                    internal = net.internal == true,
                    attachable = net.isAttachable == true,
                    enableIpv6 = net.enableIPv6 == true,
                    options = net.options.orEmpty(),
                    labels = net.labels.orEmpty(),
                    endpoints = net.containers.orEmpty().map { (id, endpoint) -> NetworkEndpoint(endpoint.name ?: id, id, endpoint.ipv4Address, endpoint.ipv6Address, endpoint.macAddress, endpoint.endpointId) }.sortedBy { it.container }
                )
            }
    }

    fun createNetworkIfMissing(name: String) {
        val networks = client.listNetworksCmd().exec()
        val exists = networks.any { it.name == name }

        if (!exists) {
            client.createNetworkCmd().withName(name).exec()
            logger.info("Created Docker network: $name")
        } else {
            logger.info("Docker network already exists: $name")
        }
    }

    fun createNetwork(name: String, driver: String = "bridge") {
        require(name.matches(Regex("^[A-Za-z0-9_.-]{1,63}$"))) { "Invalid network name" }
        client.createNetworkCmd().withName(name).withDriver(driver).exec()
    }

    fun deleteNetwork(name: String) {
        require(name != "bridge" && name != "host" && name != "none") { "Docker built-in networks cannot be deleted" }
        client.removeNetworkCmd(name).exec()
    }

    fun listVolumes(): List<VolumeInfo> {
        val attachedByVolume = client.listContainersCmd().withShowAll(true).exec().flatMap { container ->
            val name = container.names?.firstOrNull()?.trimStart('/') ?: container.id.orEmpty()
            runCatching {
                client.inspectContainerCmd(container.id).exec().mounts.orEmpty()
                    .filter { !it.name.isNullOrBlank() && it.driver?.equals("local", ignoreCase = true) != false }
                    .map { it.name!! to name }
            }.getOrDefault(emptyList())
        }.groupBy({ it.first }, { it.second }).mapValues { (_, names) -> names.distinct().sorted() }

        return client.listVolumesCmd().exec().volumes.orEmpty().map { volume ->
            val name = volume.name.orEmpty()
            VolumeInfo(
                name = name,
                driver = volume.driver.orEmpty(),
                mountpoint = volume.mountpoint.orEmpty(),
                scope = "local",
                labels = volume.labels.orEmpty(),
                options = volume.options.orEmpty(),
                containers = attachedByVolume[name].orEmpty()
            )
        }
    }

    fun createVolume(name: String, driver: String = "local") {
        require(name.matches(Regex("^[A-Za-z0-9_.-]{1,255}$"))) { "Invalid volume name" }
        client.createVolumeCmd().withName(name).withDriver(driver).exec()
    }

    fun deleteVolume(name: String) { client.removeVolumeCmd(name).exec() }

    fun containerHealth(containerNameOrId: String): String {
        return try {
            val inspect: InspectContainerResponse = client.inspectContainerCmd(containerNameOrId).exec()
            val state = inspect.state
            val isRunning = state.running
            when {
                isRunning != null && isRunning -> "running"
                isRunning != null && state.exitCode != null -> "exited"
                else -> "unknown"
            }
        } catch (e: Exception) {
            logger.error("Error checking health for container $containerNameOrId", e)
            "unknown"
        }
    }

    fun createContainer(request: CreateContainerRequest): ContainerInfo {
        enforceSecurityPolicy(request)
        if (request.pullImage && !imageExists(request.image)) {
            val raw = request.image.trim()
            val repo = raw.substringBeforeLast(":", raw)
            val tag = raw.substringAfterLast(":", "latest").takeIf { it != raw } ?: "latest"
            pullImage(repo, tag)
        }

        val exposedPorts = mutableListOf<ExposedPort>()
        val portBindings = Ports()
        request.ports.forEach { (hostPort, containerPort) ->
            val exposed = ExposedPort.tcp(containerPort)
            exposedPorts.add(exposed)
            portBindings.bind(exposed, Ports.Binding.bindPort(hostPort))
        }

        val envVars = request.env.map { "${it.key}=${it.value}" }

        val hostConfig = HostConfig()
            .withNetworkMode(request.network)

        if (request.ports.isNotEmpty()) {
            hostConfig.withPortBindings(portBindings)
        }

        if (request.volumes.isNotEmpty()) {
            val binds = request.volumes.map {
                Bind.parse("${it.hostPath}:${it.containerPath}" + if (it.readOnly) ":ro" else "")
            }
            hostConfig.withBinds(*binds.toTypedArray())
        }

        if (request.restartPolicy != null) {
            hostConfig.withRestartPolicy(RestartPolicy.parse(request.restartPolicy))
        }

        val createResponse: CreateContainerResponse = client.createContainerCmd(request.image)
            .withName(request.name)
            .withEnv(envVars)
            .withHostConfig(hostConfig)
            .withExposedPorts(exposedPorts)
            .exec()
        
        client.startContainerCmd(createResponse.id).exec()
        logger.info("Created and started container: ${request.name} (id: ${createResponse.id})")
        
        return getContainer(createResponse.id)!!
    }

    private fun enforceSecurityPolicy(request: CreateContainerRequest) {
        val registries = AppConfig.dockerAllowedRegistries
        if (registries.isNotEmpty()) {
            val repository = request.image.substringBeforeLast(":").substringBefore("@")
            val firstPart = repository.substringBefore('/')
            val registry = if (firstPart.contains('.') || firstPart.contains(':') || firstPart == "localhost") firstPart else "docker.io"
            require(registry.lowercase() in registries) { "Docker registry '$registry' is not allowed" }
        }
        val roots = AppConfig.dockerAllowedVolumeRoots.map { File(it).canonicalFile }
        if (roots.isNotEmpty()) {
            request.volumes.forEach { mount ->
                val hostPath = File(mount.hostPath).canonicalFile
                require(roots.any { hostPath == it || hostPath.toPath().startsWith(it.toPath()) }) {
                    "Docker volume path '${mount.hostPath}' is outside the allowed volume roots"
                }
            }
        }
    }

    fun deleteImage(image: String, tag: String = "latest", force: Boolean = true) {
        val fullName = if (tag.isNotEmpty()) "$image:$tag" else image
        logger.info("Deleting image: $fullName (force=$force)")
        val removeCmd: RemoveImageCmd = client.removeImageCmd(fullName)
            .withForce(force)
        removeCmd.exec()
        logger.info("Image deleted: $fullName")
    }

    fun deleteImageReference(reference: String) {
        client.removeImageCmd(reference).withForce(false).exec()
    }

    fun deleteContainer(containerNameOrId: String, force: Boolean = true) {
        val removeCmd: RemoveContainerCmd = client.removeContainerCmd(containerNameOrId)
            .withForce(force)
        removeCmd.exec()
        logger.info("Deleted container: $containerNameOrId")
    }

    fun renameContainer(containerNameOrId: String, newName: String) {
        client.renameContainerCmd(containerNameOrId).withName(newName).exec()
        logger.info("Renamed container $containerNameOrId to $newName")
    }

    fun close() {
        try {
            client.close()
            logger.info("Docker client closed")
        } catch (e: Exception) {
            logger.error("Error closing Docker client", e)
        }
    }

    private fun toContainerInfo(container: Container): ContainerInfo {
        val names = container.names?.map { it.removePrefix("/") } ?: emptyList()
        val ports = container.ports?.map { port ->
            val containerPort = port.privatePort?.toString() ?: return@map null
            val protocol = port.type ?: "tcp"
            port.publicPort?.let { "$it:$containerPort/$protocol" } ?: "$containerPort/$protocol"
        }?.filterNotNull()?.distinct()?.joinToString(", ") ?: ""

        return ContainerInfo(
            id = container.id.substring(0, 12),
            name = names.firstOrNull() ?: container.id.substring(0, 12),
            image = container.image ?: "unknown",
            status = container.status ?: "unknown",
            state = container.state ?: "unknown",
            ports = ports,
            created = container.created ?: 0L,
            networks = container.networkSettings?.networks?.keys?.sorted().orEmpty()
        )
    }
}
