// Arduino setup and main loop.

void setup() {
  Serial.begin(9600);
  Serial.printf("\nFW: %s, device=%s\n", FIRMWARE_VERSION, DEVICE_ID);
  Serial.printf("RESET: %s\n", ESP.getResetReason().c_str());
  Serial.printf("HEAP khoi dong: free=%u, block=%u, frag=%u%%\n",
                ESP.getFreeHeap(), ESP.getMaxFreeBlockSize(), ESP.getHeapFragmentation());
  randomSeed(ESP.getCycleCount());
  littleFsReady = LittleFS.begin();
  Serial.printf("LittleFS: %s\n", littleFsReady ? "READY" : "ERROR");
  if (littleFsReady && LittleFS.exists(FINGERPRINT_CACHE_PATH)) {
    LittleFS.remove(FINGERPRINT_CACHE_PATH);
    Serial.println("Da xoa cache van tay cu; mo cua can xac thuc online");
  }
  recoverAttendanceOutbox();
  Serial.printf("OUTBOX dang cho: %d su kien\n", attendancePendingCount());
  initializeLcd();
  initializeOutputsAndSwitch();
  initializeDoorServo();
  delay(350);
  initializeAs608();

  showLcd("DANG KET NOI", "WIFI...");
  WiFi.setAutoReconnect(true);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  configTime(0, 0, "pool.ntp.org", "time.google.com");
  uint32_t wifiStarted = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - wifiStarted < 15000) {
    delay(400);
    Serial.print('.');
  }
  if (WiFi.status() == WL_CONNECTED) {
    showLcd("DONG BO GIO...", "VUI LONG DOI");
    uint32_t timeSyncStarted = millis();
    while (time(nullptr) < MIN_VALID_UNIX_TIME && millis() - timeSyncStarted < 10000) {
      delay(200);
      yield();
    }
    if (time(nullptr) < MIN_VALID_UNIX_TIME) {
      Serial.println("LENH: chua dong bo NTP, tam hoan cap nhat");
      showLcd("LOI DONG BO GIO", "KIEM TRA MANG");
      delay(1500);
    }
    lastHeartbeat = millis();
    publishDeviceSnapshot();
  } else {
    Serial.println("WIFI offline luc khoi dong; se thu lai trong loop");
    showLcd("OFFLINE", String("CHO SYNC: ") + attendancePendingCount());
  }
  Serial.println("\nSan sang cham cong");
  if (sensorReady) showReadyScreen();
  else showLcd("LOI CAM BIEN", "KIEM TRA DAY");
}

void loop() {
  maintainWifiConnection();
  handleDoorSwitch();
  maybeCloseDoor();
  maybeRecoverSensor();
  maybePublishDeviceSnapshot();
  maybeUpdateIdleClock();
  if (attendanceOutboxBytes() > 0 &&
      millis() - lastAttendanceSync >= attendanceSyncIntervalMs) {
    lastAttendanceSync = millis();
    const bool synced = flushAttendanceOutbox();
    attendanceSyncIntervalMs = synced
        ? ATTENDANCE_SYNC_INTERVAL_MS
        : ATTENDANCE_RETRY_INTERVAL_MS;
    if (!synced && attendanceOutboxBytes() > 0) {
      Serial.println("OUTBOX dang cho mang de dong bo");
    }
  }
  if (pendingCommandResult) {
    if (millis() - lastCommandCheck >= commandPollIntervalMs) {
      lastCommandCheck = millis();
      checkDeviceCommand();
    }
    delay(80);
    return;
  }
  if (handleFingerprintRemoval()) return;

  if (millis() - lastCommandCheck >= commandPollIntervalMs) {
    lastCommandCheck = millis();
    if (checkDeviceCommand()) return;
  }
  handleFingerprintScan();
}
