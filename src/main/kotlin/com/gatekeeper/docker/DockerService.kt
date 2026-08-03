package com.gatekeeper.docker

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.command.CreateContainerResponse
import com.github.dockerjava.api.command.InspectContainerResponse
import com.github.dockerjava.api.command.RemoveContainerCmd
import com.github.dockerjava.api.command.RemoveImageCmd
import com.github.dockerjava.api.model.Bind
import com.github.dockerjava.api.model.Container
import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.HostConfig
import com.github.dockerjava.api.model.Ports
import com.github.dockerjava.api.model.RestartPolicy
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientBuilder
import com.github.dockerjava.core.DockerClientConfig
import org.slf4j.LoggerFactory
import java.util.stream.Collectors

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
            }?.let { toContainerInfo(it) }
        } catch (e: Exception) {
            logger.error("Error getting container $containerNameOrId", e)
            null
        }
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

    fun listNetworks(): List<NetworkInfo> {
        return client.listNetworksCmd()
            .exec()
            .map { net ->
                NetworkInfo(
                    id = net.id ?: "",
                    name = net.name ?: "",
                    driver = net.driver ?: "",
                    scope = net.scope ?: ""
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
        val portBindings = Ports()
        val exposedPorts = mutableListOf<ExposedPort>()
        
        request.ports.forEach { (hostPort, containerPort) ->
            val exposed = ExposedPort.tcp(containerPort)
            exposedPorts.add(exposed)
            portBindings.bind(exposed, Ports.Binding.bindPort(hostPort))
        }
        
        val envVars = request.env.map { "${it.key}=${it.value}" }
        
        val hostConfig = HostConfig()
            .withPortBindings(portBindings)
            .withNetworkMode(request.network)
        
        if (request.volumes.isNotEmpty()) {
            val binds = request.volumes.map { 
                Bind.parse("${it.hostPath}:${it.containerPath}" + 
                if (it.readOnly) ":ro" else "")
            }
            hostConfig.withBinds(*binds.toTypedArray())
        }
        
        if (request.restartPolicy != null) {
            hostConfig.withRestartPolicy(RestartPolicy.parse(request.restartPolicy))
        }
        
        val createResponse: CreateContainerResponse = client.createContainerCmd(request.image)
            .withName(request.name)
            .withEnv(envVars)
            .withExposedPorts(exposedPorts)
            .withHostConfig(hostConfig)
            .exec()
        
        client.startContainerCmd(createResponse.id).exec()
        logger.info("Created and started container: ${request.name} (id: ${createResponse.id})")
        
        return getContainer(createResponse.id)!!
    }

    fun deleteImage(image: String, tag: String = "latest", force: Boolean = true) {
        val fullName = if (tag.isNotEmpty()) "$image:$tag" else image
        logger.info("Deleting image: $fullName (force=$force)")
        val removeCmd: RemoveImageCmd = client.removeImageCmd(fullName)
            .withForce(force)
        removeCmd.exec()
        logger.info("Image deleted: $fullName")
    }

    fun deleteContainer(containerNameOrId: String, force: Boolean = true) {
        val removeCmd: RemoveContainerCmd = client.removeContainerCmd(containerNameOrId)
            .withForce(force)
        removeCmd.exec()
        logger.info("Deleted container: $containerNameOrId")
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
        val ports = container.ports?.joinToString(", ") { port ->
            "${port.privatePort}->${port.publicPort}/${port.type}"
        } ?: ""

        return ContainerInfo(
            id = container.id.substring(0, 12),
            name = names.firstOrNull() ?: container.id.substring(0, 12),
            image = container.image ?: "unknown",
            status = container.status ?: "unknown",
            state = container.state ?: "unknown",
            ports = ports,
            created = container.created ?: 0L
        )
    }
}