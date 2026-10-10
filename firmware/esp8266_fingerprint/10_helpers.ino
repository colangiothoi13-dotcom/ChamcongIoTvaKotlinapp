// Shared network, error, and attendance-failure helpers.

void beginWifiConnection() {
  WiFi.persistent(false);
  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  lastWifiReconnectAttempt = millis();
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
}

void maintainWifiConnection() {
  const wl_status_t status = WiFi.status();
  if (status == WL_CONNECTED) {
    if (!wifiWasConnected) {
      wifiWasConnected = true;
      Serial.println(F("WIFI da ket noi"));
      // A new Wi-Fi connection gets one immediate retry. A live connection
      // with no working internet keeps the normal HTTPS cooldown.
      hasHttpsTransportFailure = false;
      if (time(nullptr) < MIN_VALID_UNIX_TIME) {
        configTime(0, 0, "pool.ntp.org", "time.google.com");
        Serial.println(F("WIFI: dang dong bo lai gio NTP"));
      }
      if (attendanceOutboxBytes() > 0) {
        attendanceSyncIntervalMs = ATTENDANCE_NEXT_RECORD_INTERVAL_MS;
        lastAttendanceSync = millis() - attendanceSyncIntervalMs;
      }
    }
    return;
  }

  if (wifiWasConnected) {
    wifiWasConnected = false;
    Serial.printf_P(PSTR("WIFI mat ket noi (status=%d)\n"), status);
  }
  if (millis() - lastWifiReconnectAttempt < WIFI_RECONNECT_INTERVAL_MS) return;

  lastWifiReconnectAttempt = millis();
  Serial.printf_P(PSTR("WIFI dang ket noi lai (status=%d)\n"), status);
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
}

void deferHttpsRequests(const char* operation) {
  hasHttpsTransportFailure = true;
  lastHttpsTransportFailure = millis();
  Serial.printf_P(PSTR("HTTPS tam dung sau %s; thu lai sau %lu giay\n"),
                operation, HTTPS_RETRY_COOLDOWN_MS / 1000);
}

bool httpsRetryCooldownActive() {
  return hasHttpsTransportFailure &&
      !elapsedAtLeast(millis(), lastHttpsTransportFailure, HTTPS_RETRY_COOLDOWN_MS);
}

bool canStartHttpsRequest(const char* operation, bool prepared) {
  // Door deferral is normal pending work, never a transport error/cooldown.
  if (doorNeedsResponsiveLoop()) {
    firebaseSyncStatus = "PENDING";
    return false;
  }
  if (WiFi.status() != WL_CONNECTED) return false;

  if (httpsRetryCooldownActive()) {
    Serial.printf_P(PSTR("HTTPS bo qua %s: dang cho mang on dinh\n"), operation);
    return false;
  }

  const uint32_t freeHeap = ESP.getFreeHeap();
  const uint32_t maxBlock = ESP.getMaxFreeBlockSize();
  const uint8_t fragmentation = ESP.getHeapFragmentation();
  const uint32_t minimumFreeHeap = prepared ? MIN_HTTPS_CONNECT_FREE_HEAP : MIN_HTTPS_FREE_HEAP;
  const uint32_t minimumMaxBlock = prepared ? MIN_HTTPS_CONNECT_MAX_FREE_BLOCK : MIN_HTTPS_MAX_FREE_BLOCK;
  if (freeHeap < minimumFreeHeap || maxBlock < minimumMaxBlock) {
    Serial.printf_P(PSTR("HTTPS bo qua %s: heap=%u, block=%u, frag=%u%%\n"),
                  operation, freeHeap, maxBlock, fragmentation);
    deferHttpsRequests(operation);
    return false;
  }
  if (prepared) {
    Serial.printf_P(PSTR("HTTPS %s truoc TLS: heap=%u, block=%u, frag=%u%%\n"),
                    operation, freeHeap, maxBlock, fragmentation);
  }
  return true;
}

void recordHttpsResult(const char* operation, int code) {
  if (code < 0) {
    Serial.printf_P(PSTR("HTTPS %s loi %d (%s), heap=%u, block=%u, frag=%u%%\n"),
                  operation, code, HTTPClient::errorToString(code).c_str(), ESP.getFreeHeap(), ESP.getMaxFreeBlockSize(),
                  ESP.getHeapFragmentation());
    deferHttpsRequests(operation);
  } else {
    hasHttpsTransportFailure = false;
  }
}

void setLatestError(const String& message) {
  lastError = message;
  if (lastError.length() > 160) lastError = lastError.substring(0, 160);
  Serial.printf_P(PSTR("LOI GAN NHAT: %s\n"), lastError.c_str());
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
