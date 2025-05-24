# File: tools/analyze_combined_hex.py

import sys
import os
import struct

# Set default path or allow override via argument
project_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
hex_path = os.path.join(project_dir, ".pio", "build", "combined", "firmware_combined.hex")
bin_path = os.path.join(project_dir, ".pio", "build", "combined", "firmware_combined.bin")

UPDATER_BASE_ADDR = 0x27000
APP_BASE_ADDR = 0x40000

if not os.path.exists(hex_path):
    print(f"[analyze] ERROR: Cannot find hex file at: {hex_path}")
    sys.exit(1)

print(f"[analyze] Analyzing {hex_path}\n")

# Intel HEX line parser
address_ranges = []
data_bytes = {}
current_base = 0

with open(hex_path, "r") as f:
    for line in f:
        if not line.startswith(":"):
            continue
        byte_count = int(line[1:3], 16)
        address = int(line[3:7], 16)
        record_type = int(line[7:9], 16)
        if record_type == 0x00:
            absolute_addr = current_base + address
            for i in range(byte_count):
                data_bytes[absolute_addr + i] = int(line[9 + i*2:11 + i*2], 16)
            address_ranges.append((absolute_addr, absolute_addr + byte_count - 1))
        elif record_type == 0x04:
            high = int(line[9:13], 16)
            current_base = high << 16

if not address_ranges:
    print("[analyze] No data records found.")
    sys.exit(1)

address_ranges.sort()
merged = [address_ranges[0]]
for start, end in address_ranges[1:]:
    last_start, last_end = merged[-1]
    if start <= last_end + 1:
        merged[-1] = (last_start, max(last_end, end))
    else:
        merged.append((start, end))

print("[analyze] Memory regions:")
for start, end in merged:
    print(f"  0x{start:08X} - 0x{end:08X} ({end - start + 1} bytes)")

print(f"\n[analyze] Total firmware size: {sum(end - start + 1 for start, end in merged)} bytes\n")

# Dump content around APP_BASE_ADDR
print("[analyze] Hex dump around 0x3FFFF - 0x40010:")
for addr in range(APP_BASE_ADDR - 4, APP_BASE_ADDR + 16):
    byte = data_bytes.get(addr, None)
    if byte is not None:
        print(f"  0x{addr:08X}: 0x{byte:02X}")
    else:
        print(f"  0x{addr:08X}: --")
print()

# Helper: extract vector from parsed HEX memory image
def read_vector_from_hex(data, base_addr, label):
    def get32(addr):
        return (data.get(addr, 0) |
                (data.get(addr + 1, 0) << 8) |
                (data.get(addr + 2, 0) << 16) |
                (data.get(addr + 3, 0) << 24))

    msp = get32(base_addr)
    reset = get32(base_addr + 4)

    print(f"[analyze] {label} vector table (from .hex @ 0x{base_addr:08X}):")
    print(f"  Initial MSP:   0x{msp:08X}")
    print(f"  Reset vector:  0x{reset:08X}")
    if 0x20000000 <= msp <= 0x20040000:
        print("  ✅ MSP valid (within SRAM)")
    else:
        print("  ⚠️ MSP outside expected RAM range")
    if any(start <= reset <= end for start, end in merged):
        print("  ✅ Reset vector points inside firmware")
    else:
        print("  ⚠️ Reset vector outside firmware range")
    print()

# Analyze vector tables directly from HEX data
read_vector_from_hex(data_bytes, UPDATER_BASE_ADDR, "Updater")
read_vector_from_hex(data_bytes, APP_BASE_ADDR, "App")

# Optional: also analyze from BIN file if it exists
if os.path.exists(bin_path):
    print("[analyze] Also checking vector tables from .bin...\n")
    bin_base_addr = min(start for start, _ in merged)
    try:
        with open(bin_path, "rb") as f:
            def analyze_vector_from_bin(file, base_addr, label):
                bin_offset = base_addr - bin_base_addr
                file.seek(bin_offset)
                vector = file.read(8)
                if len(vector) != 8:
                    print(f"[analyze] {label} vector table incomplete in .bin")
                    return
                msp, reset = struct.unpack("<II", vector)
                print(f"[analyze] {label} vector table (from .bin @ 0x{base_addr:08X}):")
                print(f"  Initial MSP:   0x{msp:08X}")
                print(f"  Reset vector:  0x{reset:08X}")
                if 0x20000000 <= msp <= 0x20040000:
                    print("  ✅ MSP valid (within SRAM)")
                else:
                    print("  ⚠️ MSP outside expected RAM range")
                if any(start <= reset <= end for start, end in merged):
                    print("  ✅ Reset vector points inside firmware")
                else:
                    print("  ⚠️ Reset vector outside firmware range")
                print()

            analyze_vector_from_bin(f, UPDATER_BASE_ADDR, "Updater")
            analyze_vector_from_bin(f, APP_BASE_ADDR, "App")
    except Exception as e:
        print(f"[analyze] ERROR: Failed to read vector tables from .bin: {e}")
else:
    print("[analyze] .bin file not found — skipped binary-level vector validation.")
