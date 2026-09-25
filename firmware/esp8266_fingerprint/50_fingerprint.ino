// Fingerprint capture and enrollment.

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
  tone(BUZZER_PIN, 1200, 120);
  if (!waitForFinger(30000) || finger.image2Tz(1) != FINGERPRINT_OK) return false;
  Serial.println("DANG KY: nhac ngon tay ra");
  showLcd("DANG KY VAN TAY", "NHAC NGON TAY RA");
  tone(BUZZER_PIN, 1500, 100);
  if (!waitForFingerRemoval(10000)) return false;
  delay(600);
  Serial.println("DANG KY: dat cung ngon tay lan 2");
  showLcd("DANG KY VAN TAY", "DAT NGON TAY 2");
  tone(BUZZER_PIN, 1200, 120);
  if (!waitForFinger(30000) || finger.image2Tz(2) != FINGERPRINT_OK) return false;
  if (finger.createModel() != FINGERPRINT_OK) return false;
  return finger.storeModel(templateId) == FINGERPRINT_OK;
}

