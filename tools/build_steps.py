# File: tools/build_steps.py

import os
import re
import sys
import shutil
import subprocess
from SCons.Script import DefaultEnvironment
#import("env")

env = DefaultEnvironment()
project_dir = env.get("PROJECT_DIR")
packages_dir = env.get("PROJECT_PACKAGES_DIR")

gcc_bin = os.path.join(packages_dir, "toolchain-gccarmnoneeabi", "bin")
if not os.path.exists(gcc_bin):
    print(f"❌ GCC toolchain path not found: {gcc_bin}")
    env.Exit(1)

srec_cat = os.path.join(packages_dir, "tool-sreccat", "srec_cat.exe")
if not os.path.exists(srec_cat):
    print(f"❌ srec_cat.exe path not found: {srec_cat}")
    env.Exit(1)

nrfutil_script = os.path.join(env.get("PROJECT_PACKAGES_DIR"), "tool-adafruit-nrfutil", "adafruit-nrfutil.py")
if not os.path.exists(nrfutil_script):
    print(f"❌ adafruit-nrfutil.py path not found: {nrfutil_script}")
    env.Exit(1)

verify_firmware_hex_script = os.path.join(project_dir, "tools", "verify_firmware_hex.py")
if not os.path.exists(verify_firmware_hex_script):
    print(f"❌ verify_firmware_hex.py path not found: {verify_firmware_hex_script}")
    env.Exit(1)

uf2conv_script = os.path.join(project_dir, "tools", "uf2conv.py")
if not os.path.exists(uf2conv_script):
    print(f"❌ uf2conv.py path not found: {uf2conv_script}")
    env.Exit(1)

upload_script = os.path.join(project_dir, "tools", "upload.py")
if not os.path.exists(upload_script):
    print(f"❌ upload.py path not found: {upload_script}")
    env.Exit(1)

build_dir = os.path.join(project_dir, "build_output")
os.makedirs(build_dir, exist_ok=True)

dispatcher_src = os.path.join(project_dir, "src", "dispatcher")

app_hex = os.path.join(build_dir, "app.hex")

updater_hex = os.path.join(build_dir, "updater.hex")

bootflag_hex = os.path.join(build_dir, "bootflag.hex")

firmware_hex = os.path.join(build_dir, "firmware.hex")
firmware_bin = os.path.join(build_dir, "firmware.bin")
firmware_zip = os.path.join(build_dir, "firmware.zip")
firmware_uf2 = os.path.join(build_dir, "firmware.uf2")

dispatcher_elf = os.path.join(build_dir, "dispatcher.elf")
dispatcher_hex = os.path.join(build_dir, "dispatcher.hex")
dispatcher_bin = os.path.join(build_dir, "dispatcher.bin")

layout_path = os.path.join(project_dir, "src", "firmware_layout.h")

# =====================================================
#   Step 1: Cleaning updater
# =====================================================
print("🧹 Cleaning updater environment...")
subprocess.run(["platformio", "run", "-t", "clean", "-e", "updater"])

# =====================================================
#   Step 2: Building updater
# =====================================================
print("🔨 Building updater environment via PlatformIO...")
result = subprocess.run(["platformio", "run", "-e", "updater"])
if result.returncode != 0:
    print("❌ 'updater' build failed.")
    env.Exit(1)

# =====================================================
#   Step 3: Build dispatcher manually
# =====================================================
print("🛠️ Building dispatcher manually...")
def run(cmd):
    print("[build] Running:", cmd)
    result = subprocess.run(cmd, shell=True)
    if result.returncode != 0:
        print("[build] ❌ command failed")
        env.Exit(1)
run(f'"{os.path.join(gcc_bin, "arm-none-eabi-gcc")}" -mcpu=cortex-m4 -mthumb -nostartfiles -Wall -Os -c "{os.path.join(dispatcher_src, "main.c")}" -o main.o')
run(f'"{os.path.join(gcc_bin, "arm-none-eabi-gcc")}" -mcpu=cortex-m4 -mthumb -nostartfiles -Wall -Os -c "{os.path.join(dispatcher_src, "startup_nrf52840.S")}" -o startup.o')
run(f'"{os.path.join(gcc_bin, "arm-none-eabi-gcc")}" -mcpu=cortex-m4 -mthumb -nostartfiles main.o startup.o -T "{os.path.join(dispatcher_src, "dispatcher.ld")}" -o "{dispatcher_elf}"')
run(f'"{os.path.join(gcc_bin, "arm-none-eabi-objcopy")}" -O ihex "{dispatcher_elf}" "{dispatcher_hex}"')
print("✅ dispatcher.hex created")

# =====================================================
#   Step 4: Generate bootflag.hex
# =====================================================
print("🛠️ Generating bootflag.hex from firmware_layout.h...")
os.makedirs(os.path.dirname(bootflag_hex), exist_ok=True)
with open(layout_path, "r", encoding="utf-8") as f:
    content = f.read()
addr_match = re.search(r"#define\s+BOOTFLAG_ADDR\s+0x([0-9A-Fa-f]+)", content)
slot_match = re.search(r"#define\s+BOOT_SLOT_APP\s+0x([0-9A-Fa-f]+)", content)
if not addr_match or not slot_match:
    print("❌ BOOTFLAG_ADDR or BOOT_SLOT_APP not found in firmware_layout.h")
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
with open(bootflag_hex, "w", encoding="utf-8") as f:
    f.write("\n".join(lines) + "\n")
print(f"✅ bootflag.hex created")

# =====================================================
#   Step 5: Copy app & updater output files
# =====================================================
print("💾 Copy app & updater output files")
def copy_and_rename(src, dst_dir, name):
    if os.path.exists(src):
        os.makedirs(dst_dir, exist_ok=True)
        dst = os.path.join(dst_dir, name)
        shutil.copy2(src, dst)
        print(f"[copy] ✅ Copied {src} -> {dst}")
    else:
        print(f"[copy] ⚠️ {src} not found")
copy_and_rename(os.path.join(project_dir, ".pio", "build", "app", "firmware.hex"), build_dir, "app.hex")
copy_and_rename(os.path.join(project_dir, ".pio", "build", "updater", "firmware.hex"), build_dir, "updater.hex")

# =====================================================
#   Step 6: Merge all .hex files
# =====================================================
print("🧩 Combining dispatcher + app + updater + bootflag .hex...")
#cmd_merge = f'"{srec_cat}" "{dispatcher_hex}" -Intel "{app_hex}" -Intel "{updater_hex}" -Intel "{bootflag_hex}" -Intel -o "{firmware_hex}" -Intel'
cmd_merge = f'"{srec_cat}" "{dispatcher_hex}" -Intel "{app_hex}" -Intel "{updater_hex}" -Intel -o "{firmware_hex}" -Intel'
run(cmd_merge)

# =====================================================
#   Step 7: Verify the merged firmware.hex
# =====================================================
print("🧪 Verifying firmware.hex")
with open(verify_firmware_hex_script, encoding="utf-8") as f:
    code = compile(f.read(), verify_firmware_hex_script, 'exec')
    exec(code, {"__name__": "__main__"})

# =====================================================
#   Step 8: Converting HEX to BIN using srec_cat...
# =====================================================
'''
for name in ["app", "updater"]:
    elf_in = os.path.join(build_dir, f"{name}.elf")
    bin_out = os.path.join(build_dir, f"{name}.bin")
    if os.path.exists(elf_in):
        print(f"Extracting {name}.bin...")
        run(f'"{os.path.join(gcc_bin, "arm-none-eabi-objcopy")}" -O binary "{elf_in}" "{bin_out}"')
    else:
        print(f"❌ Missing {name}.elf, cannot extract {name}.bin")
        env.Exit(1)
'''
'''
print("📦 Converting HEX to BIN using srec_cat...")
for name in ["app", "updater", "firmware"]:
    hex_in = os.path.join(build_dir, f"{name}.hex")
    bin_out = os.path.join(build_dir, f"{name}.bin")
    if os.path.exists(hex_in):
        cmd_hex2bin = [srec_cat, hex_in, "-Intel", "-o", bin_out, "-Binary"]
        res = subprocess.run(cmd_hex2bin, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        if res.returncode != 0:
            print("[Convert] ❌ srec_cat failed")
            print(res.stderr)
            env.Exit(1)
    else:
        print(f"❌ Missing {name}.hex, cannot convert to {name}.bin")
        env.Exit(1)
'''
# =====================================================
#   Step 9: Generating DFU ZIP
# =====================================================
print("[dfu] 🛠️ Generating DFU ZIP with adafruit-nrfutil...")
cmd_dfu = [
    sys.executable,
    nrfutil_script,
    "dfu",
    "genpkg",
    "--dev-type", "0x0052",
    "--application", firmware_hex,
    #"--application-version", "0xFFFFFFFF",
    firmware_zip
]
res = subprocess.run(cmd_dfu, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
if res.returncode != 0:
    print("[dfu] ❌ adafruit-nrfutil genpkg failed")
    print(res.stderr)
    env.Exit(1)
print(f"[dfu] ✅ DFU package created")

# =====================================================
#   Step 10: Generate UF2
# =====================================================
print("[uf2] 🛠️ Generating uf2 file")
if os.path.exists(uf2conv_script):
    print("[uf2] 💾 Generating UF2...")
    run(f'python "{uf2conv_script}" "{firmware_hex}" -c -f 0xADA52840 -o "{firmware_uf2}"')
    print(f"[uf2] ✅ UF2 generated: {firmware_uf2}")
else:
    print("[uf2] ⚠️ UF2 converter not found, skipping UF2 generation.")

# =====================================================
#   Step 11: Clean build files
# =====================================================
print("[clean] 🧹 Clean build files")
for f in ["main.o", "startup.o"]:
    path = os.path.join(project_dir, f)
    if os.path.exists(path):
        print(f"[clean] 🧹 Removing {f}")
        os.remove(path)
for f in ["dispatcher.elf", "app.elf", "updater.elf", "firmware.elf"]:
    path = os.path.join(build_dir, f)
    if os.path.exists(path):
        print(f"[clean] 🧹 Removing {f}")
        os.remove(path)
print("[clean] ✅ clean complete.")

# =====================================================
#   Step 12: Copy firmware.zip to .pio/build/app/
# =====================================================

print("💾 Copy firmware.zip to .pio/build/app/")
source = os.path.join(build_dir, "firmware.zip")
destination = os.path.join(project_dir, ".pio", "build", "app", "firmware.zip")
if os.path.exists(source):
    shutil.copy2(source, destination)
    print(f"[copy] ✅ Copied {source} -> {destination}")
else:
    print(f"[copy] ⚠️ {source} not found")

# =====================================================
#   Step 13: DFU Upload
# =====================================================

upload_port = env.get("UPLOAD_PORT")
if upload_port:
    print(f"[dfu] 🚀 Uploading to device via {upload_port}...")
    cmd_upload = [
        sys.executable,
        nrfutil_script,
        "dfu",
        "serial",
        "--package", firmware_zip,
        "-p", upload_port,
        "-b", "115200"
    ]
    res = subprocess.run(cmd_upload, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if res.returncode != 0:
        print("[upload] ❌ Upload failed")
        print(res.stderr)
        env.Exit(1)
    print("[upload] ✅ Upload complete")
else:
    print("[upload] ℹ️ Upload skipped: UPLOAD_PORT not defined")
'''
print("🧪 Uploading")
with open(upload_script, encoding="utf-8") as f:
    code = compile(f.read(), upload_script, 'exec')
    exec(code, {"__name__": "__main__"})
'''