package com.gatekeeper.deployment

/** Pure allocation rules shared by the persistent port allocator and focused unit tests. */
internal object DeploymentHostPortPool {
    fun candidateSlot(activeSlot: Int?): Int = if (activeSlot == 0) 1 else 0

    fun firstAvailable(range: IntRange, reserved: Set<Int>, inUse: Set<Int>): Int? =
        range.firstOrNull { it !in reserved && it !in inUse }
}
