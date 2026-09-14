const { onRequest } = require("firebase-functions/v2/https");
const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { defineSecret } = require("firebase-functions/params");
const admin = require("firebase-admin");
const crypto = require("crypto");

admin.initializeApp();
const db = admin.firestore();
const deviceApiKey = defineSecret("DEVICE_API_KEY");

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

      const matches = await db.collection("employees").where("fingerprintTemplateId", "==", templateId).where("active", "==", true).limit(1).get();
      if (matches.empty) throw new Error("FINGERPRINT_NOT_REGISTERED");
      const employee = matches.docs[0];
      const now = admin.firestore.Timestamp.now();
      const localHour = Number(new Intl.DateTimeFormat("en", { hour: "2-digit", hour12: false, timeZone: "Asia/Ho_Chi_Minh" }).format(now.toDate()));
      const data = {
        employeeId: employee.id,
        employeeName: employee.get("fullName"),
        deviceId, templateId, confidence: Number(confidence || 0),
        type: localHour < 12 ? "CHECK_IN" : "CHECK_OUT",
        status: localHour > 8 && localHour < 12 ? "LATE" : "NORMAL",
        timestamp: now, verified: true
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

exports.notifyAttendance = onDocumentCreated({ document: "attendance/{eventId}", region: "asia-southeast1" }, async event => {
  const data = event.data.data();
  return admin.messaging().send({
    topic: "attendance-admins",
    notification: { title: "Chấm công thành công", body: `${data.employeeName} • ${data.type}` },
    data: { eventId: event.params.eventId }
  });
});
