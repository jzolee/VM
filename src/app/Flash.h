//https://forums.adafruit.com/viewtopic.php?t=207187
//https://forum.seeedstudio.com/t/xiao-ble-wakes-up-from-system-on-sleep-and-writes-weather-data-to-on-board-flash/270911

#pragma once

#include "Arduino.h"

#include "globals.h"

#include "shared/P25Q16H.h"

namespace Flash {

    P25Q16H flash;
    bool isInit = false;
    uint16_t pageIndex = 0;
    uint32_t counter = 0;

    bool findMaxCounter() {

        uint32_t lo = 0, hi = flash.numPages() - 1;
        uint32_t loValue, midValue, hiValue;

        while (lo < hi) {

            if (!flash.read(lo * flash.pageSize(), (uint8_t*)&loValue, sizeof(loValue))) {
                PRINTLN("Flash::findMaxCounter() readBuffer error");
                return false;
            }
            if (!flash.read(hi * flash.pageSize(), (uint8_t*)&hiValue, sizeof(hiValue))) {
                PRINTLN("Flash::findMaxCounter() readBuffer error");
                return false;
            }
            if (loValue == UINT32_MAX) loValue = 0;
            if (hiValue == UINT32_MAX) hiValue = 0;

            PRINTf("find:\tlo:%u val:%u\thi:%u val:%u\n", lo, loValue, hi, hiValue);

            // If the current subarray is already sorted,
            // the maximum is at the hi index
            if (loValue <= hiValue) {
                pageIndex = hi;
                counter = hiValue;
                return true;
            }

            uint32_t mid = (lo + hi) / 2;
            if (!flash.read(mid * flash.pageSize(), (uint8_t*)&midValue, sizeof(midValue))) {
                PRINTLN("Flash::findMaxCounter() readBuffer error");
                return false;
            }
            if (midValue == UINT32_MAX) midValue = 0;

            // The left half is sorted, the maximum must 
            // be either arr[mid] or in the right half.
            if (loValue < midValue)
                lo = mid;
            else
                hi = mid - 1;
        }

        pageIndex = lo;
        counter = loValue;

        return true;
    }

    bool init() {
        if (isInit) return true;
        if (flash.begin()) {
            if (counter == 0)
                if (!findMaxCounter()) return false;
            PRINTf("Flash::initialise() page: %d, counter: %d\n", pageIndex, counter);
        } else {
            PRINTLN("Flash::begin() error");
            return false;
        }
        isInit = true;
        return true;
    }

    bool eraseChip() {
        if (!isInit)  if (!init()) return false;
        if (!flash.eraseChip()) {
            PRINTLN("Flash::format() error");
            return false;
        }
        PRINTLN("Flash::format() success");
        counter = 0;
        pageIndex = 0;
        return true;
    }

    bool elront() {
        if (!isInit)  if (!init()) return false;
        if (eraseChip()) {
            uint8_t buf[5] = { 0xff, 0xff, 0xff, 0xff, 0xAA };
            for (uint32_t i = 0; i < flash.numPages(); ++i)
                if (!flash.write(i * flash.pageSize(), buf, sizeof(buf))) {
                    PRINTLN("elront write hiba");
                    //break;
                    return false;
                }
        } else return false;
        return true;
    }

    bool save(uint8_t* buf, uint32_t len) {
        if (!isInit)  if (!init()) return false;
        ++counter;
        if (counter > 1) {
            ++pageIndex;
            if (pageIndex == flash.numPages())
                pageIndex = 0;
        }
        if (!flash.erasePage(pageIndex)) {
            PRINTLN("Flash::erase() error");
            return false;
        }
        PRINTf("Flash::erase() success, page: %d\n", pageIndex);
        if (!flash.write(pageIndex * flash.pageSize(), (uint8_t*)&counter, sizeof(counter))) {
            PRINTLN("Flash::save() writeBuffer error");
            return false;
        }
        if (!flash.write(pageIndex * flash.pageSize() + sizeof(counter), buf, len)) {
            PRINTLN("Flash::save() writeBuffer error");
            return false;
        }
        PRINTf("Flash::save() success, page: %d, counter: %d\n", pageIndex, counter);
        flash.sleep();
        return true;
    }

    bool load(uint8_t* buf, uint32_t len) {
        if (!isInit)  if (!init()) return false;
        if (!flash.read(pageIndex * flash.pageSize() + sizeof(counter), buf, len)) {
            PRINTLN("Flash::load() ReadBuffer error");
            return false;
        }
        PRINTf("Flash::load() success, page: %d\n", pageIndex);
        flash.sleep();
        return true;
    }

    bool loadPrev(uint8_t* buf, uint32_t len) {
        if (!isInit) if (!init()) return false;
        uint16_t temp = pageIndex;
        if (pageIndex == 0)
            pageIndex = flash.numPages() - 1;
        else
            --pageIndex;
        bool ret = load(buf, len);
        pageIndex = temp;
        return ret;
    }
}


/*
JEDEC ID: 0x00856015
Flash size (usable): 2048 KB
page size: 256
num pages: 8192
Flash::initialise() page: 8191, counter: 0
Flash::load() success, page: 8191, counter: 0
Flash::sleep()
crc error
check error
Flash::wake()
JEDEC ID: 0x00856015
Flash size (usable): 2048 KB
page size: 256
num pages: 8192
Flash::initialise() page: 8191, counter: 0
Flash::load() success, page: 8190, counter: 0
Flash::sleep()
crc error
check error
default settings applyed
Flash::wake()
JEDEC ID: 0x00856015
Flash size (usable): 2048 KB
page size: 256
num pages: 8192
Flash::initialise() page: 8191, counter: 0
Flash::erase() success, page: 0
Flash::save() success, page: 0, counter: 1
Flash::sleep()
settings saved
87.00
>>  Start.....
*/