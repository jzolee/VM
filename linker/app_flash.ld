/* File: /linker/app_flash.ld */

SEARCH_DIR(.)
GROUP(-lgcc -lc -lnosys)

MEMORY
{
  FLASH (rx) : ORIGIN = 0x40000, LENGTH = 0xED000 - 0x40000  /* App kezdődik az updater után */
  
  RAM (rwx) : ORIGIN = 0x20006000, LENGTH = 0x20040000 - 0x20006000  /* figyelembe veszi SoftDevice RAM igényt */
}

SECTIONS
{
  .isr_vector ORIGIN(FLASH) : ALIGN(4)
  {
    KEEP(*(.isr_vector))
  } > FLASH

  .text :
  {
    *(.text*)
    *(.rodata*)
  } > FLASH

  .svc_data :
  {
    PROVIDE(__start_svc_data = .);
    KEEP(*(.svc_data))
    PROVIDE(__stop_svc_data = .);
  } > RAM

  .fs_data :
  {
    PROVIDE(__start_fs_data = .);
    KEEP(*(.fs_data))
    PROVIDE(__stop_fs_data = .);
  } > RAM
}

_estack = ORIGIN(RAM) + LENGTH(RAM);

INCLUDE "nrf52_common.ld"
