// src/dispatcher/main.c

//#include "nrf.h"
//#include "cmsis_gcc.h"

#include <stdint.h>
#include "../firmware_layout.h"

// CMSIS hiányában kézzel definiáljuk
#define __disable_irq()  __asm volatile ("cpsid i")
#define __set_MSP(x)     __asm volatile ("msr msp, %0" :: "r" (x) : )
#define __DSB()          __asm volatile ("dsb")
#define __ISB()          __asm volatile ("isb")

#define SCB_VTOR        (*(volatile uint32_t*)(0xE000E000 + 0x0D00 + 0x008))
#define NVIC_ICER(i)    (*(volatile uint32_t*)(0xE000E000 + 0x0100 + 0x080 + i))
#define NVIC_ICPR(i)    (*(volatile uint32_t*)(0xE000E000 + 0x0100 + 0x180 + i))
#define SysTick_CTRL    (*(volatile uint32_t*)(0xE000E000 + 0x0010 + 0x000))
#define SysTick_LOAD    (*(volatile uint32_t*)(0xE000E000 + 0x0010 + 0x004))
#define SysTick_VAL     (*(volatile uint32_t*)(0xE000E000 + 0x0010 + 0x008))

extern uint32_t __data_start__, __data_end__;
extern uint32_t __bss_start__, __bss_end__;

typedef void (*app_entry_t)(void);

// Ugrás a megadott címen levő firmware-re
static void jump_to(uint32_t addr)
{
    // 1. Visszaállítjuk az MSP-t
    __set_MSP(*(volatile uint32_t*)addr);

    // 2. Töröljük az esetleges engedélyezett interruptokat
    __disable_irq();

    // 3. Vektor tábla áthelyezése
    SCB_VTOR = addr;

    // 4. Minden NVIC megszakítás törlése (csatornánként)
    for (int i = 0; i < 8; i++) {
        NVIC_ICER(i) = 0xFFFFFFFF;
        NVIC_ICPR(i) = 0xFFFFFFFF;
    }

    // 5. SysTick leállítása (ha a bootloader használta)
    SysTick_CTRL = 0;
    SysTick_LOAD = 0;
    SysTick_VAL = 0;

    // 5. Adat szinkronizálás (biztonság kedvéért)
    __DSB();
    __ISB();

    // 6. Ugrás a slot reset handlerre
    uint32_t reset_handler_addr = *((uint32_t*)(addr + 4));
    app_entry_t start_slot = (app_entry_t)reset_handler_addr;

    start_slot();
}

int main(void)
{
    // Olvassuk be a bootflag-et
    uint32_t flag = *((volatile uint32_t*)BOOTFLAG_ADDR);

    if (flag == BOOT_SLOT_UPDATER) {
        jump_to(UPDATER_ADDR);
    } else {
        jump_to(APP_ADDR);//jump_to(UPDATER_ADDR);//jump_to(APP_ADDR);
    }

    // Ha érvénytelen: villogás vagy hibajelzés (egyszerű végtelen ciklus)
    while (1) {
        volatile int i;
        for (i = 0; i < 100000; ++i);
    }
}