#pragma once
#include <stdint.h>

//void delay_init_safe();
//void delay_ms_safe(uint32_t ms);
//void delay_us_safe(uint32_t us);

uint32_t safe_millis();
void safe_delay(uint32_t ms);