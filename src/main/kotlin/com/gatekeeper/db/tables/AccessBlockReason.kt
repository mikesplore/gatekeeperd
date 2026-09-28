package com.gatekeeper.db.tables

/** Structured reason for project or service access blocks. */
enum class AccessBlockReason(val value: String) {
    PAYMENT("payment"),
    MANUAL_HOLD("manual_hold"),
    ABUSE_TOS("abuse_tos"),
    SUSPENDED_BY_REQUEST("suspended_by_request"),
    OTHER("other");
}
