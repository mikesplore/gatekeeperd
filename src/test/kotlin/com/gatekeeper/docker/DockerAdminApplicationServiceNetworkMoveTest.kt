package com.gatekeeper.docker

import com.gatekeeper.config.AppSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DockerAdminApplicationServiceNetworkMoveTest {
    @Test
    fun `container wizard context returns every Docker network and internal network status`() {
        val service = DockerAdminApplicationService(FakeMoveDocker(), dockerSettings)

        val result = service.containerWizardContext()

        assertEquals("gk-internal", result.internalNetwork)
        assertEquals(true, result.internalNetworkExists)
        assertEquals(listOf("app", "bridge", "gk-internal"), result.networks)
    }

    @Test
    fun `moves container by connecting target before disconnecting source`() {
        val docker = FakeMoveDocker()
        val service = DockerAdminApplicationService(docker, dockerSettings)

        val result = service.moveContainerNetwork(
            "database",
            MoveContainerNetworkRequest(targetNetwork = "app", disconnectFromNetwork = "bridge")
        )

        assertEquals(listOf("connect:app", "disconnect:bridge"), docker.operations)
        assertEquals(listOf("app"), result.networks)
    }

    @Test
    fun `does not touch container when requested source is not attached`() {
        val docker = FakeMoveDocker()
        val service = DockerAdminApplicationService(docker, dockerSettings)

        assertFailsWith<com.gatekeeper.api.HttpApiException> {
            service.moveContainerNetwork(
                "database",
                MoveContainerNetworkRequest(targetNetwork = "app", disconnectFromNetwork = "missing")
            )
        }

        assertEquals(emptyList(), docker.operations)
    }

    private val dockerSettings = AppSettings.DockerSettings(
        socket = "unix:///var/run/docker.sock",
        internalNetwork = "gk-internal",
        pullViaCli = false,
        allowedRegistries = emptyList(),
        allowedVolumeRoots = emptyList()
    )

    private class FakeMoveDocker : DockerGateway {
        private val attached = mutableListOf("bridge")
        val operations = mutableListOf<String>()

        override fun listNetworks() = listOf(
            NetworkInfo("bridge-id", "bridge", "bridge", "local"),
            NetworkInfo("app-id", "app", "bridge", "local"),
            NetworkInfo("internal-id", "gk-internal", "bridge", "local")
        )

        override fun getContainer(containerNameOrId: String) = ContainerInfo(
            id = "container-id",
            name = "database",
            image = "postgres:16",
            status = "Up",
            state = "running",
            ports = "",
            created = 1,
            networks = attached.toList()
        )

        override fun connectContainerToNetwork(containerNameOrId: String, networkName: String) {
            operations += "connect:$networkName"
            attached += networkName
        }

        override fun disconnectContainerFromNetwork(containerNameOrId: String, networkName: String) {
            operations += "disconnect:$networkName"
            attached -= networkName
        }

        override fun containerHealth(containerNameOrId: String) = "running"
        override fun dockerHealthStatus(containerNameOrId: String) = null
        override fun close() = Unit
    }
}
