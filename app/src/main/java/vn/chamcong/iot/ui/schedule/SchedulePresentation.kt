package vn.chamcong.iot.ui.schedule

import vn.chamcong.iot.domain.canAssignScheduleShift
import vn.chamcong.iot.domain.defaultShiftTemplates
import vn.chamcong.iot.model.WorkShift

internal fun assignableScheduleShifts(shifts: List<WorkShift>): List<WorkShift> =
    shifts.filter(::canAssignScheduleShift)
        .groupBy { it.category }
        .mapNotNull { (category, categoryShifts) ->
            val defaultId = defaultShiftTemplates().firstOrNull { it.category == category }?.resolve()?.id
            categoryShifts.firstOrNull { it.id == defaultId } ?: categoryShifts.firstOrNull()
        }
        .sortedBy { it.startTime }
