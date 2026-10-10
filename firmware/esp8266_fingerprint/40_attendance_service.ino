// Heartbeat, fingerprint mapping, and attendance upload.

// Alternate due background telemetry with FIFO attempts across transport backoff.
bool heartbeatWaitingForAttendanceAttempt = false;

bool publishDeviceSnapshot() {
  if (doorNeedsResponsiveLoop()) { firebaseSyncStatus = "PENDING"; return false; }
  if (WiFi.status() != WL_CONNECTED) {
    Serial.printf_P(PSTR("HEARTBEAT bo qua: WiFi status=%d\n"), WiFi.status());
    firebaseSyncStatus = "PENDING";
    return false;
  }
  if (!firebaseSignIn()) {
    Serial.println(F("HEARTBEAT bo qua: Firebase Auth that bai"));
    return false;
  }

  int templateCount = -1;
  if (sensorReady) {
    uint8_t templateStatus = finger.getTemplateCount();
    if (templateStatus == FINGERPRINT_OK) {
      templateCount = finger.templateCount;
    } else {
      setSensorError(F("AS608 loi khi doc so mau van tay"));
    }
  }

  const int pendingCount = attendancePendingCount();
  const size_t pendingBytes = attendanceOutboxBytes();
  const char* outboxStatus = attendanceOutboxStatus();
  const bool outboxError = strcmp(outboxStatus, "FULL") == 0 || strcmp(outboxStatus, "ERROR") == 0;
  const bool sendCapabilities = capabilitiesNeedSync;
  int code = -1;
  bool began = false;
  {
    String body;
    {
      // The JSON tree is released before TLS allocates its receive buffer.
      DynamicJsonDocument doc(sendCapabilities ? 4096 : 2560);
      JsonObject write = doc.createNestedArray("writes").createNestedObject();
      JsonObject update = write.createNestedObject("update");
      String documentName = FIRESTORE_URL;
      const int prefix = documentName.indexOf("/v1/");
      if (prefix < 0) {
        firebaseSyncStatus = "ERROR";
        setLatestError(F("Duong dan Firestore heartbeat khong hop le"));
        return false;
      }
      update["name"] = documentName.substring(prefix + 4) + "/devices/" + DEVICE_ID;
      JsonObject fields = update.createNestedObject("fields");
      fields["deviceId"]["stringValue"] = DEVICE_ID;
      fields["status"]["stringValue"] = "ONLINE";
      fields["firmwareVersion"]["stringValue"] = FIRMWARE_VERSION;
      if (templateCount >= 0) fields["fingerprintCount"]["integerValue"] = templateCount;
      fields["capacity"]["integerValue"] = 127;
      fields["pendingAttendanceCount"]["integerValue"] = pendingCount;
      fields["wifiStatus"]["stringValue"] = "ONLINE";
      fields["firebaseSyncStatus"]["stringValue"] = outboxError
          ? "ERROR"
          : (pendingCount == 0 ? "ONLINE" : "PENDING");
      fields["pendingAttendanceBytes"]["integerValue"] = pendingBytes;
      fields["pendingAttendanceCapacity"]["integerValue"] = ATTENDANCE_OUTBOX_MAX_BYTES;
      fields["attendanceOutboxStatus"]["stringValue"] = outboxStatus;
      fields["sensorStatus"]["stringValue"] = sensorStatus;
      fields["doorStatus"]["stringValue"] = doorStatus;
      fields["failedScanCount"]["integerValue"] = recentFailedScanCount();
      fields["lastError"]["stringValue"] = lastError;
      if (sendCapabilities) {
        JsonObject capabilityField = fields.createNestedObject("capabilities");
        JsonObject arrayValue = capabilityField.createNestedObject("arrayValue");
        JsonArray values = arrayValue.createNestedArray("values");
        const char* capabilities[] = {"fingerprint", "attendance", "enrollment", "deletion", "led", "buzzer", "door", "heartbeat", "sync", "restart"};
        for (const char* capability : capabilities) {
          JsonObject item = values.createNestedObject();
          item["stringValue"] = capability;
        }
      }
      JsonArray mask = write.createNestedObject("updateMask").createNestedArray("fieldPaths");
      const char* updatedFields[] = {"deviceId", "status", "firmwareVersion", "capacity",
          "pendingAttendanceCount", "pendingAttendanceBytes", "pendingAttendanceCapacity",
          "attendanceOutboxStatus", "wifiStatus", "firebaseSyncStatus", "sensorStatus",
          "doorStatus", "failedScanCount", "lastError"};
      for (const char* field : updatedFields) mask.add(field);
      if (sendCapabilities) mask.add("capabilities");
      if (templateCount >= 0) mask.add("fingerprintCount");
      // Presence uses Firestore request time even before NTP is ready. Scans
      // retain their original device timestamps and existing clock validation.
      JsonObject heartbeat = write.createNestedArray("updateTransforms").createNestedObject();
      heartbeat["fieldPath"] = "lastHeartbeat";
      heartbeat["setToServerValue"] = "REQUEST_TIME";
      if (doc.overflowed()) {
        firebaseSyncStatus = "ERROR";
        setLatestError(F("Bo nho JSON heartbeat khong du"));
        return false;
      }
      const size_t bodySize = measureJson(doc);
      if (!body.reserve(bodySize + 1) || serializeJson(doc, body) != bodySize) {
        firebaseSyncStatus = "PENDING";
        deferHttpsRequests("JSON heartbeat");
        setLatestError(F("Khong du RAM tao heartbeat; se thu lai"));
        return false;
      }
    }
    String url = String(FIRESTORE_URL) + ":commit";
    if (!canStartHttpsRequest("heartbeat")) {
      firebaseSyncStatus = "PENDING";
      return false;
    }
    {
      BearSSL::WiFiClientSecure client;
      client.setInsecure();
      HTTPClient https;
      if (https.begin(client, url)) {
        began = true;
        // HTTPClient has copied the URL; release its temporary heap block.
        url = String();
        https.setTimeout(5000);
        https.addHeader("Content-Type", "application/json");
        https.addHeader("Authorization", "Bearer " + firebaseIdToken);
        if (!canStartHttpsRequest("heartbeat", true)) {
          https.end();
          client.stop();
          firebaseSyncStatus = "PENDING";
          return false;
        }
        code = https.sendRequest("POST", reinterpret_cast<const uint8_t*>(body.c_str()), body.length());
        https.end();
      }
      client.stop();
    }
  }

  if (!began) {
    firebaseSyncStatus = "ERROR";
    deferHttpsRequests("heartbeat");
    setLatestError(F("Khong tao duoc ket noi heartbeat"));
    return false;
  }
  recordHttpsResult("heartbeat", code);
  Serial.printf_P(PSTR("HEARTBEAT HTTP %d\n"), code);
  const bool synced = code >= 200 && code < 300;
  if (synced) {
    if (sendCapabilities) capabilitiesNeedSync = false;
    firebaseSyncStatus = outboxError ? "ERROR" : (pendingCount == 0 ? "ONLINE" : "PENDING");
  } else {
    if (code == 401) {
      firebaseIdToken = "";
      tokenCreatedAt = 0;
    }
    firebaseSyncStatus = "ERROR";
    setLatestError(String("Heartbeat HTTP ") + code);
  }
  return synced;
}

void maybePublishDeviceSnapshot() {
  if (doorNeedsResponsiveLoop() ||
      enrollmentStage != EnrollmentStage::IDLE ||
      ((pendingCommandExecution || pendingCommandResult) && pendingCommandType != "SYNC_ATTENDANCE") ||
      !foregroundAttendanceHandled || httpsRetryCooldownActive()) return;
  const bool queuedAttendance = attendanceOutboxBytes() > 0;
  if (queuedAttendance && heartbeatWaitingForAttendanceAttempt) return;
  // Give both operations a turn when transport failures share one cooldown.
  // Foreground scans keep priority; a background backlog cannot hide presence.
  if (lastHeartbeat == 0 || elapsedAtLeast(millis(), lastHeartbeat, HEARTBEAT_INTERVAL_MS)) {
    lastHeartbeat = millis();
    heartbeatWaitingForAttendanceAttempt = queuedAttendance;
    publishDeviceSnapshot();
  }
}

bool getFingerprintMapping(uint16_t templateId, String& employeeId, String& employeeName) {
  lastFingerprintAuthorizationUnavailable = false;
  lastFingerprintAuthorizationDenied = false;
  employeeId = "";
  employeeName = "";
  if (WiFi.status() != WL_CONNECTED) {
    lastFingerprintAuthorizationUnavailable = true;
    setLatestError(F("Khong co mang de xac thuc mo cua"));
    return false;
  }
  if (!firebaseSignIn()) {
    lastFingerprintAuthorizationUnavailable = true;
    return false;
  }
  if (!canStartHttpsRequest("xac thuc van tay")) {
    lastFingerprintAuthorizationUnavailable = true;
    return false;
  }

  int code = -1;
  bool began = false;
  bool parsed = false;
  bool enabled = false;
  {
    String url = String(FIRESTORE_URL) + "/fingerprintMappings/" + String(templateId) +
                 "?mask.fieldPaths=enabled"
                 "&mask.fieldPaths=employeeId"
                 "&mask.fieldPaths=employeeName";
    BearSSL::WiFiClientSecure client;
    client.setInsecure();
    HTTPClient https;
    https.useHTTP10(true);
    if (https.begin(client, url)) {
      began = true;
      url = String();
      https.setTimeout(5000);
      https.addHeader("Authorization", "Bearer " + firebaseIdToken);
      if (!canStartHttpsRequest("xac thuc van tay", true)) {
        https.end();
        client.stop();
        lastFingerprintAuthorizationUnavailable = true;
        return false;
      }
      code = https.GET();
      if (code == 200) {
        DynamicJsonDocument doc(1024);
        if (!deserializeJson(doc, https.getStream())) {
          enabled = doc["fields"]["enabled"]["booleanValue"].as<bool>();
          employeeId = doc["fields"]["employeeId"]["stringValue"].as<String>();
          employeeName = doc["fields"]["employeeName"]["stringValue"].as<String>();
          employeeId.trim();
          employeeName.trim();
          parsed = true;
        }
      }
      https.end();
    }
    client.stop();
  }

  if (!began) {
    lastFingerprintAuthorizationUnavailable = true;
    deferHttpsRequests("xac thuc van tay");
    setLatestError(F("Khong tao duoc ket noi xac thuc van tay"));
    return false;
  }
  recordHttpsResult("xac thuc van tay", code);
  if (code != 200) {
    lastFingerprintAuthorizationUnavailable = code < 0 || code == 401 ||
        code == 408 || code == 425 || code == 429 || code >= 500;
    lastFingerprintAuthorizationDenied = !lastFingerprintAuthorizationUnavailable;
    if (lastFingerprintAuthorizationUnavailable) {
      setLatestError(String("Xac thuc van tay HTTP ") + code);
    }
    return false;
  }
  if (!parsed) {
    lastFingerprintAuthorizationUnavailable = true;
    setLatestError(F("Du lieu xac thuc van tay khong hop le"));
    return false;
  }
  if (!enabled || employeeId.length() == 0 || employeeName.length() == 0) {
    lastFingerprintAuthorizationDenied = true;
    Serial.printf_P(PSTR("Template %u khong duoc phep mo cua\n"), templateId);
    return false;
  }
  return true;
}

bool buildAttendanceEvent(uint16_t templateId, uint16_t confidence,
                          String& eventId, String& payload,
                          String& employeeName, String& attendanceTime,
                          String& attendanceType) {
  employeeName = "";
  attendanceTime = "--:--:--";
  attendanceType = "SCAN";
  lastAttendanceCreatedOffline = false;
  lastAttendanceMissingClock = false;
  // Capture scan time before any network wait so a failed GET cannot move
  // the attendance timestamp to the later retry/connection-failure time.
  time_t now = time(nullptr);
  String employeeId;
  const bool mapped = getFingerprintMapping(templateId, employeeId, employeeName);
  if (!mapped && (!OFFLINE_AS608_ACCESS_ENABLED || !lastFingerprintAuthorizationUnavailable)) return false;
  if (!mapped) lastFingerprintAuthorizationUnavailable = false;
  if (now < MIN_VALID_UNIX_TIME) {
    lastAttendanceMissingClock = true;
    Serial.println(F("Chua dong bo duoc thoi gian NTP"));
    setLatestError(F("Chua co gio hop le de luu luot quet"));
    return false;
  }
  if (!reserveAttendanceEventId(eventId)) return false;
  String timestamp = utcTimestamp(now);
  time_t localNow = now + 7 * 3600;
  struct tm localTime;
  gmtime_r(&localNow, &localTime);
  char timeValue[9];
  strftime(timeValue, sizeof(timeValue), "%H:%M:%S", &localTime);
  attendanceTime = String(timeValue);

  DynamicJsonDocument doc(1536);
  if (!mapped) {
    // Only this scan is persisted. The AS608 owns the templates; the ESP owns
    // no employee roster. Resolve identity online later, before posting.
    doc["offlineScan"] = true;
    doc["deviceId"] = DEVICE_ID;
    doc["templateId"] = templateId;
    doc["confidence"] = confidence;
    doc["timestamp"] = timestamp;
    serializeJson(doc, payload);
    employeeName = String("VAN TAY #") + templateId;
    lastAttendanceCreatedOffline = true;
    return true;
  }
  JsonObject fields = doc.createNestedObject("fields");
  fields["employeeId"]["stringValue"] = employeeId;
  fields["employeeName"]["stringValue"] = employeeName;
  fields["deviceId"]["stringValue"] = DEVICE_ID;
  fields["templateId"]["integerValue"] = templateId;
  fields["confidence"]["integerValue"] = confidence;
  fields["type"]["stringValue"] = "SCAN";
  fields["resolutionStatus"]["stringValue"] = "PENDING";
  fields["status"]["stringValue"] = "PENDING";
  fields["syncStatus"]["stringValue"] = "PENDING_SYNC";
  fields["timestamp"]["timestampValue"] = timestamp;
  fields["verified"]["booleanValue"] = true;
  payload.reserve(measureJson(doc) + 1);
  serializeJson(doc, payload);
  return true;
}

int resolveOfflineAttendancePayload(const String& payload, String& resolvedPayload) {
  uint16_t templateId = 0;
  uint16_t confidence = 0;
  String timestamp;
  {
    DynamicJsonDocument scan(2048);
    if (deserializeJson(scan, payload)) return 400;
    if (!scan["offlineScan"].as<bool>()) {
      resolvedPayload = payload;
      return 200;
    }
    const int rawTemplateId = scan["templateId"].as<int>();
    const int rawConfidence = scan["confidence"].as<int>();
    timestamp = scan["timestamp"].as<String>();
    const String sourceDevice = scan["deviceId"].as<String>();
    if (rawTemplateId < 1 || rawTemplateId > 127 || rawConfidence < 0 ||
        rawConfidence > 65535 || sourceDevice != DEVICE_ID || timestamp.length() < 20) return 400;
    templateId = rawTemplateId;
    confidence = rawConfidence;
  }
  String employeeId;
  String employeeName;
  if (!getFingerprintMapping(templateId, employeeId, employeeName)) {
    // Explicitly disabled/deleted mappings are rejected. A failed connection
    // retains the original raw scan for the next attempt.
    return lastFingerprintAuthorizationDenied ? 403 : -1;
  }
  DynamicJsonDocument doc(1536);
  JsonObject fields = doc.createNestedObject("fields");
  fields["employeeId"]["stringValue"] = employeeId;
  fields["employeeName"]["stringValue"] = employeeName;
  fields["deviceId"]["stringValue"] = DEVICE_ID;
  fields["templateId"]["integerValue"] = templateId;
  fields["confidence"]["integerValue"] = confidence;
  fields["type"]["stringValue"] = "SCAN";
  fields["resolutionStatus"]["stringValue"] = "PENDING";
  fields["status"]["stringValue"] = "PENDING";
  fields["syncStatus"]["stringValue"] = "PENDING_SYNC";
  fields["timestamp"]["timestampValue"] = timestamp;
  fields["verified"]["booleanValue"] = true;
  resolvedPayload.reserve(measureJson(doc) + 1);
  serializeJson(doc, resolvedPayload);
  return 200;
}

void invalidateForegroundAttendanceAccess() {
  foregroundAttendanceHandled = true;
  foregroundAttendanceEventId = "";
  foregroundAttendanceEmployeeName = "";
  foregroundAttendanceDelivery = AttendanceDelivery::NOT_STORED;
}

AttendanceDelivery uploadAttendance(uint16_t templateId, uint16_t confidence,
                      String& employeeName, String& attendanceTime,
                      String& attendanceType) {
  // Every new attempt supersedes only the old in-RAM door authorization.
  // Old durable records remain queued even if this attempt cannot be saved.
  invalidateForegroundAttendanceAccess();
  String eventId;
  String payload;
  if (!buildAttendanceEvent(templateId, confidence, eventId, payload, employeeName, attendanceTime, attendanceType)) {
    if (lastAttendanceMissingClock && OFFLINE_AS608_ACCESS_ENABLED) {
      foregroundAttendanceDelivery = AttendanceDelivery::ACCESS_ONLY;
      return AttendanceDelivery::ACCESS_ONLY;
    }
    return lastFingerprintAuthorizationDenied ? AttendanceDelivery::REJECTED : AttendanceDelivery::NOT_STORED;
  }
  if (!enqueueAttendanceEvent(eventId, payload)) return AttendanceDelivery::NOT_STORED;
  foregroundAttendanceEventId = eventId;
  foregroundAttendanceEmployeeName = employeeName;
  foregroundAttendanceCreatedAt = millis();
  foregroundAttendanceHandled = false;
  foregroundAttendanceDelivery = AttendanceDelivery::QUEUED;
  attendancePendingSync = true;
  attendanceSyncIntervalMs = ATTENDANCE_NEXT_RECORD_INTERVAL_MS;
  lastAttendanceSync = millis() - attendanceSyncIntervalMs;
  return AttendanceDelivery::QUEUED;
}

void handleAttendanceDelivery(const String& eventId, AttendanceDelivery delivery) {
  const bool matchesCurrent = foregroundAttendanceEventId.length() > 0 && eventId == foregroundAttendanceEventId;
  const bool mayOpen = foregroundConfirmationCanOpen(matchesCurrent, foregroundAttendanceHandled,
      millis(), foregroundAttendanceCreatedAt, FOREGROUND_ATTENDANCE_VALIDITY_MS);
  if (!matchesCurrent || foregroundAttendanceHandled) return;
  foregroundAttendanceHandled = true;
  foregroundAttendanceDelivery = delivery;
  const bool localAccess = delivery == AttendanceDelivery::LOCAL_ACCEPTED && OFFLINE_AS608_ACCESS_ENABLED;
  if ((delivery == AttendanceDelivery::CONFIRMED || localAccess) && mayOpen) {
    showLcd(foregroundAttendanceEmployeeName, F("DANG MO CUA"));
    signalResult(true);
    openDoor();
    fingerprintDoorNoticeActive = true;
    fingerprintDoorNoticeOffline = localAccess;
    fingerprintDoorNoticeAttendanceSaved = true;
    if (localAccess) Serial.printf_P(PSTR("OUTBOX %s: mo cua theo mau AS608; du lieu van cho dong bo\n"), eventId.c_str());
  } else if (delivery == AttendanceDelivery::REJECTED) {
    lastFingerprintAuthorizationDenied = true;
    showFingerprintResultNotice(F("LUOT BI TU CHOI"), F("XIN LIEN HE ADMIN"));
    signalResult(false);
  } else {
    Serial.printf_P(PSTR("OUTBOX %s: xac nhan muon, chi dong bo du lieu\n"), eventId.c_str());
  }
}

void expireForegroundAttendance() {
  if (foregroundAttendanceHandled ||
      !elapsedAtLeast(millis(), foregroundAttendanceCreatedAt, FOREGROUND_ATTENDANCE_VALIDITY_MS)) return;
  foregroundAttendanceHandled = true;
  Serial.printf_P(PSTR("OUTBOX %s: het han mo cua; ban ghi van cho dong bo\n"), foregroundAttendanceEventId.c_str());
  if (!doorNeedsResponsiveLoop() && !pendingCommandExecution && !pendingCommandResult) {
    showLcd(F("DA LUU CHO GUI"), F("CHUA MO CUA"));
  }
}
