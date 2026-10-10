// AS608/R307 fingerprint sensor: pins, object, capture, enrollment, and recovery.

// SoftwareSerial(rx, tx); UART0 van duoc giu lai cho Serial Monitor.
#if (defined(__AVR__) || defined(ESP8266)) && !defined(__AVR_ATmega2560__)
SoftwareSerial mySerial(FINGERPRINT_RX_PIN, FINGERPRINT_TX_PIN);
#else
#define mySerial Serial1
#endif
Adafruit_Fingerprint finger(&mySerial);
EnrollmentStage enrollmentStage = EnrollmentStage::IDLE;
uint16_t enrollmentTemplateId = 0;
unsigned long enrollmentStageStartedAt = 0;
unsigned long enrollmentLastServiceAt = 0;
unsigned long enrollmentLastPollAt = 0;
const unsigned long FINGERPRINT_POLL_INTERVAL_MS = 80;
unsigned long lastFingerprintPollAt = 0;

bool fingerprintPollDue() {
  const unsigned long now = millis();
  if (!elapsedAtLeast(now, lastFingerprintPollAt, FINGERPRINT_POLL_INTERVAL_MS)) return false;
  lastFingerprintPollAt = now;
  return true;
}

void setSensorError(const String& message) {
  sensorReady = false;
  sensorStatus = "ERROR";
  sensorError = message;
  setLatestError(message);
  Serial.printf_P(PSTR("AS608: vo hieu hoa doc cam bien (%s)\n"), sensorError.c_str());
  showSensorReconnectScreen();
}

void markSensorReady() {
  if (!sensorReady) Serial.println(F("AS608: cam bien da san sang"));
  sensorReady = true;
  sensorStatus = "OK";
  sensorError = "";
  lastFingerprintPollAt = millis() - FINGERPRINT_POLL_INTERVAL_MS;
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
    Serial.println(F("Dang thu ket noi lai cam bien..."));
    delay(500);
  }

  if (!sensorConnected) {
    Serial.println(F("Khong tim thay cam bien van tay"));
    setSensorError(F("AS608 khong xac thuc duoc"));
  } else {
    markSensorReady();
  }
}

bool startEnrollment(uint16_t templateId) {
  if (!sensorReady) return false;
  enrollmentTemplateId = templateId;
  enrollmentStage = EnrollmentStage::FIRST_IMAGE;
  enrollmentStageStartedAt = enrollmentLastServiceAt = millis();
  enrollmentLastPollAt = millis() - 80;
  Serial.printf_P(PSTR("DANG KY: dat ngon tay lan 1 (template %d)\n"), templateId);
  showLcd(F("DANG KY VAN TAY"), F("DAT NGON TAY 1"));
  playBuzzerTone(1200, 120);
  return true;
}

void finishEnrollment(bool success) {
  enrollmentStage = EnrollmentStage::IDLE;
  pendingCommandExecution = false;
  pendingCommandSuccess = success;
  pendingCommandResult = true;
  lastCommandCheck = millis() - COMMAND_ACTIVE_POLL_INTERVAL_MS;
}

void serviceEnrollment() {
  if (enrollmentStage == EnrollmentStage::IDLE) return;
  const unsigned long now = millis();
  if (doorNeedsResponsiveLoop()) {
    // A manual door opening pauses sensor I/O and the enrollment timeout.
    enrollmentStageStartedAt += now - enrollmentLastServiceAt;
    enrollmentLastServiceAt = now;
    return;
  }
  enrollmentLastServiceAt = now;
  if (!sensorReady) { finishEnrollment(false); return; }
  if (!elapsedAtLeast(now, enrollmentLastPollAt, 80)) return;
  enrollmentLastPollAt = now;
  const unsigned long timeoutMs = enrollmentStage == EnrollmentStage::REMOVE_FINGER ? 10000 : 30000;
  if (elapsedAtLeast(now, enrollmentStageStartedAt, timeoutMs)) {
    finishEnrollment(false);
    return;
  }
  uint8_t result = FINGERPRINT_OK;
  switch (enrollmentStage) {
    case EnrollmentStage::FIRST_IMAGE:
    case EnrollmentStage::SECOND_IMAGE:
      result = finger.getImage();
      if (result == FINGERPRINT_NOFINGER) return;
      if (result == FINGERPRINT_OK) {
        enrollmentStage = enrollmentStage == EnrollmentStage::FIRST_IMAGE
            ? EnrollmentStage::FIRST_CONVERSION : EnrollmentStage::SECOND_CONVERSION;
      }
      break;
    case EnrollmentStage::FIRST_CONVERSION:
      result = finger.image2Tz(1);
      if (result == FINGERPRINT_OK) {
        showLcd(F("DANG KY VAN TAY"), F("NHAC NGON TAY RA"));
        playBuzzerTone(1500, 100);
        enrollmentStage = EnrollmentStage::REMOVE_FINGER;
      }
      break;
    case EnrollmentStage::REMOVE_FINGER:
      result = finger.getImage();
      if (result == FINGERPRINT_OK) return;
      if (result == FINGERPRINT_NOFINGER) {
        result = FINGERPRINT_OK;
        enrollmentStage = EnrollmentStage::SECOND_GAP;
      }
      break;
    case EnrollmentStage::SECOND_GAP:
      if (!elapsedAtLeast(now, enrollmentStageStartedAt, 600)) return;
      showLcd(F("DANG KY VAN TAY"), F("DAT NGON TAY 2"));
      playBuzzerTone(1200, 120);
      enrollmentStage = EnrollmentStage::SECOND_IMAGE;
      break;
    case EnrollmentStage::SECOND_CONVERSION:
      result = finger.image2Tz(2);
      enrollmentStage = EnrollmentStage::CREATE_MODEL;
      break;
    case EnrollmentStage::CREATE_MODEL:
      result = finger.createModel();
      enrollmentStage = EnrollmentStage::STORE_MODEL;
      break;
    case EnrollmentStage::STORE_MODEL:
      finishEnrollment(finger.storeModel(enrollmentTemplateId) == FINGERPRINT_OK);
      return;
    default: return;
  }
  if (result != FINGERPRINT_OK) { finishEnrollment(false); return; }
  enrollmentStageStartedAt = now;
}

void startWaitingForFingerRemoval() {
  waitingForFingerRemoval = true;
  fingerRemovalStarted = millis();
}

void maybeRecoverSensor() {
  if (doorNeedsResponsiveLoop()) return;
  if (sensorReady || millis() - lastSensorRetry < SENSOR_RETRY_INTERVAL_MS) return;
  lastSensorRetry = millis();
  Serial.println(F("AS608: thu ket noi lai cam bien"));
  showSensorReconnectScreen();
  if (finger.verifyPassword()) {
    markSensorReady();
  } else {
    setSensorError(F("AS608 khong phan hoi"));
  }
}

bool handleFingerprintRemoval() {
  if (fingerprintResultHoldActive()) return true;
  if (doorNeedsResponsiveLoop()) return waitingForFingerRemoval;
  if (!waitingForFingerRemoval) return false;

  if (!sensorReady) {
    waitingForFingerRemoval = false;
    showSensorReconnectScreen();
    return true;
  }
  if (!fingerprintPollDue()) return true;

  uint8_t imageStatus = finger.getImage();
  if (imageStatus == FINGERPRINT_NOFINGER) {
    waitingForFingerRemoval = false;
    if (!fingerprintDoorNoticeActive || !doorOpen) showReadyScreen();
  } else if (imageStatus != FINGERPRINT_OK && imageStatus != FINGERPRINT_IMAGEFAIL) {
    Serial.printf_P(PSTR("AS608 getImage (nhac ngon): ma=%u\n"), imageStatus);
    setSensorError(F("AS608 loi khi kiem tra ngon tay"));
    waitingForFingerRemoval = false;
  } else if (millis() - fingerRemovalStarted >= 10000) {
    showLcd(F("NHAC NGON TAY"), F("RA KHOI CAM BIEN"));
    fingerRemovalStarted = millis();
  }
  return true;
}

void handleFingerprintScan() {
  if (fingerprintResultHoldActive()) return;
  if (doorNeedsResponsiveLoop()) return;
  if (!sensorReady) {
    return;
  }
  if (!fingerprintPollDue()) return;

  uint8_t imageStatus = finger.getImage();
  if (imageStatus != FINGERPRINT_OK) {
    if (imageStatus == FINGERPRINT_IMAGEFAIL) {
      invalidateForegroundAttendanceAccess();
      showFingerprintResultNotice(F("KHONG DOC DUOC"), F("DAT LAI NGON TAY"));
      signalResult(false);
      startWaitingForFingerRemoval();
    } else if (imageStatus != FINGERPRINT_NOFINGER) {
      Serial.printf_P(PSTR("AS608 getImage (quet): ma=%u\n"), imageStatus);
      setSensorError(F("AS608 loi khi doc van tay"));
    }
    return;
  }

  invalidateForegroundAttendanceAccess();
  showLcd(F("DANG XU LY..."), F("VUI LONG DOI"));
  uint8_t imageToTemplateStatus = finger.image2Tz();
  uint8_t searchStatus = imageToTemplateStatus == FINGERPRINT_OK
      ? finger.fingerFastSearch()
      : imageToTemplateStatus;
  if (imageToTemplateStatus != FINGERPRINT_OK || searchStatus != FINGERPRINT_OK) {
    Serial.println(F("Van tay khong hop le"));
    if (searchStatus == FINGERPRINT_NOTFOUND || searchStatus == FINGERPRINT_NOMATCH ||
        imageToTemplateStatus == FINGERPRINT_IMAGEMESS ||
        imageToTemplateStatus == FINGERPRINT_FEATUREFAIL ||
        imageToTemplateStatus == FINGERPRINT_INVALIDIMAGE) {
      recordFailedScan("Van tay khong hop le");
    } else {
      Serial.printf_P(PSTR("AS608 xu ly: image2Tz=%u, search=%u\n"), imageToTemplateStatus, searchStatus);
      setSensorError(F("AS608 loi khi xu ly van tay"));
    }
    showFingerprintResultNotice(F("VAN TAY SAI"), F("XIN THU LAI"));
    signalResult(false);
    startWaitingForFingerRemoval();
    return;
  }

  Serial.printf_P(PSTR("Template %d, confidence %d\n"), finger.fingerID, finger.confidence);
  String employeeName;
  String attendanceTime;
  String attendanceType;
  const AttendanceDelivery delivery = uploadAttendance(finger.fingerID, finger.confidence,
                                                        employeeName, attendanceTime, attendanceType);
  if (delivery == AttendanceDelivery::QUEUED) {
    if (lastAttendanceCreatedOffline) {
      handleAttendanceDelivery(foregroundAttendanceEventId, AttendanceDelivery::LOCAL_ACCEPTED);
    } else {
      showLcd(employeeName, F("DANG XU LY"));
      playBuzzerTone(1000, 120);
      Serial.printf_P(PSTR("%s DA LUU CHO SYNC (chua mo cua)\n"), employeeName.c_str());
    }
  } else if (delivery == AttendanceDelivery::CONFIRMED) {
    handleAttendanceDelivery(foregroundAttendanceEventId, delivery);
  } else if (delivery == AttendanceDelivery::ACCESS_ONLY && OFFLINE_AS608_ACCESS_ENABLED) {
    // AS608 identity matching is independent of Wi-Fi/NTP. Do not invent an
    // attendance timestamp or a pending record when no valid clock exists.
    showLcd(F("DANG MO CUA"), F("CHUA LUU CONG"));
    signalResult(true);
    openDoor();
    fingerprintDoorNoticeActive = true;
    fingerprintDoorNoticeOffline = true;
    fingerprintDoorNoticeAttendanceSaved = false;
    setLatestError(F("Mo cua theo AS608; CHUA LUU CONG vi chua co gio hop le"));
    Serial.printf_P(PSTR("Template %u: chi mo cua, khong tao luot cham cong chua co gio\n"), finger.fingerID);
  } else {
    if (lastFingerprintAuthorizationDenied) {
      showFingerprintResultNotice(F("KHONG DUOC PHEP"), F("XIN LIEN HE ADMIN"));
    } else if (!hasValidClock()) {
      showFingerprintResultNotice(F("CHUA CO GIO"), F("KET NOI WIFI"));
    } else if (lastFingerprintAuthorizationUnavailable) {
      showFingerprintResultNotice(F("KHONG XAC THUC"), F("KHONG MO CUA"));
    } else if (attendanceOutboxIsFull()) {
      showFingerprintResultNotice(F("HANG DOI DAY"), F("KHONG LUU DUOC"));
    } else {
      showFingerprintResultNotice(F("KHONG LUU DUOC"), F("XIN THU LAI"));
    }
    signalResult(false);
  }
  startWaitingForFingerRemoval();
}
