// File: src/app/vector_table.cpp
#ifdef WITH_UPDATER
#warning "VECTOR TABLE BUILDING"

//#include "Arduino.h"

//#include <stdint.h>

// A linker script fogja definiálni ezt a szimbólumot:
extern unsigned long _estack;

extern int main();

typedef void(*isr_handler_t)(void);

__attribute__((section(".isr_vector"), used))
const isr_handler_t vector_table[] = {
  (isr_handler_t)&_estack,
  (isr_handler_t)main
};
#endif
