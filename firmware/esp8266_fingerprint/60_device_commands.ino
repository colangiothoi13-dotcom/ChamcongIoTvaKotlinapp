// Firestore device commands.

bool refreshCommandVersion() {
  if (!firebaseSignIn()) return false;
  BearSSL::WiFiClientSecure client;
  client.setInsecure();
  HTTPClient https;
  String url = String(FIRESTORE_URL) + "/deviceCommands/" + DEVICE_ID;
  if (!https.begin(client, url)) {
    Serial.println("LENH: khong tao duoc ket noi HTTPS");
    setLatestError("Khong tao duoc ket noi doc lenh");
    return false;
  }
  https.addHeader("Authorization", "Bearer " + firebaseIdToken);
  int code = https.GET();
  String body = https.getString();
  https.end();
  if (code != 200) {
    Serial.printf("LENH refresh: HTTP %d, heap=%u\n", code, ESP.getFreeHeap());
    return false;
  }
  DynamicJsonDocument current(2048);
  DeserializationError parseError = deserializeJson(current, body);
  if (parseError) {
    Serial.printf("LENH refresh: JSON %s, bytes=%u, heap=%u\n", parseError.c_str(), static_cast<unsigned>(body.length()), ESP.getFreeHeap());
    setLatestError("Du lieu lenh khong hop le");
    return false;
  }
  String requestId = current["fields"]["requestId"]["stringValue"] | "";
  if (requestId != commandRequestId) {
    Serial.println("LENH refresh: requestId da doi, bo ket qua lenh cu");
    pendingCommandResult = false;
    return false;
  }
  commandVersion = current["updateTime"].as<String>();
  return commandVersion.length() > 0;
}

bool updateDeviceCommandStatus(const char* status, const char* message) {
  if (WiFi.status() != WL_CONNECTED || !firebaseSignIn() || !refreshCommandVersion()) return false;
  BearSSL::WiFiClientSecure client;
  client.setInsecure();
  HTTPClient https;
  String url = String(FIRESTORE_URL) + "/deviceCommands/" + DEVICE_ID +
               "?updateMask.fieldPaths=status&updateMask.fieldPaths=message&updateMask.fieldPaths=completedAt";
  url += "&currentDocument.updateTime=" + commandVersion;
  if (!https.begin(client, url)) {
    Serial.println("LENH: khong tao duoc ket noi HTTPS");
    setLatestError("Khong tao duoc ket noi cap nhat lenh");
    return false;
  }
  https.addHeader("Content-Type", "application/json");
  https.addHeader("Authorization", "Bearer " + firebaseIdToken);
  DynamicJsonDocument doc(512);
  JsonObject fields = doc.createNestedObject("fields");
  fields["status"]["stringValue"] = status;
  fields["message"]["stringValue"] = message;
  fields["completedAt"]["timestampValue"] = utcTimestamp();
  String body;
  serializeJson(doc, body);
  int code = https.sendRequest("PATCH", body);
  if (code >= 200 && code < 300) {
    DynamicJsonDocument updated(2048);
    String response = https.getString();
    if (!deserializeJson(updated, response)) commandVersion = updated["updateTime"].as<String>();
  }
  if (code < 0) {
    Serial.printf("Cap nhat lenh: HTTP %d (%s), heap=%u\n",
                  code, HTTPClient::errorToString(code).c_str(), ESP.getFreeHeap());
  } else {
    Serial.printf("Cap nhat lenh: HTTP %d, heap=%u\n", code, ESP.getFreeHeap());
  }
  https.end();
  if (code < 200 || code >= 300) setLatestError(String("Cap nhat lenh HTTP ") + code);
  return code >= 200 && code < 300;
}

bool readDeviceCommand(uint16_t& templateId, String& type, bool& wasProcessing) {
  templateId = 0;
  type = "";
  wasProcessing = false;
  if (WiFi.status() != WL_CONNECTED) {
    Serial.printf("LENH: WiFi chua ket noi, status=%d\n", WiFi.status());
    return false;
  }
  if (!firebaseSignIn()) {
    Serial.println("LENH: dang nhap Firebase that bai");
    return false;
  }

  BearSSL::WiFiClientSecure client;
  client.setInsecure();
  HTTPClient https;
  String url = String(FIRESTORE_URL) + "/deviceCommands/" + DEVICE_ID;
  if (!https.begin(client, url)) {
    Serial.println("LENH: khong tao duoc ket noi HTTPS");
    setLatestError("Khong tao duoc ket noi doc lenh");
    return false;
  }
  https.addHeader("Authorization", "Bearer " + firebaseIdToken);

  int code = https.GET();
  bool requested = false;
  if (code == 200) {
    DynamicJsonDocument response(1536);
    String commandBody = https.getString();
    DeserializationError error = deserializeJson(response, commandBody);
    if (error) {
      Serial.printf("LENH GET: JSON %s, bytes=%u, heap=%u\n", error.c_str(), static_cast<unsigned>(commandBody.length()), ESP.getFreeHeap());
      https.end();
      return false;
    }
    const char* status = response["fields"]["status"]["stringValue"] | "";
    Serial.printf("LENH GET: device=%s, HTTP=200, status=%s, type=%s, heap=%u\n", DEVICE_ID,
                  status, response["fields"]["type"]["stringValue"] | "(missing)", ESP.getFreeHeap());
    type = response["fields"]["type"]["stringValue"].as<String>();
    wasProcessing = strcmp(status, "PROCESSING") == 0;
    commandId = DEVICE_ID;
    commandVersion = response["updateTime"].as<String>();
    commandRequestId = response["fields"]["requestId"]["stringValue"] | "";
    pendingCommandEmployeeId = response["fields"]["employeeId"]["stringValue"] | "";
    pendingCommandEmployeeName = response["fields"]["employeeName"]["stringValue"] | "";
    templateId = response["fields"]["templateId"]["integerValue"].as<uint16_t>();
    if (templateId == 0) {
      const char* templateText = response["fields"]["templateId"]["integerValue"].as<const char*>();
      if (templateText != nullptr) templateId = String(templateText).toInt();
    }
    requested = !error && (strcmp(status, "REQUESTED") == 0 || strcmp(status, "PROCESSING") == 0)
        && type.length() > 0 && commandVersion.length() > 0;
  } else if (code < 0) {
    Serial.printf("Doc lenh dang ky: HTTP %d (%s), heap=%u\n",
                  code, HTTPClient::errorToString(code).c_str(), ESP.getFreeHeap());
  } else {
    Serial.printf("Doc lenh dang ky: HTTP %d\n", code);
  }
  https.end();
  return requested;
}

bool finishDeviceCommand() {
  String message;
  if (pendingCommandType == "DELETE_FINGERPRINT") {
    message = pendingCommandSuccess ? "Fingerprint deleted" : "Fingerprint deletion failed";
  } else if (pendingCommandType == "ENROLL_FINGERPRINT") {
    message = pendingCommandSuccess ? "Fingerprint stored" : "Enrollment failed, interrupted or timed out";
  } else if (pendingCommandType == "SYNC_ATTENDANCE") {
    message = pendingCommandSuccess ? "Attendance synchronized" : "Attendance synchronization failed";
  } else if (pendingCommandType == "RESTART_DEVICE") {
    message = pendingCommandSuccess ? "Restarting device" : "Device restart failed";
  } else if (pendingCommandType == "OPEN_DOOR") {
    message = pendingCommandSuccess ? "Door opened" : "Door opening failed";
  } else if (pendingCommandType == "CLOSE_DOOR") {
    message = pendingCommandSuccess ? "Door closed" : "Door closing failed";
  } else {
    message = pendingCommandSuccess ? "Device command completed" : "Unsupported device command";
  }
  if (!updateDeviceCommandStatus(pendingCommandSuccess ? "COMPLETED" : "FAILED", message.c_str())) {
    showLcd("CHO DONG BO", "KIEM TRA MANG");
    return false;
  }
  if (pendingCommandDelete && pendingCommandSuccess) {
    removeCachedFingerprintMapping(pendingCommandTemplateId);
  }
  if (pendingCommandType == "ENROLL_FINGERPRINT" && pendingCommandSuccess
      && pendingCommandEmployeeId.length() > 0 && pendingCommandEmployeeName.length() > 0) {
    cacheFingerprintMapping(pendingCommandTemplateId, pendingCommandEmployeeId, pendingCommandEmployeeName);
  }
  pendingCommandResult = false;
  if (pendingCommandRestart) {
    showLcd(pendingCommandSuccess ? "DANG KHOI DONG" : "KHOI DONG LOI", "VUI LONG DOI");
    if (pendingCommandSuccess) signalResult(true);
    delay(300);
    if (pendingCommandSuccess) ESP.restart();
    return true;
  }

  bool isTestCommand = pendingCommandType == "TEST_LED_GREEN" ||
                       pendingCommandType == "TEST_LED_RED" ||
                       pendingCommandType == "TEST_BUZZER";
  if (pendingCommandType == "DELETE_FINGERPRINT") {
    showLcd(pendingCommandSuccess ? "DA XOA VAN TAY" : "XOA THAT BAI",
            pendingCommandSuccess ? "HOAN TAT" : "KIEM TRA APP");
  } else if (pendingCommandType == "ENROLL_FINGERPRINT") {
    showLcd(pendingCommandSuccess ? "DANG KY XONG" : "DANG KY THAT BAI",
            pendingCommandSuccess ? "HOAN TAT" : "KIEM TRA APP");
  } else if (pendingCommandType == "SYNC_ATTENDANCE") {
    showLcd(pendingCommandSuccess ? "DA DONG BO" : "DONG BO THAT BAI",
            pendingCommandSuccess ? "CHAM CONG" : "KIEM TRA MANG");
  } else {
    showLcd(pendingCommandSuccess ? "LENH HOAN TAT" : "LENH THAT BAI",
            pendingCommandType);
  }
  if (!isTestCommand || !pendingCommandSuccess) signalResult(pendingCommandSuccess);
  delay(1500);
  if (pendingCommandType == "DELETE_FINGERPRINT" || pendingCommandType == "ENROLL_FINGERPRINT") {
    if (sensorReady) startWaitingForFingerRemoval();
    else showLcd("LOI CAM BIEN", "KIEM TRA DAY");
  } else {
    showReadyScreen();
  }
  return true;
}

bool isSupportedDeviceCommand(const String& type) {
  return type == "ENROLL_FINGERPRINT" || type == "DELETE_FINGERPRINT" ||
         type == "TEST_LED_GREEN" || type == "TEST_LED_RED" ||
         type == "TEST_BUZZER" || type == "SYNC_ATTENDANCE" ||
         type == "RESTART_DEVICE" || type == "OPEN_DOOR" ||
         type == "CLOSE_DOOR";
}

bool checkDeviceCommand() {
  // Neu PATCH mat mang, chi gui lai ket qua, khong thuc hien lai dang ky.
  if (pendingCommandResult) {
    finishDeviceCommand();
    return true;
  }
  uint16_t templateId = 0;
  String type;
  bool wasProcessing = false;
  if (!readDeviceCommand(templateId, type, wasProcessing)) return false;
  delay(50);
  yield();
  if (!hasValidClock()) {
    Serial.println("LENH: chua dong bo NTP, tam hoan cap nhat");
    showLcd("LOI DONG BO GIO", "KIEM TRA MANG");
    return true;
  }

  pendingCommandType = type;
  pendingCommandTemplateId = templateId;
  pendingCommandDelete = type == "DELETE_FINGERPRINT";
  pendingCommandRestart = type == "RESTART_DEVICE";

  // A PROCESSING command found after a reset may have been interrupted while
  // touching the sensor. Never rerun it; report a deterministic failure.
  if (wasProcessing) {
    Serial.printf("LENH %s dang PROCESSING sau khi khoi dong, danh bai\n", type.c_str());
    pendingCommandSuccess = false;
    pendingCommandResult = true;
    return finishDeviceCommand();
  }

  if (!isSupportedDeviceCommand(type) || commandRequestId.length() == 0) {
    setLatestError(String("Lenh khong duoc ho tro: ") + type);
    pendingCommandSuccess = false;
    pendingCommandResult = true;
    pendingCommandRestart = false;
    return finishDeviceCommand();
  }

  bool deleting = type == "DELETE_FINGERPRINT";
  bool enrolling = type == "ENROLL_FINGERPRINT";
  const char* processingMessage = deleting ? "Deleting fingerprint" :
      enrolling ? "Waiting for finger" : "Processing device command";
  if (!updateDeviceCommandStatus("PROCESSING", processingMessage)) {
    showLcd("LOI MAY CHU", "KIEM TRA MANG");
    return true;
  }
  bool success = false;

  if (deleting || enrolling) {
    if (!sensorReady) {
      setLatestError("AS608 dang loi; khong the xu ly mau van tay");
    } else if (templateId > 0 && templateId <= 127 && deleting) {
      showLcd("DANG XOA", "VAN TAY...");
      success = finger.deleteModel(templateId) == FINGERPRINT_OK;
    } else if (templateId > 0 && templateId <= 127 && enrolling) {
      success = enrollFingerprint(templateId);
    }
  } else if (type == "TEST_LED_GREEN") {
    showLcd("TEST LED XANH", "DANG THUC HIEN");
    digitalWrite(LED_GREEN_PIN, HIGH);
    delay(600);
    digitalWrite(LED_GREEN_PIN, LOW);
    success = true;
  } else if (type == "TEST_LED_RED") {
    showLcd("TEST LED DO", "DANG THUC HIEN");
    digitalWrite(LED_RED_PIN, HIGH);
    delay(600);
    digitalWrite(LED_RED_PIN, LOW);
    success = true;
  } else if (type == "TEST_BUZZER") {
    showLcd("TEST COI", "DANG THUC HIEN");
    tone(BUZZER_PIN, 1500, 600);
    delay(650);
    success = true;
  } else if (type == "SYNC_ATTENDANCE") {
    showLcd("DANG DONG BO", "CHAM CONG...");
    success = WiFi.status() == WL_CONNECTED && flushAttendanceOutbox() && attendancePendingCount() == 0;
    if (!success) setLatestError("Hang doi cham cong chua dong bo het");
  } else if (type == "RESTART_DEVICE") {
    showLcd("DANG KHOI DONG", "VUI LONG DOI");
    success = true;
  } else if (type == "OPEN_DOOR") {
    showLcd("DANG MO CUA", "VUI LONG DOI");
    openDoor();
    success = true;
  } else if (type == "CLOSE_DOOR") {
    showLcd("DANG DONG CUA", "VUI LONG DOI");
    closeDoor();
    success = true;
  }

  pendingCommandResult = true;
  pendingCommandSuccess = success;
  finishDeviceCommand();
  return true;
}
