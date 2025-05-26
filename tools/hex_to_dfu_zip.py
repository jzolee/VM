from SCons.Script import DefaultEnvironment
import os
import subprocess
import sys

env = DefaultEnvironment()
project_dir = env.subst("$PROJECT_DIR")
upload_port = env.get("UPLOAD_PORT")

hex_path = os.path.join(project_dir, "build_output", "firmware.hex")
bin_path = os.path.join(project_dir, "build_output", "firmware.bin")
zip_path = os.path.join(project_dir, "build_output", "firmware_dfu.zip")

# SRecord hex -> bin konverzió
srec_cat = os.path.join(
    env.get("PROJECT_PACKAGES_DIR"),
    "tool-sreccat",
    "srec_cat.exe"
)

print("[dfu] 🛠️ Converting HEX to BIN using srec_cat...")
cmd_hex2bin = [srec_cat, hex_path, "-Intel", "-o", bin_path, "-Binary"]

res = subprocess.run(cmd_hex2bin, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
if res.returncode != 0:
    print("[dfu] ❌ srec_cat failed")
    print(res.stderr)
    env.Exit(1)

print(f"[dfu] ✅ firmware.bin created: {bin_path}")

# adafruit-nrfutil (Python script)
nrfutil_script = os.path.join(env.get("PROJECT_PACKAGES_DIR"), "tool-adafruit-nrfutil", "adafruit-nrfutil.py")

print("[dfu] 🛠️ Generating DFU ZIP with adafruit-nrfutil...")
cmd_dfu = [
    sys.executable,
    nrfutil_script,
    "dfu",
    "genpkg",
    "--dev-type", "0x0052",
    "--application", bin_path,
    "--application-version", "0x0001",
    zip_path
]

res = subprocess.run(cmd_dfu, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
if res.returncode != 0:
    print("[dfu] ❌ adafruit-nrfutil genpkg failed")
    print(res.stderr)
    env.Exit(1)

print(f"[dfu] ✅ DFU package created: {zip_path}")

# --- DFU Upload (optional)
if upload_port:
    print(f"[dfu] 🚀 Uploading to device via {upload_port}...")
    cmd_upload = [
        sys.executable,
        nrfutil_script,
        "dfu",
        "serial",
        "--package", zip_path,
        "-p", upload_port,
        "-b", "115200"
    ]
    res = subprocess.run(cmd_upload, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if res.returncode != 0:
        print("[dfu] ❌ Upload failed")
        print(res.stderr)
        env.Exit(1)
    print("[dfu] ✅ Upload complete")
else:
    print("[dfu] ℹ️ Upload skipped: UPLOAD_PORT not defined")
