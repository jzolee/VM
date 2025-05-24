#pragma once

#include "Arduino.h"

#include "globals.h"

#include "Flash.h"

#define CRC 71

struct __attribute__((__packed__)) {

    uint8_t crc;
    //uint8_t sn[8];
    uint32_t check;
    float Kc;
    float Td;
    float KcN;
    float TdN;
    float Kc0;
    float Td0;
    float pb2;
    float nb2;
    float pb1;
    float nb1;
    float out_deadband;
    uint16_t rpm_1;
    uint16_t rpm_2;
    float slew_rate;
    float work_min;
    float work_max;
    float motor_min;
    float motor_max;
    float backlash;
    uint8_t rpm_filter_count;
    float rpm_filter_limit;
    uint8_t sma_size;
    float Kc1;
    float Td1;
    uint8_t input_type;
    uint32_t comm_time;
    uint16_t comm_rpm;
    uint32_t max_alarm;

    void Default() {
        check = 9654;
        Kc = 6.5; //11.0f; // gain
        Td = 1.23; //1.3f; // derivative time
        KcN = 1.0f; // negative error K factor
        TdN = 1.0f; // negative error T factor
        Kc0 = 0.6; //2.0f; //
        Td0 = 1.0; //0.5f; //
        Kc1 = 0.8; //1.5f; //
        Td1 = 1.0; //0.75f; //
        pb1 = 0.004f; // small error positive boundary
        nb1 = -0.004f; // small error negative boundary
        pb2 = 0.007f; // !!!!!!!!!!!!!!!
        nb2 = -0.007f; // !!!!!!!!!!!!!!!
        out_deadband = 0.0f;
        rpm_1 = 5500;
        rpm_2 = 5700;
        slew_rate = 87.0f;
        work_min = 0.8f;
        work_max = 1.15f;
        motor_min = 0.05f;
        motor_max = 1.0f;
        backlash = 0.0f; //0.4f;
        rpm_filter_count = 10;
        rpm_filter_limit = 0.1f;
        sma_size = 12;
        input_type = 2; // 0 rpm input // 1 kanardia CAN // 2 rotax CAN
        comm_time = 60; // sec
        comm_rpm = 4000;
        max_alarm = 1;

        PRINTLN("default settings applyed");
    }

    uint8_t calc_crc() {
        uint8_t ret = CRC;
        uint8_t* ptr = (uint8_t*)this;
        for (unsigned int i = 1; i < sizeof(*this); ++i)
            ret ^= *(ptr + i);
        return ret;
    }

    bool Check() {
        bool ret = true;

        if (calc_crc() != crc) {
            PRINTLN("crc error");
            ret = false;
        }

        if (check != 9654) {
            PRINTLN("check error");
            ret = false;
        }

        return ret;
    }

    void Load() {
        if (Flash::load((uint8_t*)this, sizeof(*this))) {
            if (!Check()) {
                if (Flash::loadPrev((uint8_t*)this, sizeof(*this))) {
                    if (!Check()) {
                        Default();
                        Save();
                    } else  PRINTLN("previous settings loaded");
                } else {
                    PRINTLN("load settings failed");
                    Default();
                }
            } else  PRINTLN("settings loaded");
        } else {
            PRINTLN("load settings failed");
            Default();
        }
    }

    void Save() {
        crc = calc_crc();
        if (Flash::save((uint8_t*)this, sizeof(*this)))
            PRINTLN("settings saved");
        else PRINTLN("save settings failed");
    }
} xsettings;


