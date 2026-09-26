package com.gatekeeper.nginx

import java.util.UUID

internal data class SiteProjectIdentityMarker(val present: Boolean, val projectId: UUID?)

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
