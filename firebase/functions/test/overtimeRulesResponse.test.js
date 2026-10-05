const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");

test("overtime approval rules test reads each GET response body only once", async () => {
  const cases = new Map();
  const responses = [];
  let submittedRequestId;
  vm.runInNewContext(fs.readFileSync(require.resolve("../../test/overtimeRequestRules.test.js"), "utf8"), {
    Buffer,
    process,
    require(name) {
      if (name === "node:test") return { before() {}, test: (name, run) => cases.set(name, run) };
      return require(name);
    },
    async fetch(url, options) {
      if (options.method !== "GET") {
        const writes = options.body ? JSON.parse(options.body).writes || [] : [];
        const createdRequest = writes.find(write => write.update?.name.includes("/overtimeRequests/") &&
          write.update.fields.employeeId && write.update.fields.workDate)?.update.fields;
        if (createdRequest) {
          submittedRequestId = `${createdRequest.employeeId.stringValue}_${createdRequest.workDate.stringValue}`;
        }
        return new Response("{}", { status: 200 });
      }
      assert.ok(submittedRequestId, "Capture the request created by the source-driven rules test");
      const fields = url.includes("/audit_logs/") ? {
        targetId: { stringValue: submittedRequestId }, status: { stringValue: "APPROVED" },
        actorId: { stringValue: "rules-admin" }, createdAt: { timestampValue: "2026-09-25T00:00:00Z" }
      } : {
        status: { stringValue: "APPROVED" }, reviewerId: { stringValue: "rules-admin" },
        reviewedAt: { timestampValue: "2026-09-25T00:00:00Z" }
      };
      const response = new Response(JSON.stringify({ fields }), { status: 200 });
      responses.push(response);
      return response;
    }
  });
  assert.equal(cases.size, 13);
  await cases.get("admin approves a pending overtime request")();
  assert.equal(responses.length, 2);
  assert.ok(responses.every(response => response.bodyUsed));
});
