import os
from pathlib import Path
import shutil


def java_bin():
    home = os.environ.get("JAVA_HOME")
    if home:
        return Path(home).expanduser() / "bin"
    compiler = shutil.which("javac")
    if compiler:
        return Path(compiler).resolve().parent
    raise RuntimeError("Set JAVA_HOME to a JDK 17 installation or add its bin directory to PATH.")


def android_sdk():
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not home:
        raise RuntimeError("Set ANDROID_HOME to the Android SDK directory.")
    return Path(home).expanduser()
