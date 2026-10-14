package com.gatekeeper.plugins

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** PostgreSQL advisory lock with a process-local fallback for isolated tests. */
object DistributedLock {
    private val localLocks = ConcurrentHashMap<String, ReentrantLock>()
    private val heldKeys = ThreadLocal.withInitial { mutableSetOf<String>() }

    fun <T> withLock(key: String, block: () -> T): T {
        if (key in heldKeys.get()) return block()
        fun guarded(): T {
            heldKeys.get().add(key)
            try { return block() } finally { heldKeys.get().remove(key) }
        }
        if (!DatabaseFactory.isInitialized()) return localLocks.computeIfAbsent(key) { ReentrantLock() }.withLock(::guarded)
        return DatabaseFactory.withAdvisoryLock(key, ::guarded)
    }

    /** Returns null when another worker currently holds the key instead of waiting. */
    fun <T> tryWithLock(key: String, block: () -> T): T? {
        if (key in heldKeys.get()) return block()
        fun guarded(): T {
            heldKeys.get().add(key)
            try { return block() } finally { heldKeys.get().remove(key) }
        }
        if (!DatabaseFactory.isInitialized()) {
            val lock = localLocks.computeIfAbsent(key) { ReentrantLock() }
            if (!lock.tryLock()) return null
            return try { guarded() } finally { lock.unlock() }
        }
        return DatabaseFactory.tryWithAdvisoryLock(key, ::guarded)
    }

    internal fun lockId(key: String): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(StandardCharsets.UTF_8))
        return java.nio.ByteBuffer.wrap(digest, 0, Long.SIZE_BYTES).long
    }
}
