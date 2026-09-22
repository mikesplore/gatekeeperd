package com.gatekeeper.plugins

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** PostgreSQL advisory lock with a process-local fallback for isolated tests. */
object DistributedLock {
    private val localLocks = ConcurrentHashMap<String, ReentrantLock>()

    fun <T> withLock(key: String, block: () -> T): T {
        if (!DatabaseFactory.isInitialized()) return localLocks.computeIfAbsent(key) { ReentrantLock() }.withLock(block)
        return DatabaseFactory.withAdvisoryLock(key, block)
    }

    internal fun lockId(key: String): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(StandardCharsets.UTF_8))
        return java.nio.ByteBuffer.wrap(digest, 0, Long.SIZE_BYTES).long
    }
}
