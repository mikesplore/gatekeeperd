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
