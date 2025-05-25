#include <stdint.h>

#include "../firmware_layout.h"

//#define BOOTFLAG_ADDR     ((uint32_t*)0xEF000)
//#define DISPATCHER_ADDR   0x27000
//#define APP_ADDR          0x2F000
//#define UPDATER_ADDR      0x8F000

//#define BOOT_SLOT_APP     0xAABBCCDD
//#define BOOT_SLOT_UPDATER 0x11223344

// Definiáljuk manuálisan, mivel CMSIS nincs
#define __disable_irq()   __asm volatile ("cpsid i")
#define __set_MSP(x)      __asm volatile ("msr msp, %0" :: "r" (x) : )

static void jump_to(uint32_t addr)
{
    __disable_irq();
    __set_MSP(*(volatile uint32_t*)addr);
    void (*reset_handler)(void) = (void(*)(void))(*(volatile uint32_t*)(addr + 4));
    reset_handler();
}

int main(void)
{
    uint32_t flag = *((uint32_t*)BOOTFLAG_ADDR);

    if (flag == BOOT_SLOT_APP) {
        jump_to(APP_ADDR);
    } else if (flag == BOOT_SLOT_UPDATER) {
        jump_to(UPDATER_ADDR);
    }

    // Ha egyik sem érvényes: végtelen ciklus
    while (1) {
        volatile int i;
        for (i = 0; i < 100000; ++i);
    }
}
