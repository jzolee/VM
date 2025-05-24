/* File: update_app_flash.ld */

SEARCH_DIR(.)
GROUP(-lgcc -lc -lnosys)

MEMORY
{
  FLASH (rx)       : ORIGIN = 0x27000, LENGTH = 0xED000 - 0x27000  /* teljes 1MB - 0x27000 */
  UPDATER_PART(rx) : ORIGIN = 0x27000, LENGTH = 0x40000 - 0x27000  /* 100 kB for Updater after Bootloader */
  APP_PART(rx)     : ORIGIN = 0x40000, LENGTH = 0xED000 - 0x40000  /* App section after Updater */

  /* SRAM required by Softdevice depend on
   * - Attribute Table Size (Number of Services and Characteristics)
   * - Vendor UUID count
   * - Max ATT MTU
   * - Concurrent connection peripheral + central + secure links
   * - Event Len, HVN queue, Write CMD queue
   */ 
  RAM (rwx) :  ORIGIN = 0x20006000, LENGTH = 0x20040000 - 0x20006000 /* figyelembe veszi SoftDevice RAM lefoglalást */
}

SECTIONS
{
  . = ALIGN(4);
    _end = .;

  /* Default entry point */
  .text : { KEEP(*(.isr_vector)) } > UPDATER_PART
  .text.updater : { *(.updater_text) } > UPDATER_PART
  .text.app : { *(.app_text) } > APP_PART
  .rodata : { *(.rodata*) } > APP_PART
  .data : { *(.data*) } > RAM AT > APP_PART
  .bss : { *(.bss*) *(COMMON) } > RAM

  /* Original nrf52840_s140_v7.ld content*/

  .svc_data : {
    PROVIDE(__start_svc_data = .);
    KEEP(*(.svc_data))
    PROVIDE(__stop_svc_data = .);
  } > RAM
  
  .fs_data : {
    PROVIDE(__start_fs_data = .);
    KEEP(*(.fs_data))
    PROVIDE(__stop_fs_data = .);
  } > RAM
} INSERT AFTER .data;

INCLUDE "nrf52_common.ld"
