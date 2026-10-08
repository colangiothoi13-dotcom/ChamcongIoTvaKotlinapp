package vn.chamcong.iot.data

import kotlinx.coroutines.CancellationException

/** Auth and Firestore cannot share a transaction; an audit failure cannot undo the password. */
internal suspend fun performPasswordChange(
    updatePassword: suspend () -> Unit,
    saveAudit: suspend () -> Unit
): Boolean {
    updatePassword()
    return try {
        saveAudit()
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}
