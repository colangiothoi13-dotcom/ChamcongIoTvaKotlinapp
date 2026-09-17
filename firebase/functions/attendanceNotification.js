function shouldNotifyAttendance(before, after) {
  return after?.resolutionStatus === "ACCEPTED" && before?.resolutionStatus !== "ACCEPTED";
}

module.exports = { shouldNotifyAttendance };
