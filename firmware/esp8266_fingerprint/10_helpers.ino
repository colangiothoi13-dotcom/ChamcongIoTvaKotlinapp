// Shared helpers, LCD, door, and sensor state.

void maintainWifiConnection() {
  if (WiFi.status() == WL_CONNECTED) return;
  if (millis() - lastWifiReconnectAttempt < WIFI_RECONNECT_INTERVAL_MS) return;

  lastWifiReconnectAttempt = millis();
  Serial.printf("WIFI mat ket noi, dang thu ket noi lai (status=%d)\n", WiFi.status());
  WiFi.reconnect();
}

char vietnameseLetter(uint32_t codePoint) {
  if (codePoint < 128) return static_cast<char>(codePoint);
  if (codePoint == 0x0110) return 'D';
  if (codePoint == 0x0111) return 'd';

  if (codePoint == 0x00C0 || codePoint == 0x00C1 || codePoint == 0x00C2 ||
      codePoint == 0x00C3 || codePoint == 0x0102 ||
      (codePoint >= 0x1EA0 && codePoint <= 0x1EB6 && codePoint % 2 == 0)) return 'A';
  if (codePoint == 0x00E0 || codePoint == 0x00E1 || codePoint == 0x00E2 ||
      codePoint == 0x00E3 || codePoint == 0x0103 ||
      (codePoint >= 0x1EA1 && codePoint <= 0x1EB7 && codePoint % 2 == 1)) return 'a';

  if (codePoint == 0x00C8 || codePoint == 0x00C9 || codePoint == 0x00CA ||
      (codePoint >= 0x1EB8 && codePoint <= 0x1EC6 && codePoint % 2 == 0)) return 'E';
  if (codePoint == 0x00E8 || codePoint == 0x00E9 || codePoint == 0x00EA ||
      (codePoint >= 0x1EB9 && codePoint <= 0x1EC7 && codePoint % 2 == 1)) return 'e';

  if (codePoint == 0x00CC || codePoint == 0x00CD || codePoint == 0x0128 ||
      (codePoint >= 0x1EC8 && codePoint <= 0x1ECA && codePoint % 2 == 0)) return 'I';
  if (codePoint == 0x00EC || codePoint == 0x00ED || codePoint == 0x0129 ||
      (codePoint >= 0x1EC9 && codePoint <= 0x1ECB && codePoint % 2 == 1)) return 'i';

  if (codePoint == 0x00D2 || codePoint == 0x00D3 || codePoint == 0x00D4 ||
      codePoint == 0x00D5 || codePoint == 0x01A0 ||
      (codePoint >= 0x1ECC && codePoint <= 0x1EE2 && codePoint % 2 == 0)) return 'O';
  if (codePoint == 0x00F2 || codePoint == 0x00F3 || codePoint == 0x00F4 ||
      codePoint == 0x00F5 || codePoint == 0x01A1 ||
      (codePoint >= 0x1ECD && codePoint <= 0x1EE3 && codePoint % 2 == 1)) return 'o';

  if (codePoint == 0x00D9 || codePoint == 0x00DA || codePoint == 0x0168 ||
      codePoint == 0x01AF ||
      (codePoint >= 0x1EE4 && codePoint <= 0x1EF0 && codePoint % 2 == 0)) return 'U';
  if (codePoint == 0x00F9 || codePoint == 0x00FA || codePoint == 0x0169 ||
      codePoint == 0x01B0 ||
      (codePoint >= 0x1EE5 && codePoint <= 0x1EF1 && codePoint % 2 == 1)) return 'u';

  if (codePoint == 0x00DD ||
      (codePoint >= 0x1EF2 && codePoint <= 0x1EF8 && codePoint % 2 == 0)) return 'Y';
  if (codePoint == 0x00FD ||
      (codePoint >= 0x1EF3 && codePoint <= 0x1EF9 && codePoint % 2 == 1)) return 'y';
  return 0;
}

String lcdSafeText(const String& input) {
  String output;
  output.reserve(16);

  for (size_t i = 0; i < input.length() && output.length() < 16;) {
    uint8_t first = static_cast<uint8_t>(input[i]);
    uint32_t codePoint = first;
    size_t charLength = 1;

    if ((first & 0xE0) == 0xC0 && i + 1 < input.length()) {
      codePoint = ((first & 0x1F) << 6) |
                  (static_cast<uint8_t>(input[i + 1]) & 0x3F);
      charLength = 2;
    } else if ((first & 0xF0) == 0xE0 && i + 2 < input.length()) {
      codePoint = ((first & 0x0F) << 12) |
                  ((static_cast<uint8_t>(input[i + 1]) & 0x3F) << 6) |
                  (static_cast<uint8_t>(input[i + 2]) & 0x3F);
      charLength = 3;
    }

    char replacement = vietnameseLetter(codePoint);
    if (replacement) output += replacement;
    i += charLength;
  }
  return output;
}

void lcdPrintLine(uint8_t row, const String& value) {
  String text = lcdSafeText(value);
  while (text.length() < 16) text += ' ';
  lcd.setCursor(0, row);
  lcd.print(text);
}

void renderLcd(const String& firstLine, const String& secondLine) {
  lcdPrintLine(0, firstLine);
  lcdPrintLine(1, secondLine);
}

void showLcd(const String& firstLine, const String& secondLine) {
  lcdIdleMode = false;
  renderLcd(firstLine, secondLine);
}

bool hasValidClock() {
  return time(nullptr) >= MIN_VALID_UNIX_TIME;
}

String vietnamTimeText() {
  time_t now = time(nullptr);
  if (now < MIN_VALID_UNIX_TIME) return "--:--:--";
  time_t localNow = now + 7 * 3600;
  struct tm localTime;
  gmtime_r(&localNow, &localTime);
  char value[15];
  strftime(value, sizeof(value), "%d/%m %H:%M:%S", &localTime);
  return String(value);
}

void showIdleScreen() {
  lcdIdleMode = true;
  int pendingCount = attendancePendingCount();
  String secondLine = pendingCount > 0
      ? String("CHO SYNC: ") + pendingCount
      : "DAT NGON TAY...";
  if (hasValidClock()) {
    renderLcd(vietnamTimeText(), secondLine);
  } else {
    renderLcd("CHUA DONG BO GIO", secondLine);
  }
  lastLcdClock = millis();
}

void showReadyScreen() {
  showIdleScreen();
}

void closeDoor() {
  doorServo.write(DOOR_CLOSED_ANGLE);
  doorOpen = false;
  doorOpenedAt = 0;
  doorStatus = "CLOSED";
  Serial.println("CUA: DONG");
}

void openDoor() {
  doorServo.write(DOOR_OPEN_ANGLE);
  doorOpen = true;
  doorOpenedAt = millis();
  doorStatus = "OPEN";
  Serial.println("CUA: MO, tu dong dong sau 5 giay");
}

void maybeCloseDoor() {
  if (doorOpen && millis() - doorOpenedAt >= DOOR_AUTO_CLOSE_DELAY_MS) closeDoor();
}

void handleDoorSwitch() {
  // D8 co dien tro keo xuong de boot, nen nut nhan noi D8 voi 3V3.
  // Khi nhan, muc doc la HIGH.
  int reading = digitalRead(DOOR_SWITCH_PIN);
  if (reading != lastDoorSwitchReading) {
    doorSwitchChangedAt = millis();
    lastDoorSwitchReading = reading;
  }

  if (millis() - doorSwitchChangedAt < DOOR_SWITCH_DEBOUNCE_MS) return;
  if (reading == stableDoorSwitchState) return;

  stableDoorSwitchState = reading;
  if (stableDoorSwitchState == HIGH) {
    if (doorOpen) closeDoor();
    else openDoor();
  }
}

void maybeUpdateIdleClock() {
  if (!lcdIdleMode || millis() - lastLcdClock < LCD_CLOCK_INTERVAL_MS) return;
  lastLcdClock = millis();
  int pendingCount = attendancePendingCount();
  String secondLine = pendingCount > 0
      ? String("CHO SYNC: ") + pendingCount
      : "DAT NGON TAY...";
  if (hasValidClock()) {
    renderLcd(vietnamTimeText(), secondLine);
  } else {
    renderLcd("CHUA DONG BO GIO", secondLine);
  }
}

void startWaitingForFingerRemoval() {
  waitingForFingerRemoval = true;
  fingerRemovalStarted = millis();
}

void signalResult(bool ok) {
  digitalWrite(ok ? LED_GREEN_PIN : LED_RED_PIN, HIGH);
  tone(BUZZER_PIN, ok ? 1800 : 500, ok ? 160 : 500);
  delay(ok ? 900 : 1300);
  digitalWrite(LED_GREEN_PIN, LOW);
  digitalWrite(LED_RED_PIN, LOW);
}

void setLatestError(const String& message) {
  lastError = message;
  if (lastError.length() > 160) lastError = lastError.substring(0, 160);
  Serial.printf("LOI GAN NHAT: %s\n", lastError.c_str());
}

void setSensorError(const String& message) {
  sensorReady = false;
  sensorStatus = "ERROR";
  sensorError = message;
  setLatestError(message);
  Serial.printf("AS608: vo hieu hoa doc cam bien (%s)\n", sensorError.c_str());
}

void markSensorReady() {
  if (!sensorReady) Serial.println("AS608: cam bien da san sang");
  sensorReady = true;
  sensorStatus = "OK";
  sensorError = "";
  lastHeartbeat = 0;
  if (lcdIdleMode || (!waitingForFingerRemoval && !pendingCommandResult)) showReadyScreen();
}

int recentFailedScanCount() {
  uint32_t now = millis();
  uint8_t kept = 0;
  for (uint8_t i = 0; i < failedScanSampleCount; ++i) {
    if (now - failedScanTimes[i] <= FAILED_SCAN_WINDOW_MS) {
      failedScanTimes[kept++] = failedScanTimes[i];
    }
  }
  failedScanSampleCount = kept;
  return failedScanSampleCount;
}

void recordFailedScan(const String& message) {
  recentFailedScanCount();
  if (failedScanSampleCount < sizeof(failedScanTimes) / sizeof(failedScanTimes[0])) {
    failedScanTimes[failedScanSampleCount++] = millis();
  } else {
    for (uint8_t i = 1; i < sizeof(failedScanTimes) / sizeof(failedScanTimes[0]); ++i) {
      failedScanTimes[i - 1] = failedScanTimes[i];
    }
    failedScanTimes[(sizeof(failedScanTimes) / sizeof(failedScanTimes[0])) - 1] = millis();
  }
  setLatestError(message);
}

void maybeRecoverSensor() {
  if (sensorReady || millis() - lastSensorRetry < SENSOR_RETRY_INTERVAL_MS) return;
  lastSensorRetry = millis();
  Serial.println("AS608: thu ket noi lai cam bien");
  if (finger.verifyPassword()) {
    markSensorReady();
  } else {
    setSensorError("AS608 khong phan hoi");
  }
}

