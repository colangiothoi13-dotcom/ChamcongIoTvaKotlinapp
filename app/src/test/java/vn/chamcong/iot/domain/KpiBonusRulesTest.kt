package vn.chamcong.iot.domain

import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Test
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.AttendanceAdjustment
import vn.chamcong.iot.model.AttendanceResolutionStatus
import vn.chamcong.iot.model.AttendanceType
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.OvertimeRequest
import vn.chamcong.iot.model.OvertimeRequestStatus
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.model.WorkShift
import vn.chamcong.iot.model.workedHoursForMonth
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Date

class KpiBonusRulesTest {
    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    private val month = YearMonth.of(2026, 9)
    private val employee = Employee(id = "e1", code = "NV0001", fullName = "An")

    @Test
    fun countsOnlyApprovedCompleteOvertimeAsOneFixedThreeHourShift() {
        val approvedDate = LocalDate.of(2026, 9, 10)
        val pendingDate = LocalDate.of(2026, 9, 11)
        val rejectedDate = LocalDate.of(2026, 9, 12)
        val requests = listOf(
            request(employee, approvedDate, OvertimeRequestStatus.APPROVED),
            request(employee, pendingDate, OvertimeRequestStatus.PENDING),
            request(employee, rejectedDate, OvertimeRequestStatus.REJECTED)
        )
        val rows = completePair(employee.id, approvedDate) +
            completePair(employee.id, pendingDate) +
            completePair(employee.id, rejectedDate)

        val result = calculateMonthlyKpiBonuses(
            listOf(employee), month, rows, emptyList(), emptyList(), requests, emptyList(), zone
        ).getValue(employee.id)

        assertEquals(1, result.overtimeShiftCount)
        assertEquals(3.0, result.overtimeHours, 0.001)
        assertEquals(50_000L, result.overtimeBonus)
    }

    @Test
    fun excludesDuplicateUnverifiedMalformedIncompleteAndOutOfOrderPairs() {
        val validDate = LocalDate.of(2026, 9, 1)
        val duplicateDate = LocalDate.of(2026, 9, 2)
        val unverifiedDate = LocalDate.of(2026, 9, 3)
        val malformedDate = LocalDate.of(2026, 9, 4)
        val incompleteDate = LocalDate.of(2026, 9, 5)
        val outOfOrderDate = LocalDate.of(2026, 9, 6)
        val pendingResolutionDate = LocalDate.of(2026, 9, 7)
        val dates = listOf(
            validDate,
            duplicateDate,
            unverifiedDate,
            malformedDate,
            incompleteDate,
            outOfOrderDate,
            pendingResolutionDate
        )
        val rows = completePair(employee.id, validDate) +
            completePair(employee.id, duplicateDate).mapIndexed { index, row ->
                if (index == 1) row.copy(resolutionStatus = AttendanceResolutionStatus.DUPLICATE.name) else row
            } +
            completePair(employee.id, unverifiedDate).mapIndexed { index, row ->
                if (index == 1) row.copy(verified = false) else row
            } +
            completePair(employee.id, malformedDate).mapIndexed { index, row ->
                if (index == 1) row.copy(type = "BROKEN") else row
            } +
            completePair(employee.id, incompleteDate).take(1) +
            listOf(
                overtimeAttendance(employee.id, outOfOrderDate, AttendanceType.CHECK_IN.name, "19:00"),
                overtimeAttendance(employee.id, outOfOrderDate, AttendanceType.CHECK_OUT.name, "18:00")
            ) +
            completePair(employee.id, pendingResolutionDate).map {
                it.copy(resolutionStatus = AttendanceResolutionStatus.OVERTIME_PENDING.name)
            }

        val result = calculateMonthlyKpiBonuses(
            listOf(employee),
            month,
            rows,
            emptyList(),
            emptyList(),
            dates.map { request(employee, it, OvertimeRequestStatus.APPROVED) },
            emptyList(),
            zone
        ).getValue(employee.id)

        assertEquals(1, result.overtimeShiftCount)
        assertEquals(3.0, result.overtimeHours, 0.001)
    }

    @Test
    fun calculatesLatePenaltyTopThreeTieBreakAndFloorAtZero() {
        val employees = listOf(
            Employee(id = "e1", code = "NV0002", fullName = "B"),
            Employee(id = "e2", code = "NV0001", fullName = "A"),
            Employee(id = "e3", code = "NV0003", fullName = "C"),
            Employee(id = "e4", code = "NV0004", fullName = "D"),
            Employee(id = "e5", code = "NV0005", fullName = "E")
        )
        val overtimeDates = mapOf(
            "e1" to listOf(1, 2),
            "e2" to listOf(3, 4),
            "e3" to listOf(5),
            "e4" to listOf(6, 7, 8, 9)
        )
        val requests = overtimeDates.flatMap { (employeeId, days) ->
            val owner = employees.first { it.id == employeeId }
            days.map { request(owner, LocalDate.of(2026, 9, it), OvertimeRequestStatus.APPROVED) }
        }
        val overtimeRows = overtimeDates.flatMap { (employeeId, days) ->
            days.flatMap { completePair(employeeId, LocalDate.of(2026, 9, it)) }
        }
        val mainShift = WorkShift(
            id = "main",
            name = "Main",
            startTime = "08:00",
            endTime = "17:00",
            effectiveFrom = "2026-01-01"
        )
        val lateDate = LocalDate.of(2026, 9, 15)
        val schedules = listOf("e4", "e5").map { employeeId ->
            WorkSchedule(employeeId = employeeId, date = lateDate.toString(), shiftId = mainShift.id)
        }
        val lateRows = listOf("e4", "e5").flatMap { employeeId ->
            listOf(
                mainAttendance(employeeId, lateDate, AttendanceType.CHECK_IN.name, "08:15"),
                mainAttendance(employeeId, lateDate, AttendanceType.CHECK_OUT.name, "17:00")
            )
        }

        val results = calculateMonthlyKpiBonuses(
            employees,
            month,
            overtimeRows + lateRows,
            schedules,
            listOf(mainShift),
            requests,
            emptyList(),
            zone
        )

        assertEquals(1, results.getValue("e2").top3Rank)
        assertEquals(2, results.getValue("e1").top3Rank)
        assertEquals(3, results.getValue("e3").top3Rank)
        assertEquals(null, results.getValue("e4").top3Rank)
        assertEquals(500_000L, results.getValue("e2").top3Bonus)
        assertEquals(100_000L, results.getValue("e1").overtimeBonus)
        assertEquals(600_000L, results.getValue("e1").totalBonus)
        assertEquals(1, results.getValue("e4").lateCount)
        assertEquals(100_000L, results.getValue("e4").latePenalty)
        assertEquals(100_000L, results.getValue("e4").totalBonus)
        assertEquals(0, results.getValue("e5").overtimeShiftCount)
        assertEquals(1, results.getValue("e5").lateCount)
        assertEquals(0L, results.getValue("e5").totalBonus)
    }

    @Test
    fun currentAttendanceAdjustmentControlsMainShiftLateCount() {
        val date = LocalDate.of(2026, 9, 15)
        val shift = WorkShift(
            id = "main",
            name = "Main",
            startTime = "08:00",
            endTime = "17:00",
            effectiveFrom = "2026-01-01"
        )
        val schedule = WorkSchedule(employeeId = employee.id, date = date.toString(), shiftId = shift.id)
        val rows = listOf(
            mainAttendance(employee.id, date, AttendanceType.CHECK_IN.name, "08:15"),
            mainAttendance(employee.id, date, AttendanceType.CHECK_OUT.name, "17:00")
        )
        val adjustment = AttendanceAdjustment(
            employeeId = employee.id,
            employeeName = employee.fullName,
            scheduleDate = date.toString(),
            checkInAt = date.atTime(8, 0).atZone(zone).toInstant(),
            reason = "Corrected device clock",
            actorId = "admin",
            actorName = "Admin",
            createdAt = Instant.parse("2026-09-16T01:00:00Z")
        )

        val result = calculateMonthlyKpiBonuses(
            listOf(employee), month, rows, listOf(schedule), listOf(shift), emptyList(), listOf(adjustment), zone
        ).getValue(employee.id)

        assertEquals(0, result.lateCount)
    }

    @Test
    fun lateMainCheckInCountsWithoutCheckoutAndUsesLatestAdjustment() {
        val date = LocalDate.of(2026, 9, 15)
        val shift = WorkShift(id = "main", name = "Main", lateGraceMinutes = 5, effectiveFrom = "2026-01-01")
        val schedule = WorkSchedule(employeeId = employee.id, date = date.toString(), shiftId = shift.id)
        val rows = listOf(mainAttendance(employee.id, date, AttendanceType.CHECK_IN.name, "08:15"))
        val correction = AttendanceAdjustment(
            employeeId = employee.id,
            employeeName = employee.fullName,
            scheduleDate = date.toString(),
            checkInAt = date.atTime(8, 5).atZone(zone).toInstant(),
            reason = "Corrected check-in",
            actorId = "admin",
            actorName = "Admin",
            createdAt = Instant.parse("2026-09-16T01:00:00Z")
        )
        val older = correction.copy(
            checkInAt = date.atTime(8, 15).atZone(zone).toInstant(),
            createdAt = correction.createdAt.minusSeconds(1)
        )

        val late = calculateMonthlyKpiBonuses(
            listOf(employee), month, rows, listOf(schedule), listOf(shift), emptyList(), emptyList(), zone
        ).getValue(employee.id)
        assertEquals(1, late.lateCount)
        assertEquals(100_000L, late.latePenalty)
        assertEquals(null, late.top3Rank)
        assertEquals(0L, late.totalBonus)

        for (adjustments in listOf(listOf(older, correction), listOf(correction, older))) {
            val corrected = calculateMonthlyKpiBonuses(
                listOf(employee), month, rows, listOf(schedule), listOf(shift), emptyList(), adjustments, zone
            ).getValue(employee.id)
            assertEquals(0, corrected.lateCount)
            assertEquals(500_000L, corrected.totalBonus)
        }
    }

    @Test
    fun rejectsEachInvalidEndpointAndRequiresAnApprovedMatchingRequest() {
        val date = LocalDate.of(2026, 9, 10)
        val pair = completePair(employee.id, date)
        val approved = request(employee, date, OvertimeRequestStatus.APPROVED)
        for (endpoint in pair.indices) {
            val row = pair[endpoint]
            val invalidRows = listOf(
                row.copy(verified = false),
                row.copy(type = "BROKEN"),
                row.copy(status = "BROKEN"),
                row.copy(scheduleDate = "not-a-date"),
                row.copy(shiftId = "main"),
                row.copy(employeeId = "other")
            ) + AttendanceResolutionStatus.entries
                .filter { it != AttendanceResolutionStatus.ACCEPTED }
                .map { row.copy(resolutionStatus = it.name) }
            for (invalid in invalidRows) {
                val rows = pair.mapIndexed { index, original -> if (index == endpoint) invalid else original }
                val result = calculateMonthlyKpiBonuses(
                    listOf(employee), month, rows, emptyList(), emptyList(), listOf(approved), emptyList(), zone
                ).getValue(employee.id)
                assertEquals(0, result.overtimeShiftCount)
                assertEquals(0L, result.overtimeBonus)
            }
        }
        val requestLists = listOf(
            emptyList(),
            listOf(approved.copy(employeeId = "other")),
            listOf(approved.copy(workDate = "2026-02-30")),
            listOf(approved.copy(startTime = "17:00")),
            listOf(approved.copy(status = "UNKNOWN"))
        )
        for (requests in requestLists) {
            assertEquals(0, calculateMonthlyKpiBonuses(
                listOf(employee), month, pair, emptyList(), emptyList(), requests, emptyList(), zone
            ).getValue(employee.id).overtimeShiftCount)
        }
    }

    @Test
    fun overtimeMustStayInsideLocalWindowAndNeverAddsLateness() {
        val date = LocalDate.of(2026, 9, 10)
        val approved = request(employee, date, OvertimeRequestStatus.APPROVED)
        val cases = listOf(
            Triple("17:29", "20:30", 0),
            Triple("17:30", "20:31", 0),
            Triple("19:00", "19:00", 0),
            Triple("19:00", "18:00", 0),
            Triple("17:30", "20:30", 1),
            Triple("18:00", "20:00", 1)
        )
        for (scheduleDate in listOf(date.toString(), null)) {
            for ((start, end, expectedCount) in cases) {
                val rows = listOf(
                    overtimeAttendance(employee.id, date, AttendanceType.CHECK_IN.name, start),
                    overtimeAttendance(employee.id, date, AttendanceType.CHECK_OUT.name, end)
                ).map { it.copy(scheduleDate = scheduleDate) }
                val result = calculateMonthlyKpiBonuses(
                    listOf(employee), month, rows, emptyList(), emptyList(), listOf(approved), emptyList(), zone
                ).getValue(employee.id)
                assertEquals(expectedCount, result.overtimeShiftCount)
                assertEquals(if (expectedCount == 1) 3.0 else 0.0, result.overtimeHours, 0.001)
                assertEquals(0, result.lateCount)
            }
        }
    }

    @Test
    fun deduplicatesApprovedDatesAndRestrictsCountingToTheSelectedMonth() {
        val date = LocalDate.of(2026, 9, 30)
        val nextDate = date.plusDays(1)
        val approved = request(employee, date, OvertimeRequestStatus.APPROVED)
        val next = request(employee, nextDate, OvertimeRequestStatus.APPROVED)
        val rows = completePair(employee.id, date) + completePair(employee.id, nextDate)
        val result = calculateMonthlyKpiBonuses(
            listOf(employee), month, rows.reversed(), emptyList(), emptyList(),
            listOf(approved, approved.copy(id = "duplicate"), next), emptyList(), zone
        ).getValue(employee.id)
        assertEquals(1, result.overtimeShiftCount)
        assertEquals(3.0, result.overtimeHours, 0.001)
    }

    @Test
    fun topThreeUsesIdForEqualCodesAndKeepsZeroOvertimeEmployeesEligible() {
        val employees = listOf("e4", "e2", "e1", "e3").map { Employee(id = it, code = "SAME") }
        for (ordered in listOf(employees, employees.reversed())) {
            val result = calculateMonthlyKpiBonuses(
                ordered, month, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), zone
            )
            assertEquals(1, result.getValue("e1").top3Rank)
            assertEquals(2, result.getValue("e2").top3Rank)
            assertEquals(3, result.getValue("e3").top3Rank)
            assertEquals(null, result.getValue("e4").top3Rank)
            assertEquals(500_000L, result.getValue("e1").totalBonus)
            assertEquals(0L, result.getValue("e4").totalBonus)
        }
        val few = calculateMonthlyKpiBonuses(
            employees.take(2), month, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), zone
        )
        assertEquals(listOf(500_000L, 500_000L), few.values.map { it.top3Bonus })
    }

    @Test
    fun pendingAndRejectedOvertimeDoNotIncreasePayrollHours() {
        val date = LocalDate.of(2026, 9, 10)
        val shift = WorkShift(id = "main", name = "Main", endTime = "16:00", effectiveFrom = "2026-01-01")
        val schedule = WorkSchedule(employeeId = employee.id, date = date.toString(), shiftId = shift.id)
        val rows = listOf(
            mainAttendance(employee.id, date, AttendanceType.CHECK_IN.name, "08:00"),
            mainAttendance(employee.id, date, AttendanceType.CHECK_OUT.name, "16:00")
        ) + completePair(employee.id, date)
        for (status in listOf(OvertimeRequestStatus.PENDING, OvertimeRequestStatus.REJECTED)) {
            assertEquals(8.0, payrollHoursForMonth(
                employee.id, month, rows, listOf(schedule), listOf(shift),
                listOf(request(employee, date, status)), emptyList(), zone
            ), 0.001)
        }
    }

    @Test
    fun payrollAddsFixedOvertimeHoursAndPreservesLegacyResultWithoutRequests() {
        val date = LocalDate.of(2026, 9, 10)
        val shift = WorkShift(
            id = "main",
            name = "Main",
            startTime = "08:00",
            endTime = "16:00",
            effectiveFrom = "2026-01-01"
        )
        val schedule = WorkSchedule(employeeId = employee.id, date = date.toString(), shiftId = shift.id)
        val regularRows = listOf(
            mainAttendance(employee.id, date, AttendanceType.CHECK_IN.name, "08:00"),
            mainAttendance(employee.id, date, AttendanceType.CHECK_OUT.name, "16:00")
        )
        val overtimeRows = completePair(employee.id, date)
        val allRows = regularRows + overtimeRows
        val approved = request(employee, date, OvertimeRequestStatus.APPROVED)

        assertEquals(
            11.0,
            payrollHoursForMonth(
                employee.id,
                month,
                allRows,
                listOf(schedule),
                listOf(shift),
                listOf(approved),
                emptyList(),
                zone
            ),
            0.001
        )

        val legacy = workedHoursForMonth(regularRows, employee.id, month, zone, listOf(schedule), listOf(shift))
        assertEquals(
            legacy,
            payrollHoursForMonth(
                employee.id,
                month,
                regularRows,
                listOf(schedule),
                listOf(shift),
                emptyList(),
                emptyList(),
                zone
            ),
            0.001
        )
    }

    private fun request(
        employee: Employee,
        date: LocalDate,
        status: OvertimeRequestStatus
    ) = OvertimeRequest(
        employeeId = employee.id,
        employeeName = employee.fullName,
        department = employee.department,
        workDate = date.toString(),
        startTime = SUPPLEMENTARY_START_TIME,
        endTime = SUPPLEMENTARY_END_TIME,
        status = status.name,
        reviewerId = if (status == OvertimeRequestStatus.PENDING) null else "admin",
        reviewerName = if (status == OvertimeRequestStatus.PENDING) null else "Admin",
        reviewedAt = if (status == OvertimeRequestStatus.PENDING) null else Instant.parse("2026-09-01T00:00:00Z"),
        rejectionReason = if (status == OvertimeRequestStatus.REJECTED) "Not needed" else null
    )

    private fun completePair(employeeId: String, date: LocalDate) = listOf(
        overtimeAttendance(employeeId, date, AttendanceType.CHECK_IN.name, "17:30"),
        overtimeAttendance(employeeId, date, AttendanceType.CHECK_OUT.name, "20:30")
    )

    private fun overtimeAttendance(employeeId: String, date: LocalDate, type: String, time: String) =
        attendance(employeeId, date, type, time).copy(shiftId = SUPPLEMENTARY_SHIFT_ID)

    private fun mainAttendance(employeeId: String, date: LocalDate, type: String, time: String) =
        attendance(employeeId, date, type, time).copy(shiftId = "main")

    private fun attendance(employeeId: String, date: LocalDate, type: String, time: String): Attendance {
        val instant = date.atTime(java.time.LocalTime.parse(time)).atZone(zone).toInstant()
        return Attendance(
            employeeId = employeeId,
            type = type,
            timestamp = Timestamp(Date.from(instant)),
            scheduleDate = date.toString()
        )
    }
}
