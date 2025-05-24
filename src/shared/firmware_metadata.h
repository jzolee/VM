// File: firmware/updater/include/firmware_metadata.h

#ifndef FIRMWARE_METADATA_H
#define FIRMWARE_METADATA_H

#include <stdint.h>

#define APP_START_ADDRESS 0x00040000

#define METADATA_ADDRESS 0x001FFF00  // QSPI offset for metadata (last page = 8191*256)
#define APP_FLASH_ADDRESS 0x00100000  // QSPI offset for firmware image (offset:2MB)
#define APP_MAX_SIZE    0x000ED000 - 0x00040000  // ~692 kB
#define METADATA_MAGIC 0xBEEFCAFE
#define FW_APP 6587
#define FW_UPDATER 3427

struct firmware_metadata_t {
    uint32_t type;        // 6587:app 3427:updater
    uint32_t magic;       // Magic value to identify metadata block
    uint32_t version;     // Firmware version number
    uint32_t size;        // Size of firmware in bytes
    uint32_t crc32;       // CRC32 of firmware image
    uint32_t reserved[4]; // Reserved for future use
};

#endif
