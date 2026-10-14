package com.gatekeeper.nginx

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Files and names for stable per-service nginx upstreams. */
internal class DeploymentUpstreamIncludes(
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

internal fun probeViaLocalNginx(domain: String, tls: Boolean): Boolean = runCatching {
    requireValidHostname(domain)
    val port = if (tls) 443 else 80
    val raw = java.net.Socket()
    raw.connect(InetSocketAddress("127.0.0.1", port), 2_000)
    raw.soTimeout = 3_000
    val socket = if (tls) {
        val ssl = ((SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, domain, port, true) as SSLSocket)
        ssl.sslParameters = ssl.sslParameters.apply { serverNames = listOf(SNIHostName(domain)); endpointIdentificationAlgorithm = "HTTPS" }
        ssl.startHandshake()
        ssl
    } else raw
    socket.use {
        it.getOutputStream().write("GET /.well-known/gatekeeperd-cutover-probe HTTP/1.1\r\nHost: $domain\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
        val reader = it.getInputStream().bufferedReader(Charsets.US_ASCII)
        val statusLine = reader.readLine() ?: return@runCatching false
        val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: return@runCatching false
        var hasProbeMarker = false
        while (true) {
            val header = reader.readLine() ?: return@runCatching false
            if (header.isEmpty()) break
            if (header.equals("X-Gatekeeperd-Cutover-Probe: stable", ignoreCase = true)) hasProbeMarker = true
        }
        status in 200..499 && hasProbeMarker
    }
}.getOrDefault(false)

internal fun probeCandidateLoopback(port: Int, domain: String, path: String = "/"): Boolean = runCatching {
    require(port in 1..65535)
    requireValidHostname(domain)
    val safePath = path.takeIf { it.startsWith("/") && !it.contains('\r') && !it.contains('\n') } ?: "/"
    Socket().use { socket ->
        socket.connect(InetSocketAddress("127.0.0.1", port), 2_000)
        socket.soTimeout = 3_000
        socket.getOutputStream().write("GET $safePath HTTP/1.1\r\nHost: $domain\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
        val statusLine = socket.getInputStream().bufferedReader(Charsets.US_ASCII).readLine() ?: return@runCatching false
        val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: return@runCatching false
        status in 200..499
    }
}.getOrDefault(false)
