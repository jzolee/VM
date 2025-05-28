# File: tools/custom_upload.py
# Upload script for DFU using adafruit-nrfutil and automatic port detection (SCons-aware)

import os
import subprocess
from SCons.Script import DefaultEnvironment
from platformio.public import list_serial_ports

env = DefaultEnvironment()
project_dir = env.subst("$PROJECT_DIR")
python_exe = env.subst("$PYTHONEXE") or "python"

# Locate the DFU zip file (assumed output of previous build step)
dfu_zip_path = os.path.join(project_dir, "build_output", "firmware.zip")

# Locate adafruit-nrfutil inside PlatformIO package folder
nrfutil_py = os.path.join(
    env.PioPlatform().get_package_dir("tool-adafruit-nrfutil") or "",
    "adafruit-nrfutil.py"
)

if not os.path.exists(nrfutil_py):
    print("[upload] ❌ adafruit-nrfutil.py not found")
    env.Exit(1)

# Try to autodetect the upload port (USB DFU)
def detect_dfu_serial_port():
    ports_before = set(p.device for p in list_serial_ports())
    print("[upload] 🔎 Available ports before 1200bps touch:", ports_before)

    # Try 1200bps touch (if already in DFU, this won't help)
    upload_port = env.subst("$UPLOAD_PORT")
    if upload_port and upload_port != "":
        try:
            print(f"[upload] Touching {upload_port} at 1200bps to enter DFU mode...")
            import serial
            with serial.Serial(port=upload_port, baudrate=1200, timeout=0.5):
                pass
        except Exception as e:
            print(f"[upload] ⚠️ 1200bps touch failed: {e}")

    # Wait for new port to appear
    import time
    timeout = 10
    while timeout > 0:
        time.sleep(1)
        ports_after = set(p.device for p in list_serial_ports())
        new_ports = ports_after - ports_before
        if new_ports:
            detected_port = list(new_ports)[0]
            print(f"[upload] ✅ DFU port detected: {detected_port}")
            return detected_port
        timeout -= 1

    print("[upload] ❌ No DFU port detected")
    env.Exit(1)

upload_port = env.subst("$UPLOAD_PORT") or detect_dfu_serial_port()

# Build the DFU upload command
upload_cmd = [
    python_exe,
    nrfutil_py,
    "dfu", "serial",
    "-pkg", dfu_zip_path,
    "-p", upload_port,
    "-b", "115200",
    "--singlebank"
]

print("[upload] 🚀 Uploading firmware via DFU...")
result = subprocess.run(upload_cmd)
if result.returncode != 0:
    print("[upload] ❌ Upload failed")
    env.Exit(1)

print("[upload] ✅ Upload successful")
