package vn.chamcong.iot.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private fun Throwable.hasIndexMessage(predicate: (String) -> Boolean): Boolean {
    val visited = mutableSetOf<Throwable>()
    var current: Throwable? = this
    while (current != null && visited.add(current)) {
        if (predicate(current.message.orEmpty())) return true
        current = current.cause
    }
    return false
}

internal fun Throwable.isFirestoreIndexRequired(): Boolean = hasIndexMessage {
    it.contains("query requires an index", ignoreCase = true)
}

internal fun Throwable.isFirestoreIndexBuilding(): Boolean = hasIndexMessage {
    it.contains("query requires an index", ignoreCase = true) &&
        it.contains("index is currently building", ignoreCase = true)
}

/** Retry only reads blocked by an index that Firestore has already started building. */
internal suspend fun <T> retryWhileFirestoreIndexBuilds(
    maxRetries: Int = 8,
    retryDelayMillis: Long = 15_000L,
    read: suspend () -> T
): T {
    require(maxRetries >= 0 && retryDelayMillis >= 0)
    var retries = 0
    while (true) {
        try {
            return read()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (!error.isFirestoreIndexBuilding() || retries >= maxRetries) throw error
            retries++
            delay(retryDelayMillis)
        }
    }
}
