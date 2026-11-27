#!/usr/bin/env python3
from pathlib import Path
import subprocess
import tempfile
from toolchain import java_bin

root = Path(__file__).resolve().parent.parent
java_bin = java_bin()
with tempfile.TemporaryDirectory(prefix="s22i-protocol-") as output:
    subprocess.run([
        str(java_bin / "javac"), "--release", "8", "-d", output,
        str(root / "app/src/dev/s22i/console/IdleTimer.java"),
        str(root / "tests/java/dev/s22i/console/IdleTimerTest.java"),
        str(root / "app/src/dev/s22i/console/PrivateFiles.java"),
        str(root / "app/src/dev/s22i/console/ProtocolLog.java"),
        str(root / "tests/java/dev/s22i/console/PrivacyTest.java"),
        str(root / "app/src/dev/s22i/console/RideStats.java"),
        str(root / "tests/java/dev/s22i/console/RideStatsTest.java"),
        str(root / "app/src/dev/s22i/console/RideHistory.java"),
        str(root / "tests/java/dev/s22i/console/RideHistoryTest.java"),
        str(root / "app/src/dev/s22i/console/FitPro.java"),
        str(root / "app/src/dev/s22i/console/FitProAuthentication.java"),
        str(root / "app/src/dev/s22i/console/RideInitialization.java"),
        str(root / "app/src/dev/s22i/console/BikeControlPolicy.java"),
        str(root / "app/src/dev/s22i/console/ControllerDiagnostics.java"),
        str(root / "tests/java/dev/s22i/console/FitProTest.java"),
        str(root / "tests/java/dev/s22i/console/FitProAuthenticationTest.java"),
        str(root / "tests/java/dev/s22i/console/RideInitializationTest.java"),
        str(root / "tests/java/dev/s22i/console/BikeControlPolicyTest.java"),
        str(root / "tests/java/dev/s22i/console/ControllerDiagnosticsTest.java"),
    ], check=True, timeout=30)
    subprocess.run([str(java_bin / "java"), "-cp", output, "dev.s22i.console.FitProTest"], check=True, timeout=30)
    subprocess.run([str(java_bin / "java"), "-cp", output, "dev.s22i.console.FitProAuthenticationTest"], check=True, timeout=30)
    subprocess.run([str(java_bin / "java"), "-cp", output, "dev.s22i.console.RideInitializationTest"], check=True, timeout=30)
    subprocess.run([str(java_bin / "java"), "-cp", output, "dev.s22i.console.BikeControlPolicyTest"], check=True, timeout=30)
    subprocess.run([str(java_bin / "java"), "-cp", output, "dev.s22i.console.ControllerDiagnosticsTest"], check=True, timeout=30)

    subprocess.run([str(java_bin / "java"), "-cp", output, "dev.s22i.console.RideHistoryTest"], check=True, timeout=30)

    subprocess.run([str(java_bin / "java"), "-cp", output, "dev.s22i.console.RideStatsTest"], check=True, timeout=30)
    subprocess.run([str(java_bin / "java"), "-cp", output, "dev.s22i.console.IdleTimerTest"], check=True, timeout=30)
    subprocess.run([str(java_bin / "java"), "-cp", output, "dev.s22i.console.PrivacyTest"], check=True, timeout=30)
