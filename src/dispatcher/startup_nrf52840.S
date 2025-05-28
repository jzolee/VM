// src/dispatcher/startup_nrf52840.S
.syntax unified
.cpu cortex-m4
.thumb

.section .vectors, "a", %progbits
.type   vectors, %object
.size   vectors, . - vectors
.global vectors
vectors:
    .word  0x20040000
    .word  Reset_Handler

.section .isr_vector, "a", %progbits
.global __isr_vector
__isr_vector:
    .word  0x20040000
    .word  Reset_Handler

.section .text.Reset_Handler
.global Reset_Handler
.type   Reset_Handler, %function
Reset_Handler:
    bl main
    b .
.size Reset_Handler, . - Reset_Handler