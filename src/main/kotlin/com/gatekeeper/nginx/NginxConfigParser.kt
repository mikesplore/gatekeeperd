package com.gatekeeper.nginx

/** Pure parser for Gatekeeper block markers, including legacy generated files. */
object NginxConfigParser {
    fun parse(content: String): List<NginxConfigBlock> = parseBlocks(content)

    fun parseBlocks(content: String): List<NginxConfigBlock> {
        val marker = Regex("(?m)^\\s*# gatekeeperd:block:([a-z_]+)\\s*$")
        val markers = marker.findAll(content).toList()
        if (markers.isEmpty()) return emptyList()
        val serverIndex = markers.indexOfFirst { it.groupValues[1] == "server" }
        val hasBypassMarker = markers.any { it.groupValues[1] == "bypass" }
        val legacyBypassStart = if (serverIndex >= 0 && !hasBypassMarker) {
            Regex("(?m)^\\s*# 1\\. Gatekeeper Direct Bypass Route\\s*$")
                .find(content)?.range?.first?.takeIf { it > markers[serverIndex].range.first }
        } else null

        return buildList {
            markers.forEachIndexed { index, match ->
                val name = match.groupValues[1]
                val start = match.range.first
                val nextMarkerStart = markers.getOrNull(index + 1)?.range?.first ?: content.length
                val splitBeforeBypass = name == "server" && legacyBypassStart != null && legacyBypassStart < nextMarkerStart
                if (splitBeforeBypass) {
                    add(NginxConfigBlock(name, "# gatekeeperd:block:$name", content.substring(start, legacyBypassStart).trimEnd()))
                    add(NginxConfigBlock("bypass", "# gatekeeperd:block:bypass", content.substring(legacyBypassStart, nextMarkerStart).trimEnd()))
                } else if (name != "server" || legacyBypassStart == null || start >= legacyBypassStart) {
                    add(NginxConfigBlock(name, "# gatekeeperd:block:$name", content.substring(start, nextMarkerStart).trimEnd()))
                }
            }
        }
    }
}
