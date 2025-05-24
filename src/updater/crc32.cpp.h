// File: firmware/updater/src/crc32.cpp

#include <stdint.h>
#include <stddef.h>

static uint32_t crc32_table[256];
static bool table_initialized = false;

static void init_crc32_table() {
    for (uint32_t i = 0; i < 256; ++i) {
        uint32_t crc = i;
        for (uint32_t j = 0; j < 8; ++j) {
            if (crc & 1)
                crc = (crc >> 1) ^ 0xEDB88320UL;
            else
                crc >>= 1;
        }
        crc32_table[i] = crc;
    }
    table_initialized = true;
}

uint32_t crc32(const uint8_t* data, size_t length, uint32_t seed) {
    if (!table_initialized) init_crc32_table();
    uint32_t crc = ~seed;
    for (size_t i = 0; i < length; ++i)
        crc = (crc >> 1) ^ crc32_table[(crc ^ data[i]) & 0xFF];
    return ~crc;
}
