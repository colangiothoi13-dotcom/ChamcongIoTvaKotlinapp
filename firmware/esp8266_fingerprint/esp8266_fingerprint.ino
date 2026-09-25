#include <ESP8266WiFi.h>
#include <ESP8266HTTPClient.h>
#include <WiFiClientSecureBearSSL.h>
#include <Adafruit_Fingerprint.h>
#include <SoftwareSerial.h>
#include <ArduinoJson.h>
#include <Wire.h>
#include <LiquidCrystal_I2C.h>
#include <Servo.h>
#include <LittleFS.h>
#include <time.h>

#include "secrets.h"

// ==================== KHAI BAO CHAN CAM ====================
// NodeMCU D5 (GPIO14) <- TX cua cam bien van tay AS608/R307
// NodeMCU D6 (GPIO12) -> RX cua cam bien van tay AS608/R307
const uint8_t LED_GREEN_PIN = D1;  // LED xanh: cham cong thanh cong
const uint8_t LED_RED_PIN   = D2;  // LED do: that bai
const uint8_t BUZZER_PIN    = D7;  // Coi bao; tranh D3/GPIO0 vi la chan boot
const uint8_t DOOR_SERVO_PIN = D0;   // Signal servo 9g (GPIO16)
const uint8_t DOOR_SWITCH_PIN = D8;  // Nut nhan: noi ve 3V3 khi nhan
const uint8_t DOOR_CLOSED_ANGLE = 0;
const uint8_t DOOR_OPEN_ANGLE = 90;
const unsigned long DOOR_AUTO_CLOSE_DELAY_MS = 5000;
const unsigned long DOOR_SWITCH_DEBOUNCE_MS = 50;

// D1/D2 dang duoc dung cho LED, nen LCD I2C dung D3/D4.
// D3/GPIO0 va D4/GPIO2 can duoc giu HIGH khi ESP8266 khoi dong.
// Neu cap backpack LCD bang 5V, phai dung level shifter I2C 3.3V cho SDA/SCL.
const uint8_t LCD_SDA_PIN = D3;
const uint8_t LCD_SCL_PIN = D4;
const uint8_t LCD_ADDRESS = 0x27;  // Doi thanh 0x3F neu module dung dia chi nay
// ==========================================================

// SoftwareSerial(rx, tx); UART0 van duoc giu lai cho Serial Monitor.
#if (defined(__AVR__) || defined(ESP8266)) && !defined(__AVR_ATmega2560__)
SoftwareSerial mySerial(D5, D6);
#else
#define mySerial Serial1
#endif
Adafruit_Fingerprint finger(&mySerial);
LiquidCrystal_I2C lcd(LCD_ADDRESS, 16, 2);
Servo doorServo;

const char* WIFI_SSID = DEVICE_WIFI_SSID;
const char* WIFI_PASSWORD = DEVICE_WIFI_PASSWORD;
const char* FIREBASE_API_KEY = FIREBASE_WEB_API_KEY;
const char* FIRESTORE_URL = FIRESTORE_BASE_URL;
const char* DEVICE_ID = "GATE-01";
const char* FIRMWARE_VERSION = "spark-anonymous-v4-door-servo";
const unsigned long HEARTBEAT_INTERVAL_MS = 30000;
const unsigned long ATTENDANCE_SYNC_INTERVAL_MS = 5000;
const unsigned long WIFI_RECONNECT_INTERVAL_MS = 10000;
const unsigned long SENSOR_RETRY_INTERVAL_MS = 15000;
const unsigned long LCD_CLOCK_INTERVAL_MS = 1000;
const unsigned long FAILED_SCAN_WINDOW_MS = 300000;
const size_t ATTENDANCE_OUTBOX_MAX_BYTES = 12288;
const char* ATTENDANCE_OUTBOX_PATH = "/attendance.outbox";
const char* ATTENDANCE_OUTBOX_TMP_PATH = "/attendance.outbox.tmp";
const char* ATTENDANCE_OUTBOX_BACKUP_PATH = "/attendance.outbox.bak";
const char* ATTENDANCE_SEQUENCE_PATH = "/attendance.seq";
const char* ATTENDANCE_SEQUENCE_TMP_PATH = "/attendance.seq.tmp";
const char* ATTENDANCE_SEQUENCE_BACKUP_PATH = "/attendance.seq.bak";
const char* FINGERPRINT_CACHE_PATH = "/fingerprint-cache.json";
const time_t MIN_VALID_UNIX_TIME = 1700000000;

unsigned long lastCommandCheck = 0;
unsigned long lastHeartbeat = 0;
unsigned long lastAttendanceSync = 0;
unsigned long lastWifiReconnectAttempt = 0;
unsigned long lastSensorRetry = 0;
unsigned long lastLcdClock = 0;
unsigned long tokenCreatedAt = 0;
String firebaseIdToken;
bool waitingForFingerRemoval = false;
String commandId;
String commandRequestId;
String commandVersion;
String pendingCommandType;
String pendingCommandEmployeeId;
String pendingCommandEmployeeName;
bool pendingCommandResult = false;
bool pendingCommandSuccess = false;
bool pendingCommandDelete = false;
bool pendingCommandRestart = false;
uint16_t pendingCommandTemplateId = 0;
bool attendancePendingSync = false;
bool littleFsReady = false;
unsigned long fingerRemovalStarted = 0;
bool sensorReady = false;
bool lcdIdleMode = false;
bool doorOpen = false;
unsigned long doorOpenedAt = 0;
String doorStatus = "CLOSED";
int lastDoorSwitchReading = LOW;
int stableDoorSwitchState = LOW;
unsigned long doorSwitchChangedAt = 0;
String sensorError = "AS608 chua san sang";
String sensorStatus = "UNKNOWN";
String lastError = "";
String firebaseSyncStatus = "PENDING";
String lastRejectedAttendanceEventId = "";
uint32_t failedScanTimes[32] = {};
uint8_t failedScanSampleCount = 0;

int attendancePendingCount();

// Implementation is split into the .ino tabs in this folder.
