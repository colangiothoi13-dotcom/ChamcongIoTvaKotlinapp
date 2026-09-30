// Shared network, error, and attendance-failure helpers.

void maintainWifiConnection() {
  if (WiFi.status() == WL_CONNECTED) return;
  if (millis() - lastWifiReconnectAttempt < WIFI_RECONNECT_INTERVAL_MS) return;

  lastWifiReconnectAttempt = millis();
  Serial.printf("WIFI mat ket noi, dang thu ket noi lai (status=%d)\n", WiFi.status());
  WiFi.reconnect();
}

void deferHttpsRequests(const char* operation) {
  hasHttpsTransportFailure = true;
  lastHttpsTransportFailure = millis();
  Serial.printf("HTTPS tam dung sau %s; thu lai sau %lu giay\n",
                operation, HTTPS_RETRY_COOLDOWN_MS / 1000);
}

bool canStartHttpsRequest(const char* operation) {
  if (WiFi.status() != WL_CONNECTED) return false;

  if (hasHttpsTransportFailure &&
      millis() - lastHttpsTransportFailure < HTTPS_RETRY_COOLDOWN_MS) {
    Serial.printf("HTTPS bo qua %s: dang cho mang on dinh\n", operation);
    return false;
  }

  const uint32_t freeHeap = ESP.getFreeHeap();
  const uint32_t maxBlock = ESP.getMaxFreeBlockSize();
  const uint8_t fragmentation = ESP.getHeapFragmentation();
  if (freeHeap < MIN_HTTPS_FREE_HEAP || maxBlock < MIN_HTTPS_MAX_FREE_BLOCK) {
    Serial.printf("HTTPS bo qua %s: heap=%u, block=%u, frag=%u%%\n",
                  operation, freeHeap, maxBlock, fragmentation);
    deferHttpsRequests(operation);
    return false;
  }
  return true;
}

void recordHttpsResult(const char* operation, int code) {
  if (code < 0) {
    Serial.printf("HTTPS %s loi %d, heap=%u, block=%u, frag=%u%%\n",
                  operation, code, ESP.getFreeHeap(), ESP.getMaxFreeBlockSize(),
                  ESP.getHeapFragmentation());
    deferHttpsRequests(operation);
  } else {
    hasHttpsTransportFailure = false;
  }
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
