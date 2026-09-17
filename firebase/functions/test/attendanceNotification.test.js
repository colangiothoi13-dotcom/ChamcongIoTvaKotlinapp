const test = require("node:test");
const assert = require("node:assert/strict");
const { shouldNotifyAttendance } = require("../attendanceNotification");

test("notifies only when an attendance update transitions to accepted", () => {
  assert.equal(
    shouldNotifyAttendance({ resolutionStatus: "PENDING" }, { resolutionStatus: "ACCEPTED" }),
    true
  );
});

test("does not notify rejected, pending, repeated accepted, or malformed updates", () => {
  assert.equal(shouldNotifyAttendance({ resolutionStatus: "PENDING" }, { resolutionStatus: "DUPLICATE" }), false);
  assert.equal(shouldNotifyAttendance({ resolutionStatus: "PENDING" }, { resolutionStatus: "PENDING" }), false);
  assert.equal(shouldNotifyAttendance({ resolutionStatus: "ACCEPTED" }, { resolutionStatus: "ACCEPTED" }), false);
  assert.doesNotThrow(() => shouldNotifyAttendance(undefined, undefined));
  assert.equal(shouldNotifyAttendance(undefined, undefined), false);
});
