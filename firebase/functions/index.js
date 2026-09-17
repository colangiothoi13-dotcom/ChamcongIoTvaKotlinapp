const { onRequest } = require("firebase-functions/v2/https");
const { onDocumentCreated, onDocumentUpdated } = require("firebase-functions/v2/firestore");
const { defineSecret } = require("firebase-functions/params");
const admin = require("firebase-admin");
const crypto = require("crypto");
const { TIME_ZONE, localDateForMs, pickSchedule, resolveMappedEmployee, resolveScan } = require("./attendanceResolver");
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

exports.recordAttendance = onRequest({ region: "asia-southeast1", secrets: [deviceApiKey] }, async (req, res) => {
  if (req.method !== "POST") return res.status(405).json({ ok: false, message: "POST only" });
  if (!safeEqual(req.get("x-device-key"), deviceApiKey.value())) return res.status(401).json({ ok: false, message: "Thiết bị không hợp lệ" });

  const { deviceId, templateId, confidence, eventId } = req.body || {};
  if (!deviceId || !Number.isInteger(templateId) || !eventId) return res.status(400).json({ ok: false, message: "Thiếu dữ liệu" });
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
    const shiftSnapshots = await Promise.all(scheduleRows.map(row => transaction.get(db.collection("shifts").doc(row.shiftId))));
    const schedules = scheduleRows.map((row, index) => {
      const shift = shiftSnapshots[index];
      return shift.exists ? { scheduleDate: row.date, shiftId: row.shiftId, shift: { id: row.shiftId, ...shift.data() } } : null;
    }).filter(Boolean);

    const selected = pickSchedule(scanMs, schedules);
    const sessionRef = selected && db.collection("attendanceSessions").doc(`${employee.id}_${selected.scheduleDate}`);
    const sessionSnapshot = sessionRef ? await transaction.get(sessionRef) : null;
    const session = sessionSnapshot && sessionSnapshot.exists ? sessionSnapshot.data() : null;
    const result = resolveScan({
      scan: { timestampMs: scanMs, eventId: event.params.eventId }, schedules, session,
      latestAccepted: session && session.lastAcceptedAt ? { timestampMs: timestampToMs(session.lastAcceptedAt), eventId: session.lastAcceptedEventId } : null
    });

    transaction.update(eventRef, {
      employeeId: employee.id, employeeName: employee.fullName,
      type: result.type, resolutionStatus: result.resolutionStatus, status: result.status,
      scheduleDate: result.scheduleDate, shiftId: result.shiftId,
      receivedAt: admin.firestore.FieldValue.serverTimestamp(),
      resolvedAt: admin.firestore.FieldValue.serverTimestamp()
    });
    if (result.resolutionStatus === "ACCEPTED") {
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

exports.getEnrollmentCommand = onRequest({ region: "asia-southeast1", secrets: [deviceApiKey] }, async (req, res) => {
  if (!safeEqual(req.get("x-device-key"), deviceApiKey.value())) return res.status(401).json({ ok: false });
  const deviceId = String(req.query.deviceId || "");
  if (!deviceId) return res.status(400).json({ ok: false, message: "Missing deviceId" });
  const commands = await db.collection("deviceCommands")
    .where("deviceId", "==", deviceId).where("status", "==", "REQUESTED").limit(1).get();
  if (commands.empty) return res.json({ ok: true, hasCommand: false });
  const command = commands.docs[0];
  await command.ref.update({ status: "PROCESSING", startedAt: admin.firestore.FieldValue.serverTimestamp() });
  return res.json({ ok: true, hasCommand: true, commandId: command.id, ...command.data() });
});

exports.completeEnrollment = onRequest({ region: "asia-southeast1", secrets: [deviceApiKey] }, async (req, res) => {
  if (req.method !== "POST") return res.status(405).json({ ok: false });
  if (!safeEqual(req.get("x-device-key"), deviceApiKey.value())) return res.status(401).json({ ok: false });
  const { commandId, success, message } = req.body || {};
  if (!commandId) return res.status(400).json({ ok: false, message: "Missing commandId" });
  try {
    await db.runTransaction(async transaction => {
      const commandRef = db.collection("deviceCommands").doc(String(commandId));
      const command = await transaction.get(commandRef);
      if (!command.exists) throw new Error("COMMAND_NOT_FOUND");
      const data = command.data();
      transaction.update(commandRef, {
        status: success ? "COMPLETED" : "FAILED",
        message: String(message || ""),
        completedAt: admin.firestore.FieldValue.serverTimestamp()
      });
      if (success) transaction.update(db.collection("employees").doc(data.employeeId), {
        fingerprintTemplateId: Number(data.templateId)
      });
    });
    return res.json({ ok: true });
  } catch (error) {
    return res.status(500).json({ ok: false, message: error.message });
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
