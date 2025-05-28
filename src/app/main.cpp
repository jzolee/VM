#include <Arduino.h>
#include "Adafruit_TinyUSB.h"
#include "firmware_layout.h"
#include "nrf_sdm.h"
#include "nrf_soc.h"

#define LED_PIN LED_GREEN

void printBootflag(uint32_t value) {
    if (value == BOOT_SLOT_APP) {
        ///Serial.println("Bootflag: APP");
    } else if (value == BOOT_SLOT_UPDATER) {
       /// Serial.println("Bootflag: UPDATER");
    } else {
        ///Serial.print("Bootflag: UNKNOWN (0x");
        //Serial.print(value, HEX);
        ///Serial.println(")");
    }
}

void setup() {
    pinMode(LED_PIN, OUTPUT);
    digitalWrite(LED_PIN, LOW);
    delay(3000);
    ///Serial.begin(115200);
   /// while (!Serial) { delay(500); }  // Várakozás, hogy a soros port elinduljon
   /// Serial.println("Start APP");
    delay(3000);

    // Kiolvassuk a bootflag értékét
    uint32_t bootflag = *(volatile uint32_t*)BOOTFLAG_ADDR;
    printBootflag(bootflag);

    // Átváltjuk a bootflaget
    uint32_t newflag = (bootflag == BOOT_SLOT_APP) ? BOOT_SLOT_UPDATER : BOOT_SLOT_APP;

    //Serial.print("Setting new bootflag to: 0x");
    ///Serial.println(newflag, HEX);

    // Calculate page number
    uint32_t page_size = NRF_FICR->CODEPAGESIZE;        // typically 4096
    uint32_t page_num = BOOTFLAG_ADDR / page_size;
    // Request SoftDevice flash operation
    sd_flash_page_erase(page_num);
    // Writing must be word-aligned
    sd_flash_write((uint32_t*)BOOTFLAG_ADDR, &newflag, 1);

    ///Serial.println("Rebooting in 3 seconds...");

    delay(3000);

    NVIC_SystemReset();
}

void loop() {
    digitalWrite(LED_PIN, HIGH);
    delay(300);
    digitalWrite(LED_PIN, LOW);
    delay(300);
}
