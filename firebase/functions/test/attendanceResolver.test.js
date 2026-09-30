const test = require("node:test");
const assert = require("node:assert/strict");
const {
  DUPLICATE_WINDOW_MS,
  TIME_ZONE,
  SUPPLEMENTARY_SHIFT_ID,
  SUPPLEMENTARY_START_TIME,
  SUPPLEMENTARY_END_TIME,
  attendanceSessionId,
  buildSupplementarySchedule,
  buildShiftWindow,
  pickSchedule,
  resolveMappedEmployee,
  resolveScan
} = require("../attendanceResolver");

const dayShift = { id: "day", startTime: "08:00", endTime: "17:00", allowEarlyMinutes: 15, missingCheckOutGraceMinutes: 60 };
const nightShift = { id: "night", startTime: "22:00", endTime: "06:00", allowEarlyMinutes: 30, missingCheckOutGraceMinutes: 60 };
const morningShift = {
  id: "morning", category: "MORNING", startTime: "08:00", endTime: "12:00",
  allowEarlyMinutes: 120, lateGraceMinutes: 0, earlyLeaveAllowedMinutes: 0
};
const afternoonShift = {
  id: "afternoon", category: "EVENING", startTime: "13:00", endTime: "17:00",
  allowEarlyMinutes: 0, lateGraceMinutes: 0, earlyLeaveAllowedMinutes: 0
};
const at = value => Date.parse(value);
const schedule = (scheduleDate, shift) => ({ scheduleDate, shiftId: shift.id, shift });
const scan = (timestampMs, eventId = "event") => ({ timestampMs, eventId });

test("buildShiftWindow creates a same-day Asia/Ho_Chi_Minh window", () => {
  const window = buildShiftWindow("2026-09-17", dayShift);
  assert.equal(TIME_ZONE, "Asia/Ho_Chi_Minh");
  assert.equal(window.startMs, at("2026-09-17T01:00:00Z"));
  assert.equal(window.endMs, at("2026-09-17T10:00:00Z"));
});

test("buildShiftWindow carries overnight end into the next local day", () => {
  const window = buildShiftWindow("2026-09-17", nightShift);
  assert.equal(window.startMs, at("2026-09-17T15:00:00Z"));
  assert.equal(window.endMs, at("2026-09-17T23:00:00Z"));
});

test("buildSupplementarySchedule creates the fixed Asia/Ho_Chi_Minh window", () => {
  const candidate = buildSupplementarySchedule("2026-09-17", { id: "request-1", status: "PENDING" });
  const window = buildShiftWindow(candidate.scheduleDate, candidate.shift);

  assert.equal(SUPPLEMENTARY_SHIFT_ID, "SUPPLEMENTARY_1800_2200");
  assert.equal(SUPPLEMENTARY_START_TIME, "18:00");
  assert.equal(SUPPLEMENTARY_END_TIME, "22:00");
  assert.equal(candidate.shiftId, SUPPLEMENTARY_SHIFT_ID);
  assert.equal(candidate.overtimeRequestId, "request-1");
  assert.equal(window.startMs, at("2026-09-17T11:00:00Z"));
  assert.equal(window.endMs, at("2026-09-17T15:00:00Z"));
});

test("attendanceSessionId isolates supplementary attendance from the legacy main session", () => {
  assert.equal(attendanceSessionId("employee-1", "2026-09-17", "afternoon"), "employee-1_2026-09-17");
  assert.equal(
    attendanceSessionId("employee-1", "2026-09-17", SUPPLEMENTARY_SHIFT_ID),
    "employee-1_2026-09-17_SUPPLEMENTARY_1800_2200"
  );
});

test("pickSchedule accepts only the configured early and missing-checkout window", () => {
  const candidate = schedule("2026-09-17", dayShift);
  assert.equal(pickSchedule(at("2026-09-17T00:45:00Z"), [candidate]).shiftId, "day");
  assert.equal(pickSchedule(at("2026-09-17T11:00:00Z"), [candidate]).shiftId, "day");
  assert.equal(pickSchedule(at("2026-09-17T00:44:59Z"), [candidate]), null);
  assert.equal(pickSchedule(at("2026-09-17T11:00:01Z"), [candidate]), null);
});

test("pickSchedule gives legacy shifts without a grace field the sixty-minute default", () => {
  const legacyShift = { ...dayShift };
  delete legacyShift.missingCheckOutGraceMinutes;
  assert.equal(pickSchedule(at("2026-09-17T10:30:00Z"), [schedule("2026-09-17", legacyShift)]).shiftId, "day");
});

test("pickSchedule preserves an explicit zero missing-checkout grace", () => {
  const noGraceShift = { ...dayShift, missingCheckOutGraceMinutes: 0 };
  assert.equal(pickSchedule(at("2026-09-17T10:00:01Z"), [schedule("2026-09-17", noGraceShift)]), null);
});

test("resolveMappedEmployee trusts an enabled mapping even when the legacy employee slot differs", () => {
  const employee = { id: "employee-1", active: true, fingerprintTemplateId: 99 };
  assert.equal(resolveMappedEmployee({ enabled: true, employeeId: "employee-1" }, employee), employee);
  assert.equal(resolveMappedEmployee({ enabled: false, employeeId: "employee-1" }, employee), null);
  assert.equal(resolveMappedEmployee(undefined, employee), null);
  assert.equal(resolveMappedEmployee({ enabled: true, employeeId: "employee-1" }, { ...employee, active: false }), null);
});

test("resolveScan makes a first scan near shift start a check-in", () => {
  const result = resolveScan({ scan: scan(at("2026-09-17T01:05:00Z")), schedules: [schedule("2026-09-17", dayShift)], session: null, latestAccepted: null });
  assert.equal(result.type, "CHECK_IN");
  assert.equal(result.resolutionStatus, "ACCEPTED");
  assert.equal(result.nextSession.openCheckInAt, at("2026-09-17T01:05:00Z"));
});

test("two standard shifts remain separate and a 10:00 scan is late in the morning shift", () => {
  const schedules = [
    schedule("2026-09-17", morningShift),
    schedule("2026-09-17", afternoonShift)
  ];

  const morningIn = resolveScan({
    scan: scan(at("2026-09-17T03:00:00Z"), "morning-in"),
    schedules, session: null, latestAccepted: null
  });
  assert.equal(morningIn.shiftId, "morning");
  assert.equal(morningIn.type, "CHECK_IN");
  assert.equal(morningIn.status, "LATE");

  const morningOut = resolveScan({
    scan: scan(at("2026-09-17T05:00:00Z"), "morning-out"),
    schedules, session: morningIn.nextSession,
    latestAccepted: { timestampMs: morningIn.nextSession.lastAcceptedAt, eventId: "morning-in" }
  });
  assert.equal(morningOut.shiftId, "morning");
  assert.equal(morningOut.type, "CHECK_OUT");
  assert.equal(morningOut.status, "NORMAL");

  const afternoonIn = resolveScan({
    scan: scan(at("2026-09-17T06:00:00Z"), "afternoon-in"),
    schedules, session: null, latestAccepted: null
  });
  assert.equal(afternoonIn.shiftId, "afternoon");
  assert.equal(afternoonIn.type, "CHECK_IN");
});

test("17:30 is the inclusive afternoon checkout boundary", () => {
  const result = resolveScan({
    scan: scan(at("2026-09-17T10:30:00Z"), "afternoon-out"),
    schedules: [schedule("2026-09-17", afternoonShift)],
    session: { openCheckInAt: at("2026-09-17T06:00:00Z"), closed: false },
    latestAccepted: { timestampMs: at("2026-09-17T06:00:00Z"), eventId: "afternoon-in" }
  });
  assert.equal(result.shiftId, "afternoon");
  assert.equal(result.type, "CHECK_OUT");
  assert.equal(result.status, "NORMAL");
});

test("a checkout before the shift end is marked early leave", () => {
  const result = resolveScan({
    scan: scan(at("2026-09-17T04:30:00Z"), "morning-early-out"),
    schedules: [schedule("2026-09-17", morningShift)],
    session: { openCheckInAt: at("2026-09-17T03:00:00Z"), closed: false },
    latestAccepted: { timestampMs: at("2026-09-17T03:00:00Z"), eventId: "morning-in" }
  });
  assert.equal(result.type, "CHECK_OUT");
  assert.equal(result.status, "EARLY_LEAVE");
});

test("resolveScan makes a first scan near shift end a check-out to expose a missing check-in", () => {
  const result = resolveScan({ scan: scan(at("2026-09-17T10:00:00Z")), schedules: [schedule("2026-09-17", dayShift)], session: null, latestAccepted: null });
  assert.equal(result.type, "CHECK_OUT");
  assert.equal(result.resolutionStatus, "ACCEPTED");
  assert.equal(result.nextSession.closed, true);
});

test("resolveScan closes an open check-in with a later scan", () => {
  const result = resolveScan({
    scan: scan(at("2026-09-17T10:00:00Z"), "out"), schedules: [schedule("2026-09-17", dayShift)],
    session: { openCheckInAt: at("2026-09-17T01:05:00Z"), closed: false },
    latestAccepted: { timestampMs: at("2026-09-17T01:05:00Z"), eventId: "in" }
  });
  assert.equal(result.type, "CHECK_OUT");
  assert.equal(result.resolutionStatus, "ACCEPTED");
  assert.equal(result.nextSession.openCheckInAt, null);
  assert.equal(result.nextSession.closed, true);
});

test("resolveScan rejects scans within the three-minute duplicate window", () => {
  const result = resolveScan({
    scan: scan(at("2026-09-17T01:03:00Z")), schedules: [schedule("2026-09-17", dayShift)], session: null,
    latestAccepted: { timestampMs: at("2026-09-17T01:00:00Z") }
  });
  assert.equal(DUPLICATE_WINDOW_MS, 180000);
  assert.equal(result.resolutionStatus, "DUPLICATE");
  assert.equal(result.nextSession, null);
});

test("resolveScan rejects another scan for a closed schedule session", () => {
  const session = { closed: true, lastAcceptedAt: at("2026-09-17T09:55:00Z") };
  const result = resolveScan({ scan: scan(at("2026-09-17T10:20:00Z")), schedules: [schedule("2026-09-17", dayShift)], session, latestAccepted: { timestampMs: session.lastAcceptedAt } });
  assert.equal(result.resolutionStatus, "UNSCHEDULED");
  assert.equal(result.nextSession, session);
});

test("resolveScan marks scans outside every schedule window as unscheduled", () => {
  const result = resolveScan({ scan: scan(at("2026-09-17T13:00:00Z")), schedules: [schedule("2026-09-17", dayShift)], session: null, latestAccepted: null });
  assert.equal(result.type, "UNSCHEDULED");
  assert.equal(result.resolutionStatus, "UNSCHEDULED");
});

test("resolveScan rejects older non-duplicate scans as out of order", () => {
  const result = resolveScan({
    scan: scan(at("2026-09-17T01:00:00Z")), schedules: [schedule("2026-09-17", dayShift)], session: null,
    latestAccepted: { timestampMs: at("2026-09-17T01:05:00Z") }
  });
  assert.equal(result.type, "OUT_OF_ORDER");
  assert.equal(result.resolutionStatus, "OUT_OF_ORDER");
});
