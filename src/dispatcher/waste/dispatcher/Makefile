TARGET = firmware
CC = arm-none-eabi-gcc
OBJCOPY = arm-none-eabi-objcopy
CFLAGS = -mcpu=cortex-m4 -mthumb -nostartfiles -Wall -Os
LDFLAGS = -Tdispatch.ld

SRC = main.c startup_nrf52840.S
OBJ = $(SRC:.c=.o) $(SRC:.S=.o)

all: $(TARGET).hex

%.o: %.c
	$(CC) $(CFLAGS) -c $< -o $@

%.o: %.S
	$(CC) $(CFLAGS) -c $< -o $@

$(TARGET).elf: $(OBJ)
	$(CC) $(CFLAGS) $(OBJ) $(LDFLAGS) -o $@

%.hex: %.elf
	$(OBJCOPY) -O ihex $< $@

clean:
	rm -f *.o *.elf *.hex
