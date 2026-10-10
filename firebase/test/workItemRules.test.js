// Local emulator only: lifecycle, privilege boundaries, immutable history and concurrent writers.
const { before, test } = require("node:test");
const assert = require("node:assert/strict");
const project = "demo-work-item-rules";
const host = process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8080";
assert.match(host, /^(localhost|127\.0\.0\.1|\[::1\]):\d+$/);
const documents = `http://${host}/v1/projects/${project}/databases/(default)/documents`;
const name = path => `projects/${project}/databases/(default)/documents/${path}`;
const suffix = `${Date.now()}-${process.pid}`;
const encode = value => Buffer.from(JSON.stringify(value)).toString("base64url");
function actor(label, provider = "password") {
  const uid = `${label}-${suffix}`;
  const now = Math.floor(Date.now() / 1000);
  return { uid, token: `${encode({ alg: "none", typ: "JWT" })}.${encode({
    iss: `https://securetoken.google.com/${project}`, aud: project, sub: uid, user_id: uid,
    iat: now, exp: now + 3600, auth_time: now,
    firebase: { sign_in_provider: provider, identities: {} }
  })}.` };
}
const admin = actor("admin");
const employee = { ...actor("employee"), employeeId: `e1-${suffix}` };
const other = { ...actor("other"), employeeId: `e2-${suffix}` };
const device = actor("device", "anonymous");
const field = value => value === null ? { nullValue: null }
  : Buffer.isBuffer(value) ? { bytesValue: value.toString("base64") }
  : value instanceof Date ? { timestampValue: value.firestoreIso || value.toISOString() }
  : Array.isArray(value) ? { arrayValue: { values: value.map(field) } }
  : typeof value === "boolean" ? { booleanValue: value }
  : typeof value === "number" ? { integerValue: String(value) }
  : typeof value === "object" ? { mapValue: { fields: fields(value) } }
  : { stringValue: value };
const fields = value => Object.fromEntries(Object.entries(value).map(([key, item]) => [key, field(item)]));
const value = raw => raw.nullValue !== undefined ? null
  : raw.timestampValue !== undefined ? Object.assign(new Date(raw.timestampValue), { firestoreIso: raw.timestampValue })
  : raw.integerValue !== undefined ? Number(raw.integerValue)
  : raw.booleanValue !== undefined ? raw.booleanValue
  : raw.bytesValue !== undefined ? Buffer.from(raw.bytesValue, "base64")
  : raw.arrayValue ? (raw.arrayValue.values || []).map(value)
  : raw.mapValue ? Object.fromEntries(Object.entries(raw.mapValue.fields).map(([k, v]) => [k, value(v)]))
  : raw.stringValue;
async function request(path, method, auth, body) {
  return fetch(`${documents}${path}`, { method,
    headers: { Authorization: `Bearer ${auth}`, "Content-Type": "application/json" },
    ...(body ? { body: JSON.stringify(body) } : {}) });
}
async function expectStatus(response, expected) {
  assert.equal(response.status, expected, await response.text());
}
async function seed(path, data) {
  await expectStatus(await request(`/${path}`, "PATCH", "owner", { fields: fields(data) }), 200);
}
async function read(id, caller = admin) {
  const response = await request(`/workItems/${id}`, "GET", caller.token);
  assert.equal(response.status, 200, await response.clone().text());
  return Object.fromEntries(Object.entries((await response.json()).fields).map(([k, v]) => [k, value(v)]));
}
function draft(overrides = {}) {
  return { title: "Kiểm tra máy A", description: "Kiểm tra thiết bị", requiredResult: "Gửi biên bản",
    assignedById: admin.uid, assignedByName: admin.uid, assigneeId: employee.employeeId,
    assigneeName: employee.uid, startAt: new Date("2026-10-08T01:00:00Z"),
    deadline: new Date("2026-10-08T09:00:00Z"), priority: "NORMAL", status: "ASSIGNED",
    completedAt: null, resultReport: "", managerFeedback: "", relatedScheduleId: null, relatedShiftId: null,
    relatedScheduleDate: null, version: 1, reworkCount: 0, lastUpdatedById: admin.uid,
    lastUpdatedByName: admin.uid, ...overrides };
}
function writeVersion(id, before, after, action, caller, options = {}) {
  const version = after.version;
  const data = { ...after };
  delete data.updatedAt;
  if (!before) delete data.createdAt;
  if (action === "APPROVED") delete data.completedAt;
  const transformed = ["updatedAt", ...(!before ? ["createdAt"] : []), ...(action === "APPROVED" ? ["completedAt"] : [])];
  const update = {
    update: { name: name(`workItems/${id}`), fields: fields(data) },
    currentDocument: { exists: !!before },
    updateTransforms: transformed.map(fieldPath => ({ fieldPath, setToServerValue: "REQUEST_TIME" }))
  };
  const history = {
    update: { name: name(`workItems/${id}/history/${version}`), fields: fields({ workItemId: id, version,
      action, actorId: caller.uid, actorName: caller.uid, before, after: data }) },
    currentDocument: { exists: false },
    updateTransforms: ["createdAt", ...transformed.map(path => `after.${path}`)]
      .map(fieldPath => ({ fieldPath, setToServerValue: "REQUEST_TIME" }))
  };
  if (options.forgeBefore) history.update.fields.before = field(options.forgeBefore);
  return [update, ...(!options.omitHistory ? [history] : [])];
}
async function create(id, overrides = {}, caller = admin, options = {}) {
  return request(":commit", "POST", caller.token, { writes: writeVersion(id, null,
    draft({ ...overrides, lastUpdatedById: caller.uid, lastUpdatedByName: caller.uid }), "CREATED", caller, options) });
}
async function mutate(id, before, changes, action, caller = employee, options = {}) {
  const after = { ...before, ...changes, version: options.version ?? before.version + 1,
    lastUpdatedById: caller.uid, lastUpdatedByName: caller.uid };
  return request(":commit", "POST", caller.token, { writes: writeVersion(id, before, after, action, caller, options) });
}
async function ready(id, overrides = {}) {
  await expectStatus(await create(id, overrides), 200);
  await expectStatus(await mutate(id, await read(id), { status: "IN_PROGRESS" }, "STARTED"), 200);
}
async function pending(id) {
  await ready(id);
  await expectStatus(await mutate(id, await read(id), { status: "PENDING_REVIEW", resultReport: "Biên bản đã ký" }, "RESULT_SUBMITTED"), 200);
}

before(async () => {
  await seed(`users/${admin.uid}`, { role: "ADMIN", active: true });
  for (const who of [employee, other]) {
    await seed(`users/${who.uid}`, { role: "EMPLOYEE", active: true, employeeId: who.employeeId });
    await seed(`employees/${who.employeeId}`, { fullName: who.uid, active: true });
  }
  await seed(`workSchedules/${employee.employeeId}_2026-10-08`, {
    employeeId: employee.employeeId, shiftId: "morning", shiftIds: ["morning", "evening"], date: "2026-10-08" });
});

test("create requires manager, valid fields and matching immutable history", async () => {
  const id = `create-${suffix}`;
  await expectStatus(await create(id), 200);
  for (const [label, overrides, caller, options] of [
    ["employee", {}, employee, {}], ["device", {}, device, {}],
    ["missing-result", { requiredResult: " " }, admin, {}],
    ["bad-deadline", { deadline: new Date("2026-10-07T00:00:00Z") }, admin, {}],
    ["forged-author", { assignedById: employee.uid }, admin, {}],
    ["no-history", {}, admin, { omitHistory: true }],
    ["wrong-name", { assigneeName: "Forged" }, admin, {}]
  ]) await expectStatus(await create(`${id}-${label}`, overrides, caller, options), 403);
  await expectStatus(await request(`/workItems/${id}/history/1`, "PATCH", admin.token,
    { fields: fields({ action: "APPROVED" }) }), 403);
  await expectStatus(await request(`/workItems/${id}/history/1`, "DELETE", admin.token), 403);
  await expectStatus(await request(`/workItems/${id}`, "DELETE", admin.token), 403);
});

test("employee reads only their own work and completes only through manager review", async () => {
  const id = `flow-${suffix}`;
  await expectStatus(await create(id), 200);
  await expectStatus(await request(`/workItems/${id}`, "GET", other.token), 403);
  await expectStatus(await request(`/workItems/${id}`, "GET", device.token), 403);
  let current = await read(id, employee);
  await expectStatus(await mutate(id, current, { status: "COMPLETED", completedAt: new Date(), resultReport: "Done" }, "APPROVED"), 403);
  await expectStatus(await mutate(id, current, { status: "IN_PROGRESS" }, "STARTED", other), 403);
  await expectStatus(await mutate(id, current, { status: "IN_PROGRESS" }, "STARTED", admin), 403);
  await expectStatus(await mutate(id, current, { status: "IN_PROGRESS" }, "STARTED"), 200);
  current = await read(id);
  await expectStatus(await mutate(id, current, { status: "PENDING_REVIEW", resultReport: " " }, "RESULT_SUBMITTED"), 403);
  await expectStatus(await mutate(id, current, { status: "PENDING_REVIEW", resultReport: "Biên bản đạt yêu cầu" }, "RESULT_SUBMITTED"), 200);
  current = await read(id);
  await expectStatus(await mutate(id, current, { resultReport: "Changed after submitting" }, "PROGRESS_UPDATED"), 403);
  await expectStatus(await mutate(id, current, { status: "COMPLETED", managerFeedback: "Đạt" }, "APPROVED", admin), 200);
  current = await read(id);
  assert.equal(current.status, "COMPLETED");
  assert(current.completedAt instanceof Date);
  await expectStatus(await mutate(id, current, { title: "Rewrite approved work" }, "EDITED", admin), 403);
});

test("rework needs reason and preserves result evidence, then allows resubmission", async () => {
  const id = `rework-${suffix}`;
  await pending(id);
  let current = await read(id);
  await expectStatus(await mutate(id, current, { status: "IN_PROGRESS", reworkCount: 1 }, "REWORK_REQUESTED", admin), 403);
  await expectStatus(await mutate(id, current,
    { status: "IN_PROGRESS", reworkCount: 1, managerFeedback: "Bổ sung ảnh" }, "REWORK_REQUESTED", admin), 200);
  current = await read(id);
  assert.equal(current.resultReport, "Biên bản đã ký");
  assert.equal(current.reworkCount, 1);
  await expectStatus(await mutate(id, current, { status: "PENDING_REVIEW", resultReport: "Đã bổ sung ảnh" }, "RESULT_SUBMITTED"), 200);
});

test("manager edits audit deadline and reassignment, invalidating pending approval", async () => {
  const id = `edit-${suffix}`;
  await pending(id);
  // Existing assignment had two rework cycles; the next assignee must not inherit them.
  for (let cycle = 1; cycle <= 2; cycle++) {
    await expectStatus(await mutate(id, await read(id), { status: "IN_PROGRESS", reworkCount: cycle,
      managerFeedback: "B? sung k?t qu?" }, "REWORK_REQUESTED", admin), 200);
    await expectStatus(await mutate(id, await read(id), { status: "PENDING_REVIEW", resultReport: "?? b? sung" }, "RESULT_SUBMITTED"), 200);
  }
  let current = await read(id);
  assert.equal(current.reworkCount, 2);
  const deadline = new Date("2026-10-09T09:00:00Z");
  await expectStatus(await mutate(id, current, { deadline }, "EDITED", employee), 403);
  await expectStatus(await mutate(id, current, { deadline }, "EDITED", admin), 403);
  await expectStatus(await mutate(id, current, { deadline, status: "IN_PROGRESS" }, "EDITED", admin), 200);
  current = await read(id);
  await expectStatus(await mutate(id, current,
    { assigneeId: other.employeeId, assigneeName: other.uid, status: "ASSIGNED" }, "EDITED", admin), 403);
  await expectStatus(await mutate(id, current,
    { assigneeId: other.employeeId, assigneeName: other.uid, status: "ASSIGNED", resultReport: "", managerFeedback: "" }, "EDITED", admin), 403);
  await expectStatus(await mutate(id, current,
    { assigneeId: other.employeeId, assigneeName: other.uid, status: "ASSIGNED", resultReport: "", managerFeedback: "", reworkCount: 0 }, "EDITED", admin), 200);
  const reassigned = await read(id);
  assert.equal(reassigned.reworkCount, 0);
  const reassignmentHistory = await request(`/workItems/${id}/history/${reassigned.version}`, "GET", admin.token);
  assert.equal(reassignmentHistory.status, 200);
  const historical = value({ mapValue: { fields: (await reassignmentHistory.json()).fields } });
  assert.equal(historical.before.reworkCount, 2);
  assert.equal(historical.after.reworkCount, 0);
  await expectStatus(await request(`/workItems/${id}`, "GET", employee.token), 403);
  await expectStatus(await request(`/workItems/${id}`, "GET", other.token), 200);
});

test("shift links must belong to assignee and choose a shift actually in that schedule", async () => {
  const link = { relatedScheduleId: `${employee.employeeId}_2026-10-08`, relatedShiftId: "evening", relatedScheduleDate: "2026-10-08" };
  await expectStatus(await create(`shift-${suffix}`, link), 200);
  await expectStatus(await create(`shift-other-${suffix}`, { ...link, assigneeId: other.employeeId, assigneeName: other.uid }), 403);
  await expectStatus(await create(`shift-missing-${suffix}`, { ...link, relatedShiftId: "missing" }), 403);
  await expectStatus(await create(`shift-half-${suffix}`, { relatedScheduleId: link.relatedScheduleId }), 403);
  await expectStatus(await create(`shift-date-${suffix}`, { ...link, relatedScheduleDate: "2026-10-09" }), 403);
});

test("two simultaneous writes of the same version commit once and retain both snapshots", async () => {
  const id = `concurrency-${suffix}`;
  await ready(id);
  const current = await read(id);
  const responses = await Promise.all([
    mutate(id, current, { resultReport: "Writer A" }, "PROGRESS_UPDATED"),
    mutate(id, current, { resultReport: "Writer B" }, "PROGRESS_UPDATED")
  ]);
  const statuses = responses.map(response => response.status).sort();
  assert.deepEqual(statuses, [200, 403], await Promise.all(responses.map(response => response.text())));
  const result = await read(id);
  assert.equal(result.version, current.version + 1);
  const historyResponse = await request(`/workItems/${id}/history/${result.version}`, "GET", employee.token);
  assert.equal(historyResponse.status, 200, await historyResponse.clone().text());
  const history = value({ mapValue: { fields: (await historyResponse.json()).fields } });
  assert.equal(history.before.resultReport, current.resultReport);
  assert.equal(history.after.resultReport, result.resultReport);
  await expectStatus(await mutate(id, result, { resultReport: "No history" }, "PROGRESS_UPDATED", employee, { omitHistory: true }), 403);
  await expectStatus(await mutate(id, result, { resultReport: "Wrong before" }, "PROGRESS_UPDATED", employee,
    { forgeBefore: { ...result, resultReport: "Forged" } }), 403);
});


test("employee observer queries are scoped and history queries follow current assignee", async () => {
  const id = `query-${suffix}`;
  await expectStatus(await create(id), 200);
  const query = where => ({ structuredQuery: {
    from: [{ collectionId: "workItems" }], orderBy: [{ field: { fieldPath: "deadline" }, direction: "ASCENDING" }],
    ...(where ? { where: { fieldFilter: { field: { fieldPath: "assigneeId" }, op: "EQUAL", value: field(where) } } } : {})
  } });
  await expectStatus(await request(":runQuery", "POST", employee.token, query(employee.employeeId)), 200);
  await expectStatus(await request(":runQuery", "POST", employee.token, query(other.employeeId)), 403);
  await expectStatus(await request(":runQuery", "POST", employee.token, query()), 403);
  await expectStatus(await request(":runQuery", "POST", admin.token, query()), 200);
  const history = { structuredQuery: { from: [{ collectionId: "history" }],
    orderBy: [{ field: { fieldPath: "version" }, direction: "DESCENDING" }] } };
  await expectStatus(await request(`/workItems/${id}:runQuery`, "POST", employee.token, history), 200);
  await expectStatus(await request(`/workItems/${id}:runQuery`, "POST", other.token, history), 403);
});

function attachmentWriteBatch(workId, attachmentId, bytes, caller = employee, overrides = {}) {
  const chunkSize = 512 * 1024;
  const metadata = { id: attachmentId, fileName: "ket-qua.png", mimeType: "image/png",
    sizeBytes: bytes.length, sha256: require("node:crypto").createHash("sha256").update(bytes).digest("hex"),
    chunkCount: Math.ceil(bytes.length / chunkSize), uploadedById: caller.uid,
    assigneeId: caller.employeeId, ...overrides };
  const write = (path, data) => ({ update: { name: name(path), fields: fields(data) },
    currentDocument: { exists: false },
    updateTransforms: [{ fieldPath: "createdAt", setToServerValue: "REQUEST_TIME" }] });
  return [write(`workItems/${workId}/attachments/${attachmentId}`, metadata),
    ...Array.from({ length: Math.ceil(bytes.length / chunkSize) }, (_, index) =>
      write(`workItems/${workId}/attachments/${attachmentId}/chunks/${index}`,
        { index, data: bytes.subarray(index * chunkSize, Math.min(bytes.length, (index + 1) * chunkSize)) }))];
}
async function upload(workId, attachmentId, bytes = Buffer.from("evidence"), caller = employee, overrides = {}, transform = x => x) {
  return request(":commit", "POST", caller.token,
    { writes: transform(attachmentWriteBatch(workId, attachmentId, bytes, caller, overrides)) });
}
async function attachment(workId, attachmentId, caller = employee) {
  const response = await request(`/workItems/${workId}/attachments/${attachmentId}`, "GET", caller.token);
  assert.equal(response.status, 200, await response.clone().text());
  return value({ mapValue: { fields: (await response.json()).fields } });
}

test("attachments upload atomically through ten chunks at the full five MiB boundary", async () => {
  const id = `attachment-boundary-${suffix}`;
  await ready(id);
  await expectStatus(await upload(id, "five-megabytes", Buffer.alloc(5 * 1024 * 1024, 7)), 200);
  const saved = await attachment(id, "five-megabytes");
  assert.equal(saved.sizeBytes, 5 * 1024 * 1024);
  assert.equal(saved.chunkCount, 10);
  assert(saved.createdAt instanceof Date);
  // A single omitted interior/final chunk invalidates the entire metadata + binary batch.
  for (const [label, transform] of [
    ["metadata-only", writes => writes.slice(0, 1)],
    ["missing-first", writes => writes.filter((_, index) => index !== 1)],
    ["missing-middle", writes => writes.filter((_, index) => index !== 5)],
    ["missing-last", writes => writes.slice(0, -1)],
    ["bad-index", writes => { writes[1].update.fields.index = field(1); return writes; }],
    ["wrong-bytes", writes => { writes[1].update.fields.data = field(Buffer.from("short")); return writes; }]
  ]) await expectStatus(await upload(id, label, Buffer.alloc(5 * 1024 * 1024), employee, {}, transform), 403);
  await expectStatus(await request(`/workItems/${id}/attachments/metadata-only`, "GET", employee.token), 404);
  await expectStatus(await upload(id, "too-big", Buffer.alloc(5 * 1024 * 1024 + 1)), 403);
});

test("attachment upload checks actor, active assignment, exact metadata and task state", async () => {
  const id = `attachment-permissions-${suffix}`;
  await expectStatus(await create(id), 200);
  await expectStatus(await upload(id, "not-started"), 403);
  await expectStatus(await mutate(id, await read(id), { status: "IN_PROGRESS" }, "STARTED"), 200);
  for (const who of [admin, other, device]) {
    await expectStatus(await upload(id, `wrong-actor-${who.uid}`, Buffer.from("abc"), who,
      { assigneeId: employee.employeeId }), 403);
  }
  for (const [label, changes] of [
    ["forged-owner", { uploadedById: other.uid }], ["forged-assignee", { assigneeId: other.employeeId }],
    ["path-name", { fileName: "../report.pdf" }], ["windows-path", { fileName: "folder\\report.pdf" }],
    ["multiline-path", { fileName: "a\nb/report.pdf" }],
    ["bad-sha", { sha256: "abc" }], ["bad-mime", { mimeType: "image/png\r\nother" }],
    ["multiline-mime", { mimeType: "image\npng\nother" }],
    ["bad-size", { sizeBytes: 0 }], ["bad-count", { chunkCount: 2 }],
    ["extra-field", { externalUrl: "https://example.org/forged" }]
  ]) await expectStatus(await upload(id, label, Buffer.from("abc"), employee, changes), 403);
  await seed(`users/${employee.uid}`, { role: "EMPLOYEE", active: false, employeeId: employee.employeeId });
  try { await expectStatus(await upload(id, "inactive-account"), 403); }
  finally { await seed(`users/${employee.uid}`, { role: "EMPLOYEE", active: true, employeeId: employee.employeeId }); }
  await seed(`employees/${employee.employeeId}`, { fullName: employee.uid, active: false });
  try { await expectStatus(await upload(id, "inactive-employee"), 403); }
  finally { await seed(`employees/${employee.employeeId}`, { fullName: employee.uid, active: true }); }
  await expectStatus(await upload(id, "normal"), 200);
  for (const who of [admin, employee]) {
    await expectStatus(await request(`/workItems/${id}/attachments/normal`, "GET", who.token), 200);
    await expectStatus(await request(`/workItems/${id}/attachments/normal/chunks/0`, "GET", who.token), 200);
    await expectStatus(await request(`/workItems/${id}/attachments/normal`, "DELETE", who.token), 403);
    await expectStatus(await request(`/workItems/${id}/attachments/normal/chunks/0`, "DELETE", who.token), 403);
    await expectStatus(await request(`/workItems/${id}/attachments/normal`, "PATCH", who.token,
      { fields: fields({ fileName: "overwritten" }) }), 403);
    await expectStatus(await request(`/workItems/${id}/attachments/normal/chunks/0`, "PATCH", who.token,
      { fields: fields({ index: 0, data: Buffer.from("overwritten") }) }), 403);
  }
  for (const who of [other, device]) {
    await expectStatus(await request(`/workItems/${id}/attachments/normal`, "GET", who.token), 403);
    await expectStatus(await request(`/workItems/${id}/attachments/normal/chunks/0`, "GET", who.token), 403);
  }
  await expectStatus(await mutate(id, await read(id), { status: "PENDING_REVIEW", resultReport: "Done" }, "RESULT_SUBMITTED"), 200);
  await expectStatus(await upload(id, "after-submit"), 403);
});

test("five real references and attachment-only results preserve immutable evidence through review and history", async () => {
  const id = `attachment-results-${suffix}`;
  await ready(id);
  const attachments = [];
  for (let index = 0; index < 5; index++) {
    await expectStatus(await upload(id, `file-${index}`, Buffer.from(`file-${index}`)), 200);
    attachments.push(await attachment(id, `file-${index}`));
  }
  await expectStatus(await mutate(id, await read(id), { resultAttachments: attachments }, "PROGRESS_UPDATED"), 200);
  // Updating an already-full list must also fit the Rules expression budget.
  await expectStatus(await upload(id, "replacement", Buffer.from("updated evidence")), 200);
  attachments[0] = await attachment(id, "replacement");
  await expectStatus(await mutate(id, await read(id), { resultAttachments: attachments }, "PROGRESS_UPDATED"), 200);
  let current = await read(id);
  assert.equal(current.resultAttachments.length, 5);
  for (const [label, forged] of [
    ["missing", [{ ...attachments[0], id: "does-not-exist" }]],
    ["fields", [{ ...attachments[0], fileName: "forged.png" }]],
    ["owner", [{ ...attachments[0], uploadedById: other.uid }]],
    ["duplicate", [attachments[0], attachments[0]]],
    ["too-many", [...attachments, attachments[0]]]
  ]) await expectStatus(await mutate(id, current, { resultAttachments: forged }, "PROGRESS_UPDATED"), 403);
  const crossId = `attachment-other-task-${suffix}`;
  await ready(crossId);
  await expectStatus(await upload(crossId, "foreign-file"), 200);
  await expectStatus(await mutate(id, current, { resultAttachments: [await attachment(crossId, "foreign-file")] }, "PROGRESS_UPDATED"), 403);
  await expectStatus(await mutate(id, current, { status: "PENDING_REVIEW", resultReport: "" }, "RESULT_SUBMITTED"), 200);
  current = await read(id);
  await expectStatus(await mutate(id, current, { resultAttachments: [] }, "PROGRESS_UPDATED"), 403);
  await expectStatus(await mutate(id, current,
    { status: "IN_PROGRESS", reworkCount: 1, managerFeedback: "Please add details" }, "REWORK_REQUESTED", admin), 200);
  current = await read(id);
  assert.deepEqual(current.resultAttachments, attachments);
  await expectStatus(await mutate(id, current, { status: "PENDING_REVIEW" }, "RESULT_SUBMITTED"), 200);
  await expectStatus(await mutate(id, await read(id), { status: "COMPLETED", managerFeedback: "Approved" }, "APPROVED", admin), 200);
  current = await read(id);
  assert.deepEqual(current.resultAttachments, attachments);
  const history = await request(`/workItems/${id}/history/${current.version}`, "GET", admin.token);
  const saved = value({ mapValue: { fields: (await history.json()).fields } });
  assert.deepEqual(saved.before.resultAttachments, attachments);
  assert.deepEqual(saved.after.resultAttachments, attachments);
});

test("reassignment clears current references and keeps old evidence readable only by manager", async () => {
  const id = `attachment-reassign-${suffix}`;
  await ready(id);
  await expectStatus(await upload(id, "old-evidence"), 200);
  const evidence = await attachment(id, "old-evidence");
  await expectStatus(await mutate(id, await read(id), { resultAttachments: [evidence] }, "PROGRESS_UPDATED"), 200);
  const current = await read(id);
  const changed = { assigneeId: other.employeeId, assigneeName: other.uid, status: "ASSIGNED",
    resultReport: "", managerFeedback: "", reworkCount: 0 };
  await expectStatus(await mutate(id, current, changed, "EDITED", admin), 403);
  await expectStatus(await mutate(id, current, { ...changed, resultAttachments: [] }, "EDITED", admin), 200);
  for (const who of [employee, other]) {
    await expectStatus(await request(`/workItems/${id}/attachments/old-evidence`, "GET", who.token), 403);
    await expectStatus(await request(`/workItems/${id}/attachments/old-evidence/chunks/0`, "GET", who.token), 403);
  }
  await expectStatus(await request(`/workItems/${id}/attachments/old-evidence`, "GET", admin.token), 200);
  await expectStatus(await request(`/workItems/${id}/attachments/old-evidence/chunks/0`, "GET", admin.token), 200);
  const reassigned = await read(id);
  const history = await request(`/workItems/${id}/history/${reassigned.version}`, "GET", admin.token);
  const saved = value({ mapValue: { fields: (await history.json()).fields } });
  assert.deepEqual(saved.before.resultAttachments, [evidence]);
  assert.deepEqual(saved.after.resultAttachments, []);
  await expectStatus(await mutate(id, reassigned, { status: "IN_PROGRESS" }, "STARTED", other), 200);
  await expectStatus(await mutate(id, await read(id), { resultAttachments: [evidence] }, "PROGRESS_UPDATED", other), 403);
});

test("employee submits five newly uploaded references directly without a separate progress save", async () => {
  const id = `attachment-direct-submit-${suffix}`;
  await ready(id, { relatedScheduleId: `${employee.employeeId}_2026-10-08`,
    relatedShiftId: "evening", relatedScheduleDate: "2026-10-08" });
  const attachments = [];
  for (let index = 0; index < 5; index++) {
    await expectStatus(await upload(id, `direct-${index}`, Buffer.from(`direct-${index}`)), 200);
    attachments.push(await attachment(id, `direct-${index}`));
  }
  await expectStatus(await mutate(id, await read(id),
    { status: "PENDING_REVIEW", resultReport: "", resultAttachments: attachments }, "RESULT_SUBMITTED"), 200);
  await expectStatus(await mutate(id, await read(id), { status: "COMPLETED" }, "APPROVED", admin), 200);
});

test("legacy tasks without attachment field can save progress, review and reassign with normalized empty list", async () => {
  const id = `attachment-legacy-${suffix}`;
  await ready(id);
  const legacy = await read(id);
  assert(!Object.hasOwn(legacy, "resultAttachments"));
  await expectStatus(await mutate(id, legacy, { resultReport: "done", resultAttachments: [] }, "PROGRESS_UPDATED"), 200);
  await expectStatus(await mutate(id, await read(id), { status: "PENDING_REVIEW" }, "RESULT_SUBMITTED"), 200);
  await expectStatus(await mutate(id, await read(id), { status: "COMPLETED" }, "APPROVED", admin), 200);
});
