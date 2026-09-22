package com.gatekeeper.nginx

import java.nio.file.Path

private val HOSTNAME_LABEL = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")
private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

fun requireValidHostname(value: String, field: String = "domain"): String {
    val hostname = value.trim().trimEnd('.')
    require(hostname.length in 1..253) { "$field must be a valid hostname" }
    require(hostname.split('.').all { HOSTNAME_LABEL.matches(it) }) { "$field must be a valid hostname" }
    return hostname
}

fun requireValidEmail(value: String): String {
    val email = value.trim()
    require(email.length in 3..254 && EMAIL.matches(email)) { "email must be a valid email address" }
    return email
}

fun requireCertificatePath(value: String, certificateRoot: String): String {
    val path = value.trim()
    require(path.isNotBlank() && !path.contains("..")) { "certificate path is invalid" }
    require(Path.of(path).isAbsolute) { "certificate path must be absolute" }
    val root = Path.of(certificateRoot).toAbsolutePath().normalize()
    val candidate = Path.of(path).toAbsolutePath().normalize()
    require(candidate.startsWith(root)) { "certificate path must be inside the configured certificate directory" }
    require(candidate.fileName.toString() in setOf("fullchain.pem", "privkey.pem")) { "certificate path must reference a certificate or private key file" }
    return candidate.toString()
}
