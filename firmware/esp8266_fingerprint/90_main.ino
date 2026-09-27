// Arduino setup and main loop.

void setup() {
  Serial.begin(9600);
  Serial.printf("\nFW: %s, device=%s\n", FIRMWARE_VERSION, DEVICE_ID);
  randomSeed(ESP.getCycleCount());
  littleFsReady = LittleFS.begin();
  Serial.printf("LittleFS: %s\n", littleFsReady ? "READY" : "ERROR");
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
  if (millis() - lastAttendanceSync >= ATTENDANCE_SYNC_INTERVAL_MS) {
    lastAttendanceSync = millis();
    if (!flushAttendanceOutbox() && attendanceOutboxBytes() > 0) {
      Serial.println("OUTBOX dang cho mang de dong bo");
    }
  }
  if (pendingCommandResult) {
    if (millis() - lastCommandCheck >= 3000) {
      lastCommandCheck = millis();
      checkDeviceCommand();
    }
    delay(80);
    return;
  }
  if (handleFingerprintRemoval()) return;

  if (millis() - lastCommandCheck >= 3000) {
    lastCommandCheck = millis();
    if (checkDeviceCommand()) return;
  }
  handleFingerprintScan();
}
