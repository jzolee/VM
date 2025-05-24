// File: firmware/updater/src/updater_main.cpp

//#include <Arduino.h>

#include "shared/P25Q16H.h"
#include "shared/firmware_metadata.h"
#include "shared/log.h"

//#include "nrf_nvmc.h"

#include "nrf.h"
#include "cmsis_gcc.h"

P25Q16H flash;
firmware_metadata_t meta;
typedef void (*app_entry_t)(void);

bool copy_firmware_from_qspi() {

    PRINTf("[COPY] Flashing firmware v%lu (%lu bytes)...\n", meta.version, meta.size);

    for (uint32_t offset = 0; offset < meta.size; offset += 4) {

        uint32_t word;

        uint32_t src_addr = APP_FLASH_ADDRESS + offset;

        if (!flash.read(src_addr, (uint8_t*)&word, sizeof(word))) {
            PRINTf("[COPY] Failed to read QSPI at 0x%08lX\n", src_addr);
            return false;
        }

        uint32_t dest_addr = APP_START_ADDRESS + offset;

        if ((offset % 4096) == 0) {
            PRINTf("[COPY] Erasing flash sector at 0x%08lX\n", dest_addr);
            NRF_NVMC->CONFIG = NVMC_CONFIG_WEN_Een << NVMC_CONFIG_WEN_Pos;
            while (!NRF_NVMC->READY);
            NRF_NVMC->ERASEPAGE = dest_addr;
            while (!NRF_NVMC->READY);
        }

        NRF_NVMC->CONFIG = NVMC_CONFIG_WEN_Wen << NVMC_CONFIG_WEN_Pos;
        while (!NRF_NVMC->READY);
        *(uint32_t*)dest_addr = word;
        while (!NRF_NVMC->READY);
    }

    NRF_NVMC->CONFIG = NVMC_CONFIG_WEN_Ren << NVMC_CONFIG_WEN_Pos;

    PRINTLN("[COPY] Flashing complete");

    return true;
}

/*void start_app() {

    // Stack pointer beállítása az alkalmazás szerint
    __set_MSP(*(uint32_t*)APP_START_ADDRESS);

    // Ugrás a Reset_Handler-re
    uint32_t reset_handler = *(uint32_t*)(APP_START_ADDRESS + 4);
    ((app_entry_t)reset_handler)();
}*/

/*void jump_to_app(uint32_t addr) {
    SCB->VTOR = addr;
    __set_MSP(*(uint32_t*)addr);
    app_entry_t app = (app_entry_t) * (uint32_t*)(addr + 4);
    app();
}*/

/*void start_app() {
    // 1. Állítsd vissza az MSP-t az app által elvártra
    __set_MSP(*(uint32_t*)APP_START_ADDRESS);

    // 2. Vektor tábla átállítása
    SCB->VTOR = APP_START_ADDRESS;

    // 3. Reset_Handler címének lekérdezése
    uint32_t reset_handler_addr = *((uint32_t*)(APP_START_ADDRESS + 4));

    // 4. Ugrás az alkalmazás belépési pontjára
    app_entry_t app_start = (app_entry_t)reset_handler_addr;
    app_start();
}*/

/*void start_app()
{
    // 1. Olvasd ki az új vektor tábla értékeit
    uint32_t msp_value = *((uint32_t *)APP_START_ADDRESS);
    uint32_t reset_handler = *((uint32_t *)(APP_START_ADDRESS + 4));

    // 2. Állítsd le az összes megszakítást
    __disable_irq();

    // 3. Visszaállítjuk az MSP-t
    __set_MSP(msp_value);

    // 4. Vektor tábla áthelyezése
    SCB->VTOR = APP_START_ADDRESS;

    // 5. Opció: adat szinkronizálás (biztonság kedvéért)
    __DSB();
    __ISB();

    // 6. Ugrás az alkalmazás belépési pontjára
    app_entry_t app = (app_entry_t)reset_handler;
    app();

    // IDEÁLIS ESETBEN ide már soha nem tér vissza
}*/

void start_app() {
    // 1. Állítsd vissza az MSP-t
    __set_MSP(*(uint32_t*)APP_START_ADDRESS);

    // 2. Töröljük az esetleges engedélyezett interruptokat
    __disable_irq();

    // 3. Állítsuk át a vector tábla címét az app-ra
    SCB->VTOR = APP_START_ADDRESS;

    // 4. Minden NVIC megszakítás törlése (csatornánként)
    for (int i = 0; i < 8; i++) {
        NVIC->ICER[i] = 0xFFFFFFFF;
        NVIC->ICPR[i] = 0xFFFFFFFF;
    }

    // 5. SysTick leállítása (ha a bootloader használta)
    SysTick->CTRL = 0;
    SysTick->LOAD = 0;
    SysTick->VAL = 0;

    // 6. Ugrás az app reset handlerre
    uint32_t reset_handler_addr = *((uint32_t*)(APP_START_ADDRESS + 4));
    app_entry_t app_start = (app_entry_t)reset_handler_addr;

    // Például: visszaállítja a perifériák állapotát
    NVIC->ICER[0] = 0xFFFFFFFF;
    NVIC->ICPR[0] = 0xFFFFFFFF;
    SysTick->CTRL = 0;
    SysTick->LOAD = 0;
    SysTick->VAL = 0;

    app_start();
}

void updater() {
    SERIAL_BEGIN(115200);
    //delay(200);
    PRINTLN("[UPDATER] Updater started");
    if (flash.begin()) {
        if (flash.read(METADATA_ADDRESS, (uint8_t*)&meta, sizeof(meta))) {
            if (meta.magic == METADATA_MAGIC) {
                if (meta.type == FW_APP) {
                    if (meta.size <= APP_MAX_SIZE) {
                        if (flash.crc32(APP_FLASH_ADDRESS, meta.size) == meta.crc32) {
                            if (copy_firmware_from_qspi()) {
                                PRINTLN("[UPDATER] Firmware updated");
                                flash.erasePage(METADATA_ADDRESS / flash.pageSize());
                            } else PRINTLN("[UPDATER] Update failed or invalid");
                        } else PRINTLN("[UPDATER] CRC error");
                    } else PRINTLN("[UPDATER] Not enough free space");
                } else PRINTLN("[UPDATER] No update requested");
            } else PRINTLN("[UPDATER] Invalid metadata magic");
        } else PRINTLN("[UPDATER] Failed to read metadata");
    } else PRINTLN("[UPDATER] QSPI init failed");
    flash.end();
    PRINTLN("[UPDATER] Jumping to app...");
    //delay(100);
    start_app();
}

/*void loop() {
    // should never reach here
    PRINTLN("[UPDATER] error");
    delay(1000);
}*/

int main() {
    //init();           // alap inicializálás (clock, peripheral)
    //initVariant();    // board-specifikus init (pl. USB CDC bekapcsolás)

    //Serial.begin(115200);
    //delay(100);       // várj a hostra, különben resetre fagyhat

    //Serial.println("[UPDATER] Serial ready");
    //start_app();
    updater();

    while (1);
}