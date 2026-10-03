// Servo cửa: pin, object, initialization, mở/đóng, và tự động đóng.

// Theo cach lap servo cua ban: 180 do = cua dong, 0 do = cua mo.
const uint8_t DOOR_CLOSED_ANGLE = 180;
const uint8_t DOOR_OPEN_ANGLE = 0;
const unsigned long DOOR_AUTO_CLOSE_DELAY_MS = 5000;
const uint8_t DOOR_MOVE_STEP_DELAY_MS = 10;
Servo doorServo;
int doorCurrentAngle = DOOR_CLOSED_ANGLE;

void moveDoorSmoothly(int targetAngle) {
  targetAngle = constrain(targetAngle, 0, 180);
  int step = targetAngle >= doorCurrentAngle ? 1 : -1;

  for (int angle = doorCurrentAngle; angle != targetAngle; angle += step) {
    doorServo.write(angle);
    delay(DOOR_MOVE_STEP_DELAY_MS);
  }

  doorServo.write(targetAngle);
  doorCurrentAngle = targetAngle;
}

void initializeDoorServo() {
  // SG90: cho phep dai xung rong hon de servo nhan du goc 0..180.
  doorServo.attach(DOOR_SERVO_PIN, 500, 2400);
  doorCurrentAngle = DOOR_CLOSED_ANGLE;
  closeDoor();
}

void closeDoor() {
  moveDoorSmoothly(DOOR_CLOSED_ANGLE);
  doorOpen = false;
  doorOpenedAt = 0;
  doorStatus = "CLOSED";
  Serial.println("CUA: DONG");
}

void openDoor() {
  moveDoorSmoothly(DOOR_OPEN_ANGLE);
  doorOpen = true;
  doorOpenedAt = millis();
  doorStatus = "OPEN";
  Serial.println("CUA: MO, tu dong dong sau 5 giay");
}

void maybeCloseDoor() {
  if (doorOpen && millis() - doorOpenedAt >= DOOR_AUTO_CLOSE_DELAY_MS) {
    closeDoor();
    if (fingerprintDoorNoticeActive) showReadyScreen();
  }
}
