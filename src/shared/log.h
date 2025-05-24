#pragma once

#if defined(LOG_ON)
#include "Arduino.h"
#include "Adafruit_TinyUSB.h"
#define SERIAL_BEGIN(...) { Serial.begin(__VA_ARGS__);/* while (!Serial);*/ }
#define PRINT(...) Serial.print(__VA_ARGS__)
#define PRINTLN(...) Serial.println(__VA_ARGS__)
#define PRINTf(...) Serial.printf(__VA_ARGS__)
#else
#define SERIAL_BEGIN(...)
#define PRINT(...)
#define PRINTLN(...)
#define PRINTf(...)
#endif