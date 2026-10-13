#pragma once

// Arduino.h must precede this header so the ESP-IDF target is known.
// Select the chip in Arduino IDE first; no secrets.h edits are needed to choose it.
#define CAMERA_BOARD_AI_THINKER 1
#define CAMERA_BOARD_S3_N16R8 2

#ifndef CAMERA_BOARD_PROFILE
#if defined(CONFIG_IDF_TARGET_ESP32S3)
#define CAMERA_BOARD_PROFILE CAMERA_BOARD_S3_N16R8
#elif defined(CONFIG_IDF_TARGET_ESP32)
#define CAMERA_BOARD_PROFILE CAMERA_BOARD_AI_THINKER
#else
#error "Supported camera targets: ESP32-S3 N16R8 or classic AI Thinker ESP32-CAM."
#endif
#endif

#if CAMERA_BOARD_PROFILE == CAMERA_BOARD_S3_N16R8
#if !defined(CONFIG_IDF_TARGET_ESP32S3)
#error "S3 N16R8 profile needs Arduino board ESP32S3 Dev Module."
#endif
// Known dual-Type-C ESP32-S3-WROOM camera carrier bus. Board names alone
// do not guarantee wiring; compare your PCB/vendor schematic before flashing.
// Reference: https://github.com/yoursunny/esp32cam/blob/main/src/esp32cam/pins.hpp
// OV2640/OV5640 selection is performed by the camera driver's sensor probe.
constexpr const char* CAMERA_BOARD_NAME = "ESP32-S3 WROOM N16R8";
constexpr bool CAMERA_REQUIRE_PSRAM = true;
constexpr int CAMERA_FLASH_LED_PIN = -1; // GPIO4 is camera SDA, NOT a flash LED.
constexpr int CAMERA_PIN_PWDN = -1;
constexpr int CAMERA_PIN_RESET = -1;
constexpr int CAMERA_PIN_XCLK = 15;
constexpr int CAMERA_PIN_SDA = 4;
constexpr int CAMERA_PIN_SCL = 5;
constexpr int CAMERA_PIN_D0 = 11;
constexpr int CAMERA_PIN_D1 = 9;
constexpr int CAMERA_PIN_D2 = 8;
constexpr int CAMERA_PIN_D3 = 10;
constexpr int CAMERA_PIN_D4 = 12;
constexpr int CAMERA_PIN_D5 = 18;
constexpr int CAMERA_PIN_D6 = 17;
constexpr int CAMERA_PIN_D7 = 16;
constexpr int CAMERA_PIN_VSYNC = 6;
constexpr int CAMERA_PIN_HREF = 7;
constexpr int CAMERA_PIN_PCLK = 13;
#elif CAMERA_BOARD_PROFILE == CAMERA_BOARD_AI_THINKER
#if !defined(CONFIG_IDF_TARGET_ESP32)
#error "AI Thinker profile needs Arduino board AI Thinker ESP32-CAM."
#endif
constexpr const char* CAMERA_BOARD_NAME = "AI Thinker ESP32-CAM";
constexpr bool CAMERA_REQUIRE_PSRAM = false;
constexpr int CAMERA_FLASH_LED_PIN = 4;
constexpr int CAMERA_PIN_PWDN = 32;
constexpr int CAMERA_PIN_RESET = -1;
constexpr int CAMERA_PIN_XCLK = 0;
constexpr int CAMERA_PIN_SDA = 26;
constexpr int CAMERA_PIN_SCL = 27;
constexpr int CAMERA_PIN_D0 = 5;
constexpr int CAMERA_PIN_D1 = 18;
constexpr int CAMERA_PIN_D2 = 19;
constexpr int CAMERA_PIN_D3 = 21;
constexpr int CAMERA_PIN_D4 = 36;
constexpr int CAMERA_PIN_D5 = 39;
constexpr int CAMERA_PIN_D6 = 34;
constexpr int CAMERA_PIN_D7 = 35;
constexpr int CAMERA_PIN_VSYNC = 25;
constexpr int CAMERA_PIN_HREF = 23;
constexpr int CAMERA_PIN_PCLK = 22;
#else
#error "Unknown CAMERA_BOARD_PROFILE. Use 1 (AI Thinker) or 2 (S3 N16R8)."
#endif
