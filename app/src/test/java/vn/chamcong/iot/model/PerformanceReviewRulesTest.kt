package vn.chamcong.iot.model

import org.junit.Assert.*
import org.junit.Test

class PerformanceReviewRulesTest {
    private val review = PerformanceReview(employeeId = "employee_with_underscore", period = "2026-10", score = 82.5, note = "  Hoàn thành tốt  ")

    @Test fun `new review uses shared web ID while an existing document keeps its identity`() {
        assertEquals("employee_with_underscore_2026-10", performanceReviewDocumentId(review))
        assertEquals("existing-web-document", performanceReviewDocumentId(review.copy(id = "existing-web-document")))
        assertEquals("Hoàn thành tốt", validatePerformanceReview(review).note)
        assertEquals("  Hoàn thành tốt  ", review.note)
    }

    @Test fun `only finite scores within the full inclusive range and bounded notes are accepted`() {
        for (score in listOf(0.0, 100.0, 72.35)) assertEquals(score, validatePerformanceReview(review.copy(score = score)).score, 0.0)
        for (score in listOf(-0.1, 100.1, Double.NaN, Double.POSITIVE_INFINITY))
            assertThrows(IllegalArgumentException::class.java) { validatePerformanceReview(review.copy(score = score)) }
        assertThrows(IllegalArgumentException::class.java) { validatePerformanceReview(review.copy(note = "x".repeat(2001))) }
        for (invalid in listOf(review.copy(employeeId = "bad/id"), review.copy(period = "2026-13"), review.copy(id = "path/child")))
            assertThrows(IllegalArgumentException::class.java) { validatePerformanceReview(invalid) }
    }
}
