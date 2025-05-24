#include "safe_delay.h"
#include "nrf.h"


/*
static volatile uint32_t _ticks = 0;

extern "C" void SysTick_Handler(void) {
    _ticks++;
}

void safe_delay_init() {
    // Configure SysTick for 1ms interrupts
    SysTick->LOAD = (CPU_FREQ_MHZ * 1000) - 1;
    SysTick->VAL = 0;
    SysTick->CTRL = SysTick_CTRL_CLKSOURCE_Msk |
        SysTick_CTRL_TICKINT_Msk |
        SysTick_CTRL_ENABLE_Msk;
}

void safe_delay()(uint32_t ms) {
    if (_ticks == 0) safe_delay_init() ;
    uint32_t start = _ticks;
    while ((_ticks - start) < ms);
}

void delay_us_safe(uint32_t us) {
    if (_ticks == 0) delay_init_safe();
    uint32_t cycles = us * CPU_FREQ_MHZ;
    while (cycles--) {
        __asm__ volatile("nop");
    }
}*/
#define CPU_FREQ_MHZ 64  // nRF52840 default: 64 MHz/*
volatile uint32_t safe_millis_counter = 0;
bool safe_millis_init = false;

extern "C" void SysTick_Handler(void) { ++safe_millis_counter; }

void safe_delay_init() {
    //SysTick_Config(64000); // Configure SysTick for 1ms interrupts
    SysTick->LOAD = (CPU_FREQ_MHZ * 1000) - 1;
    SysTick->VAL = 0;
    SysTick->CTRL =
        SysTick_CTRL_CLKSOURCE_Msk |
        SysTick_CTRL_TICKINT_Msk |
        SysTick_CTRL_ENABLE_Msk;
    safe_millis_init = true;
} // 64 MHz / 1000 → 1 ms tick

uint32_t safe_millis() {
    if (safe_millis_init == false) safe_delay_init();
    return safe_millis_counter;
}

void safe_delay(uint32_t ms) {
    uint32_t start = safe_millis();
    while ((safe_millis() - start) < ms);
}
