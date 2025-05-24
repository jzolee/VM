#include <Arduino.h>
#include "qspi_driver.h"
#include "nrfx_qspi.h"

bool qspi_init() {
    /*nrfx_qspi_config_t config = {
        .xip_offset = 0,
        .pins = {
            .sck_pin  = 19,  // P0.19 (XIAO default)
            .csn_pin  = 17,  // P0.17
            .io0_pin  = 20,  // P0.20
            .io1_pin  = 21,  // P0.21
            .io2_pin  = 22,  // P0.22
            .io3_pin  = 23   // P0.23
        },
        .prot_if = {
            .readoc = NRF_QSPI_READOC_FASTREAD,
            .writeoc = NRF_QSPI_WRITEOC_PP,
            .addrmode = NRF_QSPI_ADDRMODE_24BIT,
            .dpmconfig = false
        },
        .phy_if = {
            .sck_delay = 1,
            .dpmen = false,
            .spi_mode = NRF_QSPI_MODE_0,
            .sck_freq = NRF_QSPI_FREQ_32MDIV1
        },
        .irq_priority = 7
    };*/
    nrfx_qspi_config_t config = {
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

    nrfx_err_t ret = nrfx_qspi_init(&config, NULL, NULL);
    if (ret != NRFX_SUCCESS) {
        PRINTLN("[QSPI] Init failed");
        return false;
    }

    nrfx_qspi_mem_busy_check();
    PRINTLN("[QSPI] Initialized");
    return true;
}

bool qspi_read(uint32_t address, uint8_t* buffer, size_t length) {
    nrfx_err_t ret = nrfx_qspi_read(buffer, length, address);
    if (ret != NRFX_SUCCESS) {
        Serial.printf("[QSPI] Read failed at 0x%08lx\n", address);
        return false;
    }
    return true;
}

bool qspi_uninit() {
    nrfx_qspi_uninit();
    return true;
}


/*static const nrfx_qspi_config_t qspi_config = NRFX_QSPI_DEFAULT_CONFIG;

bool qspi_init() {
    nrfx_err_t ret = nrfx_qspi_init(&qspi_config, NULL, NULL);
    if (ret != NRFX_SUCCESS) {
        PRINTLN("[QSPI] Init failed");
        return false;
    }

    ret = nrfx_qspi_mem_busy_check(); // optional: wait for ready
    if (ret != NRFX_SUCCESS) {
        PRINTLN("[QSPI] Init busy check failed");
        return false;
    }

    PRINTLN("[QSPI] Initialized");
    return true;
}

bool qspi_read(uint32_t address, uint8_t* buffer, size_t length) {
    nrfx_err_t ret = nrfx_qspi_read(buffer, length, address);
    if (ret != NRFX_SUCCESS) {
        Serial.printf("[QSPI] Read failed at 0x%08lx\n", address);
        return false;
    }
    return true;
}

bool qspi_uninit() {
    ret_code_t ret = nrfx_qspi_uninit();
    if (ret != NRFX_SUCCESS) {
        PRINTLN("[QSPI] Uninit failed");
        return false;
    }
    return true;
}*/
