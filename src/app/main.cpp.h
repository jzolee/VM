#warning "MAIN BUILDING"

#include <Arduino.h>
#include "shared/safe_delay.h"

// Arduino főbelépési pont saját vektortáblához
/*int main() {
    init();            // <- ez az Arduino belső inicializálása
    delay(1);          // néha szükséges stabilizálás

    setup();           // saját inicializálásod
    while (true) {
        loop();          // fő ciklus
    }
}*/

int main() {
    pinMode(LED_GREEN, OUTPUT);
    for (;;) {
        digitalToggle(LED_GREEN);
        for (volatile uint32_t i = 0; i < 1000000; i++) __asm__("nop");
        //safe_delay(500);
    }
}
