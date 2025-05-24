#pragma once

//#include <Arduino.h>

#include <stdbool.h>
#include <stdint.h>
#include <string.h>
#include "nrfx_qspi.h"
#include "pins_arduino.h"
#include "log.h"

//#define  FLASH_CMD_READ                 0x03 // Single Read
//#define  FLASH_CMD_FAST_READ            0x0B // Fast Read
//#define  FLASH_CMD_QUAD_READ            0x6B // 1 line address, 4 line data
//#define  FLASH_CMD_READ_JEDEC_ID        0x9f
//#define  FLASH_CMD_PAGE_PROGRAM         0x02
//#define  FLASH_CMD_QUAD_PAGE_PROGRAM    0x32 // 1 line address, 4 line data
#define  FLASH_CMD_READ_STATUS          0x05
#define  FLASH_CMD_READ_STATUS2         0x35
#define  FLASH_CMD_WRITE_STATUS         0x01
//#define  FLASH_CMD_WRITE_STATUS2        0x31
#define  FLASH_CMD_ENABLE_RESET         0x66
#define  FLASH_CMD_RESET                0x99
#define  FLASH_CMD_WRITE_ENABLE         0x06
#define  FLASH_CMD_WRITE_DISABLE        0x04
#define  FLASH_CMD_ERASE_PAGE           0x81
//#define  FLASH_CMD_ERASE_SECTOR         0x20
//#define  FLASH_CMD_ERASE_BLOCK          0xD8
#define  FLASH_CMD_ERASE_CHIP           0xC7
//#define  FLASH_CMD_4_BYTE_ADDR          0xB7
//#define  FLASH_CMD_3_BYTE_ADDR          0xE9

#define QSPI_DPM_ENTER                  0x0003 // 3 x 256 x 62.5ns = 48ms
#define QSPI_DPM_EXIT                   0x0003

class P25Q16H {

public:
    P25Q16H() {};
    ~P25Q16H() {};
    bool begin() { return chipInit(); }
    void end() { qspi_end(); }
    bool chipInit(void);
    bool read(uint32_t address, uint8_t* buffer, uint32_t len);
    bool write(uint32_t address, uint8_t const* buffer, uint32_t len);
    bool erasePage(uint32_t pageNumber);
    bool eraseChip(void);
    void sleep();
    uint32_t crc32(uint32_t address, uint32_t length);

    uint32_t pageSize() { return 0x100; }
    uint32_t numPages() { return 0x2000; }

private:
    bool qspi_init = false;
    bool chip_init = false;
    bool deep_power_down = false;
    void wake();
    bool qspi_begin(void);
    void qspi_end(void);
    uint8_t readStatus(void);
    uint8_t readStatus2(void);
    bool isReady(void) { return nrfx_qspi_mem_busy_check() == NRFX_SUCCESS; }//{ return (readStatus() & 0x03) == 0; } // both WIP and WREN bit should be clear
    void waitForReady(void) { while (!isReady()) /*yield()*/; }
    bool runCommand(uint8_t command);
    bool readCommand(uint8_t command, uint8_t* response, uint32_t len);
    bool writeCommand(uint8_t command, uint8_t const* data, uint32_t len);
    bool writeEnable(void) { return runCommand(FLASH_CMD_WRITE_ENABLE); }
    bool writeDisable(void) { return runCommand(FLASH_CMD_WRITE_DISABLE); }
    uint32_t read_write_odd(bool read_op, uint32_t addr, uint8_t* data, uint32_t len);
    bool read_write(bool read_op, uint32_t addr, uint8_t* data, uint32_t len);
};

void P25Q16H::sleep(void) {
    if (deep_power_down == true) return;
    if (qspi_begin()) {
        while (!runCommand(0xB9));
        delayMicroseconds(8);
        deep_power_down = true;
        PRINTLN("Flash::sleep()");
    }
}

void P25Q16H::wake() {
    if (deep_power_down == false) return;
    if (qspi_begin()) {
        while (!runCommand(0xAB));
        delayMicroseconds(8);
        deep_power_down = false;
        PRINTLN("Flash::wake()");
    }
}

bool P25Q16H::qspi_begin(void) {
    if (qspi_init) return true;

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
            .readoc = NRF_QSPI_READOC_READ4O, // 0x6B read command
            .writeoc = NRF_QSPI_WRITEOC_PP4O, // 0x32 write command
            .addrmode = NRF_QSPI_ADDRMODE_24BIT,
            //.dpmconfig = false
            .dpmconfig = true // Setup QSPI to allow for DPM but with it turned off
        },
        .phy_if = {
            //.sck_delay = 10,
            .sck_delay = 0,
            .dpmen = false,
            .spi_mode = NRF_QSPI_MODE_0,
            //.sck_freq = NRF_QSPI_FREQ_32MDIV16, // start with low 2 Mhz speed
            .sck_freq = (nrf_qspi_frequency_t)NRF_QSPI_FREQ_32MDIV1  // I had to do it this way as it complained about nrf_qspi_phy_conf_t not being visible
        },
        .irq_priority = 7
    };

    NRF_QSPI->DPMDUR = (QSPI_DPM_ENTER << 16) | QSPI_DPM_EXIT; // Found this on the Nordic Q&A pages, Sets the Deep power-down mode timer

    // Retry mechanism for QSPI initialization
    uint32_t retry_count = 3; // Number of retries
    nrfx_err_t init_result;

    do {
        init_result = nrfx_qspi_init(&cfg, NULL, NULL);
        if (init_result == NRFX_SUCCESS) {
            qspi_init = true;
            break;
        } else {
            PRINTf("nrfx_qspi_init failed with error: %d\n", init_result);
            delay(100); // Delay before retrying
        }
    } while (--retry_count > 0);

    if (qspi_init) {
        PRINTLN("QSPI initialaised succesfully");
        NRF_QSPI->TASKS_ACTIVATE = 1;
    } else PRINTLN("Failed to initialize QSPI after retries.");

    return qspi_init;
}

void P25Q16H::qspi_end(void) {
    if (!qspi_init) return;
    qspi_init = false;
    nrfx_qspi_uninit();
}

/*bool P25Q16H::chipInit() {
    qspi_begin();

    //------------- flash detection -------------//
    // Note: Manufacturer can be assigned with numerous of continuation code
    // (0x7F)
    uint8_t jedec_ids[4];
    readCommand(FLASH_CMD_READ_JEDEC_ID, jedec_ids, 4);

    // For simplicity with commonly used device, we only check for continuation
    // code at 2nd byte (e.g Fujitsu FRAM devices)
    if (jedec_ids[1] == 0x7F) {
        // Shift and skip continuation code in 2nd byte
        jedec_ids[1] = jedec_ids[2];
        jedec_ids[2] = jedec_ids[3];
    }

    // We don't know what state the flash is in so wait for any remaining writes
    // and then reset

    // The write in progress bit should be low.
    while (readStatus() & 0x01) {}

    // The suspended write/erase bit should be low.
    while (readStatus2() & 0x80) {}

    runCommand(FLASH_CMD_ENABLE_RESET);
    runCommand(FLASH_CMD_RESET);

    // Wait 30us for the reset
    delayMicroseconds(30);

    // Speed up to max device frequency, or as high as possible
    //uint32_t wr_speed = _flash_dev->max_clock_speed_mhz * 1000000U;

    // Limit to CPU speed if defined
    //wr_speed = min(wr_speed, (uint32_t)F_CPU);

    //uint32_t rd_speed = wr_speed;

    //_trans->setClockSpeed(wr_speed, rd_speed);

    // Verify that QSPI mode is enabled.
    uint8_t status = readStatus2();

    // Check the quad enable bit.
    if ((status & 0x02) == 0) {
        writeEnable();
        uint8_t buf[2] = { 0x00, 0x02 };
        writeCommand(FLASH_CMD_WRITE_STATUS, buf, 2);
    }

    writeDisable();
    waitForReady();

    return true;
}*/

bool P25Q16H::chipInit() {
    wake();
    if (chip_init) return true;
    if (!qspi_begin())return false;
    bool success = true;

    nrf_qspi_cinstr_conf_t cfg = {
      .opcode = FLASH_CMD_ENABLE_RESET,
      .length = NRF_QSPI_CINSTR_LEN_1B,
      .io2_level = true,
      .io3_level = true,
      .wipwait = false,
      .wren = true
    };

    waitForReady();

    if (nrfx_qspi_cinstr_xfer(&cfg, NULL, NULL) != NRFX_SUCCESS) { // Send reset enable
        PRINTLN("Chip init 'Send reset enable' failed!");
        success = false;
    } else {
        cfg.opcode = FLASH_CMD_RESET;

        waitForReady();

        if (nrfx_qspi_cinstr_xfer(&cfg, NULL, NULL) != NRFX_SUCCESS) { // Send reset command
            PRINTLN("Chip init 'Send reset' failed!");
            success = false;
        } else {
            cfg.opcode = FLASH_CMD_WRITE_STATUS;
            cfg.length = NRF_QSPI_CINSTR_LEN_3B;

            waitForReady();

            uint8_t  temp[] = { 0x00, 0x02 };

            if (nrfx_qspi_cinstr_xfer(&cfg, &temp, NULL) != NRFX_SUCCESS) { // Switch to qspi mode
                PRINTLN("Chip init failed to switch to QSPI mode!");
                success = false;
            } else {
                PRINTLN("Chip initialaised succesfully");
            }
        }
    }

    //NRF_QSPI->TASKS_ACTIVATE = 1;

    waitForReady();

    chip_init = success;

    return chip_init;
}

bool P25Q16H::runCommand(uint8_t command) {
    nrf_qspi_cinstr_conf_t cinstr_cfg = {
        .opcode = command,
        .length = NRF_QSPI_CINSTR_LEN_1B,
        .io2_level = true,
        .io3_level = true,
        .wipwait = false,
        .wren = false
    };
    return nrfx_qspi_cinstr_xfer(&cinstr_cfg, NULL, NULL) == NRFX_SUCCESS;
}

bool P25Q16H::readCommand(uint8_t command, uint8_t* response, uint32_t len) {
    nrf_qspi_cinstr_conf_t cinstr_cfg = {
        .opcode = command,
        .length = (nrf_qspi_cinstr_len_t)(len + 1),
        .io2_level = true,
        .io3_level = true,
        .wipwait = false,
        .wren = false
    };
    return nrfx_qspi_cinstr_xfer(&cinstr_cfg, NULL, response) == NRFX_SUCCESS;
}

bool P25Q16H::writeCommand(uint8_t command, uint8_t const* data, uint32_t len) {
    nrf_qspi_cinstr_conf_t cinstr_cfg = {
        .opcode = command,
        .length = (nrf_qspi_cinstr_len_t)(len + 1),
        .io2_level = true,
        .io3_level = true,
        .wipwait = false,
        .wren = false // We do this manually.
    };
    return nrfx_qspi_cinstr_xfer(&cinstr_cfg, data, NULL) == NRFX_SUCCESS;
}

uint32_t P25Q16H::read_write_odd(bool read_op, uint32_t addr, uint8_t* data, uint32_t len) {
    uint8_t buf4[4] __attribute__((aligned(4)));
    uint32_t count = 4 - (((uint32_t)data) & 0x03);
    count = count < len ? count : len; /////////////////////////////////////////////////////////////////////////////////////////min(count, len);

    if (read_op) {
        if (NRFX_SUCCESS != nrfx_qspi_read(buf4, 4, addr)) {
            return 0;
        }

        memcpy(data, buf4, count);
    } else {
        memset(buf4, 0xff, 4);
        memcpy(buf4, data, count);

        if (NRFX_SUCCESS != nrfx_qspi_write(buf4, 4, addr)) {
            return 0;
        }
    }

    return count;
}

bool P25Q16H::read_write(bool read_op, uint32_t addr, uint8_t* data, uint32_t len) {
    // buffer is not 4-byte aligned
    if (((uint32_t)data) & 3) {
        uint32_t const count = read_write_odd(read_op, addr, data, len);
        if (!count) return false;
        data += count;
        addr += count;
        len -= count;
    }

    // nrfx_qspi_read works in 4 byte increments, though it doesn't
    // signal an error if sz is not a multiple of 4.  Read (directly into data)
    // all but the last 1, 2, or 3 bytes depending on the (remaining) length.
    if (len > 3) {
        uint32_t const len4 = len & ~(uint32_t)3;

        if (read_op) {
            if (NRFX_SUCCESS != nrfx_qspi_read(data, len4, addr)) {
                return 0;
            }
        } else {
            if (NRFX_SUCCESS != nrfx_qspi_write(data, len4, addr)) {
                return 0;
            }
        }

        data += len4;
        addr += len4;
        len -= len4;
    }

    // Now, if we have any bytes left over, we must do a final read of 4
    // bytes and copy 1, 2, or 3 bytes to data.
    if (len) {
        if (!read_write_odd(read_op, addr, data, len)) {
            return false;
        }
    }

    return true;
}

uint8_t P25Q16H::readStatus(void) {
    uint8_t status;
    readCommand(FLASH_CMD_READ_STATUS, &status, 1);
    return status;
}

uint8_t P25Q16H::readStatus2(void) {
    uint8_t status;
    readCommand(FLASH_CMD_READ_STATUS2, &status, 1);
    return status;
}

bool P25Q16H::erasePage(uint32_t pageIndex) {
    if (!chipInit()) return false;
    if (pageIndex >= numPages()) return false; // Invalid page index
    waitForReady();
    writeEnable();
    uint16_t address = pageIndex * pageSize();
    return writeCommand(FLASH_CMD_ERASE_PAGE, (uint8_t*)&address, 3);
}

bool P25Q16H::eraseChip(void) {
    if (!chipInit()) return false;
    waitForReady();
    writeEnable();
    return runCommand(FLASH_CMD_ERASE_CHIP);;
}

bool P25Q16H::read(uint32_t address, uint8_t* buffer, uint32_t len) {
    if (!chipInit()) return false;
    waitForReady();
    return read_write(true, address, buffer, len);
}

bool P25Q16H::write(uint32_t address, uint8_t const* buffer, uint32_t len) {
    if (!chipInit()) return false;
    uint32_t remain = len;
    // write one page (256 bytes) at a time and must not go over page boundary
    while (remain) {
        waitForReady();
        writeEnable();
        uint32_t const leftOnPage = pageSize() - (address & (pageSize() - 1));
        uint32_t const toWrite = remain < leftOnPage ? remain : leftOnPage; ////////////////////////////////////////////////////min(remain, leftOnPage);
        //if (!writeMemory(address, buffer, toWrite)) break;
        if (!read_write(false, address, (uint8_t*)buffer, toWrite)) break;
        remain -= toWrite;
        buffer += toWrite;
        address += toWrite;
    }
    return remain ? false : true;
}

uint32_t P25Q16H::crc32(uint32_t address, uint32_t length) {
    const uint32_t polynomial = 0xEDB88320;
    uint32_t crc = ~0U;
    uint8_t buffer[64];

    while (length > 0) {
        uint32_t chunk_size = (length > sizeof(buffer)) ? sizeof(buffer) : length;

        if (!read(address, buffer, chunk_size)) {
            return 0; // hiba esetén 0-t ad vissza
        }

        for (uint32_t i = 0; i < chunk_size; ++i) {
            crc ^= buffer[i];
            for (int j = 0; j < 8; ++j) {
                crc = (crc >> 1) ^ (-(int)(crc & 1) & polynomial);
            }
        }

        address += chunk_size;
        length -= chunk_size;
    }

    return ~crc;
}