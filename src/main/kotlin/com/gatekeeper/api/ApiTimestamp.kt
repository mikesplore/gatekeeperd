package com.gatekeeper.api

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Formats legacy local database timestamps with an explicit East Africa offset. */
object ApiTimestamp {
    private val zone = ZoneId.of("Africa/Nairobi")

    fun format(value: LocalDateTime): String =
        value.atZone(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}
