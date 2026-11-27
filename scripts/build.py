#!/usr/bin/env python3

import argparse
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile
from toolchain import android_sdk, java_bin


ROOT = Path(__file__).resolve().parent.parent


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True, cwd=ROOT, timeout=120)


def main():
    parser = argparse.ArgumentParser(description="Build a production APK by default, or an explicit debug APK.")
    parser.add_argument("--debug", action="store_true", help="Build an explicitly debuggable development APK")
    parser.add_argument("--init-signing-key", action="store_true", help="Create a new signing identity; never use this for an existing installation")
    args = parser.parse_args()
    SDK = android_sdk()
    JAVA = java_bin()
    BUILD_TOOLS = SDK / "build-tools" / "34.0.0"
    ANDROID_JAR = SDK / "platforms" / "android-34" / "android.jar"
    KEY = Path(os.environ.get("S22I_KEYSTORE", str(ROOT / ".cache" / ("s22i-debug.keystore" if args.debug else "s22i-release.keystore")))).expanduser()
    alias = os.environ.get("S22I_KEY_ALIAS", "s22idebug" if args.debug else "s22i")
    if args.debug:
        os.environ.setdefault("S22I_STORE_PASSWORD", "android")
    if not os.environ.get("S22I_STORE_PASSWORD"):
        raise RuntimeError("Set S22I_STORE_PASSWORD for production signing; never commit the password.")
    os.environ.setdefault("S22I_KEY_PASSWORD", os.environ["S22I_STORE_PASSWORD"])
    output = ROOT / "build" / ("s22i-console-debug.apk" if args.debug else "s22i-console-release.apk")
    required = [
        JAVA / "java", JAVA / "javac", JAVA / "keytool", ANDROID_JAR,
        BUILD_TOOLS / "aapt2", BUILD_TOOLS / "zipalign",
        BUILD_TOOLS / "lib/d8.jar", BUILD_TOOLS / "lib/apksigner.jar",
    ]
    for path in required:
        if not path.is_file():
            raise RuntimeError(f"Missing build tool: {path}. Set JAVA_HOME / ANDROID_HOME as needed.")
    sources = sorted((ROOT / "app/src").rglob("*.java"))
    if not sources:
        raise RuntimeError("No Java sources found under app/src.")
    output.parent.mkdir(exist_ok=True)
    KEY.parent.mkdir(parents=True, exist_ok=True)
    if args.init_signing_key and KEY.exists():
        raise RuntimeError("The signing key already exists. Refusing to replace it.")
    if not KEY.exists() and not (args.debug or args.init_signing_key):
        raise RuntimeError("Restore your existing signing key. For a first installation only, use --init-signing-key.")
    if not KEY.exists():
        print("Creating a local signing key. Keep it private and back it up for updates.", flush=True)
        run(
            JAVA / "keytool", "-genkeypair", "-noprompt", "-keystore", KEY,
            "-storepass:env", "S22I_STORE_PASSWORD", "-keypass:env", "S22I_KEY_PASSWORD", "-alias", alias,
            "-dname", "CN=S22i Console", "-keyalg", "RSA",
            "-keysize", "2048", "-validity", "10000",
        )
        KEY.chmod(0o600)

    with tempfile.TemporaryDirectory(prefix="apk-", dir=output.parent) as temporary:
        work = Path(temporary)
        classes = work / "classes"
        dex = work / "dex"
        generated = work / "generated"
        for folder in (classes, dex, generated):
            folder.mkdir()
        resource_inputs = []
        resources = ROOT / "app/res"
        if resources.is_dir() and any(p.is_file() for p in resources.rglob("*")):
            archive = work / "resources.zip"
            run(BUILD_TOOLS / "aapt2", "compile", "--dir", resources, "-o", archive)
            resource_inputs.append(archive)
        unsigned = work / "unsigned.apk"
        asset_args = ["-A", ROOT / "app/assets"] if (ROOT / "app/assets").is_dir() else []
        print("Packaging Android manifest and resources.", flush=True)
        run(
            BUILD_TOOLS / "aapt2", "link", "-o", unsigned,
            "--manifest", ROOT / "app/AndroidManifest.xml", "-I", ANDROID_JAR,
            "--java", generated, *(["--debug-mode"] if args.debug else []), *asset_args, *resource_inputs,
        )
        print("Compiling Java and Android DEX.", flush=True)
        run(
            JAVA / "javac", "--release", "8", "-encoding", "UTF-8",
            "-classpath", ANDROID_JAR, "-g" if args.debug else "-g:none", "-d", classes,
            *sources, *sorted(generated.rglob("*.java")),
        )
        run(
            JAVA / "java", "-cp", BUILD_TOOLS / "lib/d8.jar", "com.android.tools.r8.D8",
            "--debug" if args.debug else "--release", "--min-api", "28", "--lib", ANDROID_JAR,
            "--output", dex, *sorted(classes.rglob("*.class")),
        )
        with zipfile.ZipFile(unsigned, "a", compression=zipfile.ZIP_DEFLATED) as apk:
            for file in sorted(dex.glob("*.dex")):
                apk.write(file, file.name)
        aligned = work / "aligned.apk"
        signed = work / "signed.apk"
        run(BUILD_TOOLS / "zipalign", "-f", "4", unsigned, aligned)
        print("Signing with the local identity and verifying the APK.", flush=True)
        signer = (JAVA / "java", "-jar", BUILD_TOOLS / "lib/apksigner.jar")
        run(
            *signer, "sign", "--ks", KEY, "--ks-key-alias", alias,
            "--ks-pass", "env:S22I_STORE_PASSWORD", "--key-pass", "env:S22I_KEY_PASSWORD",
            "--v4-signing-enabled", "false", "--out", signed, aligned,
        )
        run(*signer, "verify", "--verbose", signed)
        run(BUILD_TOOLS / "zipalign", "-c", "4", signed)
        signed.replace(output)
    print(f"Built {output} ({output.stat().st_size:,} bytes)")
    print("This command builds only. It does not install or launch the app.")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, subprocess.SubprocessError) as error:
        print(f"Build failed: {error}", file=sys.stderr)
        sys.exit(1)
