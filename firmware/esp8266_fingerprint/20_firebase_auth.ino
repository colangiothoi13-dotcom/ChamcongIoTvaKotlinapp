// Firebase authentication and timestamp helpers.

bool firebaseSignIn() {
  if (firebaseIdToken.length() > 0 && millis() - tokenCreatedAt < 3300000UL) return true;
  if (FIREBASE_API_KEY == nullptr || String(FIREBASE_API_KEY).length() == 0) {
    firebaseSyncStatus = "ERROR";
    setLatestError("Chua cau hinh FIREBASE_WEB_API_KEY");
    return false;
  }
  BearSSL::WiFiClientSecure client;
  client.setInsecure();
  HTTPClient https;
  String url = String("https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=") + FIREBASE_API_KEY;
  if (!https.begin(client, url)) {
    firebaseSyncStatus = "ERROR";
    setLatestError("Khong tao duoc ket noi Firebase Auth");
    return false;
  }
  https.addHeader("Content-Type", "application/json");
  int code = https.POST("{\"returnSecureToken\":true}");
  String response = https.getString();
  https.end();
  if (code != 200) {
    Serial.printf("Firebase Auth loi HTTP %d: %s\n", code, response.c_str());
    firebaseSyncStatus = "ERROR";
    setLatestError(String("Firebase Auth HTTP ") + code);
    return false;
  }
  DynamicJsonDocument authDoc(2048);
  if (deserializeJson(authDoc, response)) {
    firebaseSyncStatus = "ERROR";
    setLatestError("Firebase Auth tra ve JSON khong hop le");
    return false;
  }
  firebaseIdToken = authDoc["idToken"].as<String>();
  tokenCreatedAt = millis();
  Serial.println("Da dang nhap Firebase Anonymous");
  if (firebaseIdToken.length() == 0) {
    firebaseSyncStatus = "ERROR";
    setLatestError("Firebase Auth khong tra ve token");
    return false;
  }
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

