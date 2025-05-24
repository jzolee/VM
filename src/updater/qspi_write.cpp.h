// File: firmware/updater/src/qspi_write.cpp

#include <Arduino.h>
#include <nrfx_qspi.h>

bool qspi_write(uint32_t address, const uint8_t* data, size_t length) {
    nrfx_err_t ret = nrfx_qspi_write(data, length, address);
    if (ret != NRF_SUCCESS) {
        PRINTf("[QSPI] Write failed at 0x%08lX\n", address);
        return false;
    }
    return true;
}

bool qspi_erase_page(uint32_t address) {
    nrfx_err_t ret = nrfx_qspi_erase(NRF_QSPI_ERASE_LEN_4KB, address);
    if (ret != NRF_SUCCESS) {
        PRINTf("[QSPI] Erase failed at 0x%08lX\n", address);
        return false;
    }
    return true;
}
