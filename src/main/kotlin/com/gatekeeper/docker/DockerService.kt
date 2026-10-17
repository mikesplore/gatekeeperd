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
import com.github.dockerjava.api.model.Volume
import com.github.dockerjava.api.model.AccessMode
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientBuilder
import com.github.dockerjava.core.DockerClientConfig
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient
import org.slf4j.LoggerFactory
import com.gatekeeper.config.AppConfig
import java.io.File
import java.util.stream.Collectors
import com.github.dockerjava.api.async.ResultCallback

private val logger = LoggerFactory.getLogger("com.gatekeeper.docker.DockerService")

internal data class CreatedContainer(val id: String, val info: ContainerInfo)

/** Cleans up only after create returned an identity for the resource this operation owns. */
internal fun <Handle, Result> createContainerWithCleanup(
    beforeCreate: () -> Unit = {},
    create: () -> Handle,
    start: (Handle) -> Unit,
    inspect: (Handle) -> Result,
    remove: (Handle) -> Unit
): Result {
    beforeCreate()
    var created: Handle? = null
    try {
        created = create()
        start(created)
        return inspect(created)
    } catch (failure: Throwable) {
        val ownedContainer = created
        if (ownedContainer != null) {
            try {
                remove(ownedContainer)
            } catch (cleanupFailure: Throwable) {
                if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
                logger.warn("Could not remove container created by failed operation", cleanupFailure)
            }
        }
        throw failure
    }
}

class DockerService(dockerSocketPath: String) : DockerGateway {
    private val client: DockerClient

    init {
        val config: DockerClientConfig = DefaultDockerClientConfig.createDefaultConfigBuilder()
            .withDockerHost(dockerSocketPath)
            .build()

        val httpClient = ApacheDockerHttpClient.Builder()
            .dockerHost(config.getDockerHost())
            .sslConfig(config.getSSLConfig())
            .build()
        client = DockerClientBuilder.getInstance(config)
            .withDockerHttpClient(httpClient)
            .build()
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
            inspectContainer(containerNameOrId)
        } catch (e: Exception) {
            logger.error("Error getting container $containerNameOrId", e)
            null
        }
    }

    /** Returns only the standard database/Redis preset credentials, never arbitrary container env. */
    fun getContainerPresetCredentials(containerNameOrId: String): ContainerPresetCredentialsResponse? = try {
        val listed = client.listContainersCmd().withShowAll(true).exec().firstOrNull { container ->
            container.id.startsWith(containerNameOrId) || container.names.orEmpty().any { it.removePrefix("/") == containerNameOrId }
        } ?: return null
        val inspected = client.inspectContainerCmd(listed.id).exec()
        val config = inspected.config ?: return null
        val env = config.env.orEmpty().mapNotNull { entry ->
            val separator = entry.indexOf('=')
            if (separator <= 0) null else entry.substring(0, separator) to entry.substring(separator + 1)
        }.toMap()
        val command = config.cmd.orEmpty().toList() + config.entrypoint.orEmpty().toList()
        val image = config.image.orEmpty().lowercase()
        val presetLabel = config.labels.orEmpty()["com.gatekeeperd.container-preset"]
        val isPostgres = presetLabel == "postgres" ||
            (env.containsKey("POSTGRES_USER") && env.containsKey("POSTGRES_DB"))
        val isRedis = presetLabel == "redis" ||
            (image.substringAfterLast('/').startsWith("redis") || command.any { it == "redis-server" })
        if (!isPostgres && !isRedis) return null

        val containerPort = if (isPostgres) 5432 else 6379
        val hostPort = inspected.networkSettings?.ports?.bindings
            ?.get(ExposedPort.tcp(containerPort))
            ?.firstOrNull()
            ?.hostPortSpec
            ?.toIntOrNull()
        val containerName = listed.names?.firstOrNull()?.removePrefix("/") ?: listed.id.take(12)
        val networkNames = inspected.networkSettings?.networks?.keys.orEmpty()
        val internalDnsAvailable = networkNames.any { it != "bridge" }
        val password = if (isPostgres) env["POSTGRES_PASSWORD"] else {
            val requirePassIndex = command.indexOf("--requirepass")
            command.getOrNull(requirePassIndex + 1)
                ?: command.firstOrNull { it.startsWith("--requirepass=") }?.substringAfter('=')
        }
        ContainerPresetCredentialsResponse(
            preset = if (isPostgres) "postgres" else "redis",
            database = if (isPostgres) env["POSTGRES_DB"] ?: "postgres" else null,
            username = if (isPostgres) env["POSTGRES_USER"] ?: "postgres" else "default",
            password = password,
            hostPort = hostPort,
            internalAddress = "$containerName:$containerPort",
            internalDnsAvailable = internalDnsAvailable,
            hostAddress = hostPort?.let { "127.0.0.1:$it" }
        )
    } catch (e: Exception) {
        logger.warn("Unable to read preset credentials for container {}", containerNameOrId, e)
        null
    }

    private fun inspectContainer(containerNameOrId: String): ContainerInfo? {
        val container = client.listContainersCmd()
            .withShowAll(true)
            .exec()
            .firstOrNull { listed ->
                listed.id.startsWith(containerNameOrId) ||
                    listed.names.orEmpty().any { name -> name.removePrefix("/") == containerNameOrId }
            } ?: return null
        val base = toContainerInfo(container)
        val inspected = client.inspectContainerCmd(container.id).exec()
        return base.copy(
            networks = inspected.networkSettings?.networks?.keys?.sorted().orEmpty(),
            volumes = inspected.mounts?.mapNotNull { mount ->
                val source = mount.source ?: return@mapNotNull null
                val destination = mount.destination?.path ?: return@mapNotNull null
                VolumeMount(source, destination, mount.mode?.contains("ro") == true, mount.getName())
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

    /** Force-removes an exactly named container if present; Docker/list failures propagate. */
    fun removeContainerIfPresent(containerName: String): Boolean {
        val container = client.listContainersCmd()
            .withShowAll(true)
            .exec()
            .firstOrNull { listed ->
                listed.names.orEmpty().any { it.removePrefix("/") == containerName }
            } ?: return false

        client.removeContainerCmd(container.id)
            .withForce(true)
            .exec()
        logger.info("Removed existing container before retry: {} (id: {})", containerName, container.id)
        return true
    }

    /** Runtime details for explicit project adoption. Environment values stay inside the service. */
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

    fun adoptionDetails(containerIdOrName: String): AdoptionDetails? = try {
        val listed = client.listContainersCmd().withShowAll(true).exec().firstOrNull { container ->
            container.id.startsWith(containerIdOrName) || container.names.orEmpty().any { it.removePrefix("/") == containerIdOrName }
        } ?: return null
        val inspected = client.inspectContainerCmd(listed.id).exec()
        val image = inspected.config?.image ?: listed.image ?: return null
        val containerImageId = inspected.imageId ?: return null
        val mappings = listed.ports.orEmpty().mapNotNull { port ->
            val containerPort = port.privatePort?.takeIf { it in 1..65535 } ?: return@mapNotNull null
            val hostPort = port.publicPort?.takeIf { it in 1..65535 } ?: return@mapNotNull null
            if ((port.type ?: "tcp") != "tcp") null else containerPort to hostPort
        }.toMap()
        val portHostIps = listed.ports.orEmpty().mapNotNull { port ->
            val containerPort = port.privatePort?.takeIf { it in 1..65535 } ?: return@mapNotNull null
            if ((port.type ?: "tcp") != "tcp" || port.publicPort == null) null
            else containerPort to (port.ip?.takeIf(String::isNotBlank) ?: "0.0.0.0")
        }.toMap()
        val environment = inspected.config?.env.orEmpty().mapNotNull { value ->
            val index = value.indexOf('=')
            if (index <= 0) null else value.substring(0, index) to value.substring(index + 1)
        }.toMap()
        AdoptionDetails(
            id = listed.id,
            name = listed.names?.firstOrNull()?.removePrefix("/") ?: listed.id.take(12),
            image = image,
            imageDigest = runCatching { client.inspectImageCmd(containerImageId).exec().repoDigests?.firstOrNull() }.getOrNull(),
            imageId = containerImageId,
            state = inspected.state?.status ?: listed.state ?: "unknown",
            ports = mappings,
            portHostIps = portHostIps,
            networks = inspected.networkSettings?.networks?.keys?.sorted().orEmpty(),
            restartPolicy = inspected.hostConfig?.restartPolicy?.name ?: "unless-stopped",
            volumes = inspected.mounts.orEmpty().mapNotNull { mount ->
                val source = mount.source ?: return@mapNotNull null
                val destination = mount.destination?.path ?: return@mapNotNull null
                VolumeMount(source, destination, mount.mode?.contains("ro") == true, mount.getName())
            },
            environment = environment,
            runtimeOptions = ContainerRuntimeOptions(
                command = inspected.config?.cmd?.toList(),
                entrypoint = inspected.config?.entrypoint?.toList(),
                workingDirectory = inspected.config?.workingDir,
                user = inspected.config?.user
            )
        )
    } catch (e: Exception) {
        logger.warn("Unable to inspect container for adoption", e)
        null
    }

    /** Finds the running deployment currently serving an image when older records lack a container name. */
    fun findContainerByImage(image: String): ContainerInfo? =
        listContainers(all = true).firstOrNull { it.image == image || it.image.substringBefore('@') == image }

    fun findContainerByHostPort(hostPort: Int): ContainerInfo? =
        client.listContainersCmd().withShowAll(true).exec()
            .firstOrNull { container -> container.ports.orEmpty().any { it.publicPort == hostPort } }
            ?.let { getContainer(it.id) }

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

    /** Immutable local identity for recovery when the registry does not expose a repository digest. */
    fun immutableImageReference(imageRef: String): String? = runCatching {
        val image = client.inspectImageCmd(imageRef).exec()
        image.repoDigests?.firstOrNull() ?: image.id?.takeIf { it.startsWith("sha256:") }
    }.getOrNull()

    fun containerImageId(containerNameOrId: String): String? = runCatching {
        client.inspectContainerCmd(containerNameOrId).exec().imageId
    }.getOrNull()

    /** Reuse only the expected immutable image at the exact recorded name; never delete a conflicting container. */
    fun ensureRestoredContainer(request: CreateContainerRequest): ContainerInfo {
        require(request.name.isNotBlank()) { "Restored runtime name must not be blank" }
        val expectedImageId = client.inspectImageCmd(request.image).exec().id
            ?: error("Immutable runtime image is unavailable")
        fun existing(): Container? = client.listContainersCmd().withShowAll(true).exec().firstOrNull { listed ->
            listed.names.orEmpty().any { it.removePrefix("/") == request.name }
        }
        val found = existing()
        if (found != null) {
            val inspected = client.inspectContainerCmd(found.id).exec()
            verifyRestoredContainer(found, inspected, request, expectedImageId)
            if (inspected.state?.running != true) client.startContainerCmd(found.id).exec()
            val attachedNetworks = inspected.networkSettings?.networks?.keys.orEmpty()
            (request.additionalNetworks + request.network).distinct()
                .filter { it !in attachedNetworks && it !in setOf("host", "none") && !it.startsWith("container:") }
                .forEach { network ->
                client.connectToNetworkCmd().withNetworkId(network).withContainerId(found.id).exec()
            }
            return getContainer(found.id) ?: error("Restored runtime could not be inspected")
        }
        return try {
            createContainer(request)
        } catch (createError: Exception) {
            // A competing retry may have created the named container after the first lookup.
            val raced = existing() ?: throw createError
            val inspected = client.inspectContainerCmd(raced.id).exec()
            verifyRestoredContainer(raced, inspected, request, expectedImageId)
            if (inspected.state?.running != true) client.startContainerCmd(raced.id).exec()
            val attachedNetworks = inspected.networkSettings?.networks?.keys.orEmpty()
            (request.additionalNetworks + request.network).distinct()
                .filter { it !in attachedNetworks && it !in setOf("host", "none") && !it.startsWith("container:") }
                .forEach { network ->
                client.connectToNetworkCmd().withNetworkId(network).withContainerId(raced.id).exec()
            }
            getContainer(raced.id) ?: throw createError
        }
    }

    private fun verifyRestoredContainer(
        listed: Container,
        inspected: InspectContainerResponse,
        request: CreateContainerRequest,
        expectedImageId: String
    ) {
        check(inspected.imageId == expectedImageId) {
            "A container with the previous runtime name exists with a different immutable image"
        }
        fun toEnvironmentMap(values: Array<out String>?): Map<String, String> = values.orEmpty().mapNotNull { item ->
            val separator = item.indexOf('=')
            if (separator <= 0) null else item.substring(0, separator) to item.substring(separator + 1)
        }.toMap()
        val expectedEnvironment = toEnvironmentMap(client.inspectImageCmd(request.image).exec().config?.env) + request.env
        val actualEnvironment = toEnvironmentMap(inspected.config?.env)
        check(actualEnvironment == expectedEnvironment) {
            "A container with the previous runtime name has different environment configuration"
        }
        val actualPorts = listed.ports.orEmpty().filter { (it.type ?: "tcp") == "tcp" && it.publicPort != null }
            .associate { it.privatePort to it.publicPort }
        val expectedPorts = request.ports.map { (hostPort, containerPort) -> containerPort to hostPort }.toMap()
        check(actualPorts == expectedPorts) { "A container with the previous runtime name has different published ports" }
        val expectedPortIps = expectedPorts.keys.associateWith { request.portHostIps[it] ?: request.portHostIp }
        check(listed.ports.orEmpty().filter { it.publicPort != null }.all { port ->
            expectedPortIps[port.privatePort]?.let { it == port.ip } ?: true
        }) {
            "A container with the previous runtime name has a different published port address"
        }
        val expectedNetworks = (request.additionalNetworks + request.network).filterNot {
            it in setOf("host", "none") || it.startsWith("container:")
        }.toSet()
        check(inspected.networkSettings?.networks?.keys.orEmpty() == expectedNetworks) {
            "A container with the previous runtime name has different network attachments"
        }
        request.volumes.forEach { expected ->
            check(inspected.mounts.orEmpty().any { mount ->
                mount.destination?.path == expected.containerPath &&
                    (if (expected.volumeName != null) mount.getName() == expected.volumeName else mount.source == expected.hostPath) &&
                    mount.rw == !expected.readOnly
            }) { "A container with the previous runtime name has different volume mounts" }
        }
        request.restartPolicy?.let { expected ->
            check(inspected.hostConfig?.restartPolicy?.name == expected) {
                "A container with the previous runtime name has a different restart policy"
            }
        }
        request.runtimeOptions.command?.let { expected ->
            check(inspected.config?.cmd?.toList() == expected) { "A container with the previous runtime name has a different command" }
        }
        request.runtimeOptions.entrypoint?.let { expected ->
            check(inspected.config?.entrypoint?.toList() == expected) { "A container with the previous runtime name has a different entrypoint" }
        }
        request.runtimeOptions.workingDirectory?.let { expected ->
            check(inspected.config?.workingDir == expected) { "A container with the previous runtime name has a different working directory" }
        }
        request.runtimeOptions.user?.let { expected ->
            check(inspected.config?.user == expected) { "A container with the previous runtime name has a different user" }
        }
    }

    /** TCP ports declared by the image's Docker metadata (Dockerfile EXPOSE). */
    fun imageExposedTcpPorts(imageRef: String): List<Int> = client.inspectImageCmd(imageRef).exec()
        .config?.exposedPorts.orEmpty()
        .asSequence()
        .filter { it.protocol.toString().equals("tcp", ignoreCase = true) }
        .map { it.port }
        .filter { it in 1..65535 }
        .distinct()
        .sorted()
        .toList()

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
                // listNetworks returns summaries; endpoint details are only present
                // on the network inspection response.
                val inspected = net.id?.let { id ->
                    runCatching { client.inspectNetworkCmd().withNetworkId(id).exec() }.getOrNull()
                }
                val networkContainers = inspected?.containers ?: net.containers
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
                    endpoints = networkContainers.orEmpty().map { (id, endpoint) -> NetworkEndpoint(endpoint.name ?: id, id, endpoint.ipv4Address, endpoint.ipv6Address, endpoint.macAddress, endpoint.endpointId) }.sortedBy { it.container }
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

    fun dockerHealthStatus(containerNameOrId: String): String? = try {
        val inspect: InspectContainerResponse = client.inspectContainerCmd(containerNameOrId).exec()
        inspect.state?.health?.status
    } catch (e: Exception) {
        logger.debug("Docker health status unavailable for {}", containerNameOrId, e)
        null
    }

    fun createContainer(request: CreateContainerRequest): ContainerInfo = createContainerTracked(request).info

    internal fun createContainerTracked(request: CreateContainerRequest): CreatedContainer {
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
            val hostIp = request.portHostIps[containerPort] ?: request.portHostIp
            portBindings.bind(exposed, hostIp?.let { Ports.Binding.bindIpAndPort(it, hostPort) } ?: Ports.Binding.bindPort(hostPort))
        }
        request.randomHostPorts.forEach { containerPort ->
            require(containerPort in 1..65535) { "Container port must be between 1 and 65535" }
            val exposed = ExposedPort.tcp(containerPort)
            exposedPorts.add(exposed)
            portBindings.bind(exposed, Ports.Binding.bindIpAndPort("127.0.0.1", 0))
        }

        val envVars = request.env.map { "${it.key}=${it.value}" }

        val hostConfig = HostConfig()
            .withNetworkMode(request.network)

        if (request.ports.isNotEmpty() || request.randomHostPorts.isNotEmpty()) {
            hostConfig.withPortBindings(portBindings)
        }

        if (request.volumes.isNotEmpty()) {
            val binds = request.volumes.map {
                if (it.volumeName != null) {
                    Bind(it.volumeName, Volume(it.containerPath), if (it.readOnly) AccessMode.ro else AccessMode.rw)
                } else {
                    Bind.parse("${it.hostPath}:${it.containerPath}" + if (it.readOnly) ":ro" else "")
                }
            }
            hostConfig.withBinds(*binds.toTypedArray())
        }

        if (request.restartPolicy != null) {
            hostConfig.withRestartPolicy(RestartPolicy.parse(request.restartPolicy))
        }

        val createCommand = client.createContainerCmd(request.image)
            .withName(request.name)
            .withEnv(envVars)
            .withHostConfig(hostConfig)
            .withExposedPorts(exposedPorts)
        request.preset?.takeIf { it == "postgres" || it == "redis" }?.let { preset ->
            createCommand.withLabels(mapOf("com.gatekeeperd.container-preset" to preset))
        }
        request.runtimeOptions.command?.let { createCommand.withCmd(*it.toTypedArray()) }
        request.runtimeOptions.entrypoint?.let { createCommand.withEntrypoint(*it.toTypedArray()) }
        request.runtimeOptions.workingDirectory?.let(createCommand::withWorkingDir)
        request.runtimeOptions.user?.let(createCommand::withUser)
        val created = createContainerWithCleanup(
            create = { createCommand.exec() },
            start = { response ->
                client.startContainerCmd(response.id).exec()
                request.additionalNetworks.distinct().filter { it != request.network }.forEach { network ->
                    client.connectToNetworkCmd().withNetworkId(network).withContainerId(response.id).exec()
                }
            },
            inspect = { response ->
                response to (inspectContainer(response.id)
                    ?: error("Created container ${response.id} could not be inspected"))
            },
            remove = { response -> removeContainerByIdIfPresent(response.id) }
        )
        val (createResponse, info) = created
        logger.info("Created and started container: ${request.name} (id: ${createResponse.id})")
        return CreatedContainer(createResponse.id, info)
    }

    /** Idempotently removes only a container with the exact Docker ID returned by create. */
    internal fun removeContainerByIdIfPresent(containerId: String): Boolean {
        val owned = client.listContainersCmd().withShowAll(true).exec()
            .firstOrNull { it.id == containerId } ?: return false
        client.removeContainerCmd(owned.id).withForce(true).exec()
        logger.info("Removed container created by failed operation (id: {})", owned.id)
        return true
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

    override fun close() {
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
