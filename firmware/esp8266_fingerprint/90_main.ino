// Arduino setup and main loop.

void setup() {
  Serial.begin(9600);
  Serial.printf_P(PSTR("\nFW: %s, device=%s\n"), FIRMWARE_VERSION, DEVICE_ID);
  Serial.printf_P(PSTR("RESET: %s\n"), ESP.getResetReason().c_str());
  Serial.printf_P(PSTR("HEAP khoi dong: free=%u, block=%u, frag=%u%%\n"),
                ESP.getFreeHeap(), ESP.getMaxFreeBlockSize(), ESP.getHeapFragmentation());
  randomSeed(ESP.getCycleCount());
  littleFsReady = LittleFS.begin();
  Serial.printf_P(PSTR("LittleFS: %s\n"), littleFsReady ? "READY" : "ERROR");
  if (littleFsReady && LittleFS.exists(FINGERPRINT_CACHE_PATH)) {
    LittleFS.remove(FINGERPRINT_CACHE_PATH);
    Serial.println(F("Da xoa cache nhan vien cu; mau van tay van nam trong AS608"));
  }
  recoverAttendanceOutbox();
  Serial.printf_P(PSTR("OUTBOX dang cho: %d su kien\n"), attendancePendingCount());
  initializeLcd();
  initializeOutputsAndSwitch();
  initializeDoorServo();
  delay(350);
  initializeAs608();

  showLcd(F("DANG KET NOI"), F("WIFI..."));
  beginWifiConnection();
  configTime(0, 0, "pool.ntp.org", "time.google.com");
  uint32_t wifiStarted = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - wifiStarted < 15000) {
    delay(400);
    Serial.print('.');
  }
  if (WiFi.status() == WL_CONNECTED) {
    wifiWasConnected = true;
    showLcd(F("DONG BO GIO..."), F("VUI LONG DOI"));
    uint32_t timeSyncStarted = millis();
    while (time(nullptr) < MIN_VALID_UNIX_TIME && millis() - timeSyncStarted < 10000) {
      delay(200);
      yield();
    }
    if (time(nullptr) < MIN_VALID_UNIX_TIME) {
      Serial.println(F("LENH: chua dong bo NTP, tam hoan cap nhat"));
      showLcd(F("LOI DONG BO GIO"), F("KIEM TRA MANG"));
      delay(1500);
    }
    lastHeartbeat = millis();
    publishDeviceSnapshot();
  } else {
    Serial.println(F("WIFI offline luc khoi dong; se thu lai trong loop"));
    showLcd(F("OFFLINE"), String("CHO DONG BO: ") + attendancePendingCount());
  }
  Serial.println(F("\nSan sang cham cong"));
  if (sensorReady) showReadyScreen();
  else showSensorReconnectScreen();
}

void loop() {
  // Always service the physical controls and deadlines before network/sensor I/O.
  handleDoorSwitch();
  serviceDoor();
  serviceOutputEffects();
  // Keep failed scan feedback readable while physical controls remain active.
  if (fingerprintResultHoldActive()) {
    delay(5);
    return;
  }
  serviceFingerprintResultNotice();
  expireForegroundAttendance();
  serviceEnrollment();
  serviceDeviceCommandExecution();
  if (doorNeedsResponsiveLoop()) {
    delay(5);
    return;
  }
  maintainWifiConnection();
  maybeRecoverSensor();
  maybeUpdateIdleClock();
  // Enrollment runs one sensor operation per tick and needs no HTTPS until it
  // finishes. Its waits cannot hold up a manual door opening.
  if (enrollmentStage != EnrollmentStage::IDLE) {
    delay(5);
    return;
  }
  // A due background heartbeat gets a turn before a failing FIFO head can
  // restart the shared transport cooldown. Active foreground scans still win.
  maybePublishDeviceSnapshot();
  if (attendanceOutboxBytes() > 0 && WiFi.status() == WL_CONNECTED &&
      !httpsRetryCooldownActive() &&
      millis() - lastAttendanceSync >= attendanceSyncIntervalMs) {
    const AttendanceSyncResult result = flushAttendanceOutbox();
    attendancePendingSync = attendanceOutboxBytes() > 0;
    // Deferral did not attempt delivery. Preserve the due head so a cooldown
    // cannot add another retry interval or keep moving its deadline forward.
    if (result != AttendanceSyncResult::DEFERRED) {
      heartbeatWaitingForAttendanceAttempt = false;
      lastAttendanceSync = millis();
      attendanceSyncIntervalMs = attendanceSyncMadeProgress(result)
          ? ATTENDANCE_NEXT_RECORD_INTERVAL_MS : ATTENDANCE_RETRY_INTERVAL_MS;
    }
    if (pendingCommandExecution && pendingCommandType == "SYNC_ATTENDANCE" && result == AttendanceSyncResult::REJECTED) {
      syncCommandHadRejections = true;
    }
    if (result == AttendanceSyncResult::RETRY || result == AttendanceSyncResult::DEFERRED) {
      Serial.println(F("OUTBOX dang cho mang de dong bo"));
    }
    // Current delivery may have opened the door or started a rejection notice.
    if (doorNeedsResponsiveLoop() || fingerprintResultHoldActive()) return;
  }
  // A new scan can enter the queue while an empty SYNC awaits its completion
  // report. Drain that new head before reporting success, using the same deadline.
  if (pendingCommandResult && pendingCommandType == "SYNC_ATTENDANCE" &&
      pendingCommandSuccess && !attendanceOutboxIsEmpty()) {
    pendingCommandResult = false;
    pendingCommandExecution = true;
  }
  serviceDeviceCommandExecution();
  // SYNC is background work: waiting for the queue must not reserve the sensor.
  // Commands that own the sensor/door retain their existing exclusive flow.
  if (pendingCommandExecution && pendingCommandType != "SYNC_ATTENDANCE") {
    delay(5);
    return;
  }
  if (pendingCommandResult) {
    const bool backgroundSyncResult = pendingCommandType == "SYNC_ATTENDANCE";
    // A retrying status PATCH can change the LCD. Preserve the current scan,
    // its finger-removal step and its result while SYNC reports in background.
    const bool mayReportSyncResult = foregroundAttendanceHandled &&
        !waitingForFingerRemoval && !fingerprintResultHoldActive();
    if ((!backgroundSyncResult || mayReportSyncResult) &&
        millis() - lastCommandCheck >= commandPollIntervalMs) {
      lastCommandCheck = millis();
      checkDeviceCommand();
    }
    if (!backgroundSyncResult) {
      delay(5);
      return;
    }
  }
  // Holding a finger after delivery cannot starve telemetry. A head attempt
  // may also have released the heartbeat turn above without using HTTPS.
  maybePublishDeviceSnapshot();
  if (handleFingerprintRemoval()) return;

  // Never replace the active command's request/version while its background
  // execution or completion report still needs retrying.
  // Drain saved scans before a new command GET can consume TLS time or start
  // another transport cooldown. Physical controls and scanning stay active.
  if (!pendingCommandExecution && !pendingCommandResult && attendanceOutboxIsEmpty() &&
      millis() - lastCommandCheck >= commandPollIntervalMs) {
    lastCommandCheck = millis();
    if (checkDeviceCommand()) return;
  }
  handleFingerprintScan();
  delay(5);
}
