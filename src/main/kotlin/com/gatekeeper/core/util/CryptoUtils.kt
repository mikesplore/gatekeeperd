package com.gatekeeper.core.util

import java.security.MessageDigest

fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }