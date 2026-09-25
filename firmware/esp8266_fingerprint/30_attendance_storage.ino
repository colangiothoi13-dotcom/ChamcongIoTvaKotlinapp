// LittleFS fingerprint cache and attendance outbox.

bool cacheFingerprintMapping(uint16_t templateId, const String& employeeId, const String& employeeName) {
  if (!littleFsReady) return false;
  char key[8];
  snprintf(key, sizeof(key), "%u", templateId);
  DynamicJsonDocument cache(4096);
  File input = LittleFS.open(FINGERPRINT_CACHE_PATH, "r");
  if (input) {
    deserializeJson(cache, input);
    input.close();
  }
  JsonObject entry = cache[key].to<JsonObject>();
  entry["employeeId"] = employeeId;
  entry["employeeName"] = employeeName;
  File output = LittleFS.open(FINGERPRINT_CACHE_PATH, "w");
  if (!output) return false;
  serializeJson(cache, output);
  output.close();
  return true;
}

bool loadCachedFingerprintMapping(uint16_t templateId, String& employeeId, String& employeeName) {
  if (!littleFsReady) return false;
  char key[8];
  snprintf(key, sizeof(key), "%u", templateId);
  File input = LittleFS.open(FINGERPRINT_CACHE_PATH, "r");
  if (!input) return false;
  DynamicJsonDocument cache(4096);
  DeserializationError error = deserializeJson(cache, input);
  input.close();
  if (error) return false;
  JsonObject entry = cache[key].as<JsonObject>();
  if (entry.isNull()) return false;
  employeeId = entry["employeeId"] | "";
  employeeName = entry["employeeName"] | "";
  employeeId.trim();
  employeeName.trim();
  return employeeId.length() > 0 && employeeName.length() > 0;
}

void removeCachedFingerprintMapping(uint16_t templateId) {
  if (!littleFsReady) return;
  char key[8];
  snprintf(key, sizeof(key), "%u", templateId);
  File input = LittleFS.open(FINGERPRINT_CACHE_PATH, "r");
  if (!input) return;
  DynamicJsonDocument cache(4096);
  if (deserializeJson(cache, input)) {
    input.close();
    return;
  }
  input.close();
  cache.remove(key);
  File output = LittleFS.open(FINGERPRINT_CACHE_PATH, "w");
  if (!output) return;
  serializeJson(cache, output);
  output.close();
}

bool parseAttendanceOutboxRecord(const String& line, String& eventId, String& payload) {
  DynamicJsonDocument record(2048);
  if (deserializeJson(record, line)) return false;
  eventId = record["eventId"] | "";
  payload = record["payload"] | "";
  eventId.trim();
  payload.trim();
  return eventId.length() > 0 && payload.length() > 0;
}

// Read only complete, valid records. A torn final JSON line can happen when
// the ESP8266 loses power during append; the valid prefix is still recoverable.
bool readCanonicalOutbox(const char* path, String& canonical, String& original, bool& clean) {
  canonical = "";
  original = "";
  clean = true;
  if (!LittleFS.exists(path)) return false;

  File input = LittleFS.open(path, "r");
  if (!input) return false;
  original = input.readString();
  input.close();

  String seenIds;
  size_t cursor = 0;
  while (cursor < original.length()) {
    int lineEnd = original.indexOf('\n', cursor);
    String line = lineEnd < 0
        ? original.substring(cursor)
        : original.substring(cursor, lineEnd);
    cursor = lineEnd < 0 ? original.length() : static_cast<size_t>(lineEnd + 1);
    line.trim();
    if (line.length() == 0) continue;

    String eventId;
    String payload;
    if (!parseAttendanceOutboxRecord(line, eventId, payload)) {
      clean = false;
      break;
    }
    String marker = String("\n") + eventId + "\n";
    if (seenIds.indexOf(marker) >= 0) continue;
    seenIds += marker;

    DynamicJsonDocument record(2048);
    record["eventId"] = eventId;
    record["payload"] = payload;
    String normalized;
    serializeJson(record, normalized);
    canonical += normalized;
    canonical += '\n';
    yield();
  }
  return true;
}

// Replace a file using a temporary file and a backup. If power is lost
// between the two renames, recoverAttendanceOutbox() can choose the complete
// copy that remains on LittleFS instead of silently losing the queue.
bool installLittleFsText(const char* path, const char* tempPath,
                         const char* backupPath, const String& content) {
  File temp = LittleFS.open(tempPath, "w");
  if (!temp) return false;
  size_t expected = content.length();
  size_t written = temp.print(content);
  temp.flush();
  temp.close();
  if (written != expected) {
    LittleFS.remove(tempPath);
    return false;
  }

  if (LittleFS.exists(backupPath)) LittleFS.remove(backupPath);
  bool movedOld = false;
  if (LittleFS.exists(path)) {
    movedOld = LittleFS.rename(path, backupPath);
    if (!movedOld) return false;
  }

  if (!LittleFS.rename(tempPath, path)) {
    if (movedOld) {
      LittleFS.remove(path);
      LittleFS.rename(backupPath, path);
    }
    return false;
  }
  LittleFS.remove(backupPath);
  return true;
}

void recoverAttendanceOutbox() {
  if (!littleFsReady) return;

  String mainCanonical;
  String mainOriginal;
  bool mainClean = false;
  bool hasMain = readCanonicalOutbox(ATTENDANCE_OUTBOX_PATH, mainCanonical, mainOriginal, mainClean);

  String tempCanonical;
  String tempOriginal;
  bool tempClean = false;
  bool hasTemp = readCanonicalOutbox(ATTENDANCE_OUTBOX_TMP_PATH, tempCanonical, tempOriginal, tempClean);

  String backupCanonical;
  String backupOriginal;
  bool backupClean = false;
  bool hasBackup = readCanonicalOutbox(ATTENDANCE_OUTBOX_BACKUP_PATH, backupCanonical, backupOriginal, backupClean);

  // A complete main file is the safest choice when both files exist. The
  // server-side eventId makes retrying already accepted records idempotent.
  if (hasMain && mainClean) {
    if (mainCanonical != mainOriginal) {
      if (!installLittleFsText(ATTENDANCE_OUTBOX_PATH, ATTENDANCE_OUTBOX_TMP_PATH,
                               ATTENDANCE_OUTBOX_BACKUP_PATH, mainCanonical)) {
        setLatestError("Khong sua duoc hang doi LittleFS");
      }
    } else {
      LittleFS.remove(ATTENDANCE_OUTBOX_TMP_PATH);
      LittleFS.remove(ATTENDANCE_OUTBOX_BACKUP_PATH);
    }
    return;
  }

  // If the main file is torn or was removed during a queue swap, choose the
  // complete candidate with the most records. Choosing the largest complete
  // copy prevents a stale temp file from hiding valid records in main; equal
  // counts prefer main because it is the live queue.
  const String* recovered = nullptr;
  size_t recoveredCount = 0;
  if (hasMain) {
    recovered = &mainCanonical;
    recoveredCount = canonicalOutboxCount(mainCanonical);
  }
  if (hasTemp && tempClean && (recovered == nullptr || canonicalOutboxCount(tempCanonical) > recoveredCount)) {
    recovered = &tempCanonical;
    recoveredCount = canonicalOutboxCount(tempCanonical);
  }
  if (hasBackup && backupClean && (recovered == nullptr || canonicalOutboxCount(backupCanonical) > recoveredCount)) {
    recovered = &backupCanonical;
    recoveredCount = canonicalOutboxCount(backupCanonical);
  }
  if (recovered != nullptr) {
    if (!installLittleFsText(ATTENDANCE_OUTBOX_PATH, ATTENDANCE_OUTBOX_TMP_PATH,
                             ATTENDANCE_OUTBOX_BACKUP_PATH, *recovered)) {
      setLatestError("Khong khoi phuc duoc hang doi LittleFS");
    }
  }
}

bool outboxContainsEvent(const String& targetEventId) {
  if (!littleFsReady || !LittleFS.exists(ATTENDANCE_OUTBOX_PATH)) return false;
  File input = LittleFS.open(ATTENDANCE_OUTBOX_PATH, "r");
  if (!input) return false;
  while (input.available()) {
    String line = input.readStringUntil('\n');
    String eventId;
    String payload;
    if (parseAttendanceOutboxRecord(line, eventId, payload) && eventId == targetEventId) {
      input.close();
      return true;
    }
    yield();
  }
  input.close();
  return false;
}

size_t canonicalOutboxCount(const String& canonical) {
  size_t count = 0;
  for (size_t i = 0; i < canonical.length(); ++i) {
    if (canonical[i] == '\n') count++;
  }
  return count;
}

uint32_t readSequenceCandidate(const char* path) {
  if (!LittleFS.exists(path)) return 0;
  File input = LittleFS.open(path, "r");
  if (!input) return 0;
  String value = input.readString();
  input.close();
  value.trim();
  if (value.length() == 0) return 0;
  for (size_t i = 0; i < value.length(); ++i) {
    if (value[i] < '0' || value[i] > '9') return 0;
  }
  long parsed = value.toInt();
  return parsed > 0 ? static_cast<uint32_t>(parsed) : 0;
}

uint32_t nextAttendanceSequence() {
  uint32_t candidate = readSequenceCandidate(ATTENDANCE_SEQUENCE_PATH);
  candidate = max(candidate, readSequenceCandidate(ATTENDANCE_SEQUENCE_TMP_PATH));
  candidate = max(candidate, readSequenceCandidate(ATTENDANCE_SEQUENCE_BACKUP_PATH));
  return candidate == 0 ? 1 : candidate;
}

bool reserveAttendanceEventId(String& eventId) {
  if (!littleFsReady) return false;
  uint32_t sequence = nextAttendanceSequence();
  if (sequence == 0xFFFFFFFFUL) {
    setLatestError("Het bo dem ma su kien");
    return false;
  }
  // Reserve the next value before adding the event to the outbox. A crash can
  // skip an ID, but it can never reuse an ID for a different scan.
  if (!installLittleFsText(ATTENDANCE_SEQUENCE_PATH, ATTENDANCE_SEQUENCE_TMP_PATH,
                           ATTENDANCE_SEQUENCE_BACKUP_PATH, String(sequence + 1))) {
    setLatestError("Khong luu duoc bo dem su kien");
    return false;
  }
  eventId = String(DEVICE_ID) + "-" + String(ESP.getChipId(), HEX) + "-" + String(sequence);
  return true;
}

bool enqueueAttendanceEvent(const String& eventId, const String& payload) {
  if (!littleFsReady) return false;
  if (outboxContainsEvent(eventId)) return true;

  File input = LittleFS.open(ATTENDANCE_OUTBOX_PATH, "r");
  String existing;
  if (input) {
    existing = input.readString();
    input.close();
  }

  DynamicJsonDocument record(2048);
  record["eventId"] = eventId;
  record["payload"] = payload;
  String line;
  serializeJson(record, line);
  line += '\n';
  if (existing.length() + line.length() > ATTENDANCE_OUTBOX_MAX_BYTES) {
    Serial.printf("OUTBOX day: bo qua event %s, queue da day\n", eventId.c_str());
    setLatestError("Hang doi cham cong da day");
    return false;
  }
  File output = LittleFS.open(ATTENDANCE_OUTBOX_PATH, "a");
  if (!output) return false;
  size_t written = output.print(line);
  output.flush();
  output.close();
  if (written != line.length()) {
    setLatestError("Khong ghi duoc su kien vao LittleFS");
    return false;
  }
  Serial.printf("OUTBOX them event %s, bytes=%u\n", eventId.c_str(),
                static_cast<unsigned>(existing.length() + line.length()));
  return true;
}

bool httpResponseAcknowledgesEvent(int code);

int postAttendanceEvent(const String& eventId, const String& payload) {
  if (WiFi.status() != WL_CONNECTED) {
    firebaseSyncStatus = "PENDING";
    return -1;
  }
  if (!firebaseSignIn()) return -1;
  BearSSL::WiFiClientSecure client;
  client.setInsecure();
  HTTPClient https;
  String url = String(FIRESTORE_URL) + "/attendance?documentId=" + eventId;
  if (!https.begin(client, url)) {
    firebaseSyncStatus = "ERROR";
    setLatestError("Khong tao duoc ket noi dong bo cham cong");
    return -1;
  }
  https.setTimeout(5000);
  https.addHeader("Content-Type", "application/json");
  https.addHeader("Authorization", "Bearer " + firebaseIdToken);
  int code = https.POST(payload);
  String response = https.getString();
  https.end();
  Serial.printf("ATTENDANCE HTTP %d: %s\n", code, response.c_str());
  if (code == 401) {
    // Force a fresh anonymous token on the next attempt. A stale token is a
    // transient auth failure and must not be discarded from the outbox.
    firebaseIdToken = "";
    tokenCreatedAt = 0;
  }
  if (!httpResponseAcknowledgesEvent(code)) {
    firebaseSyncStatus = "ERROR";
    if (code == 403) {
      setLatestError("Attendance 403: mapping/nhan vien/Rules tu choi");
    } else {
      setLatestError(String("Dong bo cham cong HTTP ") + code);
    }
  }
  return code;
}

bool httpResponseAcknowledgesEvent(int code) {
  return (code >= 200 && code < 300) || code == 409;
}

bool isPermanentAttendanceFailure(int code) {
  // A permission/validation response cannot succeed by retrying the same
  // immutable payload. Keep transport/auth throttling failures retryable.
  return code >= 400 && code < 500 && code != 401 && code != 408 &&
         code != 409 && code != 425 && code != 429;
}

bool flushAttendanceOutbox() {
  if (!littleFsReady) {
    firebaseSyncStatus = "ERROR";
    setLatestError("Khong mo duoc hang doi LittleFS");
    return false;
  }
  File input = LittleFS.open(ATTENDANCE_OUTBOX_PATH, "r");
  if (!input || input.size() == 0) {
    if (input) input.close();
    firebaseSyncStatus = WiFi.status() == WL_CONNECTED ? "ONLINE" : "PENDING";
    return true;
  }
  String remaining;
  bool blocked = false;
  bool syncError = false;
  bool permanentError = false;
  while (input.available()) {
    String line = input.readStringUntil('\n');
    line.trim();
    if (line.length() == 0) continue;
    if (blocked) {
      remaining += line + '\n';
      continue;
    }
    DynamicJsonDocument record(2048);
    DeserializationError error = deserializeJson(record, line);
    String eventId = record["eventId"] | "";
    String payload = record["payload"] | "";
    int code = error || eventId.length() == 0 || payload.length() == 0 ? -1 : postAttendanceEvent(eventId, payload);
    if (httpResponseAcknowledgesEvent(code)) {
      Serial.printf("OUTBOX da dong bo event %s\n", eventId.c_str());
    } else if (isPermanentAttendanceFailure(code)) {
      // Do not let one invalid/retired fingerprint event block every later
      // attendance event. The rejected event is intentionally not retried;
      // its HTTP code is retained in the device log/lastError for diagnosis.
      permanentError = true;
      lastRejectedAttendanceEventId = eventId;
      Serial.printf("OUTBOX bo qua event %s, loi vinh vien HTTP %d\n", eventId.c_str(), code);
    } else {
      blocked = true;
      if (WiFi.status() == WL_CONNECTED) syncError = true;
      remaining += line + '\n';
      Serial.printf("OUTBOX giu event %s, HTTP %d\n", eventId.c_str(), code);
    }
    yield();
  }
  input.close();
  if (remaining.length() == 0) {
    LittleFS.remove(ATTENDANCE_OUTBOX_PATH);
    LittleFS.remove(ATTENDANCE_OUTBOX_TMP_PATH);
    LittleFS.remove(ATTENDANCE_OUTBOX_BACKUP_PATH);
  } else {
    if (!installLittleFsText(ATTENDANCE_OUTBOX_PATH, ATTENDANCE_OUTBOX_TMP_PATH,
                             ATTENDANCE_OUTBOX_BACKUP_PATH, remaining)) {
      firebaseSyncStatus = "ERROR";
      setLatestError("Khong cap nhat duoc hang doi LittleFS");
      return false;
    }
  }
  bool synced = remaining.length() == 0;
  firebaseSyncStatus = permanentError
      ? "ERROR"
      : (synced && WiFi.status() == WL_CONNECTED
      ? "ONLINE"
      : (syncError ? "ERROR" : "PENDING"));
  return synced && !permanentError;
}

size_t attendanceOutboxBytes() {
  if (!littleFsReady) return 0;
  File input = LittleFS.open(ATTENDANCE_OUTBOX_PATH, "r");
  if (!input) return 0;
  size_t bytes = input.size();
  input.close();
  return bytes;
}

int attendancePendingCount() {
  if (!littleFsReady) return 0;
  File input = LittleFS.open(ATTENDANCE_OUTBOX_PATH, "r");
  if (!input) return 0;
  int count = 0;
  while (input.available()) {
    String line = input.readStringUntil('\n');
    String eventId;
    String payload;
    if (parseAttendanceOutboxRecord(line, eventId, payload)) count++;
    yield();
  }
  input.close();
  return count;
}

