// Optional LAN gateway. The legacy queue files, sequence and source IDs stay
// unchanged; only the transport for attendance changes when explicitly enabled.

bool canStartGatewayRequest() {
  if (doorNeedsResponsiveLoop() || WiFi.status() != WL_CONNECTED) return false;
  if (strlen(ATTENDANCE_DEVICE_TOKEN) < 16 ||
      strcmp(ATTENDANCE_DEVICE_TOKEN, "REPLACE_WITH_RANDOM_DEVICE_TOKEN") == 0 ||
      strlen(ATTENDANCE_STATION_ID) == 0 ||
      !String(ATTENDANCE_GATEWAY_URL).startsWith("http://")) {
    setLatestError(F("Chua cau hinh gateway LAN/token/station"));
    return false;
  }
  // LAN HTTP does not allocate BearSSL buffers and must not inherit Firebase
  // TLS backoff. The caller already schedules retries and physical deadlines.
  if (ESP.getFreeHeap() < 10000 || ESP.getMaxFreeBlockSize() < 4096) return false;
  return true;
}

bool buildGatewayAttendanceEvent(uint16_t templateId, uint16_t confidence, time_t captured,
                                String& eventId, String& payload, String& employeeName,
                                String& attendanceTime) {
  lastFingerprintAuthorizationUnavailable = false;
  lastFingerprintAuthorizationDenied = false;
  if (captured < MIN_VALID_UNIX_TIME) {
    lastAttendanceMissingClock = true;
    setLatestError(F("Chua co gio hop le de luu luot quet"));
    return false;
  }
  if (!reserveAttendanceEventId(eventId)) return false;
  DynamicJsonDocument scan(768);
  scan["sourceEventId"] = eventId;
  scan["deviceId"] = DEVICE_ID;
  scan["stationId"] = ATTENDANCE_STATION_ID;
  scan["templateId"] = templateId;
  scan["confidence"] = confidence;
  scan["capturedAt"] = utcTimestamp(captured);
  serializeJson(scan, payload);
  employeeName = String("VAN TAY #") + templateId;
  time_t localCaptured = captured + 7 * 3600;
  struct tm localTime;
  gmtime_r(&localCaptured, &localTime);
  char clock[9];
  strftime(clock, sizeof(clock), "%H:%M:%S", &localTime);
  attendanceTime = clock;
  lastAttendanceCreatedOffline = WiFi.status() != WL_CONNECTED;
  return !scan.overflowed() && payload.length() > 0;
}

bool convertGatewayAttendancePayload(const String& eventId, const String& payload,
                                     String& gatewayPayload) {
  DynamicJsonDocument scan(2048);
  if (deserializeJson(scan, payload)) return false;
  int templateId;
  int confidence;
  String device;
  String captured;
  String station = ATTENDANCE_STATION_ID;
  const String payloadEventId = scan["sourceEventId"].as<String>();
  if (payloadEventId.length() > 0) {
    if (payloadEventId != eventId) return false;
    templateId = scan["templateId"].as<int>();
    confidence = scan["confidence"].as<int>();
    device = scan["deviceId"].as<String>();
    captured = scan["capturedAt"].as<String>();
    station = scan["stationId"].as<String>();
  } else if (scan["offlineScan"].as<bool>()) {
    // Events stored by older fingerprint-only firmware retain their actual
    // source time and ID, even after switching the station to the gateway.
    templateId = scan["templateId"].as<int>();
    confidence = scan["confidence"].as<int>();
    device = scan["deviceId"].as<String>();
    captured = scan["timestamp"].as<String>();
  } else {
    templateId = scan["fields"]["templateId"]["integerValue"].as<int>();
    confidence = scan["fields"]["confidence"]["integerValue"].as<int>();
    device = scan["fields"]["deviceId"]["stringValue"].as<String>();
    captured = scan["fields"]["timestamp"]["timestampValue"].as<String>();
  }
  if (eventId.length() == 0 || device != DEVICE_ID || station != ATTENDANCE_STATION_ID ||
      templateId < 1 || templateId > 127 || confidence < 0 || confidence > 65535 ||
      captured.length() < 20 || !captured.endsWith("Z")) return false;
  DynamicJsonDocument outgoing(768);
  outgoing["sourceEventId"] = eventId;
  outgoing["deviceId"] = device;
  outgoing["stationId"] = station;
  outgoing["templateId"] = templateId;
  outgoing["confidence"] = confidence;
  outgoing["capturedAt"] = captured;
  serializeJson(outgoing, gatewayPayload);
  return !outgoing.overflowed() && gatewayPayload.length() > 0;
}

GatewayReceipt parseGatewayAttendanceReceipt(const String& eventId, int code, const String& body) {
  if (code < 0 || code == 408 || code == 425 || code == 429 || code >= 500) {
    return GatewayReceipt::TRANSPORT_UNAVAILABLE;
  }
  if (body.length() == 0 || body.length() > 1024) return GatewayReceipt::RETRY;
  DynamicJsonDocument receipt(768);
  if (deserializeJson(receipt, body)) return GatewayReceipt::RETRY;
  if (receipt["sourceEventId"].as<String>() != eventId) return GatewayReceipt::RETRY;
  const String status = receipt["status"].as<String>();
  const String attendanceId = receipt["attendanceId"].as<String>();
  const bool successfulHttp = code >= 200 && code < 300;
  if (successfulHttp && attendanceId.length() > 0) {
    if (status == "ACCEPTED_SYNCED") return GatewayReceipt::ACCEPTED_SYNCED;
    if (status == "DUPLICATE_SYNCED") return GatewayReceipt::DUPLICATE_SYNCED;
  }
  if (successfulHttp && status == "QUEUED") return GatewayReceipt::QUEUED;
  // Only a valid, event-specific business rejection terminates retry. Bare
  // 401/403/409 responses, HTML proxy errors and mismatched IDs keep the event.
  if ((successfulHttp || (code >= 400 && code < 500 && code != 401 && code != 409)) &&
      status == "REJECTED") return GatewayReceipt::REJECTED;
  return GatewayReceipt::RETRY;
}

GatewayReceipt postGatewayAttendanceEvent(const String& eventId, const String& payload) {
  String gatewayPayload;
  if (!convertGatewayAttendancePayload(eventId, payload, gatewayPayload)) {
    // Keep a parseable saved event for inspection rather than deleting it
    // because a new firmware version cannot interpret its payload.
    setLatestError(F("Payload gateway khong hop le; giu hang doi"));
    return GatewayReceipt::RETRY;
  }
  WiFiClient client;
  HTTPClient http;
  if (!http.begin(client, ATTENDANCE_GATEWAY_URL)) return GatewayReceipt::TRANSPORT_UNAVAILABLE;
  http.useHTTP10(true);
  http.setTimeout(2500);
  http.addHeader("Content-Type", "application/json");
  http.addHeader("X-Device-Token", ATTENDANCE_DEVICE_TOKEN);
  const int code = http.POST(reinterpret_cast<const uint8_t*>(gatewayPayload.c_str()), gatewayPayload.length());
  String body;
  // The local service sends a small Content-Length response. Never accumulate
  // an unbounded body on the ESP8266, including errors returned by a proxy.
  if (http.getSize() > 0 && http.getSize() <= 1024) body = http.getString();
  http.end();
  client.stop();
  return parseGatewayAttendanceReceipt(eventId, code, body);
}

AttendanceSyncResult flushGatewayAttendanceHead(const String& eventId, const String& payload,
                                                size_t tailOffset) {
  const GatewayReceipt receipt = postGatewayAttendanceEvent(eventId, payload);
  if (receipt == GatewayReceipt::RETRY || receipt == GatewayReceipt::TRANSPORT_UNAVAILABLE ||
      receipt == GatewayReceipt::QUEUED) {
    firebaseSyncStatus = receipt == GatewayReceipt::RETRY ? "ERROR" : "PENDING";
    if (receipt == GatewayReceipt::QUEUED) {
      setLatestError(F("Laptop da luu; cong van cho dong bo"));
    }
    if (OFFLINE_AS608_ACCESS_ENABLED && receipt == GatewayReceipt::TRANSPORT_UNAVAILABLE) {
      // Preserve selected offline door policy separately from attendance.
      // Only the currently scanned, durable event may grant local access.
      handleAttendanceDelivery(foregroundAttendanceEventId, AttendanceDelivery::LOCAL_ACCEPTED);
    }
    return AttendanceSyncResult::RETRY;
  }

  const bool rejected = receipt == GatewayReceipt::REJECTED;
  const AttendanceDelivery delivery = rejected ? AttendanceDelivery::REJECTED :
      (receipt == GatewayReceipt::DUPLICATE_SYNCED ? AttendanceDelivery::DUPLICATE : AttendanceDelivery::CONFIRMED);
  if (rejected) lastRejectedAttendanceEventId = eventId;
  else lastAcknowledgedAttendanceEventId = eventId;
  if (!removeAttendanceOutboxHead(tailOffset)) {
    firebaseSyncStatus = "ERROR";
    setLatestError(F("Khong cap nhat duoc hang doi LittleFS"));
    handleAttendanceDelivery(eventId, delivery);
    return AttendanceSyncResult::STORAGE_ERROR;
  }
  firebaseSyncStatus = rejected ? "ERROR" :
      (attendanceOutboxBytes() == 0 && WiFi.status() == WL_CONNECTED ? "ONLINE" : "PENDING");
  handleAttendanceDelivery(eventId, delivery);
  return rejected ? AttendanceSyncResult::REJECTED : AttendanceSyncResult::ACKNOWLEDGED;
}
