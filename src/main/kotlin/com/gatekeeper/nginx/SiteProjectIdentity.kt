package com.gatekeeper.nginx

import java.util.UUID

internal data class SiteProjectIdentityMarker(val present: Boolean, val projectId: UUID?)
internal data class SiteIdentityMarker(val present: Boolean, val siteId: UUID?, val projectId: UUID?)

internal fun parseSiteIdentityMarker(content: String): SiteIdentityMarker {
    val siteLines = content.lineSequence().filter { it.startsWith("# gatekeeperd:site_id:") }.toList()
    val projectMarker = parseSiteProjectIdentityMarker(content)
    if (siteLines.isEmpty()) return SiteIdentityMarker(projectMarker.present, null, projectMarker.projectId)
    if (siteLines.size != 1) return SiteIdentityMarker(true, null, projectMarker.projectId)
    val siteId = runCatching { UUID.fromString(siteLines.single().removePrefix("# gatekeeperd:site_id:").trim()) }.getOrNull()
    return SiteIdentityMarker(true, siteId, projectMarker.projectId)
}

/** Reads the optional stable ownership marker used by newly rendered nginx site files. */
internal fun parseSiteProjectIdentityMarker(content: String): SiteProjectIdentityMarker {
    val markerLines = content.lineSequence()
        .filter { it.startsWith("# gatekeeperd:project_id:") }
        .toList()
    if (markerLines.isEmpty()) return SiteProjectIdentityMarker(false, null)
    if (markerLines.size != 1) return SiteProjectIdentityMarker(true, null)
    val raw = markerLines.single().removePrefix("# gatekeeperd:project_id:").trim()
    return SiteProjectIdentityMarker(true, runCatching { UUID.fromString(raw) }.getOrNull())
}
