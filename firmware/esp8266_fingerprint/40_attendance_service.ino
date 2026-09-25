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

  BearSSL::WiFiClientSecure client;
  client.setInsecure();
  HTTPClient https;
  int pendingCount = attendancePendingCount();
  String url = String(FIRESTORE_URL) + "/devices/" + DEVICE_ID +
               "?updateMask.fieldPaths=deviceId"
               "&updateMask.fieldPaths=status"
               "&updateMask.fieldPaths=lastHeartbeat"
               "&updateMask.fieldPaths=firmwareVersion"
               "&updateMask.fieldPaths=capacity"
               "&updateMask.fieldPaths=pendingAttendanceCount"
               "&updateMask.fieldPaths=capabilities"
               "&updateMask.fieldPaths=wifiStatus"
               "&updateMask.fieldPaths=firebaseSyncStatus"
               "&updateMask.fieldPaths=sensorStatus"
               "&updateMask.fieldPaths=doorStatus"
               "&updateMask.fieldPaths=failedScanCount"
               "&updateMask.fieldPaths=lastError";
  if (templateCount >= 0) url += "&updateMask.fieldPaths=fingerprintCount";
  if (!https.begin(client, url)) {
    Serial.println("HEARTBEAT loi: khong tao duoc ket noi HTTPS");
    firebaseSyncStatus = "ERROR";
    setLatestError("Khong tao duoc ket noi heartbeat");
    return false;
  }
  https.setTimeout(5000);
  https.addHeader("Content-Type", "application/json");
  https.addHeader("Authorization", "Bearer " + firebaseIdToken);
  DynamicJsonDocument doc(3072);
  JsonObject fields = doc.createNestedObject("fields");
  fields["deviceId"]["stringValue"] = DEVICE_ID;
  fields["status"]["stringValue"] = "ONLINE";
  fields["lastHeartbeat"]["timestampValue"] = utcTimestamp();
  fields["firmwareVersion"]["stringValue"] = FIRMWARE_VERSION;
  if (templateCount >= 0) fields["fingerprintCount"]["integerValue"] = templateCount;
  fields["capacity"]["integerValue"] = 127;
  fields["pendingAttendanceCount"]["integerValue"] = pendingCount;
  fields["wifiStatus"]["stringValue"] = WiFi.status() == WL_CONNECTED ? "ONLINE" : "OFFLINE";
  fields["firebaseSyncStatus"]["stringValue"] = pendingCount == 0 ? "ONLINE" : "PENDING";
  fields["sensorStatus"]["stringValue"] = sensorStatus;
  fields["doorStatus"]["stringValue"] = doorStatus;
  fields["failedScanCount"]["integerValue"] = recentFailedScanCount();
  fields["lastError"]["stringValue"] = lastError;
  JsonObject capabilityField = fields.createNestedObject("capabilities");
  JsonObject arrayValue = capabilityField.createNestedObject("arrayValue");
  JsonArray values = arrayValue.createNestedArray("values");
  const char* capabilities[] = {"fingerprint", "attendance", "enrollment", "deletion", "led", "buzzer", "door", "heartbeat", "sync", "restart"};
  for (const char* capability : capabilities) {
    JsonObject item = values.createNestedObject();
    item["stringValue"] = capability;
  }

  String body;
  serializeJson(doc, body);
  int code = https.sendRequest("PATCH", body);
  String response = https.getString();
  https.end();
  Serial.printf("HEARTBEAT HTTP %d: %s\n", code, response.c_str());
  bool synced = code >= 200 && code < 300;
  if (synced) {
    firebaseSyncStatus = pendingCount == 0 ? "ONLINE" : "PENDING";
  } else {
    firebaseSyncStatus = "ERROR";
    setLatestError(String("Heartbeat HTTP ") + code);
  }
  return synced;
}

void maybePublishDeviceSnapshot() {
  if (lastHeartbeat == 0 || millis() - lastHeartbeat >= HEARTBEAT_INTERVAL_MS) {
    lastHeartbeat = millis();
    publishDeviceSnapshot();
  }
}

bool getFingerprintMapping(uint16_t templateId, String& employeeId, String& employeeName) {
  if (WiFi.status() != WL_CONNECTED || !firebaseSignIn()) {
    bool cached = loadCachedFingerprintMapping(templateId, employeeId, employeeName);
    if (cached) Serial.printf("Mapping cache cho template %u, se cho dong bo\n", templateId);
    return cached;
  }
  BearSSL::WiFiClientSecure client;
  client.setInsecure();
  HTTPClient https;
  String url = String(FIRESTORE_URL) + "/fingerprintMappings/" + String(templateId);
  if (!https.begin(client, url)) return loadCachedFingerprintMapping(templateId, employeeId, employeeName);
  https.addHeader("Authorization", "Bearer " + firebaseIdToken);
  int code = https.GET();
  String response = https.getString();
  https.end();
  if (code != 200) {
    if (code == 404) removeCachedFingerprintMapping(templateId);
    return code < 0 && loadCachedFingerprintMapping(templateId, employeeId, employeeName);
  }
  DynamicJsonDocument doc(1024);
  if (deserializeJson(doc, response)) return false;
  bool enabled = doc["fields"]["enabled"]["booleanValue"].as<bool>();
  if (!enabled) {
    Serial.printf("Template %u da bi vo hieu hoa\n", templateId);
    removeCachedFingerprintMapping(templateId);
    return false;
  }
  employeeId = doc["fields"]["employeeId"]["stringValue"].as<String>();
  employeeName = doc["fields"]["employeeName"]["stringValue"].as<String>();
  employeeId.trim();
  employeeName.trim();
  cacheFingerprintMapping(templateId, employeeId, employeeName);
  return employeeId.length() > 0 && employeeName.length() > 0;
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
  serializeJson(doc, payload);
  return true;
}

bool uploadAttendance(uint16_t templateId, uint16_t confidence,
                      String& employeeName, String& attendanceTime,
                      String& attendanceType) {
  String eventId;
  String payload;
  lastRejectedAttendanceEventId = "";
  if (!buildAttendanceEvent(templateId, confidence, eventId, payload, employeeName, attendanceTime, attendanceType)) return false;
  if (!enqueueAttendanceEvent(eventId, payload)) return false;
  flushAttendanceOutbox();
  attendancePendingSync = attendancePendingCount() > 0;
  if (lastRejectedAttendanceEventId == eventId) {
    // A 403/4xx means the scan is not authorized (for example an inactive
    // employee or disabled mapping), not merely waiting for the network.
    return false;
  }
  return true;
}

