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
    val name: String
)

sealed class GateResult {
    data object Active : GateResult()
    data class Blocked(val type: String, val paymentLink: String? = null, val projectName: String? = null) : GateResult()
    data class Unknown(val message: String) : GateResult()
}