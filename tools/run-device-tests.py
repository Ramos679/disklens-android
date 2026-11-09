"""Install debug test APKs on a test device and fail CI on instrumentation failures."""

import os
from pathlib import Path
import re
import shutil
import subprocess
import sys


def main():
    project = Path(__file__).resolve().parents[1]
    adb = shutil.which("adb")
    if not adb:
        sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        if sdk:
            adb = str(Path(sdk) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb"))
    if not adb or not Path(adb).is_file():
        raise RuntimeError("adb was not found. Set ANDROID_HOME or add platform-tools to PATH.")

    for apk in (
        "app/build/outputs/apk/debug/app-debug.apk",
        "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
    ):
        subprocess.run([adb, "install", "-r", "-t", str(project / apk)], check=True, timeout=120)

    result = subprocess.run(
        [adb, "shell", "am", "instrument", "-w", "-r", "app.disklens.test/app.disklens.StorageInstrumentation"],
        capture_output=True,
        text=True,
        timeout=180,
        check=True,
    )
    print(result.stdout)
    if result.stderr:
        print(result.stderr, file=sys.stderr)
    # adb can exit successfully even when the Android instrumentation reports failure.
    if not re.search(r"INSTRUMENTATION_CODE:\s*-1\b", result.stdout) or "PASS: " not in result.stdout:
        raise RuntimeError("Android instrumentation did not report a successful test run.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, subprocess.SubprocessError) as error:
        print(f"Device tests failed: {error}", file=sys.stderr)
        sys.exit(1)
