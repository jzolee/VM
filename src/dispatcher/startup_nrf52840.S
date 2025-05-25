.syntax unified
.cpu cortex-m4
.thumb

.section .isr_vector, "a", %progbits
.type g_pfnVectors, %object
.size g_pfnVectors, .-g_pfnVectors
g_pfnVectors:
    .word _estack             /* Initial Stack Pointer */
    .word Reset_Handler       /* Reset Handler */

.section .text.Reset_Handler
.global Reset_Handler
.type Reset_Handler, %function
Reset_Handler:
    bl main
    b .
.size Reset_Handler, .-Reset_Handler

.section .stack, "a", %progbits
.word 0x20040000
_estack:
