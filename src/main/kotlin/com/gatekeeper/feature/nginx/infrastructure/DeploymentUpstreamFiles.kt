package com.gatekeeper.feature.nginx.infrastructure

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Files and names for stable per-service nginx upstreams. */
class DeploymentUpstreamIncludes(
    private val directory: File
) {
    fun upstreamName(serviceId: UUID): String = "gk_${serviceId.toString().replace("-", "")}"

    fun file(serviceId: UUID): File = File(directory, "${serviceId}.conf")

    fun render(serviceId: UUID, port: Int): String {
        require(port in 1..65535)
        return "upstream ${upstreamName(serviceId)} {\n    server 127.0.0.1:$port;\n}\n"
    }

    fun write(serviceId: UUID, content: String) {
        directory.mkdirs()
        check(directory.isDirectory && directory.canWrite()) { "Nginx upstream include directory is not writable: ${directory.absolutePath}" }
        val destination = file(serviceId)
        val staged = File(directory, ".${destination.name}.${UUID.randomUUID()}.tmp")
        try {
            Files.writeString(staged.toPath(), content)
            try {
                Files.move(staged.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(staged.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(staged.toPath())
        }
    }

    fun checksum(content: String): String = MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}