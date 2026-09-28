package com.gatekeeper.feature.payment.domain.model

object MpesaPhoneNumber {
    private val kenyanMsisdn = Regex("254[17]\\d{8}")

    /** Normalize common Kenyan mobile formats to Daraja's 254XXXXXXXXX format. */
    fun normalize(input: String): String? {
        val compact = input.trim().replace(Regex("[\\s()-]"), "")
        val international = when {
            compact.startsWith("+254") -> compact.drop(1)
            compact.startsWith("0") && compact.length == 10 -> "254" + compact.drop(1)
            else -> compact
        }
        return international.takeIf(kenyanMsisdn::matches)
    }
}
