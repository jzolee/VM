# File: tools/merge_updater_app.py

import os
import subprocess
import shutil
import urllib.request
from SCons.Script import DefaultEnvironment

env = DefaultEnvironment()
project_dir = env.subst("$PROJECT_DIR")
packages_dir = env.get("PROJECT_PACKAGES_DIR")
srec_cat_path = os.path.join(packages_dir, "tool-sreccat", "srec_cat.exe")

if not os.path.exists(srec_cat_path):
    print(f"[merge] ERROR: srec_cat not found at: {srec_cat_path}")
    env.Exit(1)

# Try to locate adafruit-nrfutil
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
    print("[merge] ERROR: adafruit-nrfutil not found")
    env.Exit(1)

# Try to locate uf2conv.py and uf2families.json
uf2conv_path = os.path.join(project_dir, "tools", "uf2conv.py")
uf2json_path = os.path.join(project_dir, "tools", "uf2families.json")

# Auto-download uf2families.json if missing
if os.path.exists(uf2conv_path) and not os.path.exists(uf2json_path):
    print("[merge] Downloading missing uf2families.json...")
    try:
        urllib.request.urlretrieve(
            "https://raw.githubusercontent.com/microsoft/uf2/master/utils/uf2families.json",
            uf2json_path
        )
        print("[merge] uf2families.json downloaded successfully.")
    except Exception as e:
        print(f"[merge] WARNING: Failed to download uf2families.json: {e}")

updater_hex = os.path.join(project_dir, ".pio", "build", "updater", "firmware.hex")
app_hex = os.path.join(project_dir, ".pio", "build", "app", "firmware.hex")
output_dir = os.path.join(project_dir, ".pio", "build", "combined")
os.makedirs(output_dir, exist_ok=True)

combined_hex = os.path.join(output_dir, "firmware.hex")
combined_bin = os.path.join(output_dir, "firmware.bin")
dfu_zip_path = os.path.join(output_dir, "firmware.zip")
uf2_out = os.path.join(output_dir, "firmware.uf2")

# 1. Merge hex
print("[merge] Combining updater and app .hex...")
srec_cmd = f'"{srec_cat_path}" "{updater_hex}" -Intel "{app_hex}" -Intel -o "{combined_hex}" -Intel'
if subprocess.call(srec_cmd, shell=True) != 0:
    print("[merge] ERROR: Failed to merge .hex")
    env.Exit(1)

# 2. Convert to bin
print("[merge] Converting hex to bin...")
srec_cmd = f'"{srec_cat_path}" "{combined_hex}" -Intel -o "{combined_bin}" -Binary'
if subprocess.call(srec_cmd, shell=True) != 0:
    print("[merge] ERROR: Failed to convert to bin")
    env.Exit(1)

# 3. Create DFU zip
print("[merge] Creating DFU zip...")
dfu_cmd = f'"{adafruit_nrfutil}" dfu genpkg --dev-type 0x0052 --application "{combined_bin}" --application-version 0x0001 "{dfu_zip_path}"'
if subprocess.call(dfu_cmd, shell=True) != 0:
    print("[merge] ERROR: Failed to create DFU zip")
    env.Exit(1)

# 4. Upload via serial (if upload port available)
upload_port = env.get("UPLOAD_PORT")
if upload_port:
    print(f"[merge] Uploading via USB serial to {upload_port}...")
    serial_cmd = f'"{adafruit_nrfutil}" dfu serial --package "{dfu_zip_path}" -p {upload_port} -b 115200'
    subprocess.call(serial_cmd, shell=True)
else:
    print("[merge] Skipping upload: UPLOAD_PORT not defined")

# 5. Optionally generate UF2 file
if os.path.exists(uf2conv_path):
    print("[merge] Generating UF2 file for drag-and-drop...")
    ##uf2_cmd = f'python "{uf2conv_path}" "{combined_bin}" -c -b 0x27000 -f 0xADA52840 -o "{uf2_out}"'
    uf2_cmd = f'python "{uf2conv_path}" "{combined_hex}" -c -f 0xADA52840 -o "{uf2_out}"'
    if subprocess.call(uf2_cmd, shell=True) != 0:
        print("[merge] WARNING: UF2 conversion failed")
else:
    print("[merge] UF2 converter (uf2conv.py) not found – skipping UF2 output")

print(f"[OK] DFU zip: {dfu_zip_path}")
if os.path.exists(uf2_out):
    print(f"[OK] UF2 file: {uf2_out}")
