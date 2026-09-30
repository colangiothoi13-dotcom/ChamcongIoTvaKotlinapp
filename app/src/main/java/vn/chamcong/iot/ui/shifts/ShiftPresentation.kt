package vn.chamcong.iot.ui.shifts

import vn.chamcong.iot.model.ShiftCategory

private val standardShiftNames = setOf(
    "Ca sáng",
    "Ca chiều",
    "Ca tối",
    "Ca chiều / tối",
    "Ca bổ sung"
)

internal fun shiftNameForCategoryChange(currentName: String, category: String): String {
    val trimmed = currentName.trim()
    if (trimmed.isNotBlank() && trimmed !in standardShiftNames) return currentName
    return when (category) {
        ShiftCategory.MORNING.name -> "Ca sáng"
        ShiftCategory.EVENING.name -> "Ca chiều"
        ShiftCategory.SUPPLEMENTARY.name -> "Ca bổ sung"
        else -> currentName
    }
}

internal fun shiftCategoryLabel(category: String): String = when (category) {
    ShiftCategory.MORNING.name -> "Ca sáng"
    ShiftCategory.EVENING.name -> "Ca chiều"
    ShiftCategory.SUPPLEMENTARY.name -> "Ca bổ sung"
    else -> category
}
