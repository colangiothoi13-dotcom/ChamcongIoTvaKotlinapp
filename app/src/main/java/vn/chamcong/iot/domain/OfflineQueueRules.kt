package vn.chamcong.iot.domain

fun deduplicatePendingEventIds(ids: List<String>): List<String> = ids
    .filter(String::isNotBlank)
    .distinct()

fun httpResponseAcknowledgesEvent(code: Int): Boolean = code in 200..299 || code == 409
