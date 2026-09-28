// LCD 16x2 I2C: pins, object, initialization, text conversion, and screens.

// D3/GPIO0 va D4/GPIO2 can duoc giu HIGH khi ESP8266 khoi dong.
// Neu cap backpack LCD bang 5V, phai dung level shifter I2C 3.3V cho SDA/SCL.
const uint8_t LCD_ADDRESS = 0x27;  // Doi thanh 0x3F neu module dung dia chi nay
LiquidCrystal_I2C lcd(LCD_ADDRESS, 16, 2);

void initializeLcd() {
  Wire.begin(LCD_SDA_PIN, LCD_SCL_PIN);
  lcd.init();
  lcd.backlight();
  showLcd("KHOI DONG...", "VUI LONG DOI");
}

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

void renderLcd(const String& firstLine, const String& secondLine) {
  lcdPrintLine(0, firstLine);
  lcdPrintLine(1, secondLine);
}

void showLcd(const String& firstLine, const String& secondLine) {
  lcdIdleMode = false;
  renderLcd(firstLine, secondLine);
}

bool hasValidClock() {
  return time(nullptr) >= MIN_VALID_UNIX_TIME;
}

String vietnamTimeText() {
  time_t now = time(nullptr);
  if (now < MIN_VALID_UNIX_TIME) return "--:--:--";
  time_t localNow = now + 7 * 3600;
  struct tm localTime;
  gmtime_r(&localNow, &localTime);
  char value[15];
  strftime(value, sizeof(value), "%d/%m %H:%M:%S", &localTime);
  return String(value);
}

void showIdleScreen() {
  lcdIdleMode = true;
  if (!littleFsReady) {
    renderLcd("LOI LITTLEFS", "KHONG LUU QUEUE");
    lastLcdClock = millis();
    return;
  }
  int pendingCount = attendancePendingCount();
  String secondLine = attendanceOutboxIsFull()
      ? "HANG DOI DAY"
      : (pendingCount > 0 ? String("CHO SYNC: ") + pendingCount : "DAT NGON TAY...");
  if (hasValidClock()) {
    renderLcd(vietnamTimeText(), secondLine);
  } else {
    renderLcd("CHUA DONG BO GIO", secondLine);
  }
  lastLcdClock = millis();
}

void showReadyScreen() {
  showIdleScreen();
}

void maybeUpdateIdleClock() {
  if (!lcdIdleMode || millis() - lastLcdClock < LCD_CLOCK_INTERVAL_MS) return;
  lastLcdClock = millis();
  if (!littleFsReady) {
    renderLcd("LOI LITTLEFS", "KHONG LUU QUEUE");
    return;
  }
  int pendingCount = attendancePendingCount();
  String secondLine = attendanceOutboxIsFull()
      ? "HANG DOI DAY"
      : (pendingCount > 0 ? String("CHO SYNC: ") + pendingCount : "DAT NGON TAY...");
  if (hasValidClock()) {
    renderLcd(vietnamTimeText(), secondLine);
  } else {
    renderLcd("CHUA DONG BO GIO", secondLine);
  }
}
