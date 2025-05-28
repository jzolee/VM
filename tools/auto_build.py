# File: tools/auto_build.py

from SCons.Script import Import
import os

Import("env")
project_dir = env.get("PROJECT_DIR")
build_steps_script = os.path.join(project_dir, "tools", "build_steps.py")

def run_auto_build(source, target, env):
    with open(build_steps_script, "rb") as f:
        code = compile(f.read(), build_steps_script, 'exec')
        exec(code, {"__name__": "__main__"})

env.AddPostAction("buildprog", run_auto_build)
