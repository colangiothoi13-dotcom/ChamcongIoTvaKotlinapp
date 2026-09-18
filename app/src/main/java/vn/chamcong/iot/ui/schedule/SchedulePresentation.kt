package vn.chamcong.iot.ui.schedule

import vn.chamcong.iot.domain.canAssignScheduleShift
import vn.chamcong.iot.model.WorkShift

internal fun assignableScheduleShifts(shifts: List<WorkShift>): List<WorkShift> =
    shifts.filter(::canAssignScheduleShift)
