const admin = require("firebase-admin");

const OLD_SHIFT_ID = "SUPPLEMENTARY_1730_2030";
const NEW_SHIFT_ID = "SUPPLEMENTARY_1800_2200";
const OLD_START_TIME = "17:30";
const OLD_END_TIME = "20:30";
const NEW_START_TIME = "18:00";
const NEW_END_TIME = "22:00";

admin.initializeApp();
const db = admin.firestore();

async function commitOperations(operations, apply) {
  if (!apply || operations.length === 0) return;
  for (let index = 0; index < operations.length; index += 450) {
    const batch = db.batch();
    operations.slice(index, index + 450).forEach(operation => operation(batch));
    await batch.commit();
  }
}

async function main() {
  const apply = process.argv.includes("--apply");
  const operations = [];
  const report = {
    shiftTemplate: 0,
    schedules: 0,
    attendance: 0,
    sessions: 0,
    requests: 0
  };

  const oldShift = await db.collection("shifts").doc(OLD_SHIFT_ID).get();
  if (oldShift.exists) {
    report.shiftTemplate = 1;
    const oldData = oldShift.data();
    operations.push(batch => batch.set(db.collection("shifts").doc(NEW_SHIFT_ID), {
      ...oldData,
      id: NEW_SHIFT_ID,
      startTime: NEW_START_TIME,
      endTime: NEW_END_TIME,
      category: "SUPPLEMENTARY",
      countsOvertime: true
    }, { merge: true }));
  }

  const [scheduleSnapshot, scheduleArraySnapshot, attendanceSnapshot, sessionSnapshot, requestSnapshot] = await Promise.all([
    db.collection("workSchedules").where("shiftId", "==", OLD_SHIFT_ID).get(),
    db.collection("workSchedules").where("shiftIds", "array-contains", OLD_SHIFT_ID).get(),
    db.collection("attendance").where("shiftId", "==", OLD_SHIFT_ID).get(),
    db.collection("attendanceSessions").get(),
    db.collection("overtimeRequests").where("startTime", "==", OLD_START_TIME).get()
  ]);

  const scheduleDocuments = new Map([
    ...scheduleSnapshot.docs,
    ...scheduleArraySnapshot.docs
  ].map(document => [document.id, document]));
  scheduleDocuments.forEach(document => {
    report.schedules += 1;
    const schedule = document.data();
    const previousShiftIds = Array.isArray(schedule.shiftIds) && schedule.shiftIds.length
      ? schedule.shiftIds
      : [schedule.shiftId];
    const updates = {
      shiftIds: previousShiftIds.map(id => id === OLD_SHIFT_ID ? NEW_SHIFT_ID : id)
    };
    if (schedule.shiftId === OLD_SHIFT_ID) updates.shiftId = NEW_SHIFT_ID;
    operations.push(batch => batch.update(document.ref, updates));
  });

  attendanceSnapshot.docs.forEach(document => {
    report.attendance += 1;
    operations.push(batch => batch.update(document.ref, { shiftId: NEW_SHIFT_ID }));
  });

  sessionSnapshot.docs
    .filter(document => document.id.includes(OLD_SHIFT_ID))
    .forEach(document => {
      report.sessions += 1;
      const targetId = document.id.replace(OLD_SHIFT_ID, NEW_SHIFT_ID);
      operations.push(batch => batch.set(db.collection("attendanceSessions").doc(targetId), {
        ...document.data(),
        shiftId: NEW_SHIFT_ID
      }, { merge: true }));
    });

  requestSnapshot.docs
    .filter(document => document.data().endTime === OLD_END_TIME)
    .forEach(document => {
      report.requests += 1;
      operations.push(batch => batch.update(document.ref, {
        startTime: NEW_START_TIME,
        endTime: NEW_END_TIME
      }));
    });

  console.log(JSON.stringify({ mode: apply ? "apply" : "dry-run", report }, null, 2));
  if (!apply) {
    console.log("Dry-run only. Re-run with --apply after reviewing the counts.");
    return;
  }
  await commitOperations(operations, true);
  console.log("Migration complete. Legacy documents are retained for audit; new code uses the canonical ID/window.");
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
