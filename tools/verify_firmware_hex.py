# tools/verify_firmware_hex.py

from SCons.Script import DefaultEnvironment
import os
import re

env = DefaultEnvironment()
project_dir = env.subst("$PROJECT_DIR")

layout_path = os.path.join(project_dir, "src", "firmware_layout.h")
hex_path = os.path.join(project_dir, "build_output", "firmware.hex")

def parse_layout(path):
    content = open(path).read()
    layout = {}
    for key in [
        "DISPATCHER_ADDR", "APP_ADDR", "UPDATER_ADDR", "BOOTFLAG_ADDR",
        "BOOT_SLOT_APP", "BOOT_SLOT_UPDATER",
        "DISPATCHER_SIZE", "APP_SIZE", "UPDATER_SIZE"
    ]:
        match = re.search(r"#define\s+" + key + r"\s+(0x[0-9A-Fa-f]+)", content)
        if match:
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
            if not line.startswith(":"):
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

def verify_pc_instruction(segments, pc):
    instr = int.from_bytes([segments.get(pc + i, 0xFF) for i in range(4)], "little")
    print(f"[verify] ✅ Instruction at PC=0x{pc:08X} is 0x{instr:08X}")

def verify_reset_vector(segments, base, label):
    try:
        sp = int.from_bytes([segments[base + i] for i in range(4)], "little")
        pc = int.from_bytes([segments[base + i] for i in range(4, 8)], "little")
        print(f"[verify] Reset vector at {label} 0x{base:05X} -> SP=0x{sp:08X}, PC=0x{pc:08X}")
        verify_pc_instruction(segments, pc)
    except KeyError:
        print(f"[verify] ❌ No reset vector found at {label} 0x{base:05X}")

def verify_bootflag(segments, addr, expected_values):
    try:
        value = int.from_bytes([segments[addr + i] for i in range(4)], "little")
        status = "OK" if value in expected_values else "❌ Unexpected value"
        print(f"[verify] Bootflag at 0x{addr:05X} = 0x{value:08X} [{status}]")
    except KeyError:
        print(f"[verify] ❌ No bootflag value at 0x{addr:05X}")

def verify_overlap(layout):
    print("[verify] 🔎 Checking for memory overlaps...")
    regions = []
    for label, base_key, size_key in [
        ("DISPATCHER", "DISPATCHER_ADDR", "DISPATCHER_SIZE"),
        ("APP", "APP_ADDR", "APP_SIZE"),
        ("UPDATER", "UPDATER_ADDR", "UPDATER_SIZE")
    ]:
        if base_key in layout and size_key in layout:
            start = layout[base_key]
            end = start + layout[size_key]
            regions.append((label, start, end))
    for i in range(len(regions)):
        for j in range(i + 1, len(regions)):
            name1, s1, e1 = regions[i]
            name2, s2, e2 = regions[j]
            if s1 < e2 and s2 < e1:
                print(f"[verify] ❌ Overlap between {name1} and {name2}: 0x{s1:X}-0x{e1:X} vs 0x{s2:X}-0x{e2:X}")

def verify_vector_table(segments, base):
    print(f"[verify] 🔎 Vector table at 0x{base:05X}:")
    for i in range(16):
        addr = base + i * 4
        try:
            val = int.from_bytes([segments[addr + j] for j in range(4)], "little")
            print(f"  [0x{addr:05X}] Vector {i:2}: 0x{val:08X}")
        except KeyError:
            print(f"  [0x{addr:05X}] Vector {i:2}: <missing>")

def main():
    print("[verify] 🔍 Verifying firmware.hex...")

    if not os.path.exists(layout_path):
        print("[verify] ❌ firmware_layout.h not found")
        return
    if not os.path.exists(hex_path):
        print("[verify] ❌ firmware.hex not found")
        return

    layout = parse_layout(layout_path)
    segments = parse_hex(hex_path)

    print("[verify] 🔎 Checking reset vectors...")
    for label in ["DISPATCHER_ADDR", "APP_ADDR", "UPDATER_ADDR"]:
        if label in layout:
            verify_reset_vector(segments, layout[label], label)

    print("[verify] 🔎 Checking bootflag value...")
    if "BOOTFLAG_ADDR" in layout:
        verify_bootflag(segments, layout["BOOTFLAG_ADDR"], [
            layout.get("BOOT_SLOT_APP"), layout.get("BOOT_SLOT_UPDATER")
        ])

    verify_overlap(layout)

    print("[verify] 🔎 Checking vector tables...")
    for label in ["DISPATCHER_ADDR", "APP_ADDR", "UPDATER_ADDR"]:
        if label in layout:
            verify_vector_table(segments, layout[label])

main()
