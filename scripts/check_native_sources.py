#!/usr/bin/env python3
"""The production library and the host harness name engine sources separately.

app/src/main/cpp/CMakeLists.txt is what :app:assembleDebug compiles.
app/src/main/cpp/test/CMakeLists.txt is what native-tests compiles, and it
lists those sources again. A .cpp added to only one of them drops out of
either the APK or the host suite with the other staying green.

Test-only files (engine_tests.cpp, the stubs) are allowed in the harness.
Every production .cpp must appear in the harness.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PROD = ROOT / "app/src/main/cpp/CMakeLists.txt"
TEST = ROOT / "app/src/main/cpp/test/CMakeLists.txt"


def block(text, pattern, label):
    match = re.search(pattern, text, re.S)
    if not match:
        sys.exit(f"check_native_sources: could not find {label}")
    names = re.findall(r"[\w./\\-]+\.cpp", match.group(1))
    return {name.replace("\\", "/").rsplit("/", 1)[-1] for name in names}


def main():
    prod_src = block(
        PROD.read_text(),
        r"add_library\s*\(\s*snipsnap_surface\s+SHARED(.*?)\n\)",
        "add_library(snipsnap_surface)",
    )
    test_src = block(
        TEST.read_text(),
        r"add_executable\s*\(\s*engine_tests(.*?)\n\)",
        "add_executable(engine_tests)",
    )
    missing = sorted(prod_src - test_src)
    if missing:
        sys.exit(
            "check_native_sources: host harness omits production sources: "
            + ", ".join(missing)
        )
    if not prod_src:
        sys.exit("check_native_sources: production library lists no .cpp files")
    print("native sources agree: " + ", ".join(sorted(prod_src)))


if __name__ == "__main__":
    main()
