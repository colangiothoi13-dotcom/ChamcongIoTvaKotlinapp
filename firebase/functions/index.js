const { onRequest } = require("firebase-functions/v2/https");
const { onDocumentCreated, onDocumentUpdated, onDocumentWritten } = require("firebase-functions/v2/firestore");
const { defineSecret } = require("firebase-functions/params");
const admin = require("firebase-admin");
const crypto = require("crypto");
const {
  TIME_ZONE,
  SUPPLEMENTARY_SHIFT_ID,
  attendanceSessionId,
  buildSupplementarySchedule,
  localDateForMs,
  pickSchedule,
  resolveMappedEmployee,
  resolveScan
} = require("./attendanceResolver");
const { shouldNotifyAttendance } = require("./attendanceNotification");

admin.initializeApp();
const db = admin.firestore();
const deviceApiKey = defineSecret("DEVICE_API_KEY");

function previousLocalDate(date) {
  const [year, month, day] = date.split("-").map(Number);
  return new Date(Date.UTC(year, month - 1, day - 1)).toISOString().slice(0, 10);
}

function timestampToMs(timestamp) {
  if (timestamp && typeof timestamp.toMillis === "function") return timestamp.toMillis();
  if (timestamp instanceof Date) return timestamp.getTime();
  const parsed = Date.parse(timestamp);
  if (!Number.isNaN(parsed)) return parsed;
  throw new Error("INVALID_TIMESTAMP");
}

function timestampFromRequest(timestamp, fallback) {
  if (!timestamp) return fallback;
  return admin.firestore.Timestamp.fromMillis(timestampToMs(timestamp));
}

function zonedDateTimeToMs(date, time, timeZone = TIME_ZONE) {
  const [year, month, day] = date.split("-").map(Number);
  const [hour, minute] = time.split(":").map(Number);
  const utcGuess = Date.UTC(year, month - 1, day, hour, minute);
  const local = Object.fromEntries(new Intl.DateTimeFormat("en-CA", {
    timeZone, year: "numeric", month: "2-digit", day: "2-digit",
    hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23"
  }).formatToParts(new Date(utcGuess))
    .filter(part => part.type !== "literal")
    .map(part => [part.type, Number(part.value)]));
  return utcGuess + (Date.UTC(year, month - 1, day, hour, minute) - Date.UTC(
    local.year, local.month - 1, local.day, local.hour, local.minute
  ));
}

function validLocalDate(date) {
  if (typeof date !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(date)) return false;
  const [year, month, day] = date.split("-").map(Number);
  const parsed = new Date(Date.UTC(year, month - 1, day));
  return parsed.getUTCFullYear() === year && parsed.getUTCMonth() + 1 === month && parsed.getUTCDate() === day;
}

function validShiftTime(value) {
  return typeof value === "string" && /^([01]\d|2[0-3]):[0-5]\d$/.test(value);
}

function dateWithinShiftValidity(date, shift) {
  return (!shift.effectiveFrom || date >= shift.effectiveFrom) &&
    (!shift.effectiveTo || date <= shift.effectiveTo);
}

function shiftSessionData(employeeId, scheduleDate, shiftId, session) {
  return {
    employeeId, scheduleDate, shiftId,
    lastAcceptedEventId: session.lastAcceptedEventId || null,
    lastAcceptedType: session.lastAcceptedType || null,
    lastAcceptedAt: session.lastAcceptedAt == null ? null : admin.firestore.Timestamp.fromMillis(session.lastAcceptedAt),
    openCheckInAt: session.openCheckInAt == null ? null : admin.firestore.Timestamp.fromMillis(session.openCheckInAt),
    closed: session.closed === true,
    updatedAt: admin.firestore.FieldValue.serverTimestamp()
  };
}

const permanentOffScheduleFailureReasons = {
  INVALID_OFF_SCHEDULE_REVIEW: "Yêu cầu duyệt có ngày hoặc quyết định không hợp lệ.",
  SUNDAY_OFF_SCHEDULE_APPROVAL: "Không thể duyệt phân ca vào Chủ nhật.",
  INCOMPLETE_OFF_SCHEDULE_REVIEW: "Yêu cầu duyệt thiếu thông tin nhân viên, ca hoặc người duyệt.",
  INVALID_OFF_SCHEDULE_REVIEW_REASON: "Lý do duyệt không hợp lệ.",
  REVIEWER_NOT_ACTIVE_ADMIN: "Tài khoản Admin gửi yêu cầu hiện không còn hoạt động.",
  EMPLOYEE_SNAPSHOT_MISMATCH: "Thông tin nhân viên đã thay đổi hoặc không còn tồn tại.",
  SHIFT_SNAPSHOT_MISMATCH: "Ca đã thay đổi hoặc không còn phù hợp để duyệt.",
  SHIFT_OR_EMPLOYEE_NOT_ASSIGNABLE: "Nhân viên hoặc ca không còn đủ điều kiện phân lịch.",
  OFF_SCHEDULE_REVIEW_AUDIT_ALREADY_EXISTS: "Yêu cầu đã có nhật ký xử lý nên không thể áp dụng lần nữa.",
  WORK_SCHEDULE_IDENTITY_MISMATCH: "Lịch làm việc trong ngày không khớp với yêu cầu.",
  INVALID_EXISTING_WORK_SCHEDULE_SHIFTS: "Lịch hiện tại có danh sách ca không hợp lệ.",
  EXISTING_WORK_SCHEDULE_SHIFT_UNAVAILABLE: "Một ca đang có trong lịch không còn hợp lệ.",
  WORK_SCHEDULE_ALREADY_HAS_TWO_SHIFTS: "Ngày này đã có đủ hai ca.",
  WORK_SCHEDULE_HAS_DUPLICATE_SHIFT_CATEGORY: "Ngày này đã có một ca cùng buổi.",
  TOO_MANY_OFF_SCHEDULE_SCANS_FOR_ATOMIC_REVIEW: "Có quá nhiều lượt quét trong ngày để xử lý an toàn cùng lúc.",
  NO_UNRESOLVED_OFF_SCHEDULE_SCANS: "Không còn lượt quét ngoài lịch chưa được xử lý cho ca này.",
  AMBIGUOUS_EXISTING_ATTENDANCE_SESSION: "Phiên chấm công cũ không thể chuyển đổi an toàn sang lịch nhiều ca."
};

async function getMappedActiveEmployee(transaction, templateId) {
  const mappingSnapshot = await transaction.get(db.collection("fingerprintMappings").doc(String(templateId)));
  if (!mappingSnapshot.exists) return null;
  const mapping = mappingSnapshot.data();
  if (!mapping.employeeId) return null;
  const employeeSnapshot = await transaction.get(db.collection("employees").doc(String(mapping.employeeId)));
  if (!employeeSnapshot.exists) return null;
  return resolveMappedEmployee(mapping, { ...employeeSnapshot.data(), id: employeeSnapshot.id });
}

function safeEqual(a, b) {
  const left = Buffer.from(a || "");
  const right = Buffer.from(b || "");
  return left.length === right.length && crypto.timingSafeEqual(left, right);
}

function validDeviceId(deviceId) {
  return typeof deviceId === "string" && /^[A-Za-z0-9._-]{1,64}$/.test(deviceId);
}

function authenticateDevice(req, res) {
  if (!safeEqual(req.get("x-device-key"), deviceApiKey.value())) {
    res.status(401).json({ ok: false, message: "Thiết bị không hợp lệ" });
    return false;
  }
  return true;
}

function validBoundedString(value, maxLength) {
  return typeof value === "string" && value.length <= maxLength;
}

function validDeviceSnapshot(snapshot) {
  const integerInRange = (value, min, max) => Number.isInteger(value) && value >= min && value <= max;
  return snapshot.status === "ONLINE"
    && validBoundedString(snapshot.firmwareVersion, 120)
    && integerInRange(snapshot.capacity, 1, 127)
    && integerInRange(snapshot.pendingAttendanceCount, 0, 500)
    && (snapshot.fingerprintCount == null || integerInRange(snapshot.fingerprintCount, 0, 127))
    && Array.isArray(snapshot.capabilities)
    && snapshot.capabilities.length <= 32
    && snapshot.capabilities.every(capability => validBoundedString(capability, 64))
    && ["ONLINE", "OFFLINE", "UNKNOWN"].includes(snapshot.wifiStatus)
    && ["ONLINE", "PENDING", "ERROR", "UNKNOWN"].includes(snapshot.firebaseSyncStatus)
    && ["OK", "ERROR", "UNKNOWN"].includes(snapshot.sensorStatus)
    && integerInRange(snapshot.failedScanCount, 0, 100000)
    && validBoundedString(snapshot.lastError, 240);
}

function notificationDocumentId(kind, sourceId) {
  const digest = crypto.createHash("sha256").update(`${kind}:${sourceId}`).digest("hex");
  return `SYS_${digest}`;
}

async function createEmployeeNotification({ kind, sourceId, referenceId, employeeId, employeeName, title, body }) {
  if (![kind, sourceId, employeeId, title, body].every(value => typeof value === "string" && value.trim())) return null;
  const ref = db.collection("notifications").doc(notificationDocumentId(kind, sourceId));
  return db.runTransaction(async transaction => {
    const existing = await transaction.get(ref);
    if (existing.exists) return null;
    transaction.create(ref, {
      type: kind,
      title,
      body,
      referenceId: referenceId || sourceId,
      recipientEmployeeId: employeeId,
      audienceLabel: typeof employeeName === "string" ? employeeName : "",
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
      read: false
    });
    return null;
  });
}

exports.recordAttendance = onRequest({ region: "asia-southeast1", secrets: [deviceApiKey] }, async (req, res) => {
  if (req.method !== "POST") return res.status(405).json({ ok: false, message: "POST only" });
  if (!authenticateDevice(req, res)) return;

  const { deviceId, templateId, eventId } = req.body || {};
  const confidence = Number.isInteger(req.body?.confidence) ? req.body.confidence : 0;
  if (!validDeviceId(deviceId) || !Number.isInteger(templateId) || templateId < 1 || templateId > 127
      || !Number.isInteger(confidence) || confidence < 0 || confidence > 1000
      || typeof eventId !== "string" || !/^[A-Za-z0-9._-]{1,160}$/.test(eventId)) {
    return res.status(400).json({ ok: false, message: "Thiếu hoặc sai dữ liệu thiết bị" });
  }
  const eventRef = db.collection("attendance").doc(String(eventId));

  try {
    const result = await db.runTransaction(async transaction => {
      const previous = await transaction.get(eventRef);
      if (previous.exists) return { duplicate: true, ...previous.data() };

      const employee = await getMappedActiveEmployee(transaction, templateId);
      if (!employee) throw new Error("FINGERPRINT_NOT_REGISTERED");
      const now = admin.firestore.Timestamp.now();
      const data = {
        employeeId: employee.id,
        employeeName: employee.fullName,
        deviceId, templateId, confidence: Number(confidence || 0),
        type: "SCAN", resolutionStatus: "PENDING", status: "PENDING",
        syncStatus: "PENDING_SYNC",
        timestamp: timestampFromRequest(req.body.timestamp, now),
        receivedAt: admin.firestore.FieldValue.serverTimestamp(), verified: true
      };
      transaction.create(eventRef, data);
      return data;
    });
    return res.json({ ok: true, employeeName: result.employeeName, status: result.status, duplicate: !!result.duplicate });
  } catch (error) {
    const status = error.message === "FINGERPRINT_NOT_REGISTERED" ? 404 : 500;
    return res.status(status).json({ ok: false, message: error.message });
  }
});

exports.resolveAttendance = onDocumentCreated({ document: "attendance/{eventId}", region: "asia-southeast1" }, async event => {
  const eventRef = event.data.ref;
  const rawEvent = event.data.data();
  if (rawEvent.resolutionStatus !== "PENDING" || rawEvent.type !== "SCAN") return null;

  return db.runTransaction(async transaction => {
    const currentEvent = await transaction.get(eventRef);
    if (!currentEvent.exists) return null;
    const scan = currentEvent.data();
    if (scan.resolutionStatus !== "PENDING" || scan.type !== "SCAN") return null;

    const employee = await getMappedActiveEmployee(transaction, scan.templateId);
    if (!employee) {
      transaction.update(eventRef, {
        type: "UNSCHEDULED", resolutionStatus: "UNSCHEDULED", status: "ABNORMAL",
        syncStatus: "SYNCED",
        scheduleDate: null, shiftId: null,
        receivedAt: admin.firestore.FieldValue.serverTimestamp(),
        resolvedAt: admin.firestore.FieldValue.serverTimestamp()
      });
      return null;
    }

    const scanMs = timestampToMs(scan.timestamp);
    const localDate = localDateForMs(scanMs, TIME_ZONE);
    const scheduleSnapshot = await transaction.get(db.collection("workSchedules")
      .where("employeeId", "==", employee.id).where("date", "in", [previousLocalDate(localDate), localDate]));
    const scheduleRows = scheduleSnapshot.docs.map(doc => ({ id: doc.id, ...doc.data() }));
    const scheduledShifts = scheduleRows.flatMap(row => {
      const shiftIds = Array.isArray(row.shiftIds)
        ? [...new Set(row.shiftIds.filter(shiftId => typeof shiftId === "string" && shiftId.length > 0))]
        : [];
      const resolvedShiftIds = shiftIds.length ? shiftIds : row.shiftId ? [row.shiftId] : [];
      return resolvedShiftIds.map(shiftId => ({
        row,
        shiftId,
        separateShiftSession: resolvedShiftIds.length > 1
      }));
    });
    const shiftSnapshots = await Promise.all(scheduledShifts.map(({ shiftId }) =>
      transaction.get(db.collection("shifts").doc(shiftId))
    ));
    const schedules = scheduledShifts.map(({ row, shiftId, separateShiftSession }, index) => {
      const shiftSnapshot = shiftSnapshots[index];
      return shiftSnapshot.exists ? {
        scheduleDate: row.date,
        shiftId,
        separateShiftSession,
        shift: { id: shiftId, ...shiftSnapshot.data() }
      } : null;
    }).filter(Boolean);

    const requestId = `${employee.id}_${localDate}`;
    const requestSnapshot = await transaction.get(db.collection("overtimeRequests").doc(requestId));
    const requestData = requestSnapshot.exists ? requestSnapshot.data() : null;
    const overtimeRequest = requestData &&
      requestData.employeeId === employee.id &&
      requestData.workDate === localDate &&
      ["PENDING", "APPROVED", "REJECTED"].includes(requestData.status)
      ? { id: requestSnapshot.id, ...requestData }
      : null;
    if (overtimeRequest) schedules.push(buildSupplementarySchedule(localDate, overtimeRequest));

    const selected = pickSchedule(scanMs, schedules);
    const sessionRef = selected && db.collection("attendanceSessions").doc(
      attendanceSessionId(employee.id, selected.scheduleDate, selected.shiftId, selected.separateShiftSession)
    );
    const sessionSnapshot = sessionRef ? await transaction.get(sessionRef) : null;
    const session = sessionSnapshot && sessionSnapshot.exists ? sessionSnapshot.data() : null;
    const result = resolveScan({
      scan: { timestampMs: scanMs, eventId: event.params.eventId }, schedules, session,
      latestAccepted: session && session.lastAcceptedAt ? { timestampMs: timestampToMs(session.lastAcceptedAt), eventId: session.lastAcceptedEventId } : null,
      requestStatus: overtimeRequest ? overtimeRequest.status : null
    });
    const overtimeRequestId = result.shiftId === SUPPLEMENTARY_SHIFT_ID ? requestId : null;

    transaction.update(eventRef, {
      employeeId: employee.id, employeeName: employee.fullName,
      type: result.type, resolutionStatus: result.resolutionStatus, status: result.status,
      syncStatus: "SYNCED",
      scheduleDate: result.scheduleDate, shiftId: result.shiftId, overtimeRequestId,
      receivedAt: admin.firestore.FieldValue.serverTimestamp(),
      resolvedAt: admin.firestore.FieldValue.serverTimestamp()
    });
    if (sessionRef && result.nextSession && ["CHECK_IN", "CHECK_OUT"].includes(result.type)) {
      transaction.set(sessionRef, {
        employeeId: employee.id, scheduleDate: result.scheduleDate, shiftId: result.shiftId,
        lastAcceptedEventId: result.nextSession.lastAcceptedEventId,
        lastAcceptedType: result.nextSession.lastAcceptedType,
        lastAcceptedAt: admin.firestore.Timestamp.fromMillis(result.nextSession.lastAcceptedAt),
        openCheckInAt: result.nextSession.openCheckInAt == null ? null : admin.firestore.Timestamp.fromMillis(result.nextSession.openCheckInAt),
        closed: result.nextSession.closed,
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      }, { merge: true });
    }
    return null;
  });
});

exports.reviewOffScheduleAttendance = onDocumentCreated({
  document: "offScheduleReviews/{reviewId}", region: "asia-southeast1", retry: true
}, async event => {
  const reviewRef = event.data.ref;
  const reviewId = event.params.reviewId;
  const auditId = `OFF_SCHEDULE_REVIEW_${crypto.createHash("sha256").update(reviewId).digest("hex")}`;
  const auditRef = db.collection("audit_logs").doc(auditId);

  try {
    return await db.runTransaction(async transaction => {
    const reviewSnapshot = await transaction.get(reviewRef);
    if (!reviewSnapshot.exists) return null;
    const review = reviewSnapshot.data();
    if (review.status !== "PENDING") return null;
    if (!validLocalDate(review.scheduleDate) || !["APPROVE", "REJECT"].includes(review.decision)) {
      throw new Error("INVALID_OFF_SCHEDULE_REVIEW");
    }
    const [reviewYear, reviewMonth, reviewDay] = review.scheduleDate.split("-").map(Number);
    if (review.decision === "APPROVE" && new Date(Date.UTC(reviewYear, reviewMonth - 1, reviewDay)).getUTCDay() === 0) {
      throw new Error("SUNDAY_OFF_SCHEDULE_APPROVAL");
    }
    if (![review.employeeId, review.employeeName, review.shiftId, review.shiftName,
      review.reviewerId, review.reviewerName].every(value => typeof value === "string" && value.trim())) {
      throw new Error("INCOMPLETE_OFF_SCHEDULE_REVIEW");
    }
    if (typeof review.reason !== "string" || review.reason.length > 1000) {
      throw new Error("INVALID_OFF_SCHEDULE_REVIEW_REASON");
    }

    const scheduleDate = review.scheduleDate;
    const dayStartMs = zonedDateTimeToMs(scheduleDate, "00:00");
    const [year, month, day] = scheduleDate.split("-").map(Number);
    const nextDate = new Date(Date.UTC(year, month - 1, day + 1)).toISOString().slice(0, 10);
    const dayEndMs = zonedDateTimeToMs(nextDate, "00:00");
    const scheduleRef = db.collection("workSchedules").doc(`${review.employeeId}_${scheduleDate}`);
    const dayAttendanceQuery = db.collection("attendance")
      .where("timestamp", ">=", admin.firestore.Timestamp.fromMillis(dayStartMs))
      .where("timestamp", "<", admin.firestore.Timestamp.fromMillis(dayEndMs));
    const [reviewerSnapshot, employeeSnapshot, shiftSnapshot, scheduleSnapshot,
      attendanceSnapshot, auditSnapshot] = await Promise.all([
      transaction.get(db.collection("users").doc(review.reviewerId)),
      transaction.get(db.collection("employees").doc(review.employeeId)),
      transaction.get(db.collection("shifts").doc(review.shiftId)),
      transaction.get(scheduleRef),
      transaction.get(dayAttendanceQuery),
      transaction.get(auditRef)
    ]);
    if (!reviewerSnapshot.exists || reviewerSnapshot.data().role !== "ADMIN" || reviewerSnapshot.data().active !== true) {
      throw new Error("REVIEWER_NOT_ACTIVE_ADMIN");
    }
    if (!employeeSnapshot.exists || employeeSnapshot.data().fullName !== review.employeeName) {
      throw new Error("EMPLOYEE_SNAPSHOT_MISMATCH");
    }
    const employee = employeeSnapshot.data();
    const selectedShift = shiftSnapshot.exists ? { id: shiftSnapshot.id, ...shiftSnapshot.data() } : null;
    if (!selectedShift || selectedShift.name !== review.shiftName ||
        !["MORNING", "EVENING"].includes(selectedShift.category) ||
        !validShiftTime(selectedShift.startTime) || !validShiftTime(selectedShift.endTime) ||
        selectedShift.startTime >= selectedShift.endTime) {
      throw new Error("SHIFT_SNAPSHOT_MISMATCH");
    }
    if (review.decision === "APPROVE" &&
        (selectedShift.active !== true || employee.active !== true || !dateWithinShiftValidity(scheduleDate, selectedShift))) {
      throw new Error("SHIFT_OR_EMPLOYEE_NOT_ASSIGNABLE");
    }
    if (auditSnapshot.exists) throw new Error("OFF_SCHEDULE_REVIEW_AUDIT_ALREADY_EXISTS");

    const existingSchedule = review.decision === "APPROVE" && scheduleSnapshot.exists
      ? scheduleSnapshot.data()
      : null;
    if (existingSchedule && (existingSchedule.employeeId !== review.employeeId || existingSchedule.date !== scheduleDate)) {
      throw new Error("WORK_SCHEDULE_IDENTITY_MISMATCH");
    }
    const existingShiftIds = existingSchedule
      ? (Array.isArray(existingSchedule.shiftIds) && existingSchedule.shiftIds.length
        ? existingSchedule.shiftIds
        : existingSchedule.shiftId ? [existingSchedule.shiftId] : [])
      : [];
    if (existingShiftIds.length > 2 || existingShiftIds.some(id => typeof id !== "string" || !id) ||
        new Set(existingShiftIds).size !== existingShiftIds.length) {
      throw new Error("INVALID_EXISTING_WORK_SCHEDULE_SHIFTS");
    }
    const existingShiftSnapshots = await Promise.all([...new Set([...existingShiftIds, review.shiftId])].map(id =>
      transaction.get(db.collection("shifts").doc(id))
    ));
    const shiftsById = new Map(existingShiftSnapshots.filter(snapshot => snapshot.exists).map(snapshot =>
      [snapshot.id, { id: snapshot.id, ...snapshot.data() }]
    ));
    for (const id of existingShiftIds) {
      const previousShift = shiftsById.get(id);
      if (!previousShift || !["MORNING", "EVENING"].includes(previousShift.category) ||
          !validShiftTime(previousShift.startTime) || !validShiftTime(previousShift.endTime) ||
          previousShift.startTime >= previousShift.endTime) {
        throw new Error("EXISTING_WORK_SCHEDULE_SHIFT_UNAVAILABLE");
      }
    }
    const nextShiftIds = [...new Set([...existingShiftIds, review.shiftId])];
    if (nextShiftIds.length > 2) throw new Error("WORK_SCHEDULE_ALREADY_HAS_TWO_SHIFTS");
    if (review.decision === "APPROVE" && new Set(nextShiftIds.map(id => shiftsById.get(id)?.category)).size !== nextShiftIds.length) {
      throw new Error("WORK_SCHEDULE_HAS_DUPLICATE_SHIFT_CATEGORY");
    }
    nextShiftIds.sort((left, right) =>
      shiftsById.get(left).startTime.localeCompare(shiftsById.get(right).startTime) || left.localeCompare(right)
    );
    const hasMultipleShifts = review.decision === "APPROVE" && nextShiftIds.length > 1;
    const separateShiftSession = hasMultipleShifts;
    const approvedSchedule = {
      scheduleDate,
      shiftId: review.shiftId,
      separateShiftSession,
      shift: selectedShift
    };

    const matchingScans = attendanceSnapshot.docs.filter(document => {
      const scan = document.data();
      if (scan.employeeId !== review.employeeId || scan.type !== "UNSCHEDULED" ||
          scan.resolutionStatus !== "UNSCHEDULED" || scan.status !== "ABNORMAL" || scan.offScheduleReviewStatus) return false;
      let scanMs;
      try { scanMs = timestampToMs(scan.timestamp); } catch (_) { return false; }
      if (localDateForMs(scanMs, TIME_ZONE) !== scheduleDate) return false;
      return pickSchedule(scanMs, [approvedSchedule])?.shiftId === review.shiftId;
    }).sort((left, right) => {
      const order = timestampToMs(left.data().timestamp) - timestampToMs(right.data().timestamp);
      return order || left.id.localeCompare(right.id);
    });
    if (matchingScans.length === 0) throw new Error("NO_UNRESOLVED_OFF_SCHEDULE_SCANS");
    if (matchingScans.length > 450) throw new Error("TOO_MANY_OFF_SCHEDULE_SCANS_FOR_ATOMIC_REVIEW");

    const sessionRef = db.collection("attendanceSessions").doc(
      attendanceSessionId(review.employeeId, scheduleDate, review.shiftId, separateShiftSession)
    );
    const migratingSingleShift = review.decision === "APPROVE" && existingShiftIds.length === 1 &&
      nextShiftIds.length === 2 && !existingShiftIds.includes(review.shiftId);
    const legacySessionRef = db.collection("attendanceSessions").doc(`${review.employeeId}_${scheduleDate}`);
    const previousShiftSessionRef = migratingSingleShift
      ? db.collection("attendanceSessions").doc(
        attendanceSessionId(review.employeeId, scheduleDate, existingShiftIds[0], true)
      )
      : null;
    const [sessionSnapshot, legacySessionSnapshot, previousShiftSessionSnapshot] = await Promise.all([
      review.decision === "APPROVE" ? transaction.get(sessionRef) : Promise.resolve(null),
      migratingSingleShift ? transaction.get(legacySessionRef) : Promise.resolve(null),
      previousShiftSessionRef ? transaction.get(previousShiftSessionRef) : Promise.resolve(null)
    ]);
    if (migratingSingleShift && legacySessionSnapshot.exists && previousShiftSessionSnapshot.exists) {
      throw new Error("AMBIGUOUS_EXISTING_ATTENDANCE_SESSION");
    }
    const currentSessionSnapshot = sessionSnapshot;
    const storedSession = currentSessionSnapshot && currentSessionSnapshot.exists
      ? currentSessionSnapshot.data()
      : null;
    const session = storedSession ? {
      lastAcceptedEventId: storedSession.lastAcceptedEventId || null,
      lastAcceptedType: storedSession.lastAcceptedType || null,
      lastAcceptedAt: storedSession.lastAcceptedAt == null ? null : timestampToMs(storedSession.lastAcceptedAt),
      openCheckInAt: storedSession.openCheckInAt == null ? null : timestampToMs(storedSession.openCheckInAt),
      closed: storedSession.closed === true
    } : null;
    let workingSession = session;
    let latestAccepted = session && session.lastAcceptedAt != null
      ? { timestampMs: session.lastAcceptedAt, eventId: session.lastAcceptedEventId }
      : null;
    let sessionChanged = false;
    const processedAt = admin.firestore.FieldValue.serverTimestamp();

    if (review.decision === "APPROVE") {
      transaction.set(scheduleRef, {
        ...(existingSchedule || {}),
        id: "",
        employeeId: review.employeeId,
        employeeName: employee.fullName,
        department: employee.department || existingSchedule?.department || "",
        date: scheduleDate,
        shiftIds: nextShiftIds,
        shiftId: nextShiftIds[0],
        shiftName: shiftsById.get(nextShiftIds[0]).name,
        overtimeHours: Number.isInteger(existingSchedule?.overtimeHours) ? existingSchedule.overtimeHours : 0,
        assignedBy: review.reviewerId,
        source: existingSchedule?.source || "ADMIN"
      });

      if (migratingSingleShift && legacySessionSnapshot.exists) {
        transaction.set(previousShiftSessionRef, {
          ...legacySessionSnapshot.data(),
          employeeId: review.employeeId,
          scheduleDate,
          shiftId: existingShiftIds[0],
          updatedAt: admin.firestore.FieldValue.serverTimestamp()
        });
        transaction.delete(legacySessionRef);
      }

      for (const document of matchingScans) {
        const row = document.data();
        const scanMs = timestampToMs(row.timestamp);
        const result = resolveScan({
          scan: { timestampMs: scanMs, eventId: document.id },
          schedules: [approvedSchedule], session: workingSession, latestAccepted, requestStatus: null
        });
        transaction.update(document.ref, {
          type: result.type,
          resolutionStatus: result.resolutionStatus,
          status: result.status,
          scheduleDate: result.scheduleDate,
          shiftId: result.shiftId,
          resolvedAt: processedAt,
          offScheduleReviewId: reviewId,
          offScheduleReviewStatus: "APPROVED",
          offScheduleReviewerId: review.reviewerId,
          offScheduleReviewerName: review.reviewerName,
          offScheduleReviewedAt: processedAt
        });
        if (result.nextSession && ["CHECK_IN", "CHECK_OUT"].includes(result.type)) {
          workingSession = result.nextSession;
          latestAccepted = { timestampMs: workingSession.lastAcceptedAt, eventId: workingSession.lastAcceptedEventId };
          sessionChanged = true;
        }
      }
      if (sessionChanged && workingSession) transaction.set(sessionRef,
        shiftSessionData(review.employeeId, scheduleDate, review.shiftId, workingSession), { merge: true });
    } else {
      for (const document of matchingScans) {
        transaction.update(document.ref, {
          offScheduleReviewId: reviewId,
          offScheduleReviewStatus: "REJECTED",
          offScheduleReviewerId: review.reviewerId,
          offScheduleReviewerName: review.reviewerName,
          offScheduleReviewedAt: processedAt
        });
      }
    }

    const finalStatus = review.decision === "APPROVE" ? "APPROVED" : "REJECTED";
    transaction.update(reviewRef, {
      status: finalStatus,
      reviewedAt: processedAt,
      matchedScanCount: matchingScans.length,
      auditLogId: auditId
    });
    transaction.create(auditRef, {
      actorId: review.reviewerId,
      actorName: review.reviewerName,
      action: "OFF_SCHEDULE_SCAN_REVIEW",
      targetType: "offScheduleReview",
      targetId: reviewId,
      employeeId: review.employeeId,
      scheduleDate,
      shiftId: review.shiftId,
      shiftName: review.shiftName,
      decision: review.decision,
      status: finalStatus,
      reason: review.reason,
      details: `${finalStatus}: ${matchingScans.length} scan(s) ${review.employeeName} ${scheduleDate} ${review.shiftName}`,
      createdAt: admin.firestore.FieldValue.serverTimestamp()
    });
    const notificationRef = db.collection("notifications").doc(notificationDocumentId("OFF_SCHEDULE_RESULT", reviewId));
    transaction.create(notificationRef, {
      type: "OFF_SCHEDULE_RESULT",
      title: review.decision === "APPROVE" ? "Ca ngoài lịch đã được duyệt" : "Ca ngoài lịch bị từ chối",
      body: `${scheduleDate} • ${review.shiftName}: ${review.reason}`,
      referenceId: reviewId,
      recipientEmployeeId: review.employeeId,
      audienceLabel: review.employeeName,
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
      read: false
    });
      return null;
    });
  } catch (error) {
    const failureReason = permanentOffScheduleFailureReasons[error.message];
    if (!failureReason) throw error;

    await db.runTransaction(async transaction => {
      const [currentReviewSnapshot, currentAuditSnapshot] = await Promise.all([
        transaction.get(reviewRef), transaction.get(auditRef)
      ]);
      if (!currentReviewSnapshot.exists || currentReviewSnapshot.data().status !== "PENDING") return null;
      const current = currentReviewSnapshot.data();
      const failedAt = admin.firestore.FieldValue.serverTimestamp();
      transaction.update(reviewRef, {
        status: "FAILED",
        failureReason: failureReason.slice(0, 240),
        failedAt
      });
      if (!currentAuditSnapshot.exists) transaction.create(auditRef, {
        actorId: String(current.reviewerId || ""),
        actorName: String(current.reviewerName || ""),
        action: "OFF_SCHEDULE_SCAN_REVIEW",
        targetType: "offScheduleReview",
        targetId: reviewId,
        employeeId: String(current.employeeId || ""),
        scheduleDate: String(current.scheduleDate || ""),
        shiftId: String(current.shiftId || ""),
        shiftName: String(current.shiftName || ""),
        decision: String(current.decision || ""),
        status: "FAILED",
        reason: typeof current.reason === "string" ? current.reason.slice(0, 1000) : "",
        details: failureReason.slice(0, 240),
        createdAt: failedAt
      });
      return null;
    });
    return null;
  }
});

exports.updateDeviceSnapshot = onRequest({ region: "asia-southeast1", secrets: [deviceApiKey] }, async (req, res) => {
  if (req.method !== "POST") return res.status(405).json({ ok: false, message: "POST only" });
  if (!authenticateDevice(req, res)) return;

  const snapshot = req.body || {};
  const deviceId = String(snapshot.deviceId || "");
  if (!validDeviceId(deviceId) || !validDeviceSnapshot(snapshot)) {
    return res.status(400).json({ ok: false, message: "Snapshot thiết bị không hợp lệ" });
  }

  const data = {
    deviceId,
    status: snapshot.status,
    lastHeartbeat: admin.firestore.FieldValue.serverTimestamp(),
    firmwareVersion: snapshot.firmwareVersion,
    fingerprintCount: snapshot.fingerprintCount == null ? null : snapshot.fingerprintCount,
    capacity: snapshot.capacity,
    pendingAttendanceCount: snapshot.pendingAttendanceCount,
    capabilities: snapshot.capabilities,
    wifiStatus: snapshot.wifiStatus,
    firebaseSyncStatus: snapshot.firebaseSyncStatus,
    sensorStatus: snapshot.sensorStatus,
    failedScanCount: snapshot.failedScanCount,
    lastError: snapshot.lastError
  };
  try {
    await db.collection("devices").doc(deviceId).set(data, { merge: true });
    return res.json({ ok: true });
  } catch (error) {
    console.error("Unable to update device snapshot", error);
    return res.status(500).json({ ok: false, message: "Không thể cập nhật trạng thái thiết bị" });
  }
});

async function resolveOvertimeRequestEvents(requestId, status) {
  if (!["APPROVED", "REJECTED"].includes(status)) return null;
  const attendanceQuery = db.collection("attendance").where("overtimeRequestId", "==", requestId);
  return db.runTransaction(async transaction => {
    const snapshot = await transaction.get(attendanceQuery);
    const events = snapshot.docs.slice().sort((left, right) => {
      const timestampOrder = timestampToMs(left.data().timestamp) - timestampToMs(right.data().timestamp);
      return timestampOrder || left.id.localeCompare(right.id);
    });
    if (!events.length) return null;

    const first = events[0].data();
    const scheduleDate = localDateForMs(timestampToMs(first.timestamp), TIME_ZONE);
    const requestSnapshot = await transaction.get(db.collection("overtimeRequests").doc(requestId));
    const request = requestSnapshot.exists ? requestSnapshot.data() : {};
    const schedule = buildSupplementarySchedule(scheduleDate, { ...request, id: requestId, status });
    const sessionRef = db.collection("attendanceSessions").doc(
      attendanceSessionId(first.employeeId, scheduleDate, SUPPLEMENTARY_SHIFT_ID)
    );
    const currentSessionSnapshot = await transaction.get(sessionRef);
    const currentSession = currentSessionSnapshot.exists ? currentSessionSnapshot.data() : null;
    let session = null;
    let latestAccepted = null;

    for (const event of events) {
      const row = event.data();
      const timestampMs = timestampToMs(row.timestamp);
      const result = resolveScan({
        scan: { timestampMs, eventId: event.id },
        schedules: [schedule],
        session,
        latestAccepted,
        requestStatus: status
      });
      transaction.update(event.ref, {
        type: result.type,
        resolutionStatus: result.resolutionStatus,
        status: result.status,
        scheduleDate: result.scheduleDate,
        shiftId: result.shiftId,
        overtimeRequestId: requestId,
        resolvedAt: admin.firestore.FieldValue.serverTimestamp()
      });
      if (result.nextSession && ["CHECK_IN", "CHECK_OUT"].includes(result.type)) {
        session = result.nextSession;
        latestAccepted = { timestampMs: result.nextSession.lastAcceptedAt, eventId: event.id };
      }
    }

    const currentLastAcceptedAt = currentSession && currentSession.lastAcceptedAt
      ? timestampToMs(currentSession.lastAcceptedAt)
      : null;
    if (session && (currentLastAcceptedAt == null || currentLastAcceptedAt <= session.lastAcceptedAt)) {
      transaction.set(sessionRef, {
        employeeId: first.employeeId,
        scheduleDate,
        shiftId: SUPPLEMENTARY_SHIFT_ID,
        lastAcceptedEventId: session.lastAcceptedEventId,
        lastAcceptedType: session.lastAcceptedType,
        lastAcceptedAt: admin.firestore.Timestamp.fromMillis(session.lastAcceptedAt),
        openCheckInAt: session.openCheckInAt == null ? null : admin.firestore.Timestamp.fromMillis(session.openCheckInAt),
        closed: session.closed,
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      }, { merge: true });
    }
    return null;
  });
}

exports.resolveOvertimeRequestAttendance = onDocumentUpdated({ document: "overtimeRequests/{requestId}", region: "asia-southeast1" }, async event => {
  const before = event?.data?.before?.data?.();
  const after = event?.data?.after?.data?.();
  if (!before || !after || before.status !== "PENDING" || before.status === after.status) return null;
  return resolveOvertimeRequestEvents(event.params.requestId, after.status);
});

exports.notifyOvertimeDecision = onDocumentUpdated({ document: "overtimeRequests/{requestId}", region: "asia-southeast1" }, async event => {
  const before = event?.data?.before?.data?.();
  const after = event?.data?.after?.data?.();
  if (!before || !after || before.status !== "PENDING" || !["APPROVED", "REJECTED"].includes(after.status)) return null;
  const reviewKey = after.reviewedAt ? String(timestampToMs(after.reviewedAt)) : String(event.id || event.time || "review");
  const outcome = after.status === "APPROVED" ? "đã được duyệt" : "bị từ chối";
  const reason = typeof after.rejectionReason === "string" && after.rejectionReason.trim()
    ? ` Lý do: ${after.rejectionReason.trim()}`
    : "";
  return createEmployeeNotification({
    kind: "OVERTIME_RESULT",
    sourceId: `${event.params.requestId}_${reviewKey}`,
    referenceId: event.params.requestId,
    employeeId: after.employeeId,
    employeeName: after.employeeName,
    title: `Đơn tăng ca ${outcome}`,
    body: `${after.workDate || ""} • 18:00–22:00.${reason}`
  });
});

exports.notifyWeeklyScheduleDecision = onDocumentUpdated({ document: "weeklyScheduleRequests/{requestId}", region: "asia-southeast1" }, async event => {
  const before = event?.data?.before?.data?.();
  const after = event?.data?.after?.data?.();
  if (!before || !after || before.status !== "PENDING" || !["APPROVED", "NEEDS_REVISION"].includes(after.status)) return null;
  const reviewKey = after.reviewedAt ? String(timestampToMs(after.reviewedAt)) : String(event.id || event.time || "review");
  const approved = after.status === "APPROVED";
  const note = typeof after.reviewNote === "string" && after.reviewNote.trim() ? ` ${after.reviewNote.trim()}` : "";
  return createEmployeeNotification({
    kind: "WEEKLY_SCHEDULE_RESULT",
    sourceId: `${event.params.requestId}_${reviewKey}`,
    referenceId: event.params.requestId,
    employeeId: after.employeeId,
    employeeName: after.employeeName,
    title: approved ? "Đăng ký lịch tuần đã được duyệt" : "Đăng ký lịch tuần cần chỉnh sửa",
    body: `Tuần bắt đầu ${after.weekStart || ""}.${note}`
  });
});

exports.notifyAttendanceAdjustment = onDocumentCreated({ document: "attendanceAdjustments/{adjustmentId}", region: "asia-southeast1" }, async event => {
  const adjustment = event?.data?.data?.();
  if (!adjustment) return null;
  return createEmployeeNotification({
    kind: "ATTENDANCE_ADJUSTMENT_RESULT",
    sourceId: event.params.adjustmentId,
    referenceId: event.params.adjustmentId,
    employeeId: adjustment.employeeId,
    employeeName: adjustment.employeeName,
    title: "Bảng công đã được điều chỉnh",
    body: `${adjustment.scheduleDate || ""} • ${adjustment.reason || "Admin đã cập nhật giờ công của bạn."}`
  });
});

function scheduleNotificationSnapshot(data) {
  return JSON.stringify({
    date: data.date || "",
    shiftId: data.shiftId || "",
    shiftIds: Array.isArray(data.shiftIds) ? data.shiftIds : [],
    shiftName: data.shiftName || "",
    overtimeHours: data.overtimeHours || 0,
    workedHoursOverride: data.workedHoursOverride == null ? null : data.workedHoursOverride
  });
}

exports.notifyAdminWorkScheduleChange = onDocumentWritten({ document: "workSchedules/{scheduleId}", region: "asia-southeast1" }, async event => {
  const before = event.data.before.exists ? event.data.before.data() : null;
  const after = event.data.after.exists ? event.data.after.data() : null;
  if (!after || !["ADMIN", "DEPARTMENT", "ADMIN_COPY"].includes(after.source)) return;
  if (before && scheduleNotificationSnapshot(before) === scheduleNotificationSnapshot(after)) return;

  const shiftNames = [after.shiftName || after.shiftId || "Ca làm"];
  const title = before ? "Lịch làm đã được cập nhật" : "Bạn đã được phân lịch làm";
  await createEmployeeNotification({
    kind: "WORK_SCHEDULE_CHANGED",
    sourceId: `${event.params.scheduleId}:${event.time}`,
    referenceId: event.params.scheduleId,
    employeeId: after.employeeId,
    employeeName: after.employeeName,
    title,
    body: `${after.date || "Ngày chưa rõ"}: ${shiftNames.join(" / ")}.`
  });
});

exports.getEnrollmentCommand = onRequest({ region: "asia-southeast1", secrets: [deviceApiKey] }, async (req, res) => {
  if (req.method !== "GET") return res.status(405).json({ ok: false, message: "GET only" });
  if (!authenticateDevice(req, res)) return;
  const deviceId = String(req.query.deviceId || "");
  if (!validDeviceId(deviceId)) return res.status(400).json({ ok: false, message: "Invalid deviceId" });
  try {
    const command = await db.runTransaction(async transaction => {
      const commandRef = db.collection("deviceCommands").doc(deviceId);
      const snapshot = await transaction.get(commandRef);
      if (!snapshot.exists) return null;
      const data = snapshot.data();
      if (data.deviceId !== deviceId || !["REQUESTED", "PROCESSING"].includes(data.status)) return null;
      const wasProcessing = data.status === "PROCESSING";
      if (!wasProcessing) {
        transaction.update(commandRef, {
          status: "PROCESSING",
          startedAt: admin.firestore.FieldValue.serverTimestamp()
        });
      }
      return { commandId: snapshot.id, wasProcessing, ...data, status: "PROCESSING" };
    });
    return res.json(command
      ? { ok: true, hasCommand: true, ...command }
      : { ok: true, hasCommand: false });
  } catch (error) {
    console.error("Unable to read device command", error);
    return res.status(500).json({ ok: false, message: "Không thể đọc lệnh thiết bị" });
  }
});

exports.completeEnrollment = onRequest({ region: "asia-southeast1", secrets: [deviceApiKey] }, async (req, res) => {
  if (req.method !== "POST") return res.status(405).json({ ok: false });
  if (!authenticateDevice(req, res)) return;
  const { deviceId, commandId, success, message } = req.body || {};
  if (!validDeviceId(deviceId) || !commandId || typeof success !== "boolean"
      || !validBoundedString(String(message || ""), 240)) {
    return res.status(400).json({ ok: false, message: "Thiếu hoặc sai dữ liệu hoàn tất lệnh" });
  }
  try {
    const result = await db.runTransaction(async transaction => {
      const commandRef = db.collection("deviceCommands").doc(String(commandId));
      const command = await transaction.get(commandRef);
      if (!command.exists) throw new Error("COMMAND_NOT_FOUND");
      const data = command.data();
      if (data.deviceId !== deviceId) throw new Error("COMMAND_DEVICE_MISMATCH");
      if (["COMPLETED", "FAILED"].includes(data.status)) return { duplicate: true };
      if (data.status !== "PROCESSING") throw new Error("COMMAND_NOT_PROCESSING");
      transaction.update(commandRef, {
        status: success ? "COMPLETED" : "FAILED",
        message: String(message || ""),
        completedAt: admin.firestore.FieldValue.serverTimestamp()
      });
      // The Android client still applies the mapping transaction after it
      // observes COMPLETED. Keep the legacy employee snapshot update for
      // enrollment, but never apply it to delete or test commands.
      if (success && data.type === "ENROLL_FINGERPRINT" && data.employeeId) transaction.update(db.collection("employees").doc(data.employeeId), {
        fingerprintTemplateId: Number(data.templateId)
      });
      return { duplicate: false };
    });
    return res.json({ ok: true, duplicate: result.duplicate });
  } catch (error) {
    const status = error.message === "COMMAND_NOT_FOUND" ? 404
      : ["COMMAND_DEVICE_MISMATCH", "COMMAND_NOT_PROCESSING"].includes(error.message) ? 409 : 500;
    return res.status(status).json({ ok: false, message: error.message });
  }
});

exports.notifyAttendance = onDocumentUpdated({ document: "attendance/{eventId}", region: "asia-southeast1" }, async event => {
  try {
    const before = event?.data?.before?.data?.();
    const after = event?.data?.after?.data?.();
    if (!shouldNotifyAttendance(before, after)) return null;
    return await admin.messaging().send({
      topic: "attendance-admins",
      notification: { title: "Chấm công thành công", body: `${after.employeeName || ""} • ${after.type || ""}` },
      data: { eventId: String(event.params.eventId), scheduleDate: String(after.scheduleDate || ""), type: String(after.type || "") }
    });
  } catch (error) {
    console.error("Unable to send attendance notification", error);
    return null;
  }
});
