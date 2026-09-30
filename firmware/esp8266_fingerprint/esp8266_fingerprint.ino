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

// ==================== KHAI BAO CHAN PHAN CUNG ====================
// AS608/R307: D5 (GPIO14) <- TX cam bien, D6 (GPIO12) -> RX cam bien
const uint8_t FINGERPRINT_RX_PIN = D5;
const uint8_t FINGERPRINT_TX_PIN = D6;

// LED, coi, cong tac, va servo cua
const uint8_t LED_GREEN_PIN = D1;      // LED xanh
const uint8_t LED_RED_PIN = D2;        // LED do
const uint8_t BUZZER_PIN = D7;         // Coi bao
const uint8_t DOOR_SWITCH_PIN = D0;    // Nut nhan
const uint8_t DOOR_SERVO_PIN = D8;     // Signal servo

// LCD I2C: D3 (GPIO0) = SDA, D4 (GPIO2) = SCL
const uint8_t LCD_SDA_PIN = D3;
const uint8_t LCD_SCL_PIN = D4;
// ================================================================

const char* WIFI_SSID = DEVICE_WIFI_SSID;
const char* WIFI_PASSWORD = DEVICE_WIFI_PASSWORD;
const char* FIREBASE_API_KEY = FIREBASE_WEB_API_KEY;
const char* FIRESTORE_URL = FIRESTORE_BASE_URL;
const char* DEVICE_ID = "GATE-01";
const char* FIRMWARE_VERSION = "spark-anonymous-v6-access-safe";
const unsigned long HEARTBEAT_INTERVAL_MS = 30000;
const unsigned long ATTENDANCE_SYNC_INTERVAL_MS = 5000;
const unsigned long ATTENDANCE_RETRY_INTERVAL_MS = 30000;
const unsigned long COMMAND_ACTIVE_POLL_INTERVAL_MS = 3000;
const unsigned long COMMAND_IDLE_POLL_INTERVAL_MS = 15000;
const unsigned long COMMAND_RETRY_INTERVAL_MS = 10000;
const unsigned long HTTPS_RETRY_COOLDOWN_MS = 30000;
const uint32_t MIN_HTTPS_FREE_HEAP = 30000;
const uint32_t MIN_HTTPS_MAX_FREE_BLOCK = 20000;
const unsigned long WIFI_RECONNECT_INTERVAL_MS = 10000;
const unsigned long SENSOR_RETRY_INTERVAL_MS = 15000;
const unsigned long LCD_CLOCK_INTERVAL_MS = 1000;
const unsigned long FAILED_SCAN_WINDOW_MS = 300000;
const size_t ATTENDANCE_OUTBOX_MAX_BYTES = 12288;
const size_t ATTENDANCE_OUTBOX_WARN_BYTES = 10240;
const char* ATTENDANCE_OUTBOX_PATH = "/attendance.outbox";
const char* ATTENDANCE_OUTBOX_TMP_PATH = "/attendance.outbox.tmp";
const char* ATTENDANCE_OUTBOX_BACKUP_PATH = "/attendance.outbox.bak";
const char* ATTENDANCE_SEQUENCE_PATH = "/attendance.seq";
const char* ATTENDANCE_SEQUENCE_TMP_PATH = "/attendance.seq.tmp";
const char* ATTENDANCE_SEQUENCE_BACKUP_PATH = "/attendance.seq.bak";
const char* FINGERPRINT_CACHE_PATH = "/fingerprint-cache.json";
const time_t MIN_VALID_UNIX_TIME = 1700000000;

unsigned long lastCommandCheck = 0;
unsigned long commandPollIntervalMs = COMMAND_IDLE_POLL_INTERVAL_MS;
unsigned long lastHeartbeat = 0;
unsigned long lastAttendanceSync = 0;
unsigned long attendanceSyncIntervalMs = ATTENDANCE_SYNC_INTERVAL_MS;
unsigned long lastHttpsTransportFailure = 0;
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
bool pendingCommandResult = false;
bool pendingCommandSuccess = false;
bool pendingCommandRestart = false;
uint16_t pendingCommandTemplateId = 0;
bool attendancePendingSync = false;
bool hasHttpsTransportFailure = false;
bool lastFingerprintAuthorizationUnavailable = false;
bool lastFingerprintAuthorizationDenied = false;
bool capabilitiesNeedSync = true;
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
String lastAcknowledgedAttendanceEventId = "";
uint32_t failedScanTimes[32] = {};
uint8_t failedScanSampleCount = 0;

int attendancePendingCount();
size_t attendanceOutboxBytes();
bool attendanceOutboxIsFull();
const char* attendanceOutboxStatus();

// Implementation is split into the .ino tabs in this folder.
