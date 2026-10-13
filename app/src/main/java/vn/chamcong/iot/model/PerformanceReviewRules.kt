package vn.chamcong.iot.model

import java.time.YearMonth

fun validatePerformanceReview(review: PerformanceReview): PerformanceReview {
    require(review.employeeId.isNotBlank() && '/' !in review.employeeId) { "Mã nhân viên không hợp lệ" }
    require(Regex("[0-9]{4}-(0[1-9]|1[0-2])").matches(review.period)) { "Tháng đánh giá phải có dạng yyyy-MM" }
    YearMonth.parse(review.period)
    require(review.score.isFinite() && review.score in 0.0..100.0) { "Điểm đánh giá phải từ 0 đến 100" }
    val note = review.note.trim()
    require(note.length <= 2000) { "Nhận xét tối đa 2.000 ký tự" }
    require(review.id.isBlank() || '/' !in review.id) { "Mã đánh giá không hợp lệ" }
    return review.copy(note = note)
}

fun performanceReviewDocumentId(review: PerformanceReview): String =
    validatePerformanceReview(review).let { it.id.ifBlank { "${it.employeeId}_${it.period}" } }
