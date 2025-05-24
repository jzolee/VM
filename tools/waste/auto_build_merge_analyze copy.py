# File: tools/auto_build_merge.py

from SCons.Script import Import, DefaultEnvironment
import os
import subprocess

Import("env")
project_dir = env.get("PROJECT_DIR")

# Called after the app firmware is compiled
def build_and_merge(source, target, env):
    print("[build] Step 1: Building updater...")
    result = subprocess.run(["platformio", "run", "-e", "updater"])
    if result.returncode != 0:
        print("[build] ❌ Failed to build updater.")
        env.Exit(1)

    print("[build] Step 2: Building app (already done in this env)...")

    print("[build] Step 3: Merging updater and app...")
    merge_script = os.path.join(project_dir, "tools", "merge_updater_app.py")
    try:
        with open(merge_script, encoding="utf-8") as f:
            code = compile(f.read(), merge_script, 'exec')
            exec(code, {"__name__": "__main__", "__file__": merge_script})
    except Exception as e:
        print("[build] ❌ Merge script failed:", e)
        env.Exit(1)

    print("[build] Step 4: Analyzing merged firmware...")
    analyze_script = os.path.join(project_dir, "tools", "analyze_combined_hex.py")
    try:
        with open(analyze_script, encoding="utf-8") as f:
            code = compile(f.read(), analyze_script, 'exec')
            exec(code, {"__name__": "__main__", "__file__": analyze_script})
    except Exception as e:
        print("[build] ⚠️ Analyze script failed:", e)

    print("[build] ✅ Build, merge, and analysis complete.")

# Hook into the build process of the xiao_app env
env.AddPostAction("buildprog", build_and_merge)
