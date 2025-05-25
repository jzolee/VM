#include <stdint.h>

#define __disable_irq() __asm volatile ("cpsid i")
#define __set_MSP(x)    __asm volatile ("msr msp, %0" :: "r" (x) : )

#define BOOTFLAG_ADDR 0xEF000
#define APP_A_ADDR    0x2F000
#define APP_B_ADDR    0x8F000

#define BOOT_SLOT_A   0xAABBCCDD
#define BOOT_SLOT_B   0x11223344

typedef void (*app_entry_t)(void);

void jump_to_app(uint32_t app_addr) {
    __disable_irq();
    __set_MSP(*(volatile uint32_t*)app_addr);
    app_entry_t app_start = (app_entry_t)(*(volatile uint32_t*)(app_addr + 4));
    app_start();
}

int main(void) {
    uint32_t flag = *(volatile uint32_t*)BOOTFLAG_ADDR;
    if (flag == BOOT_SLOT_B) {
        jump_to_app(APP_B_ADDR);
    } else {
        jump_to_app(APP_A_ADDR);
    }
    while (1);
}
