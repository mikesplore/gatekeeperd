package com.gatekeeper.db.tables

/** Structured reason for project or service access blocks. */
enum class AccessBlockReason(val value: String) {
    PAYMENT("payment"),
    MANUAL_HOLD("manual_hold"),
    ABUSE_TOS("abuse_tos"),
    SUSPENDED_BY_REQUEST("suspended_by_request"),
    OTHER("other");

    companion object {
        fun fromLegacy(value: String?, blockedStatus: String): AccessBlockReason? = when (
            value?.trim()?.lowercase()?.replace('-', '_')
        ) {
            "overdue", "payment_overdue", "payment_reversed", "payment" -> PAYMENT
            "manual", "manual_hold", "manual_block" -> MANUAL_HOLD
            "abuse", "tos", "abuse_tos" -> ABUSE_TOS
            "suspended_by_request", "customer_request" -> SUSPENDED_BY_REQUEST
            "other" -> OTHER
            else -> if (blockedStatus == "blocked") PAYMENT else if (blockedStatus == "manual_block") MANUAL_HOLD else null
        }

        fun legacyNote(value: String?): String? {
            if (value.isNullOrBlank() || fromLegacy(value, "") != null || value == "manual") return null
            return value
        }
    }
}
