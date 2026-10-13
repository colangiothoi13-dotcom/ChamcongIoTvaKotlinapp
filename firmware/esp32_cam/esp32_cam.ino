// ESP32-S3 WROOM N16R8 dual-Type-C camera board (OV2640 / OV5640).
// Classic AI Thinker ESP32-CAM remains selectable with its own Arduino target.
// Requires the Arduino ESP32 core 3.x (camera and HTTP server are bundled).
// Capture only: face recognition and attendance are performed on the laptop.
#include <Arduino.h>
#include <WiFi.h>
#include "esp_camera.h"
#include "esp_http_server.h"
#include "esp_random.h"
#include <time.h>
#include <freertos/FreeRTOS.h>
#include <freertos/semphr.h>
#include "secrets.h"
#include "camera_board.h"

// Explicit request declarations keep Arduino's generated prototypes from
// referring to ESP-IDF HTTP types before esp_http_server.h has been included.
bool authorizeCameraRequest(httpd_req_t* request);
esp_err_t cameraError(httpd_req_t* request, const char* status, const char* message);
esp_err_t captureCamera(httpd_req_t* request);
esp_err_t cameraHealth(httpd_req_t* request);

const uint32_t WIFI_RETRY_MS = 15000;
const uint32_t CAMERA_RETRY_MS = 15000;
const time_t MIN_VALID_CAMERA_TIME = 1700000000;
httpd_handle_t cameraServer = nullptr;
SemaphoreHandle_t cameraMutex = nullptr;
volatile bool cameraReady = false;
uint16_t cameraSensorPid = 0;
esp_err_t cameraLastError = ESP_OK;
bool connectedPreviously = false;
uint32_t lastWifiRetry = 0;
uint32_t lastCameraRetry = 0;
uint64_t frameSequence = 0;
char bootNonce[33] = {};

const char* cameraSensorName() {
  switch (cameraSensorPid) {
    case OV2640_PID: return "OV2640";
    case OV3660_PID: return "OV3660";
    case OV5640_PID: return "OV5640";
    case 0: return "unavailable";
    default: return "other";
  }
}

void initializeBoardOutputs() {
  // Only AI Thinker has a bright flash on GPIO4. S3 uses GPIO4 for SDA.
  if (CAMERA_FLASH_LED_PIN >= 0) {
    pinMode(CAMERA_FLASH_LED_PIN, OUTPUT);
    digitalWrite(CAMERA_FLASH_LED_PIN, LOW);
  }
}

bool tokenConfigured() {
  const size_t size = strlen(CAMERA_ACCESS_TOKEN);
  return size >= 16 && size <= 120 &&
      strcmp(CAMERA_ACCESS_TOKEN, "REPLACE_WITH_RANDOM_CAMERA_TOKEN") != 0;
}

bool authorizeCameraRequest(httpd_req_t* request) {
  const size_t headerLength = httpd_req_get_hdr_value_len(request, "X-Camera-Token");
  const size_t expectedLength = strlen(CAMERA_ACCESS_TOKEN);
  if (!tokenConfigured() || headerLength != expectedLength || headerLength > 120) return false;
  char supplied[121] = {};
  if (httpd_req_get_hdr_value_str(request, "X-Camera-Token", supplied, sizeof(supplied)) != ESP_OK) return false;
  uint8_t difference = 0;
  for (size_t i = 0; i < expectedLength; ++i) {
    difference |= static_cast<uint8_t>(supplied[i] ^ CAMERA_ACCESS_TOKEN[i]);
  }
  return difference == 0;
}

esp_err_t cameraError(httpd_req_t* request, const char* status, const char* message) {
  httpd_resp_set_status(request, status);
  httpd_resp_set_type(request, "application/json");
  httpd_resp_set_hdr(request, "Cache-Control", "no-store");
  return httpd_resp_send(request, message, HTTPD_RESP_USE_STRLEN);
}

bool initializeCamera() {
  if (!cameraMutex || xSemaphoreTake(cameraMutex, pdMS_TO_TICKS(100)) != pdTRUE) return false;
  const bool hasPsram = psramFound();
  if (CAMERA_REQUIRE_PSRAM && !hasPsram) {
    cameraReady = false;
    cameraSensorPid = 0;
    cameraLastError = ESP_ERR_NO_MEM;
    Serial.println("N16R8 PSRAM missing: select Tools > PSRAM > OPI PSRAM, then upload again.");
    xSemaphoreGive(cameraMutex);
    return false;
  }
  camera_config_t config = {};
  config.ledc_channel = LEDC_CHANNEL_0;
  config.ledc_timer = LEDC_TIMER_0;
  config.pin_pwdn = CAMERA_PIN_PWDN;
  config.pin_reset = CAMERA_PIN_RESET;
  config.pin_xclk = CAMERA_PIN_XCLK;
  config.pin_sccb_sda = CAMERA_PIN_SDA;
  config.pin_sccb_scl = CAMERA_PIN_SCL;
  config.pin_d7 = CAMERA_PIN_D7;
  config.pin_d6 = CAMERA_PIN_D6;
  config.pin_d5 = CAMERA_PIN_D5;
  config.pin_d4 = CAMERA_PIN_D4;
  config.pin_d3 = CAMERA_PIN_D3;
  config.pin_d2 = CAMERA_PIN_D2;
  config.pin_d1 = CAMERA_PIN_D1;
  config.pin_d0 = CAMERA_PIN_D0;
  config.pin_vsync = CAMERA_PIN_VSYNC;
  config.pin_href = CAMERA_PIN_HREF;
  config.pin_pclk = CAMERA_PIN_PCLK;
  config.xclk_freq_hz = 20000000;
  config.pixel_format = PIXFORMAT_JPEG;
  config.frame_size = hasPsram ? FRAMESIZE_VGA : FRAMESIZE_QVGA;
  config.jpeg_quality = 12;
  config.fb_count = 1;
  config.fb_location = hasPsram ? CAMERA_FB_IN_PSRAM : CAMERA_FB_IN_DRAM;
  config.grab_mode = CAMERA_GRAB_WHEN_EMPTY;
  const esp_err_t result = esp_camera_init(&config);
  cameraLastError = result;
  cameraReady = result == ESP_OK;
  cameraSensorPid = 0;
  if (!cameraReady) {
    Serial.printf("Camera init failed: 0x%x; check ribbon seating and PCB pin map; retry later\n", result);
    esp_camera_deinit();
  } else {
    sensor_t* sensor = esp_camera_sensor_get();
    if (sensor) cameraSensorPid = sensor->id.PID;
    Serial.printf("Camera ready: %s (PID 0x%04x), JPEG %s\n", cameraSensorName(), cameraSensorPid,
                  hasPsram ? "VGA" : "QVGA");
  }
  xSemaphoreGive(cameraMutex);
  return cameraReady;
}

esp_err_t captureCamera(httpd_req_t* request) {
  if (!authorizeCameraRequest(request)) return cameraError(request, "401 Unauthorized", "{\"error\":\"unauthorized\"}");
  if (!cameraMutex || xSemaphoreTake(cameraMutex, pdMS_TO_TICKS(100)) != pdTRUE) {
    return cameraError(request, "503 Service Unavailable", "{\"error\":\"camera_busy\"}");
  }
  if (!cameraReady) {
    xSemaphoreGive(cameraMutex);
    return cameraError(request, "503 Service Unavailable", "{\"error\":\"camera_unavailable\"}");
  }
  // With one framebuffer, a previous buffered image could predate this
  // request. Return it and acquire the newly captured frame before assigning
  // an ID. Repeated requests never label an old buffered frame as a new one.
  camera_fb_t* previous = esp_camera_fb_get();
  if (previous) esp_camera_fb_return(previous);
  camera_fb_t* frame = previous ? esp_camera_fb_get() : nullptr;
  if (!frame || frame->format != PIXFORMAT_JPEG) {
    if (frame) esp_camera_fb_return(frame);
    cameraReady = false;
    cameraSensorPid = 0;
    cameraLastError = ESP_FAIL;
    esp_camera_deinit();
    xSemaphoreGive(cameraMutex);
    return cameraError(request, "503 Service Unavailable", "{\"error\":\"capture_failed\"}");
  }
  char frameId[64];
  snprintf(frameId, sizeof(frameId), "%s-%llu", bootNonce, static_cast<unsigned long long>(++frameSequence));
  httpd_resp_set_type(request, "image/jpeg");
  httpd_resp_set_hdr(request, "Cache-Control", "no-store");
  httpd_resp_set_hdr(request, "X-Frame-Id", frameId);
  httpd_resp_set_hdr(request, "X-Camera-Id", CAMERA_DEVICE_ID);
  const time_t captured = time(nullptr);
  char timestamp[21];
  if (captured >= MIN_VALID_CAMERA_TIME) {
    struct tm utc;
    gmtime_r(&captured, &utc);
    strftime(timestamp, sizeof(timestamp), "%Y-%m-%dT%H:%M:%SZ", &utc);
    httpd_resp_set_hdr(request, "X-Frame-Captured-At", timestamp);
  }
  const esp_err_t result = httpd_resp_send(request, reinterpret_cast<const char*>(frame->buf), frame->len);
  esp_camera_fb_return(frame);
  xSemaphoreGive(cameraMutex);
  return result;
}

esp_err_t cameraHealth(httpd_req_t* request) {
  if (!authorizeCameraRequest(request)) return cameraError(request, "401 Unauthorized", "{\"error\":\"unauthorized\"}");
  if (!cameraMutex || xSemaphoreTake(cameraMutex, pdMS_TO_TICKS(100)) != pdTRUE) {
    return cameraError(request, "503 Service Unavailable", "{\"error\":\"camera_busy\"}");
  }
  const bool ready = cameraReady;
  char health[384];
  snprintf(health, sizeof(health),
           "{\"cameraReady\":%s,\"wifiConnected\":%s,\"psram\":%s,\"clockReady\":%s,\"uptimeMs\":%lu,"
           "\"board\":\"%s\",\"sensor\":\"%s\",\"sensorPid\":%u,\"psramBytes\":%lu,\"flashBytes\":%lu,\"cameraError\":%d}",
           ready ? "true" : "false", WiFi.status() == WL_CONNECTED ? "true" : "false",
           psramFound() ? "true" : "false", time(nullptr) >= MIN_VALID_CAMERA_TIME ? "true" : "false",
           static_cast<unsigned long>(millis()), CAMERA_BOARD_NAME, cameraSensorName(), cameraSensorPid,
           static_cast<unsigned long>(ESP.getPsramSize()), static_cast<unsigned long>(ESP.getFlashChipSize()),
           static_cast<int>(cameraLastError));
  xSemaphoreGive(cameraMutex);
  return cameraError(request, ready ? "200 OK" : "503 Service Unavailable", health);
}

bool startCameraServer() {
  if (cameraServer) return true;
  httpd_config_t config = HTTPD_DEFAULT_CONFIG();
  config.server_port = 80;
  config.max_uri_handlers = 2;
  config.max_open_sockets = 3;
  config.lru_purge_enable = true;
  config.recv_wait_timeout = 3;
  config.send_wait_timeout = 3;
  if (httpd_start(&cameraServer, &config) != ESP_OK) { cameraServer = nullptr; return false; }
  httpd_uri_t capture = {};
  capture.uri = "/capture";
  capture.method = HTTP_GET;
  capture.handler = captureCamera;
  httpd_uri_t health = {};
  health.uri = "/health";
  health.method = HTTP_GET;
  health.handler = cameraHealth;
  if (httpd_register_uri_handler(cameraServer, &capture) != ESP_OK ||
      httpd_register_uri_handler(cameraServer, &health) != ESP_OK) {
    httpd_stop(cameraServer);
    cameraServer = nullptr;
    return false;
  }
  return true;
}

void setup() {
  Serial.begin(9600);
  delay(200); // Never wait for a USB monitor: camera also runs from USB power alone.
  Serial.printf("Camera board: %s\n", CAMERA_BOARD_NAME);
  Serial.printf("Flash bytes: %lu; PSRAM bytes: %lu\n",
                static_cast<unsigned long>(ESP.getFlashChipSize()),
                static_cast<unsigned long>(ESP.getPsramSize()));
  initializeBoardOutputs();
  if (!tokenConfigured()) {
    Serial.println("Configure CAMERA_ACCESS_TOKEN in local secrets.h before use.");
    return;
  }
  cameraMutex = xSemaphoreCreateMutex();
  WiFi.persistent(false);
  WiFi.mode(WIFI_STA);
  WiFi.setSleep(false);
  snprintf(bootNonce, sizeof(bootNonce), "%08lx%08lx%08lx%08lx",
           static_cast<unsigned long>(esp_random()), static_cast<unsigned long>(esp_random()),
           static_cast<unsigned long>(esp_random()), static_cast<unsigned long>(esp_random()));
  initializeCamera();
  lastCameraRetry = millis();
  WiFi.begin(CAMERA_WIFI_SSID, CAMERA_WIFI_PASSWORD);
  configTime(0, 0, "pool.ntp.org", "time.google.com");
  lastWifiRetry = millis();
}

void loop() {
  if (!tokenConfigured()) { delay(20); return; }
  const bool connected = WiFi.status() == WL_CONNECTED;
  if (connected && !connectedPreviously) {
    Serial.print("Camera LAN address: http://");
    Serial.println(WiFi.localIP());
  }
  connectedPreviously = connected;
  if (!connected && static_cast<uint32_t>(millis() - lastWifiRetry) >= WIFI_RETRY_MS) {
    lastWifiRetry = millis();
    WiFi.reconnect();
  }
  if (connected && !cameraServer) startCameraServer();
  if (!cameraReady && static_cast<uint32_t>(millis() - lastCameraRetry) >= CAMERA_RETRY_MS) {
    lastCameraRetry = millis();
    initializeCamera();
  }
  delay(10);
}
