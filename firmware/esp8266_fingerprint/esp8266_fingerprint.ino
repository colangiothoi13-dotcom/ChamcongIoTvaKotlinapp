#include <ESP8266WiFi.h>
#include <ESP8266HTTPClient.h>
#include <WiFiClientSecureBearSSL.h>
#include <Adafruit_Fingerprint.h>
#include <SoftwareSerial.h>
#include <ArduinoJson.h>
#include <Wire.h>
#include <LiquidCrystal_I2C.h>
#include <LittleFS.h>
#include <time.h>

// ==================== KHAI BAO CHAN CAM ====================
// NodeMCU D5 (GPIO14) <- TX cua cam bien van tay AS608/R307
// NodeMCU D6 (GPIO12) -> RX cua cam bien van tay AS608/R307
const uint8_t LED_GREEN_PIN = D1;  // LED xanh: cham cong thanh cong
const uint8_t LED_RED_PIN   = D2;  // LED do: that bai
const uint8_t BUZZER_PIN    = D7;  // Coi bao; tranh D3/GPIO0 vi la chan boot

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

const char* WIFI_SSID = DEVICE_WIFI_SSID;
const char* WIFI_PASSWORD = DEVICE_WIFI_PASSWORD;
// Lay current_key trong app/google-services.json. Firebase API key khong phai mat khau.
const char* FIREBASE_API_KEY = "AIzaSyDX6BVVu7iKV-L4eukCQy0-BRehWs-NZYw";
const char* FIRESTORE_BASE_URL = "https://firestore.googleapis.com/v1/projects/chamcongiot-56ae5/databases/(default)/documents";
const char* ANONYMOUS_AUTH_URL = "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=";
const char* DEVICE_ID = "GATE-01";
const char* FIRMWARE_VERSION = "snapshot-2-offline";
const unsigned long HEARTBEAT_INTERVAL_MS = 30000;
const unsigned long ATTENDANCE_SYNC_INTERVAL_MS = 5000;
const size_t ATTENDANCE_OUTBOX_MAX_BYTES = 12288;
const char* ATTENDANCE_OUTBOX_PATH = "/attendance.outbox";
const char* ATTENDANCE_OUTBOX_TMP_PATH = "/attendance.outbox.tmp";
const char* FINGERPRINT_CACHE_PATH = "/fingerprint-cache.json";

unsigned long lastCommandCheck = 0;
unsigned long lastHeartbeat = 0;
unsigned long lastAttendanceSync = 0;
unsigned long tokenCreatedAt = 0;
String firebaseIdToken;
bool waitingForFingerRemoval = false;
String commandVersion;
String commandRequestId;
bool pendingCommandResult = false;
bool pendingCommandSuccess = false;
bool pendingCommandDelete = false;
uint16_t pendingCommandTemplateId = 0;
bool attendancePendingSync = false;
bool littleFsReady = false;
unsigned long fingerRemovalStarted = 0;

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

void showLcd(const String& firstLine, const String& secondLine) {
  lcdPrintLine(0, firstLine);
  lcdPrintLine(1, secondLine);
}

void showReadyScreen() {
  showLcd("MAY CHAM CONG", "DAT NGON TAY...");
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

bool firebaseSignIn() {
  if (firebaseIdToken.length() > 0 && millis() - tokenCreatedAt < 3300000UL) return true;
  BearSSL::WiFiClientSecure client;
  client.setInsecure();
  HTTPClient https;
  String url = String(ANONYMOUS_AUTH_URL) + FIREBASE_API_KEY;
  if (!https.begin(client, url)) return false;
  https.addHeader("Content-Type", "application/json");
  int code = https.POST("{\"returnSecureToken\":true}");
  String response = https.getString();
  https.end();
  if (code != 200) {
    Serial.printf("Firebase Auth loi HTTP %d: %s\n", code, response.c_str());
    return false;
  }
  DynamicJsonDocument authDoc(2048);
  if (deserializeJson(authDoc, response)) return false;
  firebaseIdToken = authDoc["idToken"].as<String>();
  tokenCreatedAt = millis();
  Serial.println("Da dang nhap Firebase Anonymous");
  return firebaseIdToken.length() > 0;
}

String utcTimestamp(time_t now = time(nullptr)) {
  if (now < 1700000000) return "";
  struct tm utc;
  gmtime_r(&now, &utc);
  char value[25];
  strftime(value, sizeof(value), "%Y-%m-%dT%H:%M:%SZ", &utc);
  return String(value);
}

bool cacheFingerprintMapping(uint16_t templateId, const String& employeeId, const String& employeeName) {
  if (!littleFsReady) return false;
  char key[8];
  snprintf(key, sizeof(key), "%u", templateId);
  DynamicJsonDocument cache(4096);
  File input = LittleFS.open(FINGERPRINT_CACHE_PATH, "r");
  if (input) {
    deserializeJson(cache, input);
    input.close();
  }
  JsonObject entry = cache[key].to<JsonObject>();
  entry["employeeId"] = employeeId;
  entry["employeeName"] = employeeName;
  File output = LittleFS.open(FINGERPRINT_CACHE_PATH, "w");
  if (!output) return false;
  serializeJson(cache, output);
  output.close();
  return true;
}

bool loadCachedFingerprintMapping(uint16_t templateId, String& employeeId, String& employeeName) {
  if (!littleFsReady) return false;
  char key[8];
  snprintf(key, sizeof(key), "%u", templateId);
  File input = LittleFS.open(FINGERPRINT_CACHE_PATH, "r");
  if (!input) return false;
  DynamicJsonDocument cache(4096);
  DeserializationError error = deserializeJson(cache, input);
  input.close();
  if (error) return false;
  JsonObject entry = cache[key].as<JsonObject>();
  if (entry.isNull()) return false;
  employeeId = entry["employeeId"] | "";
  employeeName = entry["employeeName"] | "";
  employeeId.trim();
  employeeName.trim();
  return employeeId.length() > 0 && employeeName.length() > 0;
}

void removeCachedFingerprintMapping(uint16_t templateId) {
  if (!littleFsReady) return;
  char key[8];
  snprintf(key, sizeof(key), "%u", templateId);
  File input = LittleFS.open(FINGERPRINT_CACHE_PATH, "r");
  if (!input) return;
  DynamicJsonDocument cache(4096);
  if (deserializeJson(cache, input)) {
    input.close();
    return;
  }
  input.close();
  cache.remove(key);
  File output = LittleFS.open(FINGERPRINT_CACHE_PATH, "w");
  if (!output) return;
  serializeJson(cache, output);
  output.close();
}

bool enqueueAttendanceEvent(const String& eventId, const String& payload) {
  if (!littleFsReady) return false;
  File input = LittleFS.open(ATTENDANCE_OUTBOX_PATH, "r");
  String existing;
  if (input) {
    existing = input.readString();
    input.close();
  }
  if (existing.indexOf(String("\"eventId\":\"") + eventId + "\"") >= 0) return true;

  DynamicJsonDocument record(2048);
  record["eventId"] = eventId;
  record["payload"] = payload;
  String line;
  serializeJson(record, line);
  line += '\n';
  if (existing.length() + line.length() > ATTENDANCE_OUTBOX_MAX_BYTES) {
    Serial.printf("OUTBOX day: bo qua event %s, queue da day\n", eventId.c_str());
    return false;
  }
  File output = LittleFS.open(ATTENDANCE_OUTBOX_PATH, "a");
  if (!output) return false;
  output.print(line);
  output.close();
  Serial.printf("OUTBOX them event %s, bytes=%u\n", eventId.c_str(), static_cast<unsigned>(existing.length() + line.length()));
  return true;
}

int postAttendanceEvent(const String& eventId, const String& payload) {
  if (WiFi.status() != WL_CONNECTED || !firebaseSignIn()) return -1;
  BearSSL::WiFiClientSecure client;
  client.setInsecure();
  HTTPClient https;
  String url = String(FIRESTORE_BASE_URL) + "/attendance?documentId=" + eventId;
  if (!https.begin(client, url)) return -1;
  https.setTimeout(5000);
  https.addHeader("Content-Type", "application/json");
  https.addHeader("Authorization", "Bearer " + firebaseIdToken);
  // Firestore Rules accept only PENDING_SYNC from a device. The trusted
  // attendance resolver changes this to SYNCED after it processes the event.
  int code = https.POST(payload);
  String response = https.getString();
  https.end();
  Serial.printf("ATTENDANCE HTTP %d: %s\n", code, response.c_str());
  return code;
}

bool httpResponseAcknowledgesEvent(int code) {
  return (code >= 200 && code < 300) || code == 409;
}

bool flushAttendanceOutbox() {
  if (!littleFsReady) return false;
  File input = LittleFS.open(ATTENDANCE_OUTBOX_PATH, "r");
  if (!input || input.size() == 0) {
    if (input) input.close();
    return true;
  }
  String remaining;
  bool blocked = false;
  while (input.available()) {
    String line = input.readStringUntil('\n');
    line.trim();
    if (line.length() == 0) continue;
    if (blocked) {
      remaining += line + '\n';
      continue;
    }
    DynamicJsonDocument record(2048);
    DeserializationError error = deserializeJson(record, line);
    String eventId = record["eventId"] | "";
    String payload = record["payload"] | "";
    int code = error || eventId.length() == 0 || payload.length() == 0 ? -1 : postAttendanceEvent(eventId, payload);
    if (httpResponseAcknowledgesEvent(code)) {
      Serial.printf("OUTBOX da dong bo event %s\n", eventId.c_str());
    } else {
      blocked = true;
      remaining += line + '\n';
      Serial.printf("OUTBOX giu event %s, HTTP %d\n", eventId.c_str(), code);
    }
    yield();
  }
  input.close();
  if (remaining.length() == 0) {
    LittleFS.remove(ATTENDANCE_OUTBOX_PATH);
  } else {
    File output = LittleFS.open(ATTENDANCE_OUTBOX_TMP_PATH, "w");
    if (!output) return false;
    output.print(remaining);
    output.close();
    LittleFS.remove(ATTENDANCE_OUTBOX_PATH);
    LittleFS.rename(ATTENDANCE_OUTBOX_TMP_PATH, ATTENDANCE_OUTBOX_PATH);
  }
  return remaining.length() == 0;
}

size_t attendanceOutboxBytes() {
  if (!littleFsReady) return 0;
  File input = LittleFS.open(ATTENDANCE_OUTBOX_PATH, "r");
  if (!input) return 0;
  size_t bytes = input.size();
  input.close();
  return bytes;
}

int attendancePendingCount() {
  if (!littleFsReady) return 0;
  File input = LittleFS.open(ATTENDANCE_OUTBOX_PATH, "r");
  if (!input) return 0;
  int count = 0;
  while (input.available()) {
    String line = input.readStringUntil('\n');
    if (line.length() > 0) count++;
    yield();
  }
  input.close();
  return count;
}

bool publishDeviceSnapshot() {
  if (WiFi.status() != WL_CONNECTED) {
    Serial.printf("HEARTBEAT bo qua: WiFi status=%d\n", WiFi.status());
    return false;
  }
  if (time(nullptr) < 1700000000) {
    Serial.println("HEARTBEAT bo qua: chua dong bo NTP");
    return false;
  }
  if (!firebaseSignIn()) {
    Serial.println("HEARTBEAT bo qua: Firebase Auth that bai");
    return false;
  }

  int templateCount = -1;
  if (finger.getTemplateCount() == FINGERPRINT_OK) templateCount = finger.templateCount;

  BearSSL::WiFiClientSecure client;
  client.setInsecure();
  HTTPClient https;
  String url = String(FIRESTORE_BASE_URL) + "/devices/" + DEVICE_ID;
  if (!https.begin(client, url)) {
    Serial.println("HEARTBEAT loi: khong tao duoc ket noi HTTPS");
    return false;
  }
  https.setTimeout(5000);
  https.addHeader("Content-Type", "application/json");
  https.addHeader("Authorization", "Bearer " + firebaseIdToken);

  DynamicJsonDocument doc(2048);
  JsonObject fields = doc.createNestedObject("fields");
  fields["deviceId"]["stringValue"] = DEVICE_ID;
  fields["name"]["stringValue"] = "Cong chinh";
  fields["location"]["stringValue"] = "Loi vao";
  fields["status"]["stringValue"] = "ONLINE";
  fields["lastHeartbeat"]["timestampValue"] = utcTimestamp();
  fields["firmwareVersion"]["stringValue"] = FIRMWARE_VERSION;
  if (templateCount >= 0) fields["fingerprintCount"]["integerValue"] = templateCount;
  fields["capacity"]["integerValue"] = 127;
  fields["pendingAttendanceCount"]["integerValue"] = attendancePendingCount();

  JsonObject capabilityField = fields.createNestedObject("capabilities");
  JsonObject arrayValue = capabilityField.createNestedObject("arrayValue");
  JsonArray values = arrayValue.createNestedArray("values");
  const char* capabilities[] = {"fingerprint", "attendance", "enrollment", "deletion", "led", "buzzer", "heartbeat"};
  for (const char* capability : capabilities) {
    JsonObject item = values.createNestedObject();
    item["stringValue"] = capability;
  }

  String body;
  serializeJson(doc, body);
  int code = https.sendRequest("PATCH", body);
  String response = https.getString();
  https.end();
  Serial.printf("HEARTBEAT HTTP %d: %s\n", code, response.c_str());
  return code >= 200 && code < 300;
}

void maybePublishDeviceSnapshot() {
  if (lastHeartbeat == 0 || millis() - lastHeartbeat >= HEARTBEAT_INTERVAL_MS) {
    lastHeartbeat = millis();
    publishDeviceSnapshot();
  }
}

bool getFingerprintMapping(uint16_t templateId, String& employeeId, String& employeeName) {
  if (WiFi.status() != WL_CONNECTED || !firebaseSignIn()) {
    bool cached = loadCachedFingerprintMapping(templateId, employeeId, employeeName);
    if (cached) Serial.printf("Mapping cache cho template %u, se cho dong bo\n", templateId);
    return cached;
  }
  BearSSL::WiFiClientSecure client;
  client.setInsecure();
  HTTPClient https;
  String url = String(FIRESTORE_BASE_URL) + "/fingerprintMappings/" + String(templateId);
  if (!https.begin(client, url)) return loadCachedFingerprintMapping(templateId, employeeId, employeeName);
  https.addHeader("Authorization", "Bearer " + firebaseIdToken);
  int code = https.GET();
  String response = https.getString();
  https.end();
  if (code != 200) return code < 0 && loadCachedFingerprintMapping(templateId, employeeId, employeeName);
  DynamicJsonDocument doc(1024);
  if (deserializeJson(doc, response)) return false;
  // A template can remain physically stored on AS608 while a delete command
  // is waiting. Missing enabled must fail closed so a legacy mapping cannot
  // create attendance after the employee was retired.
  bool enabled = doc["fields"]["enabled"]["booleanValue"].as<bool>();
  if (!enabled) {
    Serial.printf("Template %u da bi vo hieu hoa\n", templateId);
    return false;
  }
  employeeId = doc["fields"]["employeeId"]["stringValue"].as<String>();
  employeeName = doc["fields"]["employeeName"]["stringValue"].as<String>();
  employeeId.trim();
  employeeName.trim();
  cacheFingerprintMapping(templateId, employeeId, employeeName);
  return employeeId.length() > 0 && employeeName.length() > 0;
}

bool buildAttendanceEvent(uint16_t templateId, uint16_t confidence,
                          String& eventId, String& payload,
                          String& employeeName, String& attendanceTime,
                          String& attendanceType) {
  employeeName = "";
  attendanceTime = "--:--:--";
  attendanceType = "SCAN";
  String employeeId;
  if (!getFingerprintMapping(templateId, employeeId, employeeName)) {
    Serial.println("Khong tim thay nhan vien cua template");
    return false;
  }
  eventId = String(DEVICE_ID) + "-" + String(ESP.getChipId(), HEX) + "-" +
            String(millis()) + "-" + String(random(0x7fffffff), HEX);
  time_t now = time(nullptr);
  if (now < 1700000000) {
    Serial.println("Chua dong bo duoc thoi gian NTP");
    return false;
  }
  time_t localNow = now + 7 * 3600;
  struct tm localTime;
  gmtime_r(&localNow, &localTime);
  char timeValue[9];
  strftime(timeValue, sizeof(timeValue), "%H:%M:%S", &localTime);
  attendanceTime = String(timeValue);

  DynamicJsonDocument doc(1536);
  JsonObject fields = doc.createNestedObject("fields");
  fields["employeeId"]["stringValue"] = employeeId;
  fields["employeeName"]["stringValue"] = employeeName;
  fields["deviceId"]["stringValue"] = DEVICE_ID;
  fields["templateId"]["integerValue"] = templateId;
  fields["confidence"]["integerValue"] = confidence;
  fields["type"]["stringValue"] = "SCAN";
  fields["resolutionStatus"]["stringValue"] = "PENDING";
  fields["status"]["stringValue"] = "PENDING";
  fields["syncStatus"]["stringValue"] = "PENDING_SYNC";
  fields["timestamp"]["timestampValue"] = utcTimestamp(now);
  fields["verified"]["booleanValue"] = true;
  serializeJson(doc, payload);
  return true;
}

bool uploadAttendance(uint16_t templateId, uint16_t confidence,
                      String& employeeName, String& attendanceTime,
                      String& attendanceType) {
  String eventId;
  String payload;
  if (!buildAttendanceEvent(templateId, confidence, eventId, payload, employeeName, attendanceTime, attendanceType)) return false;
  if (!enqueueAttendanceEvent(eventId, payload)) return false;
  attendancePendingSync = !flushAttendanceOutbox();
  return true;
}

bool waitForFinger(uint32_t timeoutMs) {
  uint32_t started = millis();
  while (millis() - started < timeoutMs) {
    if (finger.getImage() == FINGERPRINT_OK) return true;
    delay(80);
    yield();
  }
  return false;
}

bool waitForFingerRemoval(uint32_t timeoutMs) {
  uint32_t started = millis();
  while (millis() - started < timeoutMs) {
    if (finger.getImage() == FINGERPRINT_NOFINGER) return true;
    delay(80);
    yield();
  }
  return false;
}

bool enrollFingerprint(uint16_t templateId) {
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

bool refreshCommandVersion() {
  BearSSL::WiFiClientSecure client;
  client.setInsecure();
  HTTPClient https;
  String url = String(FIRESTORE_BASE_URL) + "/deviceCommands/" + DEVICE_ID;
  if (!https.begin(client, url)) { Serial.println("LENH: khong tao duoc ket noi HTTPS"); return false; }
  https.addHeader("Authorization", "Bearer " + firebaseIdToken);
  int code = https.GET();
  String body = https.getString();
  https.end();
  if (code != 200) { Serial.printf("LENH refresh: HTTP %d, heap=%u\n", code, ESP.getFreeHeap()); return false; }
  DynamicJsonDocument current(2048);
  DeserializationError parseError = deserializeJson(current, body);
  if (parseError) { Serial.printf("LENH refresh: JSON %s, bytes=%u, heap=%u\n", parseError.c_str(), static_cast<unsigned>(body.length()), ESP.getFreeHeap()); return false; }
  String requestId = current["fields"]["requestId"]["stringValue"] | "";
  if (requestId != commandRequestId) {
    // Khong ghi ket qua cua lenh cu len lenh moi.
    Serial.println("LENH refresh: requestId da doi, bo ket qua lenh cu");
    pendingCommandResult = false;
    return false;
  }
  commandVersion = current["updateTime"].as<String>();
  if (commandVersion.length() == 0) Serial.println("LENH refresh: thieu updateTime");
  return commandVersion.length() > 0;
}
bool updateEnrollmentStatus(const char* status, const char* message) {
  if (!firebaseSignIn() || !refreshCommandVersion()) return false;
  delay(50);
  yield();
  BearSSL::WiFiClientSecure client;
  client.setInsecure();
  HTTPClient https;
  String url = String(FIRESTORE_BASE_URL) + "/deviceCommands/" + DEVICE_ID +
               "?updateMask.fieldPaths=status&updateMask.fieldPaths=message&updateMask.fieldPaths=completedAt";
  url += "&currentDocument.updateTime=" + commandVersion;
  if (!https.begin(client, url)) { Serial.println("LENH: khong tao duoc ket noi HTTPS"); return false; }
  https.addHeader("Content-Type", "application/json");
  https.addHeader("Authorization", "Bearer " + firebaseIdToken);
  DynamicJsonDocument doc(512);
  JsonObject fields = doc.createNestedObject("fields");
  fields["status"]["stringValue"] = status;
  fields["message"]["stringValue"] = message;
  fields["completedAt"]["timestampValue"] = utcTimestamp();
  String body;
  serializeJson(doc, body);
  int code = https.sendRequest("PATCH", body);
  if (code > 0 && (code < 200 || code >= 300)) {
    DynamicJsonDocument errorDoc(1024);
    if (!deserializeJson(errorDoc, https.getString())) {
      const char* detail = errorDoc["error"]["message"] | "Khong co chi tiet";
      Serial.printf("LENH PATCH %s: HTTP %d, %.200s\n", status, code, detail);
    }
  }
  if (code >= 200 && code < 300) {
    DynamicJsonDocument updated(2048);
    if (!deserializeJson(updated, https.getString())) commandVersion = updated["updateTime"].as<String>();
  }
  if (code < 0) {
    Serial.printf("Cap nhat lenh dang ky: HTTP %d (%s), heap=%u\n",
                  code, HTTPClient::errorToString(code).c_str(), ESP.getFreeHeap());
  } else {
    Serial.printf("Cap nhat lenh dang ky: HTTP %d, heap=%u\n",
                  code, ESP.getFreeHeap());
  }
  https.end();
  return code >= 200 && code < 300;
}

bool readEnrollmentCommand(uint16_t& templateId, String& type, bool& wasProcessing) {
  templateId = 0;
  if (WiFi.status() != WL_CONNECTED) {
    Serial.printf("LENH: WiFi chua ket noi, status=%d\n", WiFi.status());
    return false;
  }
  if (!firebaseSignIn()) { Serial.println("LENH: dang nhap Firebase that bai"); return false; }

  BearSSL::WiFiClientSecure client;
  client.setInsecure();
  HTTPClient https;
  String url = String(FIRESTORE_BASE_URL) + "/deviceCommands/" + DEVICE_ID;
  if (!https.begin(client, url)) { Serial.println("LENH: khong tao duoc ket noi HTTPS"); return false; }
  https.addHeader("Authorization", "Bearer " + firebaseIdToken);

  int code = https.GET();
  bool requested = false;
  if (code == 200) {
    DynamicJsonDocument response(1536);
    String commandBody = https.getString();
    DeserializationError error = deserializeJson(response, commandBody);
    if (error) {
      Serial.printf("LENH GET: JSON %s, bytes=%u, heap=%u\n", error.c_str(), static_cast<unsigned>(commandBody.length()), ESP.getFreeHeap());
      https.end();
      return false;
    }
    const char* status = response["fields"]["status"]["stringValue"] | "";
    Serial.printf("LENH GET: device=%s, HTTP=200, status=%s, type=%s, heap=%u\n", DEVICE_ID,
                  status, response["fields"]["type"]["stringValue"] | "(missing)", ESP.getFreeHeap());
    if (!error && (strcmp(status, "REQUESTED") == 0 || strcmp(status, "PROCESSING") == 0)) {
      type = response["fields"]["type"]["stringValue"].as<String>();
      wasProcessing = strcmp(status, "PROCESSING") == 0;
      commandVersion = response["updateTime"].as<String>();
      commandRequestId = response["fields"]["requestId"]["stringValue"] | "";
      templateId =
          String(response["fields"]["templateId"]["integerValue"].as<const char*>()).toInt();
      requested = true;
    }
  } else if (code < 0) {
    Serial.printf("Doc lenh dang ky: HTTP %d (%s), heap=%u\n",
                  code, HTTPClient::errorToString(code).c_str(), ESP.getFreeHeap());
  } else {
    Serial.printf("Doc lenh dang ky: HTTP %d\n", code);
  }
  https.end();
  return requested;
}

bool finishDeviceCommand() {
  const char* message = pendingCommandDelete
      ? (pendingCommandSuccess ? "Fingerprint deleted" : "Fingerprint deletion failed")
      : (pendingCommandSuccess ? "Fingerprint stored" : "Enrollment failed, interrupted or timed out");
  if (!updateEnrollmentStatus(pendingCommandSuccess ? "COMPLETED" : "FAILED", message)) {
    showLcd("CHO DONG BO", "KIEM TRA MANG");
    return false;
  }
  if (pendingCommandDelete && pendingCommandSuccess) {
    removeCachedFingerprintMapping(pendingCommandTemplateId);
  }
  pendingCommandResult = false;
  showLcd(pendingCommandDelete ? (pendingCommandSuccess ? "DA XOA VAN TAY" : "XOA THAT BAI")
                              : (pendingCommandSuccess ? "DANG KY XONG" : "DANG KY THAT BAI"),
          pendingCommandSuccess ? "HOAN TAT" : "KIEM TRA APP");
  signalResult(pendingCommandSuccess);
  delay(1500);
  startWaitingForFingerRemoval();
  return true;
}

bool checkEnrollmentCommand() {
  // Neu PATCH mat mang, chi gui lai ket qua, khong thuc hien lai dang ky.
  if (pendingCommandResult) {
    finishDeviceCommand();
    return true;
  }
  uint16_t templateId = 0;
  String type;
  bool wasProcessing = false;
  if (!readEnrollmentCommand(templateId, type, wasProcessing)) return false;
  delay(50);
  yield();
  if (time(nullptr) < 1700000000) {
    Serial.println("LENH: chua dong bo NTP, tam hoan cap nhat");
    showLcd("LOI DONG BO GIO", "KIEM TRA MANG");
    return true;
  }
  bool deleting = type == "DELETE_FINGERPRINT";
  bool enrolling = type == "ENROLL_FINGERPRINT";
  if (!wasProcessing && !updateEnrollmentStatus("PROCESSING", deleting ? "Deleting fingerprint" : "Waiting for finger")) {
    showLcd("LOI MAY CHU", "KIEM TRA MANG");
    return true;
  }
  bool success = false;
  if (templateId > 0 && templateId <= 127) {
    if (deleting) {
      showLcd("DANG XOA", "VAN TAY...");
      success = finger.deleteModel(templateId) == FINGERPRINT_OK;
    } else if (enrolling && !wasProcessing) {
      success = enrollFingerprint(templateId);
    }
  }
  // Sau reset, lenh dang ky PROCESSING chuyen FAILED; app van giu cho mau de xoa an toan.
  pendingCommandResult = true;
  pendingCommandSuccess = success;
  pendingCommandDelete = deleting;
  pendingCommandTemplateId = templateId;
  finishDeviceCommand();
  return true;
}
void setup() {
  Serial.begin(115200);
  Serial.printf("\nFW: %s, device=%s\n", FIRMWARE_VERSION, DEVICE_ID);
  randomSeed(ESP.getCycleCount());
  littleFsReady = LittleFS.begin();
  Serial.printf("LittleFS: %s\n", littleFsReady ? "READY" : "ERROR");
  Wire.begin(LCD_SDA_PIN, LCD_SCL_PIN);
  lcd.init();
  lcd.backlight();
  showLcd("KHOI DONG...", "VUI LONG DOI");
  pinMode(LED_GREEN_PIN, OUTPUT);
  pinMode(LED_RED_PIN, OUTPUT);
  pinMode(BUZZER_PIN, OUTPUT);
  finger.begin(57600);
  if (!finger.verifyPassword()) {
    Serial.println("Khong tim thay cam bien van tay");
    showLcd("LOI CAM BIEN", "KIEM TRA DAY");
    while (true) { signalResult(false); }
  }
  showLcd("DANG KET NOI", "WIFI...");
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
    while (time(nullptr) < 1700000000 && millis() - timeSyncStarted < 10000) {
      delay(200);
      yield();
    }
    if (time(nullptr) < 1700000000) {
      Serial.println("LENH: chua dong bo NTP, tam hoan cap nhat");
      showLcd("LOI DONG BO GIO", "KIEM TRA MANG");
      delay(1500);
    }
    firebaseSignIn();
    publishDeviceSnapshot();
  } else {
    Serial.println("WIFI offline luc khoi dong; se thu lai trong loop");
    showLcd("OFFLINE", "CHO DONG BO");
  }
  Serial.println("\nSan sang cham cong");
  showReadyScreen();
}

void loop() {
  maybePublishDeviceSnapshot();
  if (millis() - lastAttendanceSync >= ATTENDANCE_SYNC_INTERVAL_MS) {
    lastAttendanceSync = millis();
    if (!flushAttendanceOutbox() && attendanceOutboxBytes() > 0) {
      Serial.println("OUTBOX dang cho mang de dong bo");
    }
  }
  if (pendingCommandResult) {
    if (millis() - lastCommandCheck >= 3000) {
      lastCommandCheck = millis();
      checkEnrollmentCommand();
    }
    delay(80);
    return;
  }
  if (waitingForFingerRemoval) {
    uint8_t imageStatus = finger.getImage();
    if (imageStatus == FINGERPRINT_NOFINGER) {
      waitingForFingerRemoval = false;
      showReadyScreen();
    } else if (imageStatus != FINGERPRINT_OK) {
      showLcd("LOI CAM BIEN", "KIEM TRA DAY");
    } else if (millis() - fingerRemovalStarted >= 10000) {
      showLcd("NHAC NGON TAY", "RA KHOI CAM BIEN");
      fingerRemovalStarted = millis();
    }
    delay(80);
    return;
  }

  if (millis() - lastCommandCheck >= 3000) {
    lastCommandCheck = millis();
    if (checkEnrollmentCommand()) return;
  }
  if (finger.getImage() != FINGERPRINT_OK) { delay(80); return; }
  showLcd("DANG XU LY...", "VUI LONG DOI");
  if (finger.image2Tz() != FINGERPRINT_OK || finger.fingerFastSearch() != FINGERPRINT_OK) {
    Serial.println("Van tay khong hop le");
    showLcd("VAN TAY SAI", "XIN THU LAI");
    signalResult(false);
    delay(500);
    startWaitingForFingerRemoval();
    return;
  }
  Serial.printf("Template %d, confidence %d\n", finger.fingerID, finger.confidence);
  String employeeName;
  String attendanceTime;
  String attendanceType;
  bool hasValidTime = time(nullptr) >= 1700000000;
  bool success = hasValidTime &&
                 uploadAttendance(finger.fingerID, finger.confidence,
                                  employeeName, attendanceTime, attendanceType);
  if (success) {
    showLcd(employeeName, attendancePendingSync ? "DANG XU LY" : "DA NHAN");
  } else {
    showLcd(hasValidTime ? "CHAM CONG LOI" : "LOI DONG BO GIO",
            hasValidTime ? "XIN THU LAI" : "KIEM TRA MANG");
  }
  signalResult(success);
  delay(success ? 1800 : 500);
  startWaitingForFingerRemoval();
}
