ENTRY(Reset_Handler)

MEMORY
{
  FLASH (rx)  : ORIGIN = 0x27000, LENGTH = 0x8000
  RAM (rwx)   : ORIGIN = 0x20006000, LENGTH = 0x3A000
}

SECTIONS
{
  .text : {
    KEEP(*(.vectors))
    *(.text*)
    *(.rodata*)
  } > FLASH

  .data : {
    *(.data*)
  } > RAM AT > FLASH

  .bss : {
    *(.bss*)
    *(COMMON)
  } > RAM

  . = ALIGN(4);
  end = .;
  PROVIDE(_end = .);
}
