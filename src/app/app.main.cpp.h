#warning "APP MAIN BUILDING"

#include "Arduino.h"

#define LED_GREEN_INIT() {pinMode(LED_GREEN, OUTPUT); digitalWrite(LED_GREEN, HIGH);}
#define LED_GREEN_ON() digitalWrite(LED_GREEN, LOW)
#define LED_GREEN_OFF() digitalWrite(LED_GREEN, HIGH)

#define LED_RED_INIT() {pinMode(LED_RED, OUTPUT); digitalWrite(LED_RED, HIGH);}
#define LED_RED_ON() digitalWrite(LED_RED, LOW)
#define LED_RED_OFF() digitalWrite(LED_RED, HIGH)

#define LED_BLUE_INIT() {pinMode(LED_BLUE, OUTPUT); digitalWrite(LED_BLUE, HIGH);}
#define LED_BLUE_ON() digitalWrite(LED_BLUE, LOW)
#define LED_BLUE_OFF() digitalWrite(LED_BLUE, HIGH)

void setup() {
    //LED_GREEN_INIT();
}

void loop() {
   // LED_GREEN_ON();
   // delay(500);
   // LED_GREEN_OFF();
   delay(500);
}
