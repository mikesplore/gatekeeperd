package com.gatekeeper.plugins

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object Metrics {
    private val counters = ConcurrentHashMap<String, AtomicLong>()

    fun increment(name: String) {
        counters.computeIfAbsent(name) { AtomicLong() }.incrementAndGet()
    }

    fun snapshot(): Map<String, Long> = counters.entries
        .associate { it.key to it.value.get() }
        .toSortedMap()
}
