// Firebase authentication and timestamp helpers.

bool firebaseSignIn() {
  if (firebaseIdToken.length() > 0 && millis() - tokenCreatedAt < 3300000UL) return true;
  if (WiFi.status() != WL_CONNECTED) {
    firebaseSyncStatus = "PENDING";
    return false;
  }
  if (FIREBASE_API_KEY == nullptr || String(FIREBASE_API_KEY).length() == 0) {
    firebaseSyncStatus = "ERROR";
    setLatestError(F("Chua cau hinh FIREBASE_WEB_API_KEY"));
    return false;
  }
  if (!canStartHttpsRequest("Firebase Auth")) {
    firebaseSyncStatus = "PENDING";
    return false;
  }

  int code = -1;
  bool began = false;
  bool parsed = false;
  String idToken;
  {
    String url = String("https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=") + FIREBASE_API_KEY;
    BearSSL::WiFiClientSecure client;
    client.setInsecure();
    HTTPClient https;
    // The raw response stream is used below, so avoid HTTP/1.1 chunk framing.
    https.useHTTP10(true);
    if (https.begin(client, url)) {
      began = true;
      url = String();
      https.setTimeout(5000);
      https.addHeader("Content-Type", "application/json");
      static const uint8_t authPayload[] = "{\"returnSecureToken\":true}";
      if (!canStartHttpsRequest("Firebase Auth", true)) {
        https.end();
        client.stop();
        firebaseSyncStatus = "PENDING";
        return false;
      }
      code = https.POST(authPayload, sizeof(authPayload) - 1);
      if (code == 200) {
        DynamicJsonDocument authDoc(2048);
        if (!deserializeJson(authDoc, https.getStream())) {
          idToken = authDoc["idToken"].as<String>();
          parsed = idToken.length() > 0;
        }
      }
      https.end();
    }
    client.stop();
  }

  if (!began) {
    firebaseSyncStatus = "ERROR";
    deferHttpsRequests("Firebase Auth");
    setLatestError(F("Khong tao duoc ket noi Firebase Auth"));
    return false;
  }
  recordHttpsResult("Firebase Auth", code);
  if (code != 200) {
    Serial.printf_P(PSTR("Firebase Auth loi HTTP %d\n"), code);
    firebaseSyncStatus = "ERROR";
    setLatestError(String("Firebase Auth HTTP ") + code);
    return false;
  }
  if (!parsed) {
    firebaseSyncStatus = "ERROR";
    setLatestError(F("Firebase Auth tra ve JSON khong hop le"));
    return false;
  }
  firebaseIdToken = idToken;
  tokenCreatedAt = millis();
  Serial.println(F("Da dang nhap Firebase Anonymous"));
  firebaseSyncStatus = "PENDING";
  return true;
}

String utcTimestamp(time_t now = time(nullptr)) {
  if (now < MIN_VALID_UNIX_TIME) return "";
  struct tm utc;
  gmtime_r(&now, &utc);
  char value[25];
  strftime(value, sizeof(value), "%Y-%m-%dT%H:%M:%SZ", &utc);
  return String(value);
}
