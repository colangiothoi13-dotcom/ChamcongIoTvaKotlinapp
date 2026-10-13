#pragma once
#include <stdint.h>
#include <stddef.h>
#include <string.h>

// Parse only canonical Firestore UTC timestamps. Reject invalid dates rather
// than allowing a libc conversion to silently normalize them into a new date.
inline int faceTimestampNumber(const char* value, size_t start, size_t count) {
  int number = 0;
  for (size_t index = start; index < start + count; ++index) {
    if (value[index] < '0' || value[index] > '9') return -1;
    number = number * 10 + value[index] - '0';
  }
  return number;
}

inline bool faceTimestampLeapYear(int year) {
  return year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
}

inline int64_t faceTimestampMillis(const char* value) {
  if (value == nullptr) return -1;
  const size_t length = strlen(value);
  if (length < 20 || length > 30 || value[4] != '-' || value[7] != '-' ||
      value[10] != 'T' || value[13] != ':' || value[16] != ':' || value[length - 1] != 'Z') return -1;
  const int year = faceTimestampNumber(value, 0, 4);
  const int month = faceTimestampNumber(value, 5, 2);
  const int day = faceTimestampNumber(value, 8, 2);
  const int hour = faceTimestampNumber(value, 11, 2);
  const int minute = faceTimestampNumber(value, 14, 2);
  const int second = faceTimestampNumber(value, 17, 2);
  if (year < 1970 || year > 2099 || month < 1 || month > 12 || day < 1 ||
      hour < 0 || hour > 23 || minute < 0 || minute > 59 || second < 0 || second > 59) return -1;
  const int monthDays[] = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
  const int maximumDay = monthDays[month - 1] + (month == 2 && faceTimestampLeapYear(year) ? 1 : 0);
  if (day > maximumDay) return -1;
  int milliseconds = 0;
  if (length > 20) {
    if (value[19] != '.' || length < 22) return -1;
    for (size_t index = 20; index < length - 1; ++index) {
      if (value[index] < '0' || value[index] > '9') return -1;
      if (index < 23) milliseconds = milliseconds * 10 + value[index] - '0';
    }
    if (length == 22) milliseconds *= 100;
    else if (length == 23) milliseconds *= 10;
  }
  int64_t days = 0;
  for (int candidate = 1970; candidate < year; ++candidate) days += faceTimestampLeapYear(candidate) ? 366 : 365;
  for (int candidate = 1; candidate < month; ++candidate) {
    days += monthDays[candidate - 1] + (candidate == 2 && faceTimestampLeapYear(year) ? 1 : 0);
  }
  days += day - 1;
  return ((days * 24 + hour) * 3600 + minute * 60 + second) * 1000 + milliseconds;
}

inline bool faceDoorCommandMarked(const char* source, const char* requestId, const char* attendanceId) {
  return strcmp(source, "FACE_ATTENDANCE") == 0 || strncmp(requestId, "face-door-", 10) == 0 ||
      strlen(attendanceId) > 0;
}

inline bool faceDoorCommandCanOpen(const char* type, const char* source, const char* requestId,
                                   const char* attendanceId, int64_t createdAt, int64_t expiresAt,
                                   int64_t now) {
  if (strcmp(type, "OPEN_DOOR") != 0 || strcmp(source, "FACE_ATTENDANCE") != 0 ||
      strlen(attendanceId) != 64 || strlen(requestId) != 74 ||
      strncmp(requestId, "face-door-", 10) != 0 || strcmp(requestId + 10, attendanceId) != 0) return false;
  for (size_t index = 0; index < 64; ++index) {
    const char digit = attendanceId[index];
    if (!((digit >= '0' && digit <= '9') || (digit >= 'a' && digit <= 'f'))) return false;
  }
  // Fail closed before NTP. Future creation allows only small clock skew;
  // expiry has no grace period, even after a slow HTTPS claim.
  return now >= 1700000000000LL && createdAt >= 1700000000000LL && expiresAt > createdAt &&
      expiresAt - createdAt <= 15000 && createdAt - now <= 3000 && now < expiresAt;
}
