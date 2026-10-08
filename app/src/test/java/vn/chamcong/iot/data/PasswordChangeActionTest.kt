package vn.chamcong.iot.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordChangeActionTest {
    @Test
    fun successfulPasswordChangeWritesAuditAfterAuth() = runBlocking {
        val calls = mutableListOf<String>()
        val auditSaved = performPasswordChange(
            { calls += "auth" },
            { calls += "audit" }
        )
        assertTrue(auditSaved)
        assertEquals(listOf("auth", "audit"), calls)
    }

    @Test
    fun auditFailureReportsPasswordWasChangedWithoutAudit() = runBlocking {
        var passwordChanged = false
        val auditSaved = performPasswordChange(
            { passwordChanged = true },
            { throw IllegalStateException("Firestore unavailable") }
        )
        assertTrue(passwordChanged)
        assertFalse(auditSaved)
    }

    @Test
    fun authFailurePropagatesAndNeverWritesAudit() = runBlocking {
        val failure = IllegalStateException("Recent login required")
        var auditCalled = false
        val actual = runCatching {
            performPasswordChange({ throw failure }, { auditCalled = true })
        }.exceptionOrNull()
        assertSame(failure, actual)
        assertFalse(auditCalled)
    }

    @Test
    fun cancellationDuringAuditIsNotSwallowed() = runBlocking {
        val cancelled = CancellationException("Session cancelled")
        val actual = runCatching {
            performPasswordChange({}, { throw cancelled })
        }.exceptionOrNull()
        assertSame(cancelled, actual)
    }
}
