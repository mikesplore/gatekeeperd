package com.gatekeeper.db.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentDateTime
import org.jetbrains.exposed.sql.javatime.datetime

/** Desired nginx render configuration for a project. Deployed files remain filesystem state. */
object Sites : Table("sites") {
    val id = uuid("id").autoGenerate()
    val projectId = reference("project_id", Projects.id).uniqueIndex()
    val certificateId = reference("certificate_id", Certificates.id).nullable().index()
    val domain = text("domain")
    val upstreamHost = text("upstream_host").default("127.0.0.1")
    val upstreamMode = enumerationByName("upstream_mode", 18, UpstreamMode::class)
    val upstreamContainerName = text("upstream_container_name").nullable()
    val upstreamExplicitPort = integer("upstream_explicit_port").nullable()
    val tlsMode = enumerationByName("tls_mode", 12, TlsMode::class)
    val certMode = enumerationByName("cert_mode", 13, CertMode::class)
    val certExplicitPath = text("cert_explicit_path").nullable()
    val gateEnabled = bool("gate_enabled").default(true)
    val configVersion = integer("config_version").default(1)
    val reconciliationStatus = enumerationByName("reconciliation_status", 12, ReconciliationStatus::class).default(ReconciliationStatus.HEALTHY)
    val lastNginxError = text("last_nginx_error").nullable()
    val lastDockerError = text("last_docker_error").nullable()
    val lastReconciledAt = datetime("last_reconciled_at").nullable()
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(id)
}

enum class UpstreamMode(val value: String) {
    DOCKER_DISCOVERY("docker_discovery"),
    EXPLICIT_PORT("explicit_port")
}

enum class TlsMode(val value: String) {
    HTTP_ONLY("http_only"),
    HTTPS("https"),
    HTTPS_HTTP2("https_http2")
}

enum class CertMode(val value: String) {
    AUTO_RESOLVE("auto_resolve"),
    EXPLICIT_PATH("explicit_path")
}

enum class ReconciliationStatus(val value: String) {
    HEALTHY("healthy"),
    DRIFTED("drifted"),
    DOCKER_DOWN("docker_down"),
    DEAD_CONFIG("dead_config"),
    DISABLED("disabled"),
    ERROR("error")
}
