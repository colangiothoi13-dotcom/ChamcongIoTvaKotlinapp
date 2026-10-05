#pragma once
#include <stdint.h>

// Shared by the sketch and the host regression checks. Durations use unsigned
// subtraction so the policies keep working across the millis() rollover.
enum class AttendanceDelivery : uint8_t { NOT_STORED, QUEUED, CONFIRMED, REJECTED, LOCAL_ACCEPTED, ACCESS_ONLY };
enum class AttendanceSyncResult : uint8_t { EMPTY, DEFERRED, RETRY, ACKNOWLEDGED, REJECTED, STORAGE_ERROR };
enum class EnrollmentStage : uint8_t { IDLE, FIRST_IMAGE, FIRST_CONVERSION, REMOVE_FINGER, SECOND_GAP, SECOND_IMAGE, SECOND_CONVERSION, CREATE_MODEL, STORE_MODEL };

inline bool elapsedAtLeast(uint32_t now, uint32_t started, uint32_t duration) {
  return static_cast<uint32_t>(now - started) >= duration;
}

inline bool foregroundConfirmationCanOpen(bool matchesCurrentEvent, bool alreadyHandled,
                                           uint32_t now, uint32_t createdAt,
                                           uint32_t validityMs) {
  return matchesCurrentEvent && !alreadyHandled &&
      !elapsedAtLeast(now, createdAt, validityMs);
}

inline bool attendanceSyncMadeProgress(AttendanceSyncResult result) {
  return result == AttendanceSyncResult::ACKNOWLEDGED ||
      result == AttendanceSyncResult::REJECTED || result == AttendanceSyncResult::EMPTY;
}

inline bool syncCommandCanComplete(bool queueEmpty) { return queueEmpty; }

inline bool attendanceTransportUnavailable(int code) {
  return code < 0 || code == 408 || code == 425 || code == 429 || code >= 500;
}
