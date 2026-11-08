package com.gatekeeper.docker

internal fun isPublicPortBinding(hostIp: String): Boolean =
    hostIp == "0.0.0.0" || hostIp == "::" || hostIp == "[::]"

internal fun resolvePortBindingHostIp(
    request: CreateContainerRequest,
    containerPort: Int,
    allowPublicBindings: Boolean
): String {
    val hostIp = request.portHostIps[containerPort] ?: request.portHostIp ?: "127.0.0.1"
    require(allowPublicBindings || !isPublicPortBinding(hostIp)) {
        "Public container port bindings are disabled by server policy"
    }
    return hostIp
}
