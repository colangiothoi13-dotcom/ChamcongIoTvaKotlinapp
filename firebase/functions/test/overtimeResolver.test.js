const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const {
  SUPPLEMENTARY_SHIFT_ID,
  buildSupplementarySchedule,
  resolveScan
} = require("../attendanceResolver");

const at = value => Date.parse(value);
const scan = (timestampMs, eventId) => ({ timestampMs, eventId });
const request = { id: "employee-1_2026-09-17", status: "PENDING" };
const supplementarySchedule = () => buildSupplementarySchedule("2026-09-17", request);

test("pending overtime resolves sequential scans as a non-payable check-in and check-out", () => {
  const first = resolveScan({
    scan: scan(at("2026-09-17T11:00:00Z"), "in"),
    schedules: [supplementarySchedule()],
    session: null,
    latestAccepted: null,
    requestStatus: "PENDING"
  });
  const second = resolveScan({
    scan: scan(at("2026-09-17T15:00:00Z"), "out"),
    schedules: [supplementarySchedule()],
    session: first.nextSession,
    latestAccepted: { timestampMs: first.nextSession.lastAcceptedAt, eventId: "in" },
    requestStatus: "PENDING"
  });

  assert.equal(first.type, "CHECK_IN");
  assert.equal(first.resolutionStatus, "OVERTIME_PENDING");
  assert.equal(second.type, "CHECK_OUT");
  assert.equal(second.resolutionStatus, "OVERTIME_PENDING");
  assert.equal(second.nextSession.closed, true);
});

test("a supplementary-window scan without a request remains unscheduled", () => {
  const afternoonShift = {
    id: "afternoon",
    startTime: "13:00",
    endTime: "17:00",
    missingCheckOutGraceMinutes: 60
  };
  const result = resolveScan({
    scan: scan(at("2026-09-17T11:00:00Z"), "no-request"),
    schedules: [{ scheduleDate: "2026-09-17", shiftId: afternoonShift.id, shift: afternoonShift }],
    session: null,
    latestAccepted: null,
    requestStatus: null
  });

  assert.equal(result.type, "UNSCHEDULED");
  assert.equal(result.resolutionStatus, "UNSCHEDULED");
});

test("a regular shift that spans the supplementary window keeps its existing behavior", () => {
  const eveningShift = {
    id: "evening",
    startTime: "16:00",
    endTime: "21:00",
    missingCheckOutGraceMinutes: 60
  };
  const result = resolveScan({
    scan: scan(at("2026-09-17T10:30:00Z"), "regular-evening"),
    schedules: [{ scheduleDate: "2026-09-17", shiftId: eveningShift.id, shift: eveningShift }],
    session: null,
    latestAccepted: null,
    requestStatus: null
  });

  assert.equal(result.type, "CHECK_IN");
  assert.equal(result.resolutionStatus, "ACCEPTED");
});

test("a regular shift that overlaps supplementary hours keeps its checkout grace", () => {
  const regularShift = {
    id: "regular-late",
    startTime: "16:00",
    endTime: "19:00",
    missingCheckOutGraceMinutes: 60
  };
  const checkInAt = at("2026-09-17T09:00:00Z");
  const result = resolveScan({
    scan: scan(at("2026-09-17T12:10:00Z"), "regular-checkout"),
    schedules: [{ scheduleDate: "2026-09-17", shiftId: regularShift.id, shift: regularShift }],
    session: { openCheckInAt: checkInAt, closed: false },
    latestAccepted: { timestampMs: checkInAt, eventId: "regular-checkin" },
    requestStatus: null
  });

  assert.equal(result.type, "CHECK_OUT");
  assert.equal(result.resolutionStatus, "ACCEPTED");
  assert.equal(result.nextSession.closed, true);
});

function loadFunctions(initialRows, options = {}) {
  const rows = new Map(initialRows.map(row => [row.id, { ...row }]));
  const sessions = new Map(Object.entries(options.initialSessions || {}).map(([id, value]) => [id, { ...value }]));
  const sessionWrites = [];
  let sessionVersion = 0;
  let concurrentSessionInjected = false;
  let db;
  const firestore = Object.assign(() => db, {
    Timestamp: {
      now: () => timestamp(at("2026-09-17T14:00:00Z")),
      fromMillis: timestamp
    },
    FieldValue: { serverTimestamp: () => "server-time" }
  });

  function timestamp(milliseconds) {
    return { milliseconds, toMillis: () => milliseconds };
  }

  function attendanceSnapshot(row) {
    return {
      id: row.id,
      ref: `attendance/${row.id}`,
      data: () => ({ ...row })
    };
  }

  function attendanceQuerySnapshot(requestId) {
    return {
      docs: [...rows.values()]
        .filter(row => row.overtimeRequestId === requestId)
        .map(attendanceSnapshot)
    };
  }

  function sessionSnapshot(ref) {
    const id = ref.slice("attendanceSessions/".length);
    const value = sessions.get(id);
    return { exists: value != null, data: () => value == null ? undefined : { ...value } };
  }

  function injectConcurrentSession() {
    if (!options.concurrentSession || concurrentSessionInjected) return;
    sessions.set(options.concurrentSession.id, { ...options.concurrentSession.data });
    concurrentSessionInjected = true;
    sessionVersion += 1;
  }

  function applyOperations(operations) {
    for (const operation of operations) {
      if (operation.kind === "set") {
        const id = operation.ref.slice("attendanceSessions/".length);
        const previous = sessions.get(id) || {};
        sessions.set(id, operation.merge ? { ...previous, ...operation.data } : { ...operation.data });
        sessionWrites.push(operation);
        sessionVersion += 1;
        continue;
      }
      const id = operation.ref.slice("attendance/".length);
      rows.set(id, { ...rows.get(id), ...operation.data });
    }
  }

  db = {
    collection(name) {
      if (name === "attendance") {
        return {
          where(field, operator, value) {
            assert.equal(field, "overtimeRequestId");
            assert.equal(operator, "==");
            return {
              kind: "attendance-query",
              value,
              async get() { return attendanceQuerySnapshot(value); }
            };
          }
        };
      }
      return { doc: id => `${name}/${id}` };
    },
    batch() {
      const operations = [];
      return {
        update(ref, data) { operations.push({ kind: "update", ref, data }); },
        set(ref, data, settings) {
          operations.push({ kind: "set", ref, data, merge: settings?.merge === true });
        },
        async commit() {
          injectConcurrentSession();
          applyOperations(operations);
        }
      };
    },
    async runTransaction(callback) {
      for (let attempt = 0; attempt < 3; attempt += 1) {
        const operations = [];
        let readSessionVersion = null;
        const transaction = {
          async get(target) {
            if (target && target.kind === "attendance-query") return attendanceQuerySnapshot(target.value);
            if (typeof target === "string" && target.startsWith("overtimeRequests/")) {
              return { exists: true, data: () => ({ ...request }) };
            }
            if (typeof target === "string" && target.startsWith("attendanceSessions/")) {
              readSessionVersion = sessionVersion;
              return sessionSnapshot(target);
            }
            throw new Error(`Unexpected transaction read: ${String(target)}`);
          },
          update(ref, data) { operations.push({ kind: "update", ref, data }); },
          set(ref, data, settings) {
            operations.push({ kind: "set", ref, data, merge: settings?.merge === true });
          }
        };
        const result = await callback(transaction);
        injectConcurrentSession();
        if (readSessionVersion != null && readSessionVersion !== sessionVersion) continue;
        applyOperations(operations);
        return result;
      }
      throw new Error("TRANSACTION_RETRY_LIMIT");
    }
  };

  const exports = {};
  vm.runInNewContext(fs.readFileSync(require.resolve("../index.js"), "utf8"), {
    exports,
    Buffer,
    console,
    require(name) {
      if (name === "firebase-functions/v2/https") return { onRequest: (_, handler) => handler };
      if (name === "firebase-functions/v2/firestore") {
        return {
          onDocumentCreated: (_, handler) => handler,
          onDocumentUpdated: (_, handler) => handler,
          onDocumentWritten: (_, handler) => handler
        };
      }
      if (name === "firebase-functions/params") return { defineSecret: () => ({ value: () => "local-test-key" }) };
      if (name === "firebase-admin") return { initializeApp() {}, firestore };
      if (name.startsWith("./")) return require(`../${name.slice(2)}`);
      return require(name);
    }
  });

  async function review(status) {
    await exports.resolveOvertimeRequestAttendance({
      data: {
        before: { data: () => ({ status: "PENDING" }) },
        after: { data: () => ({ status }) }
      },
      params: { requestId: request.id }
    });
  }

  return { exports, review, rows, sessions, sessionWrites, timestamp };
}

function pendingRows(timestamp) {
  return [
    {
      id: "out",
      employeeId: "employee-1",
      employeeName: "An",
      overtimeRequestId: request.id,
      timestamp: timestamp(at("2026-09-17T15:00:00Z")),
      type: "CHECK_OUT",
      resolutionStatus: "OVERTIME_PENDING"
    },
    {
      id: "in",
      employeeId: "employee-1",
      employeeName: "An",
      overtimeRequestId: request.id,
      timestamp: timestamp(at("2026-09-17T11:00:00Z")),
      type: "CHECK_IN",
      resolutionStatus: "OVERTIME_PENDING"
    }
  ];
}

test("approving a request re-resolves pending events in timestamp order as accepted", async () => {
  const seed = loadFunctions([]);
  const harness = loadFunctions(pendingRows(seed.timestamp));

  await harness.review("APPROVED");

  assert.equal(harness.rows.size, 2);
  assert.equal(harness.rows.get("in").type, "CHECK_IN");
  assert.equal(harness.rows.get("in").resolutionStatus, "ACCEPTED");
  assert.equal(harness.rows.get("out").type, "CHECK_OUT");
  assert.equal(harness.rows.get("out").resolutionStatus, "ACCEPTED");
  assert.equal(harness.rows.get("in").scheduleDate, "2026-09-17");
  assert.equal(harness.rows.get("out").shiftId, SUPPLEMENTARY_SHIFT_ID);
  assert.equal(harness.sessionWrites.length, 1);
});

test("rejecting a request keeps pending events and marks them non-payable", async () => {
  const seed = loadFunctions([]);
  const harness = loadFunctions(pendingRows(seed.timestamp));

  await harness.review("REJECTED");

  assert.equal(harness.rows.size, 2);
  assert.equal(harness.rows.get("in").type, "CHECK_IN");
  assert.equal(harness.rows.get("out").type, "CHECK_OUT");
  assert.equal(harness.rows.get("in").resolutionStatus, "OVERTIME_REJECTED");
  assert.equal(harness.rows.get("out").resolutionStatus, "OVERTIME_REJECTED");
});

test("approval replay does not reopen a session advanced by concurrent live resolution", async () => {
  const seed = loadFunctions([]);
  const sessionId = "employee-1_2026-09-17_SUPPLEMENTARY_1800_2200";
  const checkInAt = seed.timestamp(at("2026-09-17T11:00:00Z"));
  const liveCheckOutAt = seed.timestamp(at("2026-09-17T13:00:00Z"));
  const harness = loadFunctions([
    {
      id: "in",
      employeeId: "employee-1",
      employeeName: "An",
      overtimeRequestId: request.id,
      timestamp: checkInAt,
      type: "CHECK_IN",
      resolutionStatus: "OVERTIME_PENDING"
    }
  ], {
    initialSessions: {
      [sessionId]: {
        employeeId: "employee-1",
        scheduleDate: "2026-09-17",
        shiftId: SUPPLEMENTARY_SHIFT_ID,
        lastAcceptedEventId: "in",
        lastAcceptedType: "CHECK_IN",
        lastAcceptedAt: checkInAt,
        openCheckInAt: checkInAt,
        closed: false
      }
    },
    concurrentSession: {
      id: sessionId,
      data: {
        employeeId: "employee-1",
        scheduleDate: "2026-09-17",
        shiftId: SUPPLEMENTARY_SHIFT_ID,
        lastAcceptedEventId: "live-out",
        lastAcceptedType: "CHECK_OUT",
        lastAcceptedAt: liveCheckOutAt,
        openCheckInAt: null,
        closed: true
      }
    }
  });

  await harness.review("APPROVED");

  const finalSession = harness.sessions.get(sessionId);
  assert.equal(harness.rows.get("in").resolutionStatus, "ACCEPTED");
  assert.equal(finalSession.lastAcceptedEventId, "live-out");
  assert.equal(finalSession.lastAcceptedAt.toMillis(), at("2026-09-17T13:00:00Z"));
  assert.equal(finalSession.openCheckInAt, null);
  assert.equal(finalSession.closed, true);
});
