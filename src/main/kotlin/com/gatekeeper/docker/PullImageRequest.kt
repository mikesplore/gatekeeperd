package com.gatekeeper.docker

import kotlinx.serialization.Serializable

@Serializable
data class PullImageRequest(
    val image: String,
    val tag: String = "latest"
)
