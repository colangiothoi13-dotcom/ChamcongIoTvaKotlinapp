// LittleFS attendance outbox.

bool parseAttendanceOutboxRecord(const String& line, String& eventId, String& payload) {
  DynamicJsonDocument record(2048);
  if (deserializeJson(record, line)) return false;
  eventId = record["eventId"] | "";
  payload = record["payload"] | "";
  eventId.trim();
  payload.trim();
  return eventId.length() > 0 && payload.length() > 0;
}

// Validate a candidate one record at a time. Recovery used to retain the
// original and normalized forms of all main/temp/backup queues in RAM, which
// can exceed the ESP8266 heap when the offline queue is full.
bool inspectAttendanceOutbox(const char* path, size_t& recordCount) {
  recordCount = 0;
  if (!LittleFS.exists(path)) return false;
  File input = LittleFS.open(path, "r");
  if (!input) return false;
  bool clean = true;
  while (input.available()) {
    String line = input.readStringUntil('\n');
    line.trim();
    if (line.length() == 0) continue;
    String eventId;
    String payload;
    if (!parseAttendanceOutboxRecord(line, eventId, payload)) {
      clean = false;
      yield();
      continue;
    }
    ++recordCount;
    yield();
  }
  input.close();
  return clean;
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

bool promoteAttendanceOutbox(const char* candidate) {
  if (strcmp(candidate, ATTENDANCE_OUTBOX_PATH) == 0) {
    LittleFS.remove(ATTENDANCE_OUTBOX_TMP_PATH);
    LittleFS.remove(ATTENDANCE_OUTBOX_BACKUP_PATH);
    return true;
  }
  if (LittleFS.exists(ATTENDANCE_OUTBOX_PATH)) {
    LittleFS.remove(ATTENDANCE_OUTBOX_PATH);
  }
  if (!LittleFS.rename(candidate, ATTENDANCE_OUTBOX_PATH)) return false;
  if (strcmp(candidate, ATTENDANCE_OUTBOX_TMP_PATH) != 0) {
    LittleFS.remove(ATTENDANCE_OUTBOX_TMP_PATH);
  }
  if (strcmp(candidate, ATTENDANCE_OUTBOX_BACKUP_PATH) != 0) {
    LittleFS.remove(ATTENDANCE_OUTBOX_BACKUP_PATH);
  }
  return true;
}

void recoverAttendanceOutbox() {
  if (!littleFsReady) return;

  size_t mainCount = 0;
  size_t tempCount = 0;
  size_t backupCount = 0;
  const bool mainClean = inspectAttendanceOutbox(ATTENDANCE_OUTBOX_PATH, mainCount);
  const bool tempClean = inspectAttendanceOutbox(ATTENDANCE_OUTBOX_TMP_PATH, tempCount);
  const bool backupClean = inspectAttendanceOutbox(ATTENDANCE_OUTBOX_BACKUP_PATH, backupCount);

  // The live file owns newer appends. Even when one append was torn, a stale
  // clean backup must not replace its surviving records. Flush discards only
  // damaged lines; immutable IDs keep any interrupted acknowledgment safe.
  if (mainClean || mainCount > 0) {
    if (!mainClean) setLatestError(F("Hang doi LittleFS co ban ghi khong hop le"));
    promoteAttendanceOutbox(ATTENDANCE_OUTBOX_PATH);
    return;
  }

  const char* candidate = nullptr;
  size_t candidateCount = 0;
  if (tempClean) {
    candidate = ATTENDANCE_OUTBOX_TMP_PATH;
    candidateCount = tempCount;
  }
  if (backupClean && (candidate == nullptr || backupCount > candidateCount)) {
    candidate = ATTENDANCE_OUTBOX_BACKUP_PATH;
    candidateCount = backupCount;
  }
  if (candidate != nullptr) {
    if (!promoteAttendanceOutbox(candidate)) {
      setLatestError(F("Khong khoi phuc duoc hang doi LittleFS"));
    }
  } else if (LittleFS.exists(ATTENDANCE_OUTBOX_PATH)) {
    // Keep a torn main file; flushAttendanceOutbox() will retain valid records
    // and discard only the bad line without loading the full queue at once.
    setLatestError(F("Hang doi LittleFS co ban ghi khong hop le"));
  }
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
    setLatestError(F("Het bo dem ma su kien"));
    return false;
  }
  // Reserve the next value before adding the event to the outbox. A crash can
  // skip an ID, but it can never reuse an ID for a different scan.
  if (!installLittleFsText(ATTENDANCE_SEQUENCE_PATH, ATTENDANCE_SEQUENCE_TMP_PATH,
                           ATTENDANCE_SEQUENCE_BACKUP_PATH, String(sequence + 1))) {
    setLatestError(F("Khong luu duoc bo dem su kien"));
    return false;
  }
  eventId = String(DEVICE_ID) + "-" + String(ESP.getChipId(), HEX) + "-" + String(sequence);
  return true;
}

bool enqueueAttendanceEvent(const String& eventId, const String& payload) {
  if (!littleFsReady) return false;

  String line;
  {
    DynamicJsonDocument record(2048);
    record["eventId"] = eventId;
    record["payload"] = payload;
    line.reserve(measureJson(record) + 2);
    serializeJson(record, line);
  }
  line += '\n';
  // An interrupted append can leave a partial JSON record without its newline.
  // Separate that tail before adding another scan, so recovery discards only
  // the damaged record rather than also losing the next successful enqueue.
  File input = LittleFS.open(ATTENDANCE_OUTBOX_PATH, "r");
  if (!input && LittleFS.exists(ATTENDANCE_OUTBOX_PATH)) {
    setLatestError(F("Khong doc duoc hang doi LittleFS truoc khi ghi"));
    return false;
  }
  const size_t existingBytes = input ? input.size() : 0;
  bool needsSeparator = false;
  if (existingBytes > 0) {
    if (!input.seek(existingBytes - 1)) {
      input.close();
      setLatestError(F("Khong kiem tra duoc duoi hang doi LittleFS"));
      return false;
    }
    const int lastByte = input.read();
    if (lastByte < 0) {
      input.close();
      setLatestError(F("Khong doc duoc duoi hang doi LittleFS"));
      return false;
    }
    needsSeparator = lastByte != '\n';
  }
  if (input) input.close();
  const size_t separatorBytes = needsSeparator ? 1 : 0;
  if (existingBytes + separatorBytes + line.length() > ATTENDANCE_OUTBOX_MAX_BYTES) {
    Serial.printf_P(PSTR("OUTBOX DAY: tu choi event %s, queue da day (%u/%u bytes)\n"),
                  eventId.c_str(), static_cast<unsigned>(existingBytes),
                  static_cast<unsigned>(ATTENDANCE_OUTBOX_MAX_BYTES));
    setLatestError(F("HANG DOI DAY - KHONG LUU DUOC SU KIEN"));
    return false;
  }
  File output = LittleFS.open(ATTENDANCE_OUTBOX_PATH, "a");
  if (!output) return false;
  if (needsSeparator && output.print('\n') != 1) {
    output.close();
    setLatestError(F("Khong tach duoc ban ghi hang doi LittleFS"));
    return false;
  }
  size_t written = output.print(line);
  output.flush();
  output.close();
  if (written != line.length()) {
    setLatestError(F("Khong ghi duoc su kien vao LittleFS"));
    return false;
  }
  Serial.printf_P(PSTR("OUTBOX them event %s, bytes=%u\n"), eventId.c_str(),
                static_cast<unsigned>(existingBytes + separatorBytes + line.length()));
  return true;
}

bool httpResponseAcknowledgesEvent(int code);

int postAttendanceEvent(const String& eventId, const String& payload) {
  if (WiFi.status() != WL_CONNECTED) {
    firebaseSyncStatus = "PENDING";
    return -1;
  }
  if (!firebaseSignIn()) return -1;
  if (!canStartHttpsRequest("dong bo cham cong")) {
    firebaseSyncStatus = "PENDING";
    return -1;
  }

  int code = -1;
  bool began = false;
  {
    String url = String(FIRESTORE_URL) + "/attendance?documentId=" + eventId;
    BearSSL::WiFiClientSecure client;
    client.setInsecure();
    HTTPClient https;
    if (https.begin(client, url)) {
      began = true;
      url = String();
      https.setTimeout(5000);
      https.addHeader("Content-Type", "application/json");
      https.addHeader("Authorization", "Bearer " + firebaseIdToken);
      if (!canStartHttpsRequest("dong bo cham cong", true)) {
        https.end();
        client.stop();
        firebaseSyncStatus = "PENDING";
        return -1;
      }
      code = https.POST(reinterpret_cast<const uint8_t*>(payload.c_str()), payload.length());
      https.end();
    }
    client.stop();
  }

  if (!began) {
    firebaseSyncStatus = "ERROR";
    deferHttpsRequests("dong bo cham cong");
    setLatestError(F("Khong tao duoc ket noi dong bo cham cong"));
    return -1;
  }
  recordHttpsResult("dong bo cham cong", code);
  Serial.printf_P(PSTR("ATTENDANCE HTTP %d\n"), code);
  if (code == 401) {
    // Force a fresh anonymous token on the next attempt. A stale token is a
    // transient auth failure and must not be discarded from the outbox.
    firebaseIdToken = "";
    tokenCreatedAt = 0;
  }
  if (!httpResponseAcknowledgesEvent(code)) {
    firebaseSyncStatus = "ERROR";
    if (code == 403) {
      setLatestError(F("Attendance 403: mapping/nhan vien/Rules tu choi"));
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

// The old implementation accumulated every unsent line in a String before
// rewriting LittleFS. That can be the full 12 KB queue while TLS needs a
// contiguous receive buffer. This copies only the tail of the file in 128 B
// chunks after the current record has been accepted or discarded.
bool removeAttendanceOutboxHead(size_t tailOffset) {
  File input = LittleFS.open(ATTENDANCE_OUTBOX_PATH, "r");
  if (!input) return false;
  if (tailOffset >= input.size()) {
    input.close();
    LittleFS.remove(ATTENDANCE_OUTBOX_PATH);
    LittleFS.remove(ATTENDANCE_OUTBOX_TMP_PATH);
    LittleFS.remove(ATTENDANCE_OUTBOX_BACKUP_PATH);
    return true;
  }
  if (!input.seek(tailOffset)) {
    input.close();
    return false;
  }

  File output = LittleFS.open(ATTENDANCE_OUTBOX_TMP_PATH, "w");
  if (!output) {
    input.close();
    return false;
  }

  uint8_t buffer[128];
  bool copied = true;
  while (input.available()) {
    const size_t readBytes = input.read(buffer, sizeof(buffer));
    if (readBytes == 0 || output.write(buffer, readBytes) != readBytes) {
      copied = false;
      break;
    }
    yield();
  }
  output.flush();
  output.close();
  input.close();
  if (!copied) {
    LittleFS.remove(ATTENDANCE_OUTBOX_TMP_PATH);
    return false;
  }

  if (LittleFS.exists(ATTENDANCE_OUTBOX_BACKUP_PATH)) {
    LittleFS.remove(ATTENDANCE_OUTBOX_BACKUP_PATH);
  }
  bool movedOld = false;
  if (LittleFS.exists(ATTENDANCE_OUTBOX_PATH)) {
    movedOld = LittleFS.rename(ATTENDANCE_OUTBOX_PATH, ATTENDANCE_OUTBOX_BACKUP_PATH);
    if (!movedOld) {
      LittleFS.remove(ATTENDANCE_OUTBOX_TMP_PATH);
      return false;
    }
  }
  if (!LittleFS.rename(ATTENDANCE_OUTBOX_TMP_PATH, ATTENDANCE_OUTBOX_PATH)) {
    if (movedOld) {
      LittleFS.remove(ATTENDANCE_OUTBOX_PATH);
      LittleFS.rename(ATTENDANCE_OUTBOX_BACKUP_PATH, ATTENDANCE_OUTBOX_PATH);
    }
    return false;
  }
  LittleFS.remove(ATTENDANCE_OUTBOX_BACKUP_PATH);
  return true;
}

AttendanceSyncResult flushAttendanceOutbox() {
  if (!littleFsReady) {
    firebaseSyncStatus = "ERROR";
    setLatestError(F("Khong mo duoc hang doi LittleFS"));
    return AttendanceSyncResult::STORAGE_ERROR;
  }
  if (!canStartHttpsRequest("hang doi cham cong")) {
    firebaseSyncStatus = "PENDING";
    return AttendanceSyncResult::DEFERRED;
  }
  File input = LittleFS.open(ATTENDANCE_OUTBOX_PATH, "r");
  if (!input && LittleFS.exists(ATTENDANCE_OUTBOX_PATH)) {
    firebaseSyncStatus = "ERROR";
    setLatestError(F("Khong doc duoc hang doi LittleFS"));
    return AttendanceSyncResult::STORAGE_ERROR;
  }
  if (!input || input.size() == 0) {
    if (input) input.close();
    firebaseSyncStatus = WiFi.status() == WL_CONNECTED ? "ONLINE" : "PENDING";
    return AttendanceSyncResult::EMPTY;
  }
  String line = input.readStringUntil('\n');
  const size_t tailOffset = input.position();
  input.close();
  line.trim();
  if (line.length() == 0) {
    const bool removed = removeAttendanceOutboxHead(tailOffset);
    if (!removed) {
      firebaseSyncStatus = "ERROR";
      setLatestError(F("Khong cap nhat duoc hang doi LittleFS"));
    }
    return removed ? AttendanceSyncResult::ACKNOWLEDGED : AttendanceSyncResult::STORAGE_ERROR;
  }

  String eventId;
  String payload;
  bool validRecord = false;
  {
    DynamicJsonDocument record(2048);
    if (!deserializeJson(record, line)) {
      eventId = record["eventId"] | "";
      payload = record["payload"] | "";
      validRecord = eventId.length() > 0 && payload.length() > 0;
    }
  }

  if (!validRecord) {
    const bool removed = removeAttendanceOutboxHead(tailOffset);
    firebaseSyncStatus = "ERROR";
    if (!removed) setLatestError(F("Khong cap nhat duoc hang doi LittleFS"));
    else setLatestError(F("Bo qua ban ghi hang doi khong hop le"));
    return removed ? AttendanceSyncResult::REJECTED : AttendanceSyncResult::STORAGE_ERROR;
  }

  // Resolve raw offline scans one at a time, without an employee list in RAM
  // or flash. Discard temporary JSON/text before the TLS POST allocates memory.
  line = "";
  int preparationCode = 200;
  {
    String resolvedPayload;
    preparationCode = resolveOfflineAttendancePayload(payload, resolvedPayload);
    if (preparationCode == 200) payload = resolvedPayload;
  }
  const int code = preparationCode == 200 ? postAttendanceEvent(eventId, payload) : preparationCode;
  if (!httpResponseAcknowledgesEvent(code) && !isPermanentAttendanceFailure(code)) {
    firebaseSyncStatus = WiFi.status() == WL_CONNECTED ? "ERROR" : "PENDING";
    Serial.printf_P(PSTR("OUTBOX giu event %s, HTTP %d\n"), eventId.c_str(), code);
    if (OFFLINE_AS608_ACCESS_ENABLED && attendanceTransportUnavailable(code)) {
      // The saved foreground scan was already matched by the AS608. Grant it
      // once under the selected offline policy; old/restored scans cannot open.
      handleAttendanceDelivery(foregroundAttendanceEventId, AttendanceDelivery::LOCAL_ACCEPTED);
    }
    return AttendanceSyncResult::RETRY;
  }

  const bool permanentFailure = isPermanentAttendanceFailure(code);
  if (permanentFailure) {
    lastRejectedAttendanceEventId = eventId;
    if (preparationCode != 200) setLatestError(F("Luot ngoai tuyen bi tu choi: mapping khong hop le"));
    Serial.printf_P(PSTR("OUTBOX bo qua event %s, loi vinh vien HTTP %d\n"), eventId.c_str(), code);
  } else {
    lastAcknowledgedAttendanceEventId = eventId;
    Serial.printf_P(PSTR("OUTBOX da dong bo event %s\n"), eventId.c_str());
  }

  if (!removeAttendanceOutboxHead(tailOffset)) {
    firebaseSyncStatus = "ERROR";
    setLatestError(F("Khong cap nhat duoc hang doi LittleFS"));
    // The server acknowledgment is still valid. A replay after a failed local
    // rewrite is idempotent; it must never grant a second door opening.
    handleAttendanceDelivery(eventId, permanentFailure ? AttendanceDelivery::REJECTED : AttendanceDelivery::CONFIRMED);
    return AttendanceSyncResult::STORAGE_ERROR;
   }
  firebaseSyncStatus = permanentFailure
      ? "ERROR"
      : (attendanceOutboxBytes() == 0 && WiFi.status() == WL_CONNECTED ? "ONLINE" : "PENDING");
  handleAttendanceDelivery(eventId, permanentFailure ? AttendanceDelivery::REJECTED : AttendanceDelivery::CONFIRMED);
  return permanentFailure ? AttendanceSyncResult::REJECTED : AttendanceSyncResult::ACKNOWLEDGED;
}

bool attendanceOutboxIsEmpty() {
  if (!littleFsReady) return false;
  if (!LittleFS.exists(ATTENDANCE_OUTBOX_PATH)) return true;
  File input = LittleFS.open(ATTENDANCE_OUTBOX_PATH, "r");
  if (!input) return false;
  const bool empty = input.size() == 0;
  input.close();
  return empty;
}

size_t attendanceOutboxBytes() {
  if (!littleFsReady) return 0;
  File input = LittleFS.open(ATTENDANCE_OUTBOX_PATH, "r");
  if (!input) return 0;
  size_t bytes = input.size();
  input.close();
  return bytes;
}

bool attendanceOutboxIsFull() {
  return attendanceOutboxBytes() >= ATTENDANCE_OUTBOX_MAX_BYTES;
}

const char* attendanceOutboxStatus() {
  if (!littleFsReady) return "ERROR";
  const size_t bytes = attendanceOutboxBytes();
  if (bytes >= ATTENDANCE_OUTBOX_MAX_BYTES) return "FULL";
  if (bytes >= ATTENDANCE_OUTBOX_WARN_BYTES) return "WARNING";
  return "OK";
}

int attendancePendingCount() {
  if (!littleFsReady) return 0;
  File input = LittleFS.open(ATTENDANCE_OUTBOX_PATH, "r");
  if (!input) return 0;
  int count = 0;
  bool hasContent = false;
  uint16_t bytesRead = 0;
  while (input.available()) {
    const int value = input.read();
    if (value == '\n') {
      if (hasContent) ++count;
      hasContent = false;
    } else if (value != '\r' && value != ' ' && value != '\t') {
      hasContent = true;
    }
    if (++bytesRead % 64 == 0) yield();
  }
  if (hasContent) ++count;
  input.close();
  return count;
}
