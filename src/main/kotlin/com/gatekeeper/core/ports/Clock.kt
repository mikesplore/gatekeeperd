package com.gatekeeper.core.ports

import java.time.Instant

fun interface Clock {
    fun now(): Instant
}
