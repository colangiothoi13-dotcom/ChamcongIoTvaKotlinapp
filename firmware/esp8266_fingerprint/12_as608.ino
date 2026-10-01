// AS608/R307 fingerprint sensor: pins, object, capture, enrollment, and recovery.

// SoftwareSerial(rx, tx); UART0 van duoc giu lai cho Serial Monitor.
#if (defined(__AVR__) || defined(ESP8266)) && !defined(__AVR_ATmega2560__)
SoftwareSerial mySerial(FINGERPRINT_RX_PIN, FINGERPRINT_TX_PIN);
#else
#define mySerial Serial1
#endif
Adafruit_Fingerprint finger(&mySerial);

void setSensorError(const String& message) {
  sensorReady = false;
  sensorStatus = "ERROR";
  sensorError = message;
  setLatestError(message);
  Serial.printf("AS608: vo hieu hoa doc cam bien (%s)\n", sensorError.c_str());
  showSensorReconnectScreen();
}

void markSensorReady() {
  if (!sensorReady) Serial.println("AS608: cam bien da san sang");
  sensorReady = true;
  sensorStatus = "OK";
  sensorError = "";
  if (lcdIdleMode || (!waitingForFingerRemoval && !pendingCommandResult)) showReadyScreen();
}

void initializeAs608() {
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
  } else {
    markSensorReady();
  }
}

bool waitForFinger(uint32_t timeoutMs) {
  if (!sensorReady) return false;
  uint32_t started = millis();
  while (millis() - started < timeoutMs) {
    uint8_t status = finger.getImage();
    if (status == FINGERPRINT_OK) return true;
    if (status != FINGERPRINT_NOFINGER) {
      setSensorError("AS608 loi khi doc van tay");
      return false;
    }
    delay(80);
    yield();
  }
  return false;
}

bool waitForFingerRemoval(uint32_t timeoutMs) {
  if (!sensorReady) return false;
  uint32_t started = millis();
  while (millis() - started < timeoutMs) {
    uint8_t status = finger.getImage();
    if (status == FINGERPRINT_NOFINGER) return true;
    if (status != FINGERPRINT_OK) {
      setSensorError("AS608 loi khi kiem tra ngon tay");
      return false;
    }
    delay(80);
    yield();
  }
  return false;
}

bool enrollFingerprint(uint16_t templateId) {
  if (!sensorReady) return false;
  Serial.printf("DANG KY: dat ngon tay lan 1 (template %d)\n", templateId);
  showLcd("DANG KY VAN TAY", "DAT NGON TAY 1");
  playBuzzerTone(1200, 120);
  if (!waitForFinger(30000) || finger.image2Tz(1) != FINGERPRINT_OK) return false;
  Serial.println("DANG KY: nhac ngon tay ra");
  showLcd("DANG KY VAN TAY", "NHAC NGON TAY RA");
  playBuzzerTone(1500, 100);
  if (!waitForFingerRemoval(10000)) return false;
  delay(600);
  Serial.println("DANG KY: dat cung ngon tay lan 2");
  showLcd("DANG KY VAN TAY", "DAT NGON TAY 2");
  playBuzzerTone(1200, 120);
  if (!waitForFinger(30000) || finger.image2Tz(2) != FINGERPRINT_OK) return false;
  if (finger.createModel() != FINGERPRINT_OK) return false;
  return finger.storeModel(templateId) == FINGERPRINT_OK;
}

void startWaitingForFingerRemoval() {
  waitingForFingerRemoval = true;
  fingerRemovalStarted = millis();
}

void maybeRecoverSensor() {
  if (sensorReady || millis() - lastSensorRetry < SENSOR_RETRY_INTERVAL_MS) return;
  lastSensorRetry = millis();
  Serial.println("AS608: thu ket noi lai cam bien");
  showSensorReconnectScreen();
  if (finger.verifyPassword()) {
    markSensorReady();
  } else {
    setSensorError("AS608 khong phan hoi");
  }
}

bool handleFingerprintRemoval() {
  if (!waitingForFingerRemoval) return false;

  if (!sensorReady) {
    waitingForFingerRemoval = false;
    showSensorReconnectScreen();
    delay(200);
    return true;
  }

  uint8_t imageStatus = finger.getImage();
  if (imageStatus == FINGERPRINT_NOFINGER) {
    waitingForFingerRemoval = false;
    showReadyScreen();
  } else if (imageStatus != FINGERPRINT_OK) {
    setSensorError("AS608 loi khi kiem tra ngon tay");
    waitingForFingerRemoval = false;
  } else if (millis() - fingerRemovalStarted >= 10000) {
    showLcd("NHAC NGON TAY", "RA KHOI CAM BIEN");
    fingerRemovalStarted = millis();
  }
  delay(80);
  return true;
}

void handleFingerprintScan() {
  if (!sensorReady) {
    delay(200);
    return;
  }

  uint8_t imageStatus = finger.getImage();
  if (imageStatus != FINGERPRINT_OK) {
    if (imageStatus != FINGERPRINT_NOFINGER) {
      setSensorError("AS608 loi khi doc van tay");
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
    if (lastFingerprintAuthorizationUnavailable) {
      showLcd("KHONG XAC THUC", "KHONG MO CUA");
    } else if (lastFingerprintAuthorizationDenied) {
      showLcd("KHONG DUOC PHEP", "XIN LIEN HE ADMIN");
    } else if (attendanceOutboxIsFull()) {
      showLcd("HANG DOI DAY", "KHONG LUU DUOC");
    } else {
      showLcd("CHAM CONG LOI", "XIN THU LAI");
    }
  }
  signalResult(success);
  delay(success ? 1800 : 500);
  startWaitingForFingerRemoval();
}
