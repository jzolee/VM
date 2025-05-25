.syntax unified
.cpu cortex-m4
.thumb

.section .vectors, "a", %progbits
.type   vectors, %object
.size   vectors, . - vectors

vectors:
    .word   _estack
    .word   Reset_Handler

.section .text.Reset_Handler
.global Reset_Handler
.type   Reset_Handler, %function
Reset_Handler:
    bl main
    b .
.size Reset_Handler, . - Reset_Handler

.section .stack
_estack:
    .word 0x20040000