# tools/bootflag_generator.py
import re
from intelhex import IntelHex
import os

def parse_firmware_layout(header_path):
    defines = {}
    with open(header_path, encoding="utf-8") as f:
        for line in f:
            match = re.match(r"#define\s+(\w+)\s+(0x[0-9A-Fa-f]+)", line)
            if match:
                key, value = match.groups()
                defines[key] = int(value, 16)
    return defines

def generate_bootflag_hex(defines, output_path, slot="BOOT_SLOT_APP"):
    if "BOOTFLAG_ADDR" not in defines or slot not in defines:
        raise ValueError("BOOTFLAG_ADDR or requested slot not found in definitions")

    ih = IntelHex()
    ih.puts(defines["BOOTFLAG_ADDR"], defines[slot].to_bytes(4, 'little'))
    ih.write_hex_file(output_path)
    print(f"[bootflag] {slot} -> {hex(defines[slot])} written at {hex(defines['BOOTFLAG_ADDR'])}")

if __name__ == "__main__":
    project_dir = os.path.dirname(os.path.dirname(__file__))
    layout_path = os.path.join(project_dir, "src", "firmware_layout.h")
    output_file = os.path.join(project_dir, "src", "bootflag", "bootflag.hex")

    defs = parse_firmware_layout(layout_path)
    generate_bootflag_hex(defs, output_file, slot="BOOT_SLOT_APP")
