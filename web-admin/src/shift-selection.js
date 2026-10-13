// Match Android's defaultShiftTemplates and SchedulePresentation: the saved
// stable template ID takes precedence over legacy copies of the same category.
// These helpers are presentation-only; all historical definitions stay intact.
export function defaultScheduleShifts() {
  const settings = { allowEarlyMinutes: 0, lateGraceMinutes: 10,
    earlyLeaveAllowedMinutes: 0, missingCheckOutGraceMinutes: 30,
    breakStartTime: null, breakEndTime: null, countsOvertime: false,
    effectiveFrom: '1970-01-01', effectiveTo: null, active: true };
  return [
    { ...settings, id: 'weekly_v1_morning_0800_1200', name: 'Ca sáng',
      category: 'MORNING', startTime: '08:00', endTime: '12:00', allowEarlyMinutes: 120 },
    { ...settings, id: 'weekly_v1_afternoon_1300_1700', name: 'Ca chiều',
      category: 'EVENING', startTime: '13:00', endTime: '17:00' },
  ];
}

export function defaultScheduleShift(id) {
  return defaultScheduleShifts().find(shift => shift.id === id) || null;
}

export function displayedScheduleShifts(shifts = []) {
  const defaults = defaultScheduleShifts(), defaultIds = new Set(defaults.map(shift => shift.id));
  return defaults.map(standard => shifts.find(shift => shift.id === standard.id) || standard)
    .concat(shifts.filter(shift => shift.category === 'SUPPLEMENTARY' && !defaultIds.has(shift.id)));
}

export function assignableScheduleShifts(shifts = []) {
  return displayedScheduleShifts(shifts)
    .filter(shift => ['MORNING', 'EVENING'].includes(shift.category) && shift.active !== false)
    .sort((a, b) => a.startTime.localeCompare(b.startTime));
}
