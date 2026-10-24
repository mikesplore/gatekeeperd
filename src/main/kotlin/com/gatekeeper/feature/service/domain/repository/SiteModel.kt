package com.gatekeeper.feature.service.domain.repository

import com.gatekeeper.db.tables.CertMode
import com.gatekeeper.db.tables.ReconciliationStatus
import com.gatekeeper.db.tables.TlsMode
import com.gatekeeper.db.tables.UpstreamMode
import com.gatekeeper.feature.nginx.model.DEFAULT_GATEKEEPER_BYPASS_PATHS
import com.gatekeeper.nginx.NginxCustomLocation
import java.time.LocalDateTime
import java.util.UUID

data class SiteRecord(
    val id: UUID, val projectId: UUID, val domain: String, val upstreamHost: String,
    val upstreamMode: UpstreamMode, val upstreamExplicitPort: Int?,
    val upstreamContainerPort: Int? = null,
    val tlsMode: TlsMode, val certMode: CertMode, val certExplicitPath: String?, val gateEnabled: Boolean,
    val configVersion: Int, val createdAt: LocalDateTime, val updatedAt: LocalDateTime,
    val bypassPaths: List<String> = DEFAULT_GATEKEEPER_BYPASS_PATHS,
    val reconciliationStatus: ReconciliationStatus, val lastNginxError: String?, val lastDockerError: String?, val lastReconciledAt: LocalDateTime?, val projectSlug: String? = null,
    val serviceId: UUID? = null,
    val upstreamKind: String? = null,
    val customNginxLocations: List<NginxCustomLocation> = emptyList()
)

data class SiteDashboardUpdate(
    val domain: String? = null, val upstreamHost: String? = null, val upstreamMode: UpstreamMode? = null,
    val upstreamExplicitPort: Int? = null,
    val upstreamContainerPort: Int? = null,
    val tlsMode: TlsMode? = null, val certMode: CertMode? = null, val certExplicitPath: String? = null,
    val gateEnabled: Boolean? = null, val bypassPaths: List<String>? = null,
    val customNginxLocations: List<NginxCustomLocation>? = null
)