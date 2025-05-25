
# File: tools/merge_and_package.py

import os
import re
import subprocess
from SCons.Script import DefaultEnvironment

env = DefaultEnvironment()
project_dir = env.get("PROJECT_DIR")
packages_dir = env.get("PROJECT_PACKAGES_DIR")
build_dir = os.path.join(project_dir, "build", "bin_output")
os.makedirs(build_dir, exist_ok=True)

gcc_bin = os.path.join(packages_dir, "toolchain-gccarmnoneeabi", "bin")

# Step 0: Generate bootflag.hex
layout_path = os.path.join(project_dir, "src", "firmware_layout.h")
bootflag_hex_path = os.path.join(project_dir, "src", "bootflag", "bootflag.hex")
os.makedirs(os.path.dirname(bootflag_hex_path), exist_ok=True)

print("[bootflag] 🛠️ Generating bootflag.hex from firmware_layout.h...")

with open(layout_path, "r", encoding="utf-8") as f:
    content = f.read()

addr_match = re.search(r"#define\s+BOOTFLAG_ADDR\s+0x([0-9A-Fa-f]+)", content)
slot_match = re.search(r"#define\s+BOOT_SLOT_APP\s+0x([0-9A-Fa-f]+)", content)

if not addr_match or not slot_match:
    print("[bootflag] ❌ BOOTFLAG_ADDR or BOOT_SLOT_APP not found in firmware_layout.h")
    env.Exit(1)

addr = int(addr_match.group(1), 16)
slot = int(slot_match.group(1), 16)

upper = (addr >> 16) & 0xFFFF
lower = addr & 0xFFFF

data_bytes = [slot & 0xFF, (slot >> 8) & 0xFF, (slot >> 16) & 0xFF, (slot >> 24) & 0xFF]

def checksum(byte_list):
    total = sum(byte_list) & 0xFF
    return ((~total + 1) & 0xFF)

lines = []
ela_record = [0x02, 0x00, 0x00, 0x04, (upper >> 8) & 0xFF, upper & 0xFF]
lines.append(f":02000004{upper:04X}{checksum(ela_record):02X}")
data_record = [0x04, (lower >> 8) & 0xFF, lower & 0xFF, 0x00] + data_bytes
lines.append(f":04{lower:04X}00{''.join(f'{b:02X}' for b in data_bytes)}{checksum(data_record):02X}")
lines.append(":00000001FF")

with open(bootflag_hex_path, "w", encoding="utf-8") as f:
    f.write("\n".join(lines) + "\n")

print(f"[bootflag] ✅ bootflag.hex generated at: {bootflag_hex_path}")

# Step 1: Build dispatcher using bare-metal method
dispatcher_src = os.path.join(project_dir, "src", "dispatcher")
elf_path = os.path.join(dispatcher_src, "firmware.elf")
hex_path = os.path.join(dispatcher_src, "firmware.hex")
bin_out = os.path.join(build_dir, "dispatcher.bin")

print("[merge] 🛠️ Building dispatcher manually...")

def run(cmd):
    print("[merge] Running:", cmd)
    result = subprocess.run(cmd, shell=True)
    if result.returncode != 0:
        print("[merge] ❌ command failed")
        env.Exit(1)

run(f'"{os.path.join(gcc_bin, "arm-none-eabi-gcc")}" -mcpu=cortex-m4 -mthumb -nostartfiles -Wall -Os -c "{os.path.join(dispatcher_src, "main.c")}" -o main.o')
run(f'"{os.path.join(gcc_bin, "arm-none-eabi-gcc")}" -mcpu=cortex-m4 -mthumb -nostartfiles -Wall -Os -c "{os.path.join(dispatcher_src, "startup_nrf52840.S")}" -o startup.o')
run(f'"{os.path.join(gcc_bin, "arm-none-eabi-gcc")}" -mcpu=cortex-m4 -mthumb -nostartfiles main.o startup.o -T "{os.path.join(dispatcher_src, "dispatch.ld")}" -o "{elf_path}"')
run(f'"{os.path.join(gcc_bin, "arm-none-eabi-objcopy")}" -O ihex "{elf_path}" "{hex_path}"')
run(f'"{os.path.join(gcc_bin, "arm-none-eabi-objcopy")}" -O binary "{elf_path}" "{bin_out}"')
print("[merge] ✅ dispatcher.hex and .bin created")

# Step 2: Copy firmware.bin from app and updater
def copy_bin(env_name):
    bin_path = os.path.join(project_dir, ".pio", "build", env_name, "firmware.bin")
    dst_path = os.path.join(build_dir, f"{env_name}.bin")
    if not os.path.exists(bin_path):
        elf = os.path.join(project_dir, ".pio", "build", env_name, "firmware.elf")
        run(f'"{os.path.join(gcc_bin, "arm-none-eabi-objcopy")}" -O binary "{elf}" "{bin_path}"')
    os.makedirs(build_dir, exist_ok=True)
    shutil.copy(bin_path, dst_path)
    print(f"[merge] ✅ {env_name}.bin copied to: {dst_path}")

import shutil
copy_bin("app")
copy_bin("updater")

# Step 3: Merge hex files
srec_cat = os.path.join(packages_dir, "tool-sreccat", "srec_cat.exe")
dispatcher_hex = hex_path
app_hex = os.path.join(project_dir, ".pio", "build", "app", "firmware.hex")
updater_hex = os.path.join(project_dir, ".pio", "build", "updater", "firmware.hex")
bootflag_hex = bootflag_hex_path
merged_hex = os.path.join(build_dir, "firmware.hex")

print("[merge] 🧩 Combining dispatcher + app + updater + bootflag .hex...")
merge_cmd = f'"{srec_cat}" "{dispatcher_hex}" -Intel "{app_hex}" -Intel "{updater_hex}" -Intel "{bootflag_hex}" -Intel -o "{merged_hex}" -Intel'
run(merge_cmd)

# Step 4: Convert to UF2
uf2conv = os.path.join(project_dir, "tools", "uf2conv.py")
uf2_out = os.path.join(build_dir, "firmware.uf2")
if os.path.exists(uf2conv):
    run(f'python "{uf2conv}" "{merged_hex}" -c -f 0xADA52840 -o "{uf2_out}"')
    print(f"[merge] ✅ UF2 created at: {uf2_out}")
else:
    print("[merge] ⚠️ uf2conv.py not found – skipping UF2 output")
