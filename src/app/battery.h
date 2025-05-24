#pragma once

#include "Arduino.h"

//#define CHARGING_CURRENT (22u)  // HIGH for 50mA, LOW for 100mA  //P0_13 //
//#define CHARGE_STATE (23u) // LOW for charging, HIGH not charging //P0_17 //

// for mbed /////////#define READING_ENABLE PIN_VBAT_ENABLE  // (14u) // LOW for reading, HIGH for not reading //P0_14 //
#define READING_ENABLE VBAT_ENABLE  // (14u) // LOW for reading, HIGH for not reading //P0_14 //

#define BATTERY_PIN PIN_VBAT            // (32u) //P0_31
//#define PIN_VBAT (32u)

class Battery {

public:
  Battery() {
    //pinMode(BATTERY_PIN, INPUT);
    pinMode(READING_ENABLE, OUTPUT);
    digitalWrite(READING_ENABLE, HIGH);
    //pinMode(CHARGING_CURRENT, OUTPUT);
    //digitalWrite(CHARGING_CURRENT, HIGH);
    //pinMode(CHARGE_STATE, INPUT);

    //analogReference(AR_INTERNAL2V4);
    //analogReadResolution(12);
  }
  ~Battery() {}

  /*void setChargeCurrent(bool high) {
        digitalWrite(CHARGING_CURRENT, !high);
    }*/

  float getBatteryVoltage() {
    static float alpha = 1.0f;
    digitalWrite(READING_ENABLE, LOW);
    float reading = analogRead(BATTERY_PIN) / 104.8f;  // * 3.3f / 1024.0f * 1510.0f / 510.0f;
    digitalWrite(READING_ENABLE, HIGH);
    static float vBat = reading;
    vBat += (reading - vBat) * alpha;
    if (alpha > 0.01f) {
      alpha *= 0.9f;
      if (alpha < 0.01f) alpha = 0.01f;
    }
    return vBat;
  }

  uint8_t getBatteryPercentage() {
    float voltage = getBatteryVoltage();
    if (voltage < 3.3f) voltage = 3.3f;
    uint8_t ret = (uint8_t)((voltage - 3.3f) * 100.0f / (4.06f - 3.3f) + 0.5f);
    if (ret > 100) ret = 100;
    return ret;
  }

  //bool isCharging() { return digitalRead(CHARGE_STATE) == LOW; }
};

Battery battery;
