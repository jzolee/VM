from SCons.Script import DefaultEnvironment
import os
import subprocess
import shutil

env = DefaultEnvironment()
project_dir = env.subst("$PROJECT_DIR")
build_dir = os.path.join(project_dir, ".pio", "build", env["PIOENV"])
dispatcher_dir = os.path.join(project_dir, "src", "dispatcher")
tools_dir = os.path.join(project_dir, "tools")
bootflag_hex = os.path.join(project_dir, "src", "bootflag", "bootflag.hex")
bin_out_dir = os.path.join(project_dir, "build", "bin_output")
uf2conv_py = os.path.join(tools_dir, "uf2conv.py")

# GCC toolchain location
gcc_bin = os.path.join(env["PROJECT_PACKAGES_DIR"], "toolchain-gccarmnoneeabi", "bin")

# Dispatcher source files
main_c = os.path.join(dispatcher_dir, "main.c")
startup_s = os.path.join(dispatcher_dir, "startup_nrf52840.S")
linker_script = os.path.join(dispatcher_dir, "dispatch.ld")
dispatcher_elf = os.path.join(dispatcher_dir, "firmware.elf")
dispatcher_hex = os.path.join(dispatcher_dir, "firmware.hex")
dispatcher_bin = os.path.join(bin_out_dir, "dispatcher.bin")
app_bin = os.path.join(bin_out_dir, "app.bin")
updater_bin = os.path.join(bin_out_dir, "updater.bin")
combined_hex = os.path.join(bin_out_dir, "firmware.hex")
combined_bin = os.path.join(bin_out_dir, "firmware.bin")
uf2_out = os.path.join(bin_out_dir, "firmware.uf2")

os.makedirs(bin_out_dir, exist_ok=True)

def run_cmd(cmd, fail_msg):
    print("[merge] Running:", " ".join(cmd))
    if subprocess.call(cmd) != 0:
        print("[merge] ❌", fail_msg)
        env.Exit(1)

# 1. dispatcher build
run_cmd([os.path.join(gcc_bin, "arm-none-eabi-gcc"), "-mcpu=cortex-m4", "-mthumb", "-nostartfiles",
         "-Wall", "-Os", "-c", main_c, "-o", "main.o"],
        "main.o compilation failed")

run_cmd([os.path.join(gcc_bin, "arm-none-eabi-gcc"), "-mcpu=cortex-m4", "-mthumb", "-nostartfiles",
         "-Wall", "-Os", "-c", startup_s, "-o", "startup.o"],
        "startup.o compilation failed")

run_cmd([os.path.join(gcc_bin, "arm-none-eabi-gcc"), "-mcpu=cortex-m4", "-mthumb", "-nostartfiles",
         "main.o", "startup.o", "-T", linker_script, "-o", dispatcher_elf],
        "dispatcher link failed")

run_cmd([os.path.join(gcc_bin, "arm-none-eabi-objcopy"), "-O", "ihex", dispatcher_elf, dispatcher_hex],
        "dispatcher hex export failed")

run_cmd([os.path.join(gcc_bin, "arm-none-eabi-objcopy"), "-O", "binary", dispatcher_elf, dispatcher_bin],
        "dispatcher bin export failed")

print("[merge] ✅ dispatcher.hex and .bin created")

# 2. Bin generálás app/updaterhez, ha nincs
firmware_elf = os.path.join(build_dir, "firmware.elf")
firmware_bin = os.path.join(build_dir, "firmware.bin")

if not os.path.exists(firmware_bin):
    print("[merge] ℹ️ firmware.bin not found, generating from .elf...")
    run_cmd([os.path.join(gcc_bin, "arm-none-eabi-objcopy"), "-O", "binary", firmware_elf, firmware_bin],
            "app/updater .bin generation failed")

# 3. Másolás és updater build (csak ha app környezet vagyunk)
if env["PIOENV"] == "app":
    shutil.copyfile(firmware_bin, app_bin)
    print(f"[merge] ✅ app.bin copied to: {app_bin}")

    # build updater
    print("[merge] 🔁 Building updater...")
    subprocess.run(["platformio", "run", "-e", "updater"])

    updater_bin_src = os.path.join(project_dir, ".pio", "build", "updater", "firmware.bin")
    if os.path.exists(updater_bin_src):
        shutil.copyfile(updater_bin_src, updater_bin)
        print(f"[merge] ✅ updater.bin copied to: {updater_bin}")
    else:
        print("[merge] ❌ updater.bin not found after build.")
        env.Exit(1)

    # updater hex path
    updater_hex = os.path.join(project_dir, ".pio", "build", "updater", "firmware.hex")

    # merge .hex files
    print("[merge] 🧩 Combining dispatcher + app + bootflag .hex...")
    srec_cat = os.path.join(env["PROJECT_PACKAGES_DIR"], "tool-sreccat", "srec_cat.exe")
    app_hex = os.path.join(project_dir, ".pio", "build", "app", "firmware.hex")

    cmd_merge = [
        srec_cat,
        dispatcher_hex, "-Intel",
        app_hex, "-Intel",
        updater_hex, "-Intel", bootflag_hex, "-Intel",
        "-o", combined_hex, "-Intel"
    ]
    run_cmd(cmd_merge, "hex merge failed")

    print(f"[merge] ✅ Combined .hex created: {combined_hex}")

    # create UF2 if possible
    if os.path.exists(uf2conv_py):
        run_cmd(["python", uf2conv_py, combined_hex, "-c", "-f", "0xADA52840", "-o", uf2_out],
                "UF2 conversion failed")
        print(f"[merge] ✅ UF2 created at {uf2_out}")

elif env["PIOENV"] == "updater":
    shutil.copyfile(firmware_bin, updater_bin)
    print(f"[merge] ✅ updater.bin copied to: {updater_bin}")