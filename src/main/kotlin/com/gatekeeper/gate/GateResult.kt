package com.gatekeeper.gate

sealed class GateResult {
    object Active : GateResult()
    data class Blocked(
        val type: String,
        val paymentLink: String?,
        val projectName: String?,
        val paywall: PaywallInfo?
    ) : GateResult()
    data class Unknown(val reason: String) : GateResult()
}
