/*
Battery Level	        00002a19-0000-1000-8000-00805F9B34FB
Device Name	            00002a00-0000-1000-8000-00805F9B34FB
Appearance	            00002a01-0000-1000-8000-00805F9B34FB
Peripheral Privacy Flag	00002a02-0000-1000-8000-00805F9B34FB
Reconnection Address	00002a03-0000-1000-8000-00805F9B34FB
Peripheral Preferred Connection Parameters
                        00002a04-0000-1000-8000-00805F9B34FB
Manufacturer Name	    00002a29-0000-1000-8000-00805F9B34FB
Model Number	        00002a24-0000-1000-8000-00805F9B34FB
Serial Number	        00002a25-0000-1000-8000-00805F9B34FB
Hardware Revision	    00002a27-0000-1000-8000-00805F9B34FB
Firmware Revision	    00002a26-0000-1000-8000-00805F9B34FB
IEE 11073-20601 Regulatory Certification Data List
                        00002a2a-0000-1000-8000-00805F9B34FB

*/

//https://github.com/alexanderlavrushko/BLEProof-collection/blob/main/ArduinoNano33/BLEProofPeripheral/BLEProofPeripheral.ino

//https://forum.arduino.cc/t/ble-gatt-message-stuck-at-20-byte-mtu-adafruit-board-bluefruit-library/1052124/3

#pragma once

#include "Arduino.h"

#include "globals.h"

#include <bluefruit.h>

#include "battery.h"

namespace ble {

#define SERVICE_UUID                 "96540000-d6a3-4d5b-8145-e5855fd090a7"
#define CONTROL_CHARACTERISTIC_UUID  "96540001-d6a3-4d5b-8145-e5855fd090a7"
#define STATUS_CHARACTERISTIC_UUID   "96540002-d6a3-4d5b-8145-e5855fd090a7"
#define DATA_CHARACTERISTIC_UUID     "96540003-d6a3-4d5b-8145-e5855fd090a7"

    static union {
        struct __attribute__((__packed__)) {
            float peak;
            uint8_t battery;
            uint16_t rms[sizeof(velocityBandRms) / sizeof(float)];
            uint16_t fft[sizeof(velocityRmsDownsampled) / sizeof(float)];
        } data;
        uint8_t buf[sizeof(data)];
    };

    BLEService sensorService = BLEService(SERVICE_UUID);
    BLECharacteristic controlCharacteristic = BLECharacteristic(CONTROL_CHARACTERISTIC_UUID, BLEWrite, sizeof(settings));
    BLECharacteristic statusCharacteristic = BLECharacteristic(STATUS_CHARACTERISTIC_UUID, BLERead | BLENotify, sizeof(settings));
    BLECharacteristic dataCharacteristic = BLECharacteristic(DATA_CHARACTERISTIC_UUID, BLERead | BLENotify, sizeof(buf));
    //BLEDis bledis;    // DIS (Device Information Service) helper class instance

    volatile state_t state = OFF;

    void sendData() {
        if (state == CONNECTED) {
            data.peak = dominantFrequencyHz * 60.0f;    // RPM
            data.battery = battery.getBatteryPercentage();  // %
            for (size_t i = 0; i < (sizeof(velocityBandRms) / sizeof(float)); ++i)
                data.rms[i] = (uint16_t)(velocityBandRms[i] * 3937.00787f);  // inch/s *100
            for (size_t i = 0; i < (sizeof(velocityRmsDownsampled) / sizeof(float)); ++i)
                data.fft[i] = (uint16_t)(velocityRmsDownsampled[i] * 3937.00787f);  // inch/s *100
            dataCharacteristic.notify(buf, sizeof(buf));
        }
    }

    void onControlWordReceived(uint16_t conn_hdl, BLECharacteristic* chr, uint8_t* data, uint16_t len) {
        if (len <= sizeof(settings)) {
            memcpy(settings.buf, data, len);
            statusCharacteristic.notify(settings.buf, sizeof(settings));
        }
    }

    void onConnect(uint16_t conn_handle) { state = CONNECTED; }

    void onDisconnect(uint16_t conn_handle, uint8_t reason) { state = READY; }

    void onCCCDwrite(uint16_t conn_hdl, BLECharacteristic* chr, uint16_t cccd_value)
    {
        if (chr->uuid == statusCharacteristic.uuid) {
            if (chr->notifyEnabled(conn_hdl)) {
                statusCharacteristic.notify(settings.buf, sizeof(settings));
            }
        }
    }

    void begin() {
        Bluefruit.configPrphBandwidth(BANDWIDTH_MAX);
        Bluefruit.begin(); // Initialise the Bluefruit module
        Bluefruit.setTxPower(8); // - nRF52840: -40dBm, -20dBm, -16dBm, -12dBm, -8dBm, -4dBm, 0dBm, +2dBm, +3dBm, +4dBm, +5dBm, +6dBm, +7dBm and +8dBm.
        Bluefruit.setName("Vibration Sensor");
        Bluefruit.autoConnLed(false);
        Bluefruit.Periph.setConnectCallback(onConnect);
        Bluefruit.Periph.setDisconnectCallback(onDisconnect);
        sensorService.begin();
        statusCharacteristic.setCccdWriteCallback(onCCCDwrite);
        statusCharacteristic.begin();
        controlCharacteristic.setWriteCallback(onControlWordReceived);
        controlCharacteristic.begin();
        dataCharacteristic.begin();
        Bluefruit.Advertising.addFlags(BLE_GAP_ADV_FLAGS_LE_ONLY_GENERAL_DISC_MODE);
        //Bluefruit.Advertising.addTxPower(); // ez már nem fér 31 bájtba
        Bluefruit.Advertising.addService(sensorService);
        const char* name = "VS-2505";
        Bluefruit.Advertising.addData(BLE_GAP_AD_TYPE_COMPLETE_LOCAL_NAME, name, strlen(name));
        Bluefruit.Advertising.restartOnDisconnect(true);
        Bluefruit.Advertising.setInterval(32, 244);    // in unit of 0.625 ms
        Bluefruit.Advertising.setFastTimeout(30);      // number of seconds in fast mode
        Bluefruit.Advertising.start(0);                // 0 = Don't stop advertising after n seconds  
        state = READY;
    }
}
