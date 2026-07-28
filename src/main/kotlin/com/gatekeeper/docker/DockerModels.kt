package com.gatekeeper.docker

import kotlinx.serialization.Serializable

@Serializable
data class ContainerInfo(
    val id: String,
    val name: String,
    val image: String,
    val status: String,
    val state: String,
    val ports: String,
    val created: Long
)

@Serializable
data class NetworkInfo(
    val id: String,
    val name: String,
    val driver: String,
    val scope: String
)

sealed class GateResult {
    object Active : GateResult()
    data class Blocked(val type: String, val paymentLink: String?, val projectName: String?) : GateResult()
    data class Unknown(val reason: String) : GateResult()
}
