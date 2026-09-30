// Heartbeat, fingerprint mapping, and attendance upload.

bool publishDeviceSnapshot() {
  if (WiFi.status() != WL_CONNECTED) {
    Serial.printf("HEARTBEAT bo qua: WiFi status=%d\n", WiFi.status());
    firebaseSyncStatus = "PENDING";
    return false;
  }
  if (!hasValidClock()) {
    Serial.println("HEARTBEAT bo qua: chua dong bo NTP");
    firebaseSyncStatus = "PENDING";
    return false;
  }
  if (attendanceOutboxBytes() > 0) {
    Serial.println("HEARTBEAT bo qua: dang uu tien dong bo cham cong");
    firebaseSyncStatus = "PENDING";
    return false;
  }
  if (!firebaseSignIn()) {
    Serial.println("HEARTBEAT bo qua: Firebase Auth that bai");
    return false;
  }

  int templateCount = -1;
  if (sensorReady) {
    uint8_t templateStatus = finger.getTemplateCount();
    if (templateStatus == FINGERPRINT_OK) {
      templateCount = finger.templateCount;
    } else {
      setSensorError("AS608 loi khi doc so mau van tay");
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
      DynamicJsonDocument doc(sendCapabilities ? 3072 : 1536);
      JsonObject fields = doc.createNestedObject("fields");
      fields["deviceId"]["stringValue"] = DEVICE_ID;
      fields["status"]["stringValue"] = "ONLINE";
      fields["lastHeartbeat"]["timestampValue"] = utcTimestamp();
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
      body.reserve(measureJson(doc) + 1);
      serializeJson(doc, body);
    }
    String url = String(FIRESTORE_URL) + "/devices/" + DEVICE_ID +
                 "?updateMask.fieldPaths=deviceId"
                 "&updateMask.fieldPaths=status"
                 "&updateMask.fieldPaths=lastHeartbeat"
                 "&updateMask.fieldPaths=firmwareVersion"
                 "&updateMask.fieldPaths=capacity"
                 "&updateMask.fieldPaths=pendingAttendanceCount"
                 "&updateMask.fieldPaths=pendingAttendanceBytes"
                 "&updateMask.fieldPaths=pendingAttendanceCapacity"
                 "&updateMask.fieldPaths=attendanceOutboxStatus"
                 "&updateMask.fieldPaths=wifiStatus"
                 "&updateMask.fieldPaths=firebaseSyncStatus"
                 "&updateMask.fieldPaths=sensorStatus"
                 "&updateMask.fieldPaths=doorStatus"
                 "&updateMask.fieldPaths=failedScanCount"
                 "&updateMask.fieldPaths=lastError";
    if (sendCapabilities) url += "&updateMask.fieldPaths=capabilities";
    if (templateCount >= 0) url += "&updateMask.fieldPaths=fingerprintCount";
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
        https.setTimeout(5000);
        https.addHeader("Content-Type", "application/json");
        https.addHeader("Authorization", "Bearer " + firebaseIdToken);
        code = https.sendRequest("PATCH", reinterpret_cast<const uint8_t*>(body.c_str()), body.length());
        https.end();
      }
      client.stop();
    }
  }

  if (!began) {
    firebaseSyncStatus = "ERROR";
    deferHttpsRequests("heartbeat");
    setLatestError("Khong tao duoc ket noi heartbeat");
    return false;
  }
  recordHttpsResult("heartbeat", code);
  Serial.printf("HEARTBEAT HTTP %d\n", code);
  const bool synced = code >= 200 && code < 300;
  if (synced) {
    if (sendCapabilities) capabilitiesNeedSync = false;
    firebaseSyncStatus = outboxError ? "ERROR" : (pendingCount == 0 ? "ONLINE" : "PENDING");
  } else {
    firebaseSyncStatus = "ERROR";
    setLatestError(String("Heartbeat HTTP ") + code);
  }
  return synced;
}

void maybePublishDeviceSnapshot() {
  // Give an unsynchronized attendance event priority over periodic telemetry.
  if (attendanceOutboxBytes() > 0) return;
  if (lastHeartbeat == 0 || millis() - lastHeartbeat >= HEARTBEAT_INTERVAL_MS) {
    lastHeartbeat = millis();
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
    setLatestError("Khong co mang de xac thuc mo cua");
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
      https.setTimeout(5000);
      https.addHeader("Authorization", "Bearer " + firebaseIdToken);
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
    setLatestError("Khong tao duoc ket noi xac thuc van tay");
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
    setLatestError("Du lieu xac thuc van tay khong hop le");
    return false;
  }
  if (!enabled || employeeId.length() == 0 || employeeName.length() == 0) {
    lastFingerprintAuthorizationDenied = true;
    Serial.printf("Template %u khong duoc phep mo cua\n", templateId);
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
  String employeeId;
  if (!getFingerprintMapping(templateId, employeeId, employeeName)) {
    Serial.println("Khong tim thay nhan vien cua template");
    return false;
  }
  if (!reserveAttendanceEventId(eventId)) return false;
  time_t now = time(nullptr);
  if (now < MIN_VALID_UNIX_TIME) {
    Serial.println("Chua dong bo duoc thoi gian NTP");
    return false;
  }
  String timestamp = utcTimestamp(now);
  time_t localNow = now + 7 * 3600;
  struct tm localTime;
  gmtime_r(&localNow, &localTime);
  char timeValue[9];
  strftime(timeValue, sizeof(timeValue), "%H:%M:%S", &localTime);
  attendanceTime = String(timeValue);

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
  payload.reserve(measureJson(doc) + 1);
  serializeJson(doc, payload);
  return true;
}

bool uploadAttendance(uint16_t templateId, uint16_t confidence,
                      String& employeeName, String& attendanceTime,
                      String& attendanceType) {
  String eventId;
  String payload;
  lastRejectedAttendanceEventId = "";
  lastAcknowledgedAttendanceEventId = "";
  if (!buildAttendanceEvent(templateId, confidence, eventId, payload, employeeName, attendanceTime, attendanceType)) return false;
  if (!enqueueAttendanceEvent(eventId, payload)) return false;
  flushAttendanceOutbox();
  attendancePendingSync = attendancePendingCount() > 0;
  if (lastRejectedAttendanceEventId == eventId) {
    // Firestore Rules are the authoritative check for enabled mappings and
    // active employees, so a rejected event must never open the door.
    lastFingerprintAuthorizationDenied = true;
    return false;
  }
  if (lastAcknowledgedAttendanceEventId != eventId) {
    // Keep the event for audit/synchronization, but only grant access after
    // Firestore has accepted the scan while it is being processed.
    lastFingerprintAuthorizationUnavailable = true;
    return false;
  }
  return true;
}
