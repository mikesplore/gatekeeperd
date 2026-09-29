package com.gatekeeper.admin

import com.gatekeeper.api.respondErrorWithData
import com.gatekeeper.nginx.NginxConfigClassification
import com.gatekeeper.nginx.NginxDomainConflict
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal suspend fun ApplicationCall.respondNginxDomainConflicts(conflicts: List<NginxDomainConflict>) {
    if (conflicts.isEmpty()) return
    val selfConflict = conflicts.firstOrNull { it.classification == NginxConfigClassification.SELF }
    val manualConflict = conflicts.firstOrNull { it.classification == NginxConfigClassification.MANUAL }
    val staleManagedConflict = conflicts.firstOrNull {
        it.classification == NginxConfigClassification.GATEKEEPER_MANAGED && !it.linkedToRequestingSite
    }
    val managedConflict = conflicts.firstOrNull { it.classification == NginxConfigClassification.GATEKEEPER_MANAGED }

    val (code, message) = when {
        selfConflict != null -> "nginx_self_domain_conflict" to
            "The requested domain matches Gatekeeperd's protected nginx domain and cannot be used for a client site."
        manualConflict != null -> "nginx_manual_config_conflict" to
            "Existing manual nginx config '${manualConflict.filename}' already claims ${manualConflict.matchingDomains.joinToString()} on listen port(s) ${manualConflict.matchingPorts.joinToString()}; resolve it before creating this site."
        staleManagedConflict != null -> "nginx_stale_managed_config_conflict" to
            "Gatekeeper-managed nginx config '${staleManagedConflict.filename}' claims ${staleManagedConflict.matchingDomains.joinToString()} on listen port(s) ${staleManagedConflict.matchingPorts.joinToString()}, but is not linked to this site. Reconcile or remove it before creating the site."
        managedConflict != null -> "nginx_managed_config_conflict" to
            "Gatekeeper-managed nginx config '${managedConflict.filename}' already claims ${managedConflict.matchingDomains.joinToString()} on listen port(s) ${managedConflict.matchingPorts.joinToString()}; update or resolve that config before creating another."
        else -> "nginx_domain_conflict" to "An existing nginx config conflicts with this site's domain and listen port."
    }

    val data = buildJsonObject {
        putJsonArray("conflicts") {
            conflicts.forEach { conflict ->
                add(buildJsonObject {
                    put("filename", conflict.filename?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("classification", conflict.classification.name.lowercase())
                    put("domains", kotlinx.serialization.json.buildJsonArray {
                        conflict.domains.forEach { add(JsonPrimitive(it)) }
                    })
                    put("listenPorts", kotlinx.serialization.json.buildJsonArray {
                        conflict.listenPorts.forEach { add(JsonPrimitive(it)) }
                    })
                    put("matchingDomains", kotlinx.serialization.json.buildJsonArray {
                        conflict.matchingDomains.forEach { add(JsonPrimitive(it)) }
                    })
                    put("matchingPorts", kotlinx.serialization.json.buildJsonArray {
                        conflict.matchingPorts.forEach { add(JsonPrimitive(it)) }
                    })
                    put("linkedToRequestingSite", conflict.linkedToRequestingSite)
                })
            }
        }
    }
    respondErrorWithData(HttpStatusCode.Conflict, code, message, data)
}
