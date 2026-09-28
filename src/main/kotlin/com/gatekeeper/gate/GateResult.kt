package com.gatekeeper.gate

sealed class GateResult {
    object Active : GateResult()
    data class Blocked(
        val type: String,
        val paymentLink: String?,
        val projectName: String?,
        val paywall: PaywallInfo?,
        val blockReason: String? = null
    ) : GateResult()
    data class Unknown(val reason: String) : GateResult()
}
