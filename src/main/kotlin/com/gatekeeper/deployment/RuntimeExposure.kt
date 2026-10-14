package com.gatekeeper.deployment

internal fun adoptedRuntimeHasPublicBinding(triggerSource: String?, portHostIps: Map<Int, String>?): Boolean =
    triggerSource == "container_adoption" && portHostIps.orEmpty().values.any {
        it == "0.0.0.0" || it == "::" || it == "[::]"
    }
