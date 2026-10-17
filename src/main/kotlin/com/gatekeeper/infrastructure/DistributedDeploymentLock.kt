package com.gatekeeper.infrastructure

import com.gatekeeper.core.ports.DeploymentLock
import com.gatekeeper.plugins.DistributedLock

class DistributedDeploymentLock : DeploymentLock {
    override fun <T> withLock(key: String, block: () -> T): T = DistributedLock.withLock(key, block)
    override fun <T> tryWithLock(key: String, block: () -> T): T? = DistributedLock.tryWithLock(key, block)
}
