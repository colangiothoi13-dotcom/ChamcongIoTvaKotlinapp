const test = require("node:test");
const assert = require("node:assert/strict");
const {
  DUPLICATE_WINDOW_MS,
  TIME_ZONE,
  buildShiftWindow,
  pickSchedule,
  resolveMappedEmployee,
  resolveScan
} = require("../attendanceResolver");

const dayShift = { id: "day", startTime: "08:00", endTime: "17:00", allowEarlyMinutes: 15, missingCheckOutGraceMinutes: 60 };
const nightShift = { id: "night", startTime: "22:00", endTime: "06:00", allowEarlyMinutes: 30, missingCheckOutGraceMinutes: 60 };
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

test("resolveScan makes a first scan near shift end a check-out to expose a missing check-in", () => {
  const result = resolveScan({ scan: scan(at("2026-09-17T09:55:00Z")), schedules: [schedule("2026-09-17", dayShift)], session: null, latestAccepted: null });
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
