const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");

test("overtime approval rules test reads each GET response body only once", async () => {
  const cases = new Map();
  const responses = [];
  vm.runInNewContext(fs.readFileSync(require.resolve("../../test/overtimeRequestRules.test.js"), "utf8"), {
    Buffer,
    process,
    require(name) {
      if (name === "node:test") return { before() {}, test: (name, run) => cases.set(name, run) };
      return require(name);
    },
    async fetch(url, options) {
      if (options.method !== "GET") return new Response("{}", { status: 200 });
      const fields = url.includes("/audit_logs/") ? {
        targetId: { stringValue: "EMP001_2026-09-25" }, status: { stringValue: "APPROVED" },
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
  assert.equal(cases.size, 12);
  await cases.get("admin approves a pending overtime request")();
  assert.equal(responses.length, 2);
  assert.ok(responses.every(response => response.bodyUsed));
});
