package com.gatekeeper.docker

import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class FakeDocker(
    private val images: Set<String> = emptySet(),
    private val containers: Set<String> = emptySet(),
    private val networks: Set<String> = setOf("bridge"),
    private val inUsePorts: Set<Int> = emptySet()
) : DockerWizardInspect {
    override fun imageExists(imageRef: String): Boolean = imageRef in images
    override fun containerExists(name: String): Boolean = name in containers
    override fun listNetworkNames(): Set<String> = networks
    override fun hostPortsInUse(): Set<Int> = inUsePorts
}

class ContainerWizardTest {

    private val internalNetwork = "gatekeeper-internal"

    @Test
    fun `invalid container name returns invalid_request`() {
        val result = computeContainerCreatePlan(
            request = CreateContainerRequest(
                name = "INVALID NAME",
                image = "nginx:latest"
            ),
            internalNetwork = internalNetwork,
            docker = FakeDocker(images = setOf("nginx:latest"))
        )

        val err = result as ContainerCreatePlanResult.Err
        assertEquals(HttpStatusCode.BadRequest, err.status)
        assertEquals("invalid_request", err.code)
    }

    @Test
    fun `existing container returns container_exists`() {
        val result = computeContainerCreatePlan(
            request = CreateContainerRequest(
                name = "my-app",
                image = "nginx:latest"
            ),
            internalNetwork = internalNetwork,
            docker = FakeDocker(
                images = setOf("nginx:latest"),
                containers = setOf("my-app")
            )
        )

        val err = result as ContainerCreatePlanResult.Err
        assertEquals(HttpStatusCode.Conflict, err.status)
        assertEquals("container_exists", err.code)
    }

    @Test
    fun `missing image with pullImage false returns image_not_found`() {
        val result = computeContainerCreatePlan(
            request = CreateContainerRequest(
                name = "my-app",
                image = "nginx:latest",
                pullImage = false
            ),
            internalNetwork = internalNetwork,
            docker = FakeDocker()
        )

        val err = result as ContainerCreatePlanResult.Err
        assertEquals(HttpStatusCode.NotFound, err.status)
        assertEquals("image_not_found", err.code)
    }

    @Test
    fun `non-internal missing network returns network_not_found with networks list`() {
        val result = computeContainerCreatePlan(
            request = CreateContainerRequest(
                name = "my-app",
                image = "nginx:latest",
                network = "does-not-exist"
            ),
            internalNetwork = internalNetwork,
            docker = FakeDocker(images = setOf("nginx:latest"), networks = setOf("bridge", internalNetwork))
        )

        val err = result as ContainerCreatePlanResult.Err
        assertEquals(HttpStatusCode.BadRequest, err.status)
        assertEquals("network_not_found", err.code)
        assertNotNull(err.data)
    }

    @Test
    fun `port conflict returns port_conflict`() {
        val result = computeContainerCreatePlan(
            request = CreateContainerRequest(
                name = "my-app",
                image = "nginx:latest",
                ports = mapOf(8080 to 80)
            ),
            internalNetwork = internalNetwork,
            docker = FakeDocker(
                images = setOf("nginx:latest"),
                networks = setOf("bridge", internalNetwork),
                inUsePorts = setOf(8080)
            )
        )

        val err = result as ContainerCreatePlanResult.Err
        assertEquals(HttpStatusCode.Conflict, err.status)
        assertEquals("port_conflict", err.code)
        assertNotNull(err.data)
    }

    @Test
    fun `internal network missing still validates and indicates will create`() {
        val result = computeContainerCreatePlan(
            request = CreateContainerRequest(
                name = "my-app",
                image = "nginx:latest",
                network = internalNetwork
            ),
            internalNetwork = internalNetwork,
            docker = FakeDocker(images = setOf("nginx:latest"), networks = setOf("bridge"))
        )

        val ok = result as ContainerCreatePlanResult.Ok
        assertTrue(ok.plan.willCreateInternalNetworkIfMissing)
        assertEquals(internalNetwork, ok.plan.normalizedRequest.network)
    }

    @Test
    fun `missing image with pullImage true validates and indicates will pull`() {
        val result = computeContainerCreatePlan(
            request = CreateContainerRequest(
                name = "my-app",
                image = "nginx:latest",
                pullImage = true
            ),
            internalNetwork = internalNetwork,
            docker = FakeDocker(networks = setOf("bridge", internalNetwork))
        )

        val ok = result as ContainerCreatePlanResult.Ok
        assertTrue(ok.plan.willPullImage)
        assertEquals("nginx:latest", ok.plan.normalizedRequest.image)
    }
}

