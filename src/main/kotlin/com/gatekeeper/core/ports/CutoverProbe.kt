package com.gatekeeper.core.ports

interface CutoverProbe {
    fun throughLocalNginx(domain: String, tls: Boolean): Boolean
    fun candidate(port: Int, domain: String, path: String = "/"): Boolean
}
