// Shared network, error, and attendance-failure helpers.

void maintainWifiConnection() {
  if (WiFi.status() == WL_CONNECTED) return;
  if (millis() - lastWifiReconnectAttempt < WIFI_RECONNECT_INTERVAL_MS) return;

  lastWifiReconnectAttempt = millis();
  Serial.printf("WIFI mat ket noi, dang thu ket noi lai (status=%d)\n", WiFi.status());
  WiFi.reconnect();
}

void setLatestError(const String& message) {
  lastError = message;
  if (lastError.length() > 160) lastError = lastError.substring(0, 160);
  Serial.printf("LOI GAN NHAT: %s\n", lastError.c_str());
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
