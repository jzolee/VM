# File: tools/analyze_combined_hex.py

import sys
import os
import re

# Set default path or allow override via argument
project_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
hex_path = os.path.join(project_dir, ".pio", "build", "combined", "firmware_combined.hex")

if not os.path.exists(hex_path):
    print(f"[analyze] ERROR: Cannot find hex file at: {hex_path}")
    sys.exit(1)

print(f"[analyze] Analyzing {hex_path}\n")

# Intel HEX line parser
address_ranges = []
current_base = 0

with open(hex_path, "r") as f:
    for line in f:
        if not line.startswith(":"):
            continue
        byte_count = int(line[1:3], 16)
        address = int(line[3:7], 16)
        record_type = int(line[7:9], 16)
        if record_type == 0x00:  # data record
            absolute_addr = current_base + address
            address_ranges.append((absolute_addr, absolute_addr + byte_count - 1))
        elif record_type == 0x04:  # extended linear address
            high = int(line[9:13], 16)
            current_base = high << 16

if not address_ranges:
    print("[analyze] No data records found.")
    sys.exit(1)

# Merge overlapping ranges
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
    size = end - start + 1
    print(f"  0x{start:08X} - 0x{end:08X} ({size} bytes)")

total_size = sum(end - start + 1 for start, end in merged)
print(f"\n[analyze] Total firmware size: {total_size} bytes")
