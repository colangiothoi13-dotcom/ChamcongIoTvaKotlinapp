// Runs only against the local Firestore emulator selected by FIRESTORE_EMULATOR_HOST.
const { before, test } = require("node:test");
const assert = require("node:assert/strict");

const project = "demo-overtime-request-rules";
const host = process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8080";
const database = `http://${host}/v1/projects/${project}/databases/(default)`;
const documents = `${database}/documents`;
const documentName = path => `projects/${project}/databases/(default)/documents/${path}`;
const encode = value => Buffer.from(JSON.stringify(value)).toString("base64url");
// Keep success fixtures inside the submission window regardless of when the suite runs.
// Separate non-Sunday dates also prevent accidental document collisions between cases.
function workDate(index) {
  const date = new Date();
  date.setUTCDate(date.getUTCDate() + 7);
  date.setUTCHours(0, 0, 0, 0);
  let remaining = index;
  while (date.getUTCDay() === 0 || remaining > 0) {
    if (date.getUTCDay() !== 0) remaining--;
    date.setUTCDate(date.getUTCDate() + 1);
  }
  return date.toISOString().slice(0, 10);
}

function authToken(uid) {
  const now = Math.floor(Date.now() / 1000);
  return `${encode({ alg: "none", typ: "JWT" })}.${encode({
    iss: `https://securetoken.google.com/${project}`,
    aud: project,
    sub: uid,
    user_id: uid,
    iat: now,
    exp: now + 3600,
    auth_time: now,
    firebase: { sign_in_provider: "password", identities: {} }
  })}.`;
}

const admin = { uid: "rules-admin", token: authToken("rules-admin") };
const fixtureSuffix = `${Date.now()}-${process.pid}`;
const employee = { uid: "rules-employee", employeeId: `EMP001-${fixtureSuffix}`, token: authToken("rules-employee") };
const otherEmployee = { uid: "rules-other", employeeId: `EMP002-${fixtureSuffix}`, token: authToken("rules-other") };
const inactiveEmployee = { uid: "rules-inactive", employeeId: `EMP003-${fixtureSuffix}`, token: authToken("rules-inactive") };

async function request(url, method, auth, body) {
  return fetch(url, {
    method,
    headers: {
      Authorization: `Bearer ${auth}`,
      ...(body ? { "Content-Type": "application/json" } : {})
    },
    ...(body ? { body: JSON.stringify(body) } : {})
  });
}

async function seedProfile(actor, role, employeeId) {
  const fields = {
    role: { stringValue: role },
    active: { booleanValue: true },
    ...(employeeId ? { employeeId: { stringValue: employeeId } } : {})
  };
  const response = await request(`${documents}/users/${actor.uid}`, "PATCH", "owner", { fields });
  assert.equal(response.status, 200, await response.text());
}

async function seedEmployee(employeeId, fullName, department, active = true) {
  const response = await request(`${documents}/employees/${employeeId}`, "PATCH", "owner", {
    fields: {
      fullName: { stringValue: fullName },
      department: { stringValue: department },
      active: { booleanValue: active }
    }
  });
  assert.equal(response.status, 200, await response.text());
}

function overtimeFields(employeeId, workDate, overrides = {}) {
  return {
    employeeId: { stringValue: employeeId },
    employeeName: { stringValue: `Employee ${employeeId}` },
    department: { stringValue: "Engineering" },
    workDate: { stringValue: workDate },
    startTime: { stringValue: "18:00" },
    endTime: { stringValue: "22:00" },
    reason: { stringValue: "Hỗ trợ công việc phát sinh" },
    status: { stringValue: "PENDING" },
    reviewerId: { nullValue: null },
    reviewerName: { nullValue: null },
    reviewedAt: { nullValue: null },
    rejectionReason: { nullValue: null },
    ...overrides
  };
}

async function createOvertime(actor, employeeId, workDate, overrides = {}) {
  const id = `${employeeId}_${workDate}`;
  const response = await request(`${database}/documents:commit`, "POST", actor.token, {
    writes: [{
      update: { name: documentName(`overtimeRequests/${id}`), fields: overtimeFields(employeeId, workDate, overrides) },
      updateTransforms: [{ fieldPath: "createdAt", setToServerValue: "REQUEST_TIME" }]
    }]
  });
  return { id, response };
}

async function assignOvertime(actor, employeeId, workDate, includeAudit = true) {
  const id = `${employeeId}_${workDate}`;
  const reason = "Admin assigned overtime";
  const data = overtimeFields(employeeId, workDate, {
    reason: { stringValue: reason }, status: { stringValue: "APPROVED" },
    reviewerId: { stringValue: actor.uid }, reviewerName: { stringValue: "Rules Admin" }
  });
  delete data.reviewedAt;
  const writes = [{
    update: { name: documentName(`overtimeRequests/${id}`), fields: data },
    updateTransforms: [
      { fieldPath: "createdAt", setToServerValue: "REQUEST_TIME" },
      { fieldPath: "reviewedAt", setToServerValue: "REQUEST_TIME" }
    ]
  }];
  if (includeAudit) writes.push({
    update: { name: documentName(`audit_logs/${id}_OVERTIME_ASSIGN`), fields: {
      actorId: { stringValue: actor.uid }, actorName: { stringValue: "Rules Admin" },
      action: { stringValue: "OVERTIME_ASSIGN" }, targetType: { stringValue: "overtimeRequest" },
      targetId: { stringValue: id }, reason: { stringValue: reason }, details: { stringValue: reason }
    } },
    updateTransforms: [{ fieldPath: "createdAt", setToServerValue: "REQUEST_TIME" }]
  });
  return request(`${database}/documents:commit`, "POST", actor.token, { writes });
}

async function reviewOvertime(actor, id, status, reason, { includeAudit = true } = {}) {
  const reviewerName = actor.uid === admin.uid ? "Rules Admin" : "Rules Employee";
  const writes = [{
    update: {
      name: documentName(`overtimeRequests/${id}`),
      fields: {
        status: { stringValue: status },
        reviewerId: { stringValue: actor.uid },
        reviewerName: { stringValue: reviewerName },
        rejectionReason: reason == null ? { nullValue: null } : { stringValue: reason }
      }
    },
    updateMask: { fieldPaths: ["status", "reviewerId", "reviewerName", "rejectionReason"] },
    updateTransforms: [{ fieldPath: "reviewedAt", setToServerValue: "REQUEST_TIME" }]
  }];
  if (includeAudit) {
    writes.push({
      update: {
        name: documentName(`audit_logs/${id}_OVERTIME_REVIEW`),
        fields: {
          actorId: { stringValue: actor.uid },
          actorName: { stringValue: reviewerName },
          action: { stringValue: "OVERTIME_REVIEW" },
          targetType: { stringValue: "overtimeRequest" },
          targetId: { stringValue: id },
          status: { stringValue: status },
          reason: { stringValue: reason || "" },
          details: { stringValue: `Reviewed overtime request as ${status}` }
        }
      },
      updateTransforms: [{ fieldPath: "createdAt", setToServerValue: "REQUEST_TIME" }]
    });
  }
  return request(`${database}/documents:commit`, "POST", actor.token, {
    writes
  });
}

async function expectStatus(response, expected, context) {
  assert.equal(response.status, expected, `${context}: ${await response.text()}`);
}

before(async () => {
  await seedProfile(admin, "ADMIN");
  await seedProfile(employee, "EMPLOYEE", employee.employeeId);
  await seedProfile(otherEmployee, "EMPLOYEE", otherEmployee.employeeId);
  await seedProfile(inactiveEmployee, "EMPLOYEE", inactiveEmployee.employeeId);
  await seedEmployee(employee.employeeId, `Employee ${employee.employeeId}`, "Engineering");
  await seedEmployee(otherEmployee.employeeId, `Employee ${otherEmployee.employeeId}`, "Engineering");
  await seedEmployee(inactiveEmployee.employeeId, `Employee ${inactiveEmployee.employeeId}`, "Engineering", false);
});

test("admin_directly_assigns_overtime_with_audit_record", async () => {
  await expectStatus(await assignOvertime(admin, employee.employeeId, workDate(0)), 200, "admin assignment");
  await expectStatus(await assignOvertime(admin, otherEmployee.employeeId, workDate(1), false), 403,
    "assignment without audit");
});

test("employee creates a pending overtime request for their own deterministic document", async () => {
  const { response } = await createOvertime(employee, employee.employeeId, workDate(2));
  await expectStatus(response, 200, "own create");
});

test("employee cannot create an overtime request for another employee", async () => {
  const { response } = await createOvertime(employee, otherEmployee.employeeId, workDate(3));
  await expectStatus(response, 403, "other employee create");
});

test("employee cannot tamper with canonical employee name or department", async () => {
  const tamperedName = await createOvertime(employee, employee.employeeId, workDate(4), {
    employeeName: { stringValue: "Impersonated Employee" }
  });
  await expectStatus(tamperedName.response, 403, "employee name tampering");

  const tamperedDepartment = await createOvertime(employee, employee.employeeId, workDate(5), {
    department: { stringValue: "Executive" }
  });
  await expectStatus(tamperedDepartment.response, 403, "employee department tampering");
});

test("inactive employee record cannot create an overtime request", async () => {
  const { response } = await createOvertime(inactiveEmployee, inactiveEmployee.employeeId, workDate(6));
  await expectStatus(response, 403, "inactive employee create");
});

test("employee cannot tamper with the fixed overtime window", async () => {
  const { response } = await createOvertime(employee, employee.employeeId, workDate(7), {
    startTime: { stringValue: "17:30" }
  });
  await expectStatus(response, 403, "fixed time tampering");
});

test("employee cannot create an already-approved overtime request", async () => {
  const { response } = await createOvertime(employee, employee.employeeId, workDate(8), {
    status: { stringValue: "APPROVED" }
  });
  await expectStatus(response, 403, "status tampering");
});

test("employee cannot review an overtime request", async () => {
  const { id, response: created } = await createOvertime(employee, employee.employeeId, workDate(9));
  await expectStatus(created, 200, "review seed");
  await expectStatus(await reviewOvertime(employee, id, "APPROVED", null), 403, "employee review");
});

test("admin approves a pending overtime request", async () => {
  const { id, response: created } = await createOvertime(employee, employee.employeeId, workDate(10));
  await expectStatus(created, 200, "approve seed");
  await expectStatus(await reviewOvertime(admin, id, "APPROVED", null), 200, "admin approve");

  const stored = await request(`${documents}/overtimeRequests/${id}`, "GET", admin.token);
  const body = await stored.json();
  assert.equal(stored.status, 200, JSON.stringify(body));
  assert.equal(body.fields.status.stringValue, "APPROVED");
  assert.equal(body.fields.reviewerId.stringValue, admin.uid);
  assert.ok(body.fields.reviewedAt.timestampValue);

  const audit = await request(`${documents}/audit_logs/${id}_OVERTIME_REVIEW`, "GET", admin.token);
  const auditBody = await audit.json();
  assert.equal(audit.status, 200, JSON.stringify(auditBody));
  assert.equal(auditBody.fields.targetId.stringValue, id);
  assert.equal(auditBody.fields.status.stringValue, "APPROVED");
  assert.equal(auditBody.fields.actorId.stringValue, admin.uid);
  assert.ok(auditBody.fields.createdAt.timestampValue);
});

test("admin rejects a pending overtime request with a nonblank reason", async () => {
  const { id, response: created } = await createOvertime(employee, employee.employeeId, workDate(11));
  await expectStatus(created, 200, "reject seed");
  await expectStatus(await reviewOvertime(admin, id, "REJECTED", "No staffing need"), 200, "admin reject");
});

test("admin cannot reject a pending overtime request without a reason", async () => {
  const { id, response: created } = await createOvertime(employee, employee.employeeId, workDate(12));
  await expectStatus(created, 200, "reject-without-reason seed");
  await expectStatus(await reviewOvertime(admin, id, "REJECTED", "   "), 403, "admin reject without reason");
});

test("admin cannot review a pending overtime request without the paired audit write", async () => {
  const { id, response: created } = await createOvertime(employee, employee.employeeId, workDate(13));
  await expectStatus(created, 200, "unpaired review seed");
  await expectStatus(
    await reviewOvertime(admin, id, "APPROVED", null, { includeAudit: false }),
    403,
    "unpaired admin review"
  );
});

test("overtime requests cannot be deleted", async () => {
  const { id, response: created } = await createOvertime(employee, employee.employeeId, workDate(14));
  await expectStatus(created, 200, "delete seed");
  const deleted = await request(`${documents}/overtimeRequests/${id}`, "DELETE", admin.token);
  await expectStatus(deleted, 403, "admin delete");
});
