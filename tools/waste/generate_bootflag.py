from SCons.Script import DefaultEnvironment
import os

env = DefaultEnvironment()
project_dir = env.subst("$PROJECT_DIR")

# Paraméterek
bootflag_addr = 0xEC000  # A kívánt memória cím
bootflag_value = 0xAABBCCDD  # A kiírandó érték
output_path = os.path.join(project_dir, "src", "bootflag", "bootflag.hex")

# HEX rekord készítése
# Intel HEX rekord formátum: :LLAAAARRDD...DDCC
# LL: adat hossza, AAAA: cím, RR: rekord típus (00 = adat), DD: adatbájtok, CC: checksum
value_bytes = bootflag_value.to_bytes(4, byteorder='little')
record_len = len(value_bytes)
addr = bootflag_addr
record_type = 0x00
checksum = (
    record_len +
    ((addr >> 8) & 0xFF) +
    (addr & 0xFF) +
    record_type +
    sum(value_bytes)
) & 0xFF
checksum = ((~checksum + 1) & 0xFF)

record = f":{record_len:02X}{addr:04X}{record_type:02X}{value_bytes.hex().upper()}{checksum:02X}"
eof_record = ":00000001FF"

os.makedirs(os.path.dirname(output_path), exist_ok=True)
with open(output_path, "w") as f:
    f.write(record + "\n")
    f.write(eof_record + "\n")

print(f"[bootflag] ✅ bootflag.hex generated at {output_path}")
