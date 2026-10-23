package com.gatekeeper.testing

import com.gatekeeper.core.ports.Clock
import com.gatekeeper.core.ports.CutoverProbe
import com.gatekeeper.core.ports.FileStore
import com.gatekeeper.core.ports.PortProbe
import com.gatekeeper.core.ports.ProcessResult
import com.gatekeeper.core.ports.ProcessRunner
import com.gatekeeper.docker.ContainerInfo
import com.gatekeeper.docker.DockerGateway
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Instant

class FakeDockerGateway(
    private val containers: List<ContainerInfo> = emptyList(),
    override val available: Boolean = true
) : DockerGateway {
    var closed = false
        private set

    override fun listContainers(all: Boolean) = containers
    override fun containerHealth(containerNameOrId: String) = "running"
    override fun dockerHealthStatus(containerNameOrId: String) = null
    override fun close() {
        closed = true
    }
}

class FakeProcessRunner(
    var result: ProcessResult = ProcessResult(0, "", "")
) : ProcessRunner {
    data class Call(
        val command: List<String>,
        val workingDirectory: Path?,
        val environment: Map<String, String>,
        val stdin: String?
    )

    val calls = mutableListOf<Call>()

    override fun run(
        command: List<String>,
        workingDirectory: Path?,
        environment: Map<String, String>,
        stdin: String?
    ): ProcessResult {
        calls += Call(command, workingDirectory, environment, stdin)
        return result
    }
}

class FakePortProbe(var reachable: Boolean = true) : PortProbe {
    data class Call(val host: String, val port: Int, val timeoutMillis: Int)

    val calls = mutableListOf<Call>()

    override fun isReachable(host: String, port: Int, timeoutMillis: Int): Boolean {
        calls += Call(host, port, timeoutMillis)
        return reachable
    }
}

class FakeCutoverProbe(
    var throughNginxResult: Boolean = true,
    var candidateResult: Boolean = true
) : CutoverProbe {
    val nginxDomains = mutableListOf<Pair<String, Boolean>>()
    val candidates = mutableListOf<Triple<Int, String, String>>()

    override fun throughLocalNginx(domain: String, tls: Boolean): Boolean {
        nginxDomains += domain to tls
        return throughNginxResult
    }

    override fun candidate(port: Int, domain: String, path: String): Boolean {
        candidates += Triple(port, domain, path)
        return candidateResult
}
}

class FakeClock(var instant: Instant = Instant.EPOCH) : Clock {
    override fun now(): Instant = instant

    fun advanceMillis(millis: Long) {
        instant = instant.plusMillis(millis)
    }
}

class FakeFileStore : FileStore {
    private val files = mutableMapOf<Path, String>()

    override fun exists(path: Path): Boolean = path in files
    override fun read(path: Path): String = files[path] ?: throw NoSuchFileException(path.toString())
    override fun write(path: Path, content: String) {
        files[path] = content
    }
    override fun delete(path: Path) {
        files.remove(path)
    }
}
