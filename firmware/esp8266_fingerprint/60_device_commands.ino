// Firestore device commands.

bool refreshCommandVersion() {
  if (!firebaseSignIn()) return false;
  if (!canStartHttpsRequest("refresh lenh")) return false;

  int code = -1;
  bool began = false;
  bool parsed = false;
  String requestId;
  String version;
  bool applied = false;
  {
    String url = String(FIRESTORE_URL) + "/deviceCommands/" + DEVICE_ID +
                 "?mask.fieldPaths=requestId&mask.fieldPaths=applied";
    BearSSL::WiFiClientSecure client;
    client.setInsecure();
    HTTPClient https;
    // getStream() is raw on ESP8266 HTTPClient. HTTP/1.0 keeps Firestore from
    // using chunked transfer encoding, so ArduinoJson can consume it directly.
    https.useHTTP10(true);
    if (https.begin(client, url)) {
      began = true;
      https.setTimeout(5000);
      https.addHeader("Authorization", "Bearer " + firebaseIdToken);
      code = https.GET();
      if (code == 200) {
        DynamicJsonDocument current(512);
        if (!deserializeJson(current, https.getStream())) {
          requestId = current["fields"]["requestId"]["stringValue"] | "";
          version = current["updateTime"].as<String>();
          applied = current["fields"]["applied"]["booleanValue"] | false;
          parsed = true;
        }
      }
      https.end();
    }
    client.stop();
  }

  if (!began) {
    deferHttpsRequests("refresh lenh");
    setLatestError("Khong tao duoc ket noi doc lenh");
    return false;
  }
  recordHttpsResult("refresh lenh", code);
  if (code != 200) {
    Serial.printf("LENH refresh: HTTP %d, heap=%u\n", code, ESP.getFreeHeap());
    return false;
  }
  if (!parsed) {
    Serial.printf("LENH refresh: JSON khong hop le, heap=%u\n", ESP.getFreeHeap());
    setLatestError("Du lieu lenh khong hop le");
    return false;
  }
  if (commandRequestId != requestId) {
    Serial.println("LENH refresh: requestId da doi, bo ket qua lenh cu");
    pendingCommandResult = false;
    return false;
  }
  commandVersion = version;
  commandAlreadyApplied = applied;
  return commandVersion.length() > 0;
}

bool updateDeviceCommandStatus(const char* status, const char* message) {
  if (WiFi.status() != WL_CONNECTED || !firebaseSignIn() || !refreshCommandVersion()) return false;
  if (commandAlreadyApplied && strcmp(status, "COMPLETED") == 0) return true;
  if (!canStartHttpsRequest("cap nhat lenh")) return false;

  int code = -1;
  bool began = false;
  {
    String body;
    {
      DynamicJsonDocument doc(512);
      JsonObject fields = doc.createNestedObject("fields");
      fields["status"]["stringValue"] = status;
      fields["message"]["stringValue"] = message;
      fields["completedAt"]["timestampValue"] = utcTimestamp();
      body.reserve(measureJson(doc) + 1);
      serializeJson(doc, body);
    }
    String url = String(FIRESTORE_URL) + "/deviceCommands/" + DEVICE_ID +
                 "?updateMask.fieldPaths=status&updateMask.fieldPaths=message&updateMask.fieldPaths=completedAt";
    url += "&currentDocument.updateTime=" + commandVersion;
    BearSSL::WiFiClientSecure client;
    client.setInsecure();
    HTTPClient https;
    if (https.begin(client, url)) {
      began = true;
      https.addHeader("Content-Type", "application/json");
      https.addHeader("Authorization", "Bearer " + firebaseIdToken);
      code = https.sendRequest("PATCH", reinterpret_cast<const uint8_t*>(body.c_str()), body.length());
      https.end();
    }
    client.stop();
  }

  if (!began) {
    deferHttpsRequests("cap nhat lenh");
    setLatestError("Khong tao duoc ket noi cap nhat lenh");
    return false;
  }
  recordHttpsResult("cap nhat lenh", code);
  if (code < 0) {
    Serial.printf("Cap nhat lenh: HTTP %d (%s), heap=%u\n",
                  code, HTTPClient::errorToString(code).c_str(), ESP.getFreeHeap());
  } else {
    Serial.printf("Cap nhat lenh: HTTP %d, heap=%u\n", code, ESP.getFreeHeap());
  }
  if (code < 200 || code >= 300) setLatestError(String("Cap nhat lenh HTTP ") + code);
  return code >= 200 && code < 300;
}

// The Spark device completes the reserved fingerprint slot atomically. A
// command is marked applied only in the same Firestore commit as its employee
// and mapping changes; the Admin application never has to be running.
bool commitFingerprintCompletion() {
  if (pendingCommandEmployeeId.length() == 0 || pendingCommandTemplateId == 0 ||
      pendingCommandTemplateId > 127 || !firebaseSignIn() || !refreshCommandVersion()) return false;
  if (commandAlreadyApplied) return true;
  if (!canStartHttpsRequest("hoan tat van tay")) return false;

  String base = FIRESTORE_URL;
  const int prefix = base.indexOf("/v1/");
  if (prefix < 0) return false;
  base = base.substring(prefix + 4);
  const String commandName = base + "/deviceCommands/" + DEVICE_ID;
  const String employeeName = base + "/employees/" + pendingCommandEmployeeId;
  const String mappingName = base + "/fingerprintMappings/" + String(pendingCommandTemplateId);
  String body;
  {
    DynamicJsonDocument doc(3072);
    JsonArray writes = doc.createNestedArray("writes");
    JsonObject commandWrite = writes.createNestedObject();
    JsonObject commandUpdate = commandWrite.createNestedObject("update");
    commandUpdate["name"] = commandName;
    commandUpdate["fields"]["applied"]["booleanValue"] = true;
    commandWrite.createNestedObject("updateMask").createNestedArray("fieldPaths").add("applied");
    commandWrite["currentDocument"]["updateTime"] = commandVersion;

    JsonObject employeeWrite = writes.createNestedObject();
    JsonObject employeeUpdate = employeeWrite.createNestedObject("update");
    employeeUpdate["name"] = employeeName;
    if (pendingCommandType == "ENROLL_FINGERPRINT") {
      employeeUpdate["fields"]["fingerprintTemplateId"]["integerValue"] = String(pendingCommandTemplateId);
    } else {
      employeeUpdate["fields"]["fingerprintTemplateId"]["nullValue"] = nullptr;
    }
    employeeUpdate["fields"]["pendingTemplateId"]["nullValue"] = nullptr;
    JsonArray employeeMask = employeeWrite.createNestedObject("updateMask").createNestedArray("fieldPaths");
    employeeMask.add("fingerprintTemplateId");
    employeeMask.add("pendingTemplateId");
    employeeWrite["currentDocument"]["exists"] = true;

    JsonObject mappingWrite = writes.createNestedObject();
    if (pendingCommandType == "ENROLL_FINGERPRINT") {
      JsonObject mappingUpdate = mappingWrite.createNestedObject("update");
      mappingUpdate["name"] = mappingName;
      mappingUpdate["fields"]["enabled"]["booleanValue"] = true;
      mappingWrite.createNestedObject("updateMask").createNestedArray("fieldPaths").add("enabled");
      mappingWrite["currentDocument"]["exists"] = true;
    } else {
      mappingWrite["delete"] = mappingName;
    }
    if (doc.overflowed()) {
      setLatestError("Bo nho JSON lenh van tay khong du");
      return false;
    }
    body.reserve(measureJson(doc) + 1);
    serializeJson(doc, body);
  }

  int code = -1;
  bool began = false;
  {
    BearSSL::WiFiClientSecure client;
    client.setInsecure();
    HTTPClient https;
    if (https.begin(client, String(FIRESTORE_URL) + ":commit")) {
      began = true;
      https.setTimeout(8000);
      https.addHeader("Content-Type", "application/json");
      https.addHeader("Authorization", "Bearer " + firebaseIdToken);
      code = https.sendRequest("POST", reinterpret_cast<const uint8_t*>(body.c_str()), body.length());
      https.end();
    }
    client.stop();
  }
  if (!began) {
    deferHttpsRequests("hoan tat van tay");
    return false;
  }
  recordHttpsResult("hoan tat van tay", code);
  if (code >= 200 && code < 300) return true;
  // A deployed backend may have applied this command between the status PATCH
  // and this commit. Treat that idempotent completion as success.
  if (refreshCommandVersion() && commandAlreadyApplied) return true;
  setLatestError(String("Hoan tat van tay HTTP ") + code);
  return false;
}

bool readDeviceCommand(uint16_t& templateId, String& type, String& employeeId,
                       bool& wasProcessing, bool& wasCompleted) {
  templateId = 0;
  type = "";
  wasProcessing = false;
  wasCompleted = false;
  employeeId = "";
  if (WiFi.status() != WL_CONNECTED) {
    Serial.printf("LENH: WiFi chua ket noi, status=%d\n", WiFi.status());
    commandPollIntervalMs = COMMAND_RETRY_INTERVAL_MS;
    return false;
  }
  if (!firebaseSignIn()) {
    Serial.println("LENH: dang nhap Firebase that bai");
    commandPollIntervalMs = COMMAND_RETRY_INTERVAL_MS;
    return false;
  }
  if (!canStartHttpsRequest("doc lenh")) {
    commandPollIntervalMs = COMMAND_RETRY_INTERVAL_MS;
    return false;
  }

  int code = -1;
  bool began = false;
  bool parsed = false;
  String status;
  String responseType;
  String responseVersion;
  String responseRequestId;
  String responseEmployeeId;
  bool responseApplied = false;
  uint16_t responseTemplateId = 0;
  {
    String url = String(FIRESTORE_URL) + "/deviceCommands/" + DEVICE_ID +
                 "?mask.fieldPaths=status"
                 "&mask.fieldPaths=type"
                 "&mask.fieldPaths=requestId"
                 "&mask.fieldPaths=templateId"
                 "&mask.fieldPaths=employeeId"
                 "&mask.fieldPaths=applied";
    BearSSL::WiFiClientSecure client;
    client.setInsecure();
    HTTPClient https;
    // Request only command fields used below. Firestore still returns updateTime
    // for the conditional PATCH performed after a command finishes.
    https.useHTTP10(true);
    if (https.begin(client, url)) {
      began = true;
      https.setTimeout(5000);
      https.addHeader("Authorization", "Bearer " + firebaseIdToken);
      code = https.GET();
      if (code == 200) {
        DynamicJsonDocument response(1536);
        if (!deserializeJson(response, https.getStream())) {
          status = response["fields"]["status"]["stringValue"] | "";
          responseType = response["fields"]["type"]["stringValue"] | "";
          responseVersion = response["updateTime"].as<String>();
          responseRequestId = response["fields"]["requestId"]["stringValue"] | "";
          responseEmployeeId = response["fields"]["employeeId"]["stringValue"] | "";
          responseApplied = response["fields"]["applied"]["booleanValue"] | false;
          responseTemplateId = response["fields"]["templateId"]["integerValue"].as<uint16_t>();
          if (responseTemplateId == 0) {
            const char* templateText = response["fields"]["templateId"]["integerValue"].as<const char*>();
            if (templateText != nullptr) responseTemplateId = String(templateText).toInt();
          }
          parsed = true;
        }
      }
      https.end();
    }
    client.stop();
  }

  if (!began) {
    deferHttpsRequests("doc lenh");
    setLatestError("Khong tao duoc ket noi doc lenh");
    commandPollIntervalMs = COMMAND_RETRY_INTERVAL_MS;
    return false;
  }
  recordHttpsResult("doc lenh", code);
  if (code == 200) {
    if (!parsed) {
      Serial.printf("LENH GET: JSON khong hop le, heap=%u\n", ESP.getFreeHeap());
      commandPollIntervalMs = COMMAND_RETRY_INTERVAL_MS;
      return false;
    }
    const bool recoverCompletion = status == "COMPLETED" && !responseApplied &&
        (responseType == "ENROLL_FINGERPRINT" || responseType == "DELETE_FINGERPRINT");
    const bool active = status == "REQUESTED" || status == "PROCESSING" || recoverCompletion;
    commandPollIntervalMs = active ? COMMAND_ACTIVE_POLL_INTERVAL_MS : COMMAND_IDLE_POLL_INTERVAL_MS;
    Serial.printf("LENH GET: device=%s, HTTP=200, status=%s, type=%s, heap=%u\n", DEVICE_ID,
                  status.c_str(), responseType.length() > 0 ? responseType.c_str() : "(missing)", ESP.getFreeHeap());
    if (!active) return false;
    type = responseType;
    wasProcessing = status == "PROCESSING";
    wasCompleted = recoverCompletion;
    commandId = DEVICE_ID;
    commandVersion = responseVersion;
    commandRequestId = responseRequestId;
    employeeId = responseEmployeeId;
    templateId = responseTemplateId;
    return type.length() > 0 && commandVersion.length() > 0;
  }
  if (code < 0) {
    Serial.printf("Doc lenh dang ky: HTTP %d (%s), heap=%u\n",
                  code, HTTPClient::errorToString(code).c_str(), ESP.getFreeHeap());
    commandPollIntervalMs = COMMAND_RETRY_INTERVAL_MS;
  } else {
    Serial.printf("Doc lenh dang ky: HTTP %d\n", code);
    commandPollIntervalMs = code == 404 ? COMMAND_IDLE_POLL_INTERVAL_MS : COMMAND_RETRY_INTERVAL_MS;
  }
  return false;
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
  const bool fingerprintSuccess = pendingCommandSuccess &&
      (pendingCommandType == "ENROLL_FINGERPRINT" || pendingCommandType == "DELETE_FINGERPRINT");
  const bool statusSaved = updateDeviceCommandStatus(pendingCommandSuccess ? "COMPLETED" : "FAILED", message.c_str());
  if (!statusSaved || (fingerprintSuccess && !commitFingerprintCompletion())) {
    commandPollIntervalMs = COMMAND_RETRY_INTERVAL_MS;
    showLcd("CHO DONG BO", "KIEM TRA MANG");
    return false;
  }
  pendingCommandResult = false;
  commandPollIntervalMs = COMMAND_IDLE_POLL_INTERVAL_MS;
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
    else showSensorReconnectScreen();
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
  String employeeId;
  bool wasProcessing = false;
  bool wasCompleted = false;
  if (!readDeviceCommand(templateId, type, employeeId, wasProcessing, wasCompleted)) return false;
  delay(50);
  yield();
  if (!hasValidClock()) {
    Serial.println("LENH: chua dong bo NTP, tam hoan cap nhat");
    commandPollIntervalMs = COMMAND_RETRY_INTERVAL_MS;
    showLcd("LOI DONG BO GIO", "KIEM TRA MANG");
    return true;
  }

  pendingCommandType = type;
  pendingCommandTemplateId = templateId;
  pendingCommandEmployeeId = employeeId;
  pendingCommandRestart = type == "RESTART_DEVICE";

  // A reset after the sensor stored a template can leave a COMPLETED command
  // awaiting its Firestore commit. Resume that commit without touching the sensor.
  if (wasCompleted) {
    pendingCommandSuccess = true;
    pendingCommandResult = true;
    return finishDeviceCommand();
  }

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
    commandPollIntervalMs = COMMAND_RETRY_INTERVAL_MS;
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
    testGreenLed();
    success = true;
  } else if (type == "TEST_LED_RED") {
    showLcd("TEST LED DO", "DANG THUC HIEN");
    testRedLed();
    success = true;
  } else if (type == "TEST_BUZZER") {
    showLcd("TEST COI", "DANG THUC HIEN");
    testBuzzer();
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
