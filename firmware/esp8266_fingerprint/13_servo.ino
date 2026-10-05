// Servo cửa: pin, object, initialization, mở/đóng, và tự động đóng.

// Theo cach lap servo cua ban: 180 do = cua dong, 0 do = cua mo.
const uint8_t DOOR_CLOSED_ANGLE = 180;
const uint8_t DOOR_OPEN_ANGLE = 0;
const unsigned long DOOR_AUTO_CLOSE_DELAY_MS = 5000;
const uint8_t DOOR_MOVE_STEP_DELAY_MS = 10;
Servo doorServo;
int doorCurrentAngle = DOOR_CLOSED_ANGLE;
int doorTargetAngle = DOOR_CLOSED_ANGLE;
unsigned long doorLastStepAt = 0;
unsigned long doorMoveStartedAt = 0;

bool doorNeedsResponsiveLoop() { return doorOpen || doorMoving; }

void initializeDoorServo() {
  // SG90: cho phep dai xung rong hon de servo nhan du goc 0..180.
  doorServo.attach(DOOR_SERVO_PIN, 500, 2400);
  doorCurrentAngle = DOOR_CLOSED_ANGLE;
  doorTargetAngle = DOOR_CLOSED_ANGLE;
  doorServo.write(DOOR_CLOSED_ANGLE);
  doorOpen = false;
  doorMoving = false;
  doorStatus = "CLOSED";
}

void startDoorMovement(int targetAngle) {
  doorTargetAngle = constrain(targetAngle, 0, 180);
  if (doorCurrentAngle == doorTargetAngle) {
    doorMoving = false;
    doorOpen = doorTargetAngle == DOOR_OPEN_ANGLE;
    doorOpenedAt = doorOpen ? millis() : 0;
    doorStatus = doorOpen ? "OPEN" : "CLOSED";
    return;
  }
  doorMoveStartedAt = millis();
  doorLastStepAt = millis();
  doorMoving = true;
  doorOpen = false;
  doorOpenedAt = 0;
  doorStatus = targetAngle == DOOR_OPEN_ANGLE ? "OPENING" : "CLOSING";
  Serial.printf("CUA: %s bat dau tai %lu ms\n", doorStatus.c_str(), doorMoveStartedAt);
}

void closeDoor() { startDoorMovement(DOOR_CLOSED_ANGLE); }

void openDoor() {
  if (doorOpen || (doorMoving && doorTargetAngle == DOOR_OPEN_ANGLE)) return;
  startDoorMovement(DOOR_OPEN_ANGLE);
}

void maybeCloseDoor() {
  if (doorOpen && elapsedAtLeast(millis(), doorOpenedAt, DOOR_AUTO_CLOSE_DELAY_MS)) {
    Serial.printf("CUA: bat dau dong sau %lu ms mo hoan toan\n", millis() - doorOpenedAt);
    closeDoor();
  }
}

void serviceDoor() {
  maybeCloseDoor();
  if (!doorMoving || !elapsedAtLeast(millis(), doorLastStepAt, DOOR_MOVE_STEP_DELAY_MS)) return;
  doorLastStepAt = millis();
  doorCurrentAngle += doorTargetAngle > doorCurrentAngle ? 1 : -1;
  doorServo.write(doorCurrentAngle);
  if (doorCurrentAngle != doorTargetAngle) return;
  doorMoving = false;
  doorOpen = doorTargetAngle == DOOR_OPEN_ANGLE;
  doorStatus = doorOpen ? "OPEN" : "CLOSED";
  // Measure the open hold separately from servo travel time.
  doorOpenedAt = doorOpen ? millis() : 0;
  Serial.printf("CUA: %s hoan toan; servo di chuyen %lu ms\n",
                doorStatus.c_str(), millis() - doorMoveStartedAt);
  if (doorOpen && fingerprintDoorNoticeActive) {
    renderLcd("SE DONG SAU 5S", !fingerprintDoorNoticeAttendanceSaved ? "CHUA LUU CONG"
        : (fingerprintDoorNoticeOffline ? "OFFLINE: DA LUU" : "DA XAC NHAN"));
  }
  if (!doorOpen && fingerprintDoorNoticeActive) showReadyScreen();
}
