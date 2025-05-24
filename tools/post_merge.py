# File: tools/post_merge.py

Import("env")
import subprocess
import os

def run_merge_script(source, target, env):
    script_path = os.path.join(env["PROJECT_DIR"], "tools", "merge_updater_app.py")
    if os.path.exists(script_path):
        print("[merge] Running merge_updater_app.py ...")
        result = subprocess.run(["python", script_path], shell=True)
        if result.returncode != 0:
            print("[merge] ERROR: merge_updater_app.py failed")
    else:
        print("[merge] Script not found!")

env.AddPostAction("buildprog", run_merge_script)
