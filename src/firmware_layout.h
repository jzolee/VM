// src/firmware_layout.h
#pragma once

/*
| Szegmens   | Kezdőcím | Hossz   | Leírás                   |
| ---------- | -------- | ------- | ------------------------ |
| bootloader | 0x00000  | 0x27000 | Adafruit zárt bootloader |
| dispatcher | 0x27000  | 0x08000 | Bare-metal váltó         |
| app        | 0x2F000  | 0x7D000 | Nagyobb méret, RTOS      |
| updater    | 0x8F000  | 0x40000 | RTOS-alapú               |
| bootflag   | 0xEC000  | 0x01000 | Egyetlen oldal           |
*/

// linker scripteket módosítani!!!!!!!!!!!!!!!!!!!!!!!!!!!!

#ifdef __cplusplus
extern "C" {
#endif

    // Flash régiók
#define DISPATCHER_ADDR   0x27000
#define APP_ADDR          0x2F000
#define UPDATER_ADDR      0x8F000
#define BOOTFLAG_ADDR     0xEC000

     // Bootflag értékek
#define BOOT_SLOT_APP     0xAABBCCDD
#define BOOT_SLOT_UPDATER 0x11223344

#ifdef __cplusplus
}
#endif