//https://forums.adafruit.com/viewtopic.php?t=207187
//https://forum.seeedstudio.com/t/xiao-ble-wakes-up-from-system-on-sleep-and-writes-weather-data-to-on-board-flash/270911

/*
JEDEC ID: 0x00856015
Flash size (usable): 2048 KB
page size: 256
num pages: 8192
*/

#pragma once

#include "Arduino.h"

#include "globals.h"

//#include "Adafruit_SPIFlashBase.h"

//Adafruit_FlashTransport_QSPI flashTransport;
//Adafruit_SPIFlashBase flash(&flashTransport);
//SPIFlash_Device_t const p25q16h P25Q16H;

#include "nrfx_qspi.h"

#define  SFLASH_CMD_READ        0x03     // Single Read
#define  SFLASH_CMD_FAST_READ   0x0B // Fast Read
#define  SFLASH_CMD_QUAD_READ   0x6B // 1 line address, 4 line data

#define  SFLASH_CMD_READ_JEDEC_ID   0x9f

#define  SFLASH_CMD_PAGE_PROGRAM    0x02
#define  SFLASH_CMD_QUAD_PAGE_PROGRAM   0x32 // 1 line address, 4 line data

#define  SFLASH_CMD_READ_STATUS     0x05
#define  SFLASH_CMD_READ_STATUS2    0x35

#define  SFLASH_CMD_WRITE_STATUS    0x01
#define  SFLASH_CMD_WRITE_STATUS2   0x31

#define  SFLASH_CMD_ENABLE_RESET    0x66
#define  SFLASH_CMD_RESET           0x99

#define  SFLASH_CMD_WRITE_ENABLE    0x06
#define  SFLASH_CMD_WRITE_DISABLE   0x04

#define  SFLASH_CMD_ERASE_PAGE      0x81
#define  SFLASH_CMD_ERASE_SECTOR    0x20
#define  SFLASH_CMD_ERASE_BLOCK     0xD8
#define  SFLASH_CMD_ERASE_CHIP      0xC7

#define  SFLASH_CMD_4_BYTE_ADDR     0xB7
#define  SFLASH_CMD_3_BYTE_ADDR     0xE9




#define QSPI_DPM_ENTER      0x0003 // 3 x 256 x 62.5ns = 48ms
#define QSPI_DPM_EXIT       0x0003
#define QSPI_STD_CMD_RST    0x99
#define QSPI_STD_CMD_RSTEN  0x66
#define QSPI_STD_CMD_WRSR   0x01

//static bool                 QSPIWait = false;
static uint32_t* QSPI_Status_Ptr = (uint32_t*)0x40029604;  // Setup for the SEEED XIAO BLE - nRF52840

class P25Q16H_class {

public:
    nrfx_err_t begin() {
        qspi_init();
        //print_status("init() Before flash_init()");
        flash_init();
        PRINTLN("init() Wait for QSPI to be ready ...");
        NRF_QSPI->TASKS_ACTIVATE = 1;
        waitForReady();
        PRINTLN("init() QSPI is ready");
        return isReady();
    }

    void end() {
        qspi_uninit();
        flash_initialised = false;
    }

    void qspi_init() {
        if (qspi_initialised) return;
        while (millis() < 1000);
        nrfx_qspi_config_t cfg = {
            .xip_offset = 0,
            .pins = {
                .sck_pin = (uint8_t)g_ADigitalPinMap[PIN_QSPI_SCK],
                .csn_pin = (uint8_t)g_ADigitalPinMap[PIN_QSPI_CS],
                .io0_pin = (uint8_t)g_ADigitalPinMap[PIN_QSPI_IO0],
                .io1_pin = (uint8_t)g_ADigitalPinMap[PIN_QSPI_IO1],
                .io2_pin = (uint8_t)g_ADigitalPinMap[PIN_QSPI_IO2],
                .io3_pin = (uint8_t)g_ADigitalPinMap[PIN_QSPI_IO3],
            },
            .prot_if = {
                .readoc = NRF_QSPI_READOC_READ4O,
                .writeoc = NRF_QSPI_WRITEOC_PP4O,
                .addrmode = NRF_QSPI_ADDRMODE_24BIT,
                //.dpmconfig = false,
                .dpmconfig = true,  // Setup QSPI to allow for DPM but with it turned off
            },
            .phy_if = {
                .sck_delay = 0,//.sck_delay = 10,
                .dpmen = false,
                .spi_mode = NRF_QSPI_MODE_0,
                //.sck_freq = NRF_QSPI_FREQ_32MDIV16, // start with low 2 Mhz speed
                .sck_freq = (nrf_qspi_frequency_t)NRF_QSPI_FREQ_32MDIV1,  // I had to do it this way as it complained about nrf_qspi_phy_conf_t not being visible
            },
            .irq_priority = 7,
        };
        NRF_QSPI->DPMDUR = (QSPI_DPM_ENTER << 16) | QSPI_DPM_EXIT; // Found this on the Nordic Q&A pages, Sets the Deep power-down mode timer
        // No callback for blocking API
        //nrfx_qspi_init(&qspi_cfg, NULL, NULL);
        ///////////////////////////////////////////////////
        uint32_t Error_Code = 1;
        while (Error_Code != 0) {
            Error_Code = nrfx_qspi_init(&cfg, NULL, NULL);
            if (Error_Code != NRFX_SUCCESS)
                PRINTf("(qspi_init) nrfx_qspi_init returned : %d\n", Error_Code);
            else {
                PRINTLN("(qspi_init) nrfx_qspi_init successful");
                qspi_initialised = true;
            }
        }
    }

    void qspi_uninit() {
        if (!qspi_initialised) return;
        nrfx_qspi_uninit();
        qspi_initialised = false;
    }

    void flash_init() {
        if (flash_initialised) return;
        uint8_t  temp[] = { 0x00, 0x02 };

        nrf_qspi_cinstr_conf_t cfg = {
          .opcode = QSPI_STD_CMD_RSTEN,
          .length = NRF_QSPI_CINSTR_LEN_1B,
          .io2_level = true,
          .io3_level = true,
          .wipwait = false,
          .wren = true
        };

        waitForReady();

        if (nrfx_qspi_cinstr_xfer(&cfg, NULL, NULL) != NRFX_SUCCESS) { // Send reset enable
            PRINTLN("(QSIP_Configure_Memory) QSPI 'Send reset enable' failed!");
        } else {
            cfg.opcode = QSPI_STD_CMD_RST;

            waitForReady();

            if (nrfx_qspi_cinstr_xfer(&cfg, NULL, NULL) != NRFX_SUCCESS) { // Send reset command
                PRINTLN("(QSIP_Configure_Memory) QSPI Reset failed!");
            } else {
                cfg.opcode = QSPI_STD_CMD_WRSR;
                cfg.length = NRF_QSPI_CINSTR_LEN_3B;

                waitForReady();

                if (nrfx_qspi_cinstr_xfer(&cfg, &temp, NULL) != NRFX_SUCCESS) { // Switch to qspi mode
                    PRINTLN("(flash_init()) QSPI failed to switch to QSPI mode!");
                } else {
                    PRINTLN("flash_init() success");
                    flash_initialised = true;
                }
            }
        }
    }

    bool runCommand(uint8_t command) {
        nrf_qspi_cinstr_conf_t cfg = {
            .opcode = command,
            .length = NRF_QSPI_CINSTR_LEN_1B,
            .io2_level = true,
            .io3_level = true,
            .wipwait = false,
            .wren = true //false
        };
        return nrfx_qspi_cinstr_xfer(&cfg, NULL, NULL) == NRFX_SUCCESS;
    }

    bool writeCommand(uint8_t command, uint8_t const* data, uint32_t len) {
        nrf_qspi_cinstr_conf_t cfg = {
            .opcode = command,
            .length = (nrf_qspi_cinstr_len_t)(len + 1),
            .io2_level = true,
            .io3_level = true,
            .wipwait = false,
            .wren = true ///false // We do this manually.
        };
        return nrfx_qspi_cinstr_xfer(&cfg, data, NULL) == NRFX_SUCCESS;
    }

    bool read(uint32_t addr, uint8_t* data, uint32_t len) {
        uint32_t __attribute__((aligned(4))) address = addr;
        if (nrfx_qspi_read(data, len, address) != NRFX_SUCCESS) {
            return false;
        }
        return true;
    }

    bool write(uint32_t addr, const uint8_t* data, uint32_t len) {
        if (nrfx_qspi_write(data, len, addr) != NRFX_SUCCESS) {
            return false;
        }
        return true;
    }

    nrfx_err_t waitForReady() {
        while (isReady() == NRFX_ERROR_BUSY) {
            PRINTf("*QSPI_Status_Ptr & 8 = %d, *QSPI_Status_Ptr & 0x01000000 = 0x%04x\n", *QSPI_Status_Ptr & 8, *QSPI_Status_Ptr & 0x01000000);
            //print_status("QSPI_WaitForReady");
        }
        return NRFX_SUCCESS;
    }

    nrfx_err_t isReady() {
        if (((*QSPI_Status_Ptr & 8) == 8) && (*QSPI_Status_Ptr & 0x01000000) == 0) {
            return NRFX_SUCCESS;
        } else {
            return NRFX_ERROR_BUSY;
        }
    }

    bool eraseChip(void) {
        if (!qspi_initialised) {//if (!_flash_dev) {
            return false;
        }

        // We need to wait for any writes to finish
        waitForReady();
        //writeEnable();

        bool const ret = runCommand(SFLASH_CMD_ERASE_CHIP);

        return ret;
    }

    bool erasePage(uint32_t pageNumber) {
        if (!qspi_initialised) {//if (!_flash_dev) {
            return false;
        }

        // Before we erase the page we need to wait for any writes to finish
        waitForReady();
        //writeEnable();

        union {
            uint8_t buf[3];
            struct __attribute__((__packed__)) {
                uint16_t page;
                uint8_t dummy;
            };
        } x;

        x.page = pageNumber;
        x.dummy = 0;

        bool const ret = writeCommand(SFLASH_CMD_ERASE_PAGE, x.buf, 3);

        return ret;
    }

    /*void print_status(char ASender[]) { // Prints the QSPI Status
        PRINT("(");
        PRINT(ASender);
        PRINT(") QSPI is busy/idle ... Result = ");
        PRINTLN(nrfx_qspi_mem_busy_check() & 8);
        PRINT("(");
        PRINT(ASender);
        PRINT(") QSPI Status flag = 0x");
        PRINT(NRF_QSPI->STATUS, HEX);
        PRINT(" (from NRF_QSPI) or 0x");
        PRINT(*QSPI_Status_Ptr, HEX);
        PRINTLN(" (from *QSPI_Status_Ptr)");
    }*/

private:
    bool qspi_initialised = false;
    bool flash_initialised = false;
    uint16_t pageSize = 256;
    uint16_t numPages = 8192;
};

P25Q16H_class flash;

//#define BUFSIZE 4096

// 4 byte aligned buffer has best result with nRF QSPI
//uint8_t bufwrite[BUFSIZE] __attribute__((aligned(4)));
//uint8_t bufread[BUFSIZE] __attribute__((aligned(4)));

namespace Flash {
    bool isInitialised = false;
    uint16_t pageSize = 256;
    uint16_t numPages = 8192;
    uint16_t pagePtr = 0;
    uint32_t counter = 0;
    bool deep_power_down = false;

    void sleep(void) {
        if (deep_power_down == true) return;
        flash.qspi_init();
        while (!flash.runCommand(0xB9));
        delayMicroseconds(8);
        flash.qspi_uninit();
        isInitialised = false;
        deep_power_down = true;
        PRINTLN("Flash::sleep()");
    }

    void wake(bool force = false) {
        if (deep_power_down == false && force == false) return;
        flash.qspi_init();
        while (!flash.runCommand(0xAB));
        delayMicroseconds(8);
        flash.qspi_uninit();
        deep_power_down = false;
        PRINTLN("Flash::wake()");
    }

    /*void print_speed(const char* text, uint32_t count, uint32_t ms) {
        Serial.print(text);
        Serial.print(count);
        Serial.print(" bytes in ");
        Serial.print(ms / 1000.0F, 2);
        Serial.println(" seconds.");

        Serial.print("Speed: ");
        Serial.print((count / 1000.0F) / (ms / 1000.0F), 2);
        Serial.println(" KB/s.\r\n");
    }*/

    /*bool write_and_compare(uint8_t pattern) {
        uint32_t ms;

        Serial.println("Erase chip");
        Serial.flush();

        uint32_t const flash_sz = flash.size();
        flash.eraseChip();

        flash.waitUntilReady();

        // write all
        memset(bufwrite, (int)pattern, sizeof(bufwrite));
        Serial.print("Write flash with 0x");
        Serial.println(pattern, HEX);
        Serial.flush();
        ms = millis();

        for (uint32_t addr = 0; addr < flash_sz; addr += sizeof(bufwrite)) {
            flash.writeBuffer(addr, bufwrite, sizeof(bufwrite));
        }

        uint32_t ms_write = millis() - ms;
        print_speed("Write ", flash_sz, ms_write);
        Serial.flush();

        // read and compare
        Serial.println("Read flash and compare");
        Serial.flush();
        uint32_t ms_read = 0;
        for (uint32_t addr = 0; addr < flash_sz; addr += sizeof(bufread)) {
            memset(bufread, 0, sizeof(bufread));

            ms = millis();
            flash.readBuffer(addr, bufread, sizeof(bufread));
            ms_read += millis() - ms;

            if (memcmp(bufwrite, bufread, BUFSIZE)) {
                Serial.print("Error: flash contents mismatched at address 0x");
                Serial.println(addr, HEX);
                for (uint32_t i = 0; i < sizeof(bufread); i++) {
                    if (i != 0)
                        Serial.print(' ');
                    if ((i % 16 == 0)) {
                        Serial.println();
                        if (i < 0x100)
                            Serial.print('0');
                        if (i < 0x010)
                            Serial.print('0');
                        Serial.print(i, HEX);
                        Serial.print(": ");
                    }

                    if (bufread[i] < 0x10)
                        Serial.print('0');
                    Serial.print(bufread[i], HEX);
                }

                Serial.println();
                return false;
            }
        }

        print_speed("Read  ", flash_sz, ms_read);
        Serial.flush();

        return true;
    }*/

    /*void print_flash_info() {
        PRINTf("JEDEC ID: 0x%08x\n", flash.getJEDECID());
        PRINTf("Flash size (usable): %d KB\n", flash.size() / 1024);
        PRINTf("page size: %d\n", flash.pageSize());
        PRINTf("num pages: %d\n", flash.numPages());
        //PRINTf("sector count: %d\n", flash.sectorCount());
        //PRINTf("read8: %d\n", flash.read8(0x500));
        //PRINTf("read16: %d\n", flash.read16(0x500));
        //PRINTf("read32: %d\n", flash.read32(0x500));
        //uint8_t buf[10] = { 1,2,3,4,5,6,7,8,9,20 };
        //flash.writeBuffer(0x0, buf, 10);
        //PRINTf("read8: %d\n", flash.read8(0x0));
        //write_and_compare(0xAA);
        //write_and_compare(0x55);
    }*/

    /*bool find() {
        for (int i = 0; i < numPages; ++i) {
            uint32_t val;
            if (flash.readBuffer(i * pageSize, (uint8_t*)&val, sizeof(val)) == sizeof(val)) {
                if (i == 0) {
                    if (val == UINT32_MAX) {
                        break;
                    } else {
                        counter = val;
                    }
                } else if (val == counter + 1) {
                    counter = val;
                    pagePtr = i;
                } else {
                    break;
                }
            } else {
                PRINTLN("Flash::find() readBuffer error");
                return false;
            }
        }
        PRINTf("Flash::find() actual page is: %d\n", pagePtr);
        PRINTf("Flash::find() actual counter is: %d\n", counter);
    }*/

    // binary search: Maximum element in a sorted and rotated array
    // https://www.geeksforgeeks.org/maximum-element-in-a-sorted-and-rotated-array/
    bool findMaxCounter() {

        uint32_t lo = 0, hi = numPages - 1;
        uint32_t __attribute__((aligned(4))) loValue, midValue, hiValue;

        while (lo < hi) {

            if (!flash.read(lo * pageSize, (uint8_t*)&loValue, sizeof(loValue))) {
                PRINTLN("Flash::findMaxCounter() read error");
                return false;
            }
            if (!flash.read(hi * pageSize, (uint8_t*)&hiValue, sizeof(hiValue))) {
                PRINTLN("Flash::findMaxCounter() read error");
                return false;
            }
            if (loValue == UINT32_MAX) loValue = 0;
            if (hiValue == UINT32_MAX) hiValue = 0;

            // If the current subarray is already sorted,
            // the maximum is at the hi index
            if (loValue <= hiValue) {
                pagePtr = hi;
                counter = hiValue;// > 0 ? hiValue : 1;
                return true;
            }

            uint32_t mid = (lo + hi) / 2;
            if (!flash.read(mid * pageSize, (uint8_t*)&midValue, sizeof(midValue))) {
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

        pagePtr = lo;
        counter = loValue;// > 0 ? loValue : 1;

        return true;
    }

    bool initialise() {
        // wake();
        if (isInitialised) return true;
        if (flash.begin(/*&p25q16h, 1*/)) {
            /*PRINTf("JEDEC ID: 0x%08x\n", flash.getJEDECID());
            PRINTf("Flash size (usable): %d KB\n", flash.size() / 1024);
            pageSize = flash.pageSize();
            numPages = flash.numPages();*/
            PRINTf("page size: %d\n", pageSize);
            PRINTf("num pages: %d\n", numPages);
            if (!findMaxCounter()) return false;
            PRINTf("Flash::initialise() page: %d, counter: %u\n", pagePtr, counter);
        } else {
            PRINTLN("Flash::begin() error");
            return false;
        }
        isInitialised = true;
        return true;
    }

    bool eraseChip() {
        if (!isInitialised)  if (!initialise()) return false;
        if (!flash.eraseChip()) {
            PRINTLN("Flash::format() error");
            return false;
        }
        flash.waitForReady();
        PRINTLN("Flash::format() success");
        return true;
    }

    bool erasePage() {
        if (!flash.erasePage(pagePtr * pageSize)) {
            PRINTLN("Flash::erase() error");
            return false;
        }
        PRINTf("Flash::erase() success, page: %d\n", pagePtr);
        return true;
    }

    bool save(uint8_t* buf, uint32_t len) {
        if (!isInitialised)  if (!initialise()) return false;
        ++counter;
        ++pagePtr;
        if (pagePtr == numPages) pagePtr = 0;
        erasePage();
        uint8_t writeBuf[256] __attribute__((aligned(4)));
        memcpy(writeBuf, &counter, sizeof(counter));
        memcpy(writeBuf + sizeof(counter), buf, len);
        if (!flash.write(pagePtr * pageSize, writeBuf, len + sizeof(counter))) {
            PRINTLN("Flash::save() writeBuffer error");
            return false;
        }
        PRINTf("Flash::save() success, page: %d, counter: %d\n", pagePtr, counter);
        //sleep();
        return true;
    }

    bool load(uint8_t* buf, uint32_t len) {
        if (!isInitialised)  if (!initialise()) return false;
        uint8_t readBuf[256] __attribute__((aligned(4)));
        if (!flash.read(pagePtr * pageSize + sizeof(counter), readBuf, len)) {
            PRINTLN("Flash::load() ReadBuffer error");
            return false;
        } else {
            memcpy(buf, readBuf, len);
        }
        PRINTf("Flash::load() success, page: %d, counter: %d\n", pagePtr, counter);
        //sleep();
        return true;
    }

    bool loadPrev(uint8_t* buf, uint32_t len) {
        if (!isInitialised)  if (!initialise()) return false;
        if (counter > 0)
            --counter;
        if (pagePtr == 0)
            pagePtr = numPages - 1;
        else
            --pagePtr;
        return load(buf, len);
    }
}
