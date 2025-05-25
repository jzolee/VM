from SCons.Script import Import
import os

Import("env")
project_dir = env.get("PROJECT_DIR")
merge_script = os.path.join(project_dir, "tools", "merge_and_package.py")

def merge_dispatcher_and_app(source, target, env):
    with open(merge_script, "rb") as f:
        code = compile(f.read(), merge_script, 'exec')
        exec(code, {"__name__": "__main__"})
env.AddPostAction("buildprog", merge_dispatcher_and_app)