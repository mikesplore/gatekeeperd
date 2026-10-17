package com.gatekeeper.core.ports

interface DeploymentLock {
    fun <T> withLock(key: String, block: () -> T): T
    fun <T> tryWithLock(key: String, block: () -> T): T?
}
