// Còi, LED báo trạng thái, và công tắc mở cửa.

const unsigned long DOOR_SWITCH_DEBOUNCE_MS = 50;
unsigned long outputEffectStartedAt = 0;
unsigned long outputEffectDurationMs = 0;

void initializeOutputsAndSwitch() {
  pinMode(LED_GREEN_PIN, OUTPUT);
  pinMode(LED_RED_PIN, OUTPUT);
  pinMode(BUZZER_PIN, OUTPUT);
  // D0/GPIO16 dung dien tro keo xuong noi bo; nut noi D0 voi 3V3 khi nhan.
  pinMode(DOOR_SWITCH_PIN, INPUT_PULLDOWN_16);
  lastDoorSwitchReading = digitalRead(DOOR_SWITCH_PIN);
  stableDoorSwitchState = lastDoorSwitchReading;
  doorSwitchChangedAt = millis();
  Serial.printf_P(PSTR("NUT CUA D0: luc khoi dong %s (tha nut phai LOW)\n"),
                lastDoorSwitchReading == HIGH ? "HIGH" : "LOW");
}

void playBuzzerTone(uint16_t frequency, uint16_t durationMs) {
  tone(BUZZER_PIN, frequency, durationMs);
}

void setLed(uint8_t pin, bool enabled) {
  digitalWrite(pin, enabled ? HIGH : LOW);
}

void signalResult(bool ok) {
  setLed(LED_GREEN_PIN, false);
  setLed(LED_RED_PIN, false);
  setLed(ok ? LED_GREEN_PIN : LED_RED_PIN, true);
  playBuzzerTone(ok ? 1800 : 500, ok ? 160 : 500);
  outputEffectStartedAt = millis();
  outputEffectDurationMs = ok ? 900 : 1300;
}

void serviceOutputEffects() {
  if (outputEffectDurationMs == 0 ||
      !elapsedAtLeast(millis(), outputEffectStartedAt, outputEffectDurationMs)) return;
  setLed(LED_GREEN_PIN, false);
  setLed(LED_RED_PIN, false);
  outputEffectDurationMs = 0;
}

void testGreenLed() {
  setLed(LED_GREEN_PIN, true);
  outputEffectStartedAt = millis();
  outputEffectDurationMs = 600;
}

void testRedLed() {
  setLed(LED_RED_PIN, true);
  outputEffectStartedAt = millis();
  outputEffectDurationMs = 600;
}

void testBuzzer() {
  playBuzzerTone(1500, 600);
}

void handleDoorSwitch() {
  // Chi mo cua khi phat hien canh nhan LOW -> HIGH, khong toggle khi dang mo.
  int reading = digitalRead(DOOR_SWITCH_PIN);
  if (reading != lastDoorSwitchReading) {
    Serial.printf_P(PSTR("NUT CUA D0: tin hieu %s\n"), reading == HIGH ? "HIGH" : "LOW");
    doorSwitchChangedAt = millis();
    lastDoorSwitchReading = reading;
  }

  if (millis() - doorSwitchChangedAt < DOOR_SWITCH_DEBOUNCE_MS) return;
  if (reading == stableDoorSwitchState) return;

  stableDoorSwitchState = reading;
  if (stableDoorSwitchState == HIGH) {
    Serial.println(F("NUT CUA: da nhan, dang mo cua"));
    showLcd(F("DA NHAN NUT CUA"), F("DANG MO CUA"));
    openDoor();
    if (!pendingCommandExecution) showReadyScreen();
  }
}
