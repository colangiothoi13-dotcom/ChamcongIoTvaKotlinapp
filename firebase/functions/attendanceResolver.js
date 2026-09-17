const DUPLICATE_WINDOW_MS = 180000;
const TIME_ZONE = "Asia/Ho_Chi_Minh";
const SUPPLEMENTARY_SHIFT_ID = "SUPPLEMENTARY_1730_2030";
const SUPPLEMENTARY_START_TIME = "17:30";
const SUPPLEMENTARY_END_TIME = "20:30";

function formatParts(timestampMs, timeZone) {
  return Object.fromEntries(new Intl.DateTimeFormat("en-CA", {
    timeZone, year: "numeric", month: "2-digit", day: "2-digit",
    hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23"
  }).formatToParts(new Date(timestampMs))
    .filter(part => part.type !== "literal")
    .map(part => [part.type, Number(part.value)]));
}

function zonedDateTimeToMs(scheduleDate, time, timeZone) {
  const [year, month, day] = scheduleDate.split("-").map(Number);
  const [hour, minute] = time.split(":").map(Number);
  const utcGuess = Date.UTC(year, month - 1, day, hour, minute);
  const localGuess = formatParts(utcGuess, timeZone);
  return utcGuess + (Date.UTC(year, month - 1, day, hour, minute) - Date.UTC(
    localGuess.year, localGuess.month - 1, localGuess.day, localGuess.hour, localGuess.minute
  ));
}

function nextDate(date) {
  const [year, month, day] = date.split("-").map(Number);
  return new Date(Date.UTC(year, month - 1, day + 1)).toISOString().slice(0, 10);
}

function buildShiftWindow(scheduleDate, shift, timeZone = TIME_ZONE) {
  const startMs = zonedDateTimeToMs(scheduleDate, shift.startTime, timeZone);
  let endMs = zonedDateTimeToMs(scheduleDate, shift.endTime, timeZone);
  if (endMs <= startMs) endMs = zonedDateTimeToMs(nextDate(scheduleDate), shift.endTime, timeZone);
  return { scheduleDate, startMs, endMs };
}

function buildSupplementarySchedule(workDate, request) {
  return {
    scheduleDate: workDate,
    shiftId: SUPPLEMENTARY_SHIFT_ID,
    overtimeRequestId: request && request.id ? request.id : null,
    shift: {
      id: SUPPLEMENTARY_SHIFT_ID,
      startTime: SUPPLEMENTARY_START_TIME,
      endTime: SUPPLEMENTARY_END_TIME,
      allowEarlyMinutes: 0,
      missingCheckOutGraceMinutes: 0
    }
  };
}

function attendanceSessionId(employeeId, scheduleDate, shiftId) {
  const legacyId = `${employeeId}_${scheduleDate}`;
  return shiftId === SUPPLEMENTARY_SHIFT_ID ? `${legacyId}_${SUPPLEMENTARY_SHIFT_ID}` : legacyId;
}

function normalizeSchedule(schedule) {
  const scheduleDate = schedule.scheduleDate || schedule.date;
  const shift = schedule.shift || schedule;
  const window = Number.isFinite(schedule.startMs) && Number.isFinite(schedule.endMs)
    ? { scheduleDate, startMs: schedule.startMs, endMs: schedule.endMs }
    : buildShiftWindow(scheduleDate, shift);
  return { ...schedule, scheduleDate, shift, ...window };
}

function pickSchedule(scanMs, schedules) {
  const candidates = schedules.map(normalizeSchedule).filter(candidate => {
    const opensAt = candidate.startMs - Number(candidate.shift.allowEarlyMinutes || 0) * 60000;
    const closesAt = candidate.endMs + Number(candidate.shift.missingCheckOutGraceMinutes ?? 60) * 60000;
    return scanMs >= opensAt && scanMs <= closesAt;
  });
  if (!candidates.length) return null;
  return candidates.sort((left, right) => {
    const leftDistance = Math.min(Math.abs(scanMs - left.startMs), Math.abs(scanMs - left.endMs));
    const rightDistance = Math.min(Math.abs(scanMs - right.startMs), Math.abs(scanMs - right.endMs));
    return leftDistance - rightDistance || left.startMs - right.startMs;
  })[0];
}

function unchanged(type, resolutionStatus, schedule, session) {
  return {
    type, resolutionStatus,
    scheduleDate: schedule ? schedule.scheduleDate : null,
    shiftId: schedule ? schedule.shiftId : null,
    status: "ABNORMAL",
    nextSession: session
  };
}

function isWithinSupplementaryWindow(timestampMs) {
  const scheduleDate = localDateForMs(timestampMs, TIME_ZONE);
  const window = buildShiftWindow(scheduleDate, {
    startTime: SUPPLEMENTARY_START_TIME,
    endTime: SUPPLEMENTARY_END_TIME
  });
  return timestampMs >= window.startMs && timestampMs <= window.endMs;
}

function resolveScan({ scan, schedules, session, latestAccepted, requestStatus }) {
  const schedule = pickSchedule(scan.timestampMs, schedules);
  if (requestStatus === null && isWithinSupplementaryWindow(scan.timestampMs) &&
      (!schedule || scan.timestampMs > schedule.endMs)) {
    return unchanged("UNSCHEDULED", "UNSCHEDULED", null, session);
  }
  if (!schedule) return unchanged("UNSCHEDULED", "UNSCHEDULED", null, session);

  if (latestAccepted && Math.abs(scan.timestampMs - latestAccepted.timestampMs) <= DUPLICATE_WINDOW_MS) {
    return unchanged("DUPLICATE", "DUPLICATE", schedule, session);
  }
  if (latestAccepted && scan.timestampMs < latestAccepted.timestampMs) {
    return unchanged("OUT_OF_ORDER", "OUT_OF_ORDER", schedule, session);
  }
  if (session && session.closed) return unchanged("UNSCHEDULED", "UNSCHEDULED", schedule, session);

  const opensSession = session && session.openCheckInAt != null;
  const type = opensSession ? "CHECK_OUT" :
    Math.abs(scan.timestampMs - schedule.startMs) <= Math.abs(scan.timestampMs - schedule.endMs) ? "CHECK_IN" : "CHECK_OUT";
  if (opensSession && scan.timestampMs <= session.openCheckInAt) {
    return unchanged("OUT_OF_ORDER", "OUT_OF_ORDER", schedule, session);
  }

  const nextSession = {
    lastAcceptedEventId: scan.eventId || null,
    lastAcceptedType: type,
    lastAcceptedAt: scan.timestampMs,
    openCheckInAt: type === "CHECK_IN" ? scan.timestampMs : null,
    closed: type === "CHECK_OUT"
  };
  const supplementaryStatus = schedule.shiftId === SUPPLEMENTARY_SHIFT_ID ? requestStatus : null;
  const resolutionStatus = supplementaryStatus === "PENDING" ? "OVERTIME_PENDING" :
    supplementaryStatus === "REJECTED" ? "OVERTIME_REJECTED" : "ACCEPTED";
  return {
    type, resolutionStatus, scheduleDate: schedule.scheduleDate,
    shiftId: schedule.shiftId, status: resolutionStatus === "ACCEPTED" ? "NORMAL" : "ABNORMAL", nextSession
  };
}

function localDateForMs(timestampMs, timeZone = TIME_ZONE) {
  const parts = formatParts(timestampMs, timeZone);
  return `${parts.year}-${String(parts.month).padStart(2, "0")}-${String(parts.day).padStart(2, "0")}`;
}

function resolveMappedEmployee(mapping, employee) {
  if (!mapping || mapping.enabled !== true || !mapping.employeeId) return null;
  if (!employee || employee.id !== mapping.employeeId || employee.active !== true) return null;
  return employee;
}

module.exports = {
  DUPLICATE_WINDOW_MS,
  TIME_ZONE,
  SUPPLEMENTARY_SHIFT_ID,
  SUPPLEMENTARY_START_TIME,
  SUPPLEMENTARY_END_TIME,
  attendanceSessionId,
  buildSupplementarySchedule,
  buildShiftWindow,
  pickSchedule,
  resolveScan,
  localDateForMs,
  resolveMappedEmployee
};
