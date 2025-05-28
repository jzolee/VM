# File: tools/verify_firmware_hex.py
# Ellenőrzi a firmware.hex tartalmát: reset vektorok, bootflag, átfedések, vektortáblák

import os
import re
from SCons.Script import DefaultEnvironment

env = DefaultEnvironment()
project_dir = env.subst("$PROJECT_DIR")

layout_path = os.path.join(project_dir, "src", "firmware_layout.h")
hex_path = os.path.join(project_dir, "build_output", "firmware.hex")

def parse_layout(path):
    content = open(path).read()
    layout = {}
    for key in [
        "DISPATCHER_ADDR", "APP_ADDR", "UPDATER_ADDR", "BOOTFLAG_ADDR",
        "BOOT_SLOT_APP", "BOOT_SLOT_UPDATER"
    ]:
        match = re.search(r"#define\s+" + key + r"\s+(0x[0-9A-Fa-f]+)", content)
        if not match:
            print(f"[verify] ⚠️ {key} not found in firmware_layout.h")
        else:
            layout[key] = int(match.group(1), 16)
    return layout

def parse_hex(path):
    segments = {}
    with open(path) as f:
        base = 0
        for line in f:
            if line.startswith(":02000004"):
                base = int(line[9:13], 16) << 16
                continue
            if not line.startswith(":") or len(line) < 11:
                continue
            reclen = int(line[1:3], 16)
            offset = int(line[3:7], 16)
            rectype = int(line[7:9], 16)
            if rectype != 0x00:
                continue
            data = [int(line[i:i+2], 16) for i in range(9, 9 + reclen * 2, 2)]
            abs_addr = base + offset
            for i, b in enumerate(data):
                segments[abs_addr + i] = b
    return segments

def read_u32(segments, addr):
    return sum((segments.get(addr + i, 0) << (8 * i)) for i in range(4))

def verify_reset_vector(segments, base, label):
    sp = read_u32(segments, base)
    pc = read_u32(segments, base + 4)
    print(f"[verify] Reset vector at {label} 0x{base:05X} -> SP=0x{sp:08X}, PC=0x{pc:08X}")
    verify_pc_instruction(segments, pc)

def verify_pc_instruction(segments, pc):
    instr = read_u32(segments, pc)
    print(f"[verify] ✅ Instruction at PC=0x{pc:08X} is 0x{instr:08X}")

def verify_bootflag(segments, addr, expected_values):
    value = read_u32(segments, addr)
    status = "OK" if value in expected_values else "❌ Unexpected value"
    print(f"[verify] Bootflag at 0x{addr:05X} = 0x{value:08X} [{status}]")

def check_overlap(name1, base1, name2, base2, end=0xFFFFFFFF):
    if base1 >= base2:
        base1, base2 = base2, base1
        name1, name2 = name2, name1
    if base1 + 1 >= base2:
        print(f"[verify] ❌ Overlap between {name1} and {name2}: 0x{base1:05X} - 0x{end:05X}")

def verify_vector_table(segments, base, label):
    print(f"[verify] 🔎 Vector table at 0x{base:05X}:")
    for i in range(16):
        addr = base + i * 4
        val = read_u32(segments, addr)
        print(f"  [0x{addr:05X}] Vector {i:2d}: 0x{val:08X}")

def main():
    if not os.path.exists(layout_path):
        print("[verify] ❌ firmware_layout.h not found")
        return
    if not os.path.exists(hex_path):
        print("[verify] ❌ firmware.hex not found")
        return

    layout = parse_layout(layout_path)
    segments = parse_hex(hex_path)

    print("[verify] 🔎 Checking reset vectors...")
    for key in ["DISPATCHER_ADDR", "APP_ADDR", "UPDATER_ADDR"]:
        if key in layout:
            verify_reset_vector(segments, layout[key], key)

    print("[verify] 🔎 Checking bootflag value...")
    if "BOOTFLAG_ADDR" in layout:
        verify_bootflag(
            segments,
            layout["BOOTFLAG_ADDR"],
            [layout.get("BOOT_SLOT_APP"), layout.get("BOOT_SLOT_UPDATER")]
        )

    print("[verify] 🔎 Checking for memory overlaps...")
    keys = ["DISPATCHER_ADDR", "APP_ADDR", "UPDATER_ADDR"]
    for i in range(len(keys)):
        for j in range(i + 1, len(keys)):
            a1, a2 = layout.get(keys[i]), layout.get(keys[j])
            if a1 and a2:
                check_overlap(keys[i], a1, keys[j], a2)

    print("[verify] 🔎 Checking vector tables...")
    for key in ["DISPATCHER_ADDR", "APP_ADDR", "UPDATER_ADDR"]:
        if key in layout:
            verify_vector_table(segments, layout[key], key)

main()
