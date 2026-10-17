package com.gatekeeper.docker

import java.io.Closeable

interface DockerGateway : Closeable {
    fun containerHealth(containerNameOrId: String): String
    fun dockerHealthStatus(containerNameOrId: String): String?
}
