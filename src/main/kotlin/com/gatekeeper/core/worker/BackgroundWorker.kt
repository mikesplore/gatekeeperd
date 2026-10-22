package com.gatekeeper.core.worker

interface BackgroundWorker {
    val name: String
    val intervalMillis: Long

    suspend fun tick()
}