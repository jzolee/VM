# File: tools/merge_updater_app.py

import os
import subprocess
from shutil import copyfile
from SCons.Script import DefaultEnvironment
import shutil

env = DefaultEnvironment()
project_dir = env.subst("$PROJECT_DIR")
packages_dir = env.get("PROJECT_PACKAGES_DIR")
srec_cat_path = os.path.join(packages_dir, "tool-sreccat", "srec_cat.exe")

if not os.path.exists(srec_cat_path):
    print(f"[merge] ERROR: srec_cat not found at: {srec_cat_path}")
    print("[merge] Please ensure tool-sreccat is installed and accessible.")
    env.Exit(1)

# Try to locate adafruit-nrfutil in PATH or Python Scripts folder
python_scripts_dirs = [
    os.path.join(os.environ.get("USERPROFILE", ""), "AppData", "Local", "Programs", "Python"),
    os.path.join(os.environ.get("LOCALAPPDATA", ""), "Programs", "Python"),
    os.path.join(os.environ.get("APPDATA", ""), "Python")
]

adafruit_nrfutil = shutil.which("adafruit-nrfutil")
if not adafruit_nrfutil:
    for base in python_scripts_dirs:
        for sub in os.listdir(base) if os.path.isdir(base) else []:
            path = os.path.join(base, sub, "Scripts", "adafruit-nrfutil.exe")
            if os.path.exists(path):
                adafruit_nrfutil = path
                break
        if adafruit_nrfutil:
            break

if not adafruit_nrfutil:
    print("[merge] ERROR: adafruit-nrfutil not found in PATH or common Python directories")
    env.Exit(1)

updater_hex = os.path.join(project_dir, ".pio", "build", "xiao_updater", "firmware.hex")
app_hex = os.path.join(project_dir, ".pio", "build", "xiao_app", "firmware.hex")
output_dir = os.path.join(project_dir, ".pio", "build", "combined")
os.makedirs(output_dir, exist_ok=True)

combined_hex = os.path.join(output_dir, "firmware.hex")
combined_bin = os.path.join(output_dir, "firmware.bin")
dfu_zip_path = os.path.join(output_dir, "firmware.zip")

# 1. Merge updater + app hex into one
print("[merge] Combining updater and app .hex...")
srec_cmd = f'"{srec_cat_path}" "{updater_hex}" -Intel "{app_hex}" -Intel -o "{combined_hex}" -Intel'
if subprocess.call(srec_cmd, shell=True) != 0:
    print("[merge] ERROR: Failed to merge .hex files")
    env.Exit(1)

# 2. Convert merged hex to bin
print("[merge] Converting merged hex to bin...")
srec_cmd = f'"{srec_cat_path}" "{combined_hex}" -Intel -o "{combined_bin}" -Binary'
if subprocess.call(srec_cmd, shell=True) != 0:
    print("[merge] ERROR: Failed to convert to .bin")
    env.Exit(1)

# 3. Create DFU zip using adafruit-nrfutil
print("[merge] Creating DFU .zip package using adafruit-nrfutil...")
dfu_cmd = f'"{adafruit_nrfutil}" dfu genpkg --dev-type 0x0052 --application "{combined_bin}" --application-version 0x0001 "{dfu_zip_path}"'
if subprocess.call(dfu_cmd, shell=True) != 0:
    print("[merge] ERROR: Failed to create DFU zip with adafruit-nrfutil")
    env.Exit(1)

print(f"[OK] Created DFU zip: {dfu_zip_path}")