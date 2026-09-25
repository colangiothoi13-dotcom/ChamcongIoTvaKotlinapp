// Arduino setup and main loop.

void setup() {
  Serial.begin(9600);
  Serial.printf("\nFW: %s, device=%s\n", FIRMWARE_VERSION, DEVICE_ID);
  randomSeed(ESP.getCycleCount());
  littleFsReady = LittleFS.begin();
  Serial.printf("LittleFS: %s\n", littleFsReady ? "READY" : "ERROR");
  recoverAttendanceOutbox();
  Serial.printf("OUTBOX dang cho: %d su kien\n", attendancePendingCount());
  Wire.begin(LCD_SDA_PIN, LCD_SCL_PIN);
  lcd.init();
  lcd.backlight();
  showLcd("KHOI DONG...", "VUI LONG DOI");
  pinMode(LED_GREEN_PIN, OUTPUT);
  pinMode(LED_RED_PIN, OUTPUT);
  pinMode(BUZZER_PIN, OUTPUT);
  pinMode(DOOR_SWITCH_PIN, INPUT);
  // SG90: cho phep dai xung rong hon de servo nhan du goc 0..90.
  doorServo.attach(DOOR_SERVO_PIN, 500, 2400);
  closeDoor();
  delay(350);
  finger.begin(57600);
delay(1000);

bool sensorConnected = false;

for (int i = 0; i < 5; i++) {
  if (finger.verifyPassword()) {
    sensorConnected = true;
    break;
  }
  Serial.println("Dang thu ket noi lai cam bien...");
  delay(500);
}

if (!sensorConnected) {
    Serial.println("Khong tim thay cam bien van tay");
    setSensorError("AS608 khong xac thuc duoc");
    showLcd("LOI CAM BIEN", "KIEM TRA DAY");
  } else {
    markSensorReady();
  }
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
  if (waitingForFingerRemoval) {
    if (!sensorReady) {
      waitingForFingerRemoval = false;
      showLcd("LOI CAM BIEN", "KIEM TRA DAY");
      delay(200);
      return;
    }
    uint8_t imageStatus = finger.getImage();
    if (imageStatus == FINGERPRINT_NOFINGER) {
      waitingForFingerRemoval = false;
      showReadyScreen();
    } else if (imageStatus != FINGERPRINT_OK) {
      setSensorError("AS608 loi khi kiem tra ngon tay");
      showLcd("LOI CAM BIEN", "KIEM TRA DAY");
      waitingForFingerRemoval = false;
    } else if (millis() - fingerRemovalStarted >= 10000) {
      showLcd("NHAC NGON TAY", "RA KHOI CAM BIEN");
      fingerRemovalStarted = millis();
    }
    delay(80);
    return;
  }

  if (millis() - lastCommandCheck >= 3000) {
    lastCommandCheck = millis();
    if (checkDeviceCommand()) return;
  }
  if (!sensorReady) {
    delay(200);
    return;
  }
  uint8_t imageStatus = finger.getImage();
  if (imageStatus != FINGERPRINT_OK) {
    if (imageStatus != FINGERPRINT_NOFINGER) {
      setSensorError("AS608 loi khi doc van tay");
      showLcd("LOI CAM BIEN", "KIEM TRA DAY");
    }
    delay(80);
    return;
  }
  showLcd("DANG XU LY...", "VUI LONG DOI");
  uint8_t imageToTemplateStatus = finger.image2Tz();
  uint8_t searchStatus = imageToTemplateStatus == FINGERPRINT_OK
      ? finger.fingerFastSearch()
      : imageToTemplateStatus;
  if (imageToTemplateStatus != FINGERPRINT_OK || searchStatus != FINGERPRINT_OK) {
    Serial.println("Van tay khong hop le");
    showLcd("VAN TAY SAI", "XIN THU LAI");
    if (searchStatus == FINGERPRINT_NOTFOUND || imageToTemplateStatus == FINGERPRINT_IMAGEMESS) {
      recordFailedScan("Van tay khong hop le");
    } else {
      setSensorError("AS608 loi khi xu ly van tay");
    }
    signalResult(false);
    delay(500);
    startWaitingForFingerRemoval();
    return;
  }
  Serial.printf("Template %d, confidence %d\n", finger.fingerID, finger.confidence);
  String employeeName;
  String attendanceTime;
  String attendanceType;
  bool success = uploadAttendance(finger.fingerID, finger.confidence,
                                  employeeName, attendanceTime, attendanceType);
  if (success) {
    openDoor();
    String scanStatus = attendancePendingSync
        ? String("CHO SYNC: ") + attendancePendingCount()
        : String("DA NHAN");
    showLcd(employeeName, scanStatus);
  } else {
    showLcd("CHAM CONG LOI", "XIN THU LAI");
  }
  signalResult(success);
  delay(success ? 1800 : 500);
  startWaitingForFingerRemoval();
}

