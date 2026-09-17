function shouldNotifyAttendance(before, after) {
  if (!isAttendanceState(before) || !isAttendanceState(after)) return false;
  return after.resolutionStatus === "ACCEPTED" && before.resolutionStatus !== "ACCEPTED";
}

function isAttendanceState(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value)
    && typeof value.resolutionStatus === "string";
}

module.exports = { shouldNotifyAttendance };
