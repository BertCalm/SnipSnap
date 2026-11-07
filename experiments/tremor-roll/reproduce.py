#!/usr/bin/env python3
"""Compile the frozen listening prototypes and reproduce the R3 comparison.

This opt-in experiment is outside the app/Gradle source sets. It uses JDK17 and
the Kotlin compiler already downloaded by the pinned baseline's Gradle build.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess


HERE = Path(__file__).resolve().parent


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(command: list[str], *, cwd: Path, env: dict[str, str]) -> None:
    print("Running:", " ".join(command), flush=True)
    subprocess.run(command, cwd=cwd, env=env, check=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, default=HERE.parents[1],
                        help="SnipSnap checkout with the pinned runtime sources")
    parser.add_argument("--output", type=Path, default=HERE / "build/reproduced")
    parser.add_argument("--java-home", type=Path,
                        help="JDK17 directory; otherwise use JAVA_HOME or java on PATH")
    parser.add_argument("--gradle-user-home", type=Path,
                        help="Gradle cache directory; otherwise GRADLE_USER_HOME or ~/.gradle")
    parser.add_argument("--skip-baseline-build", action="store_true",
                        help="Use existing baseline module JARs after source validation")
    args = parser.parse_args()
    baseline, output = args.baseline.resolve(), args.output.resolve()
    pin = json.loads((HERE / "baseline-sources.json").read_text())
    actual = {str(p.relative_to(baseline)): sha(p)
              for module in pin["modules"]
              for p in (baseline / module / "src/main/kotlin").rglob("*.kt")}
    if actual != pin["kotlinMainSourceSha256"]:
        raise SystemExit("Baseline runtime sources differ. Use a detached checkout of "
                         + pin["sourceCommit"] + " and pass --baseline PATH.")

    java_home = args.java_home
    if java_home is None and os.environ.get("JAVA_HOME"):
        java_home = Path(os.environ["JAVA_HOME"])
    if java_home is None:
        found = shutil.which("java")
        if found:
            java_home = Path(found).resolve().parent.parent
    if java_home is None:
        raise SystemExit("JDK17 is required; set JAVA_HOME or --java-home.")
    java_home = java_home.resolve()
    suffix = ".exe" if os.name == "nt" else ""
    java, javac = java_home / ("bin/java" + suffix), java_home / ("bin/javac" + suffix)
    version = subprocess.run([str(java), "-version"], text=True, capture_output=True, check=True)
    if not re.search(r'version "17(?:\.|\")', version.stderr + version.stdout):
        raise SystemExit("Use JDK17 for this archived listening reference.")
    if not javac.is_file():
        raise SystemExit("A full JDK, including javac, is required.")

    cache_home = (args.gradle_user_home or Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))).resolve()
    env = dict(os.environ)
    env.update(JAVA_HOME=str(java_home), GRADLE_USER_HOME=str(cache_home),
               PATH=str(java_home / "bin") + os.pathsep + env.get("PATH", ""))
    # The baseline's settings select the JVM-only graph when no local SDK is configured.
    env.pop("ANDROID_HOME", None)
    env.pop("ANDROID_SDK_ROOT", None)
    if "-XX:ActiveProcessorCount=" not in env.get("JAVA_TOOL_OPTIONS", ""):
        env["JAVA_TOOL_OPTIONS"] = (env.get("JAVA_TOOL_OPTIONS", "") + " -XX:ActiveProcessorCount=2").strip()
    if not args.skip_baseline_build:
        wrapper = baseline / ("gradlew.bat" if os.name == "nt" else "gradlew")
        run([str(wrapper), "--no-daemon", "--max-workers=2",
             "-Pkotlin.compiler.execution.strategy=in-process", ":synth:jar"], cwd=baseline, env=env)

    jars = [baseline / module / "build/libs" / (module + ".jar") for module in pin["modules"]]
    if any(not p.is_file() for p in jars):
        raise SystemExit("Missing baseline module JARs; run the baseline :synth:jar task.")
    cache = cache_home / "caches/modules-2/files-2.1"

    def dependency(group: str, artifact: str, version: str) -> Path:
        matches = sorted((cache / group / artifact / version).glob("*/*.jar"))
        if len(matches) != 1:
            raise SystemExit(f"Expected one cached {group}:{artifact}:{version} JAR, found {len(matches)}. "
                             "Warm the pinned baseline with :synth:jar first.")
        return matches[0]

    coordinates = [
        ("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.0.21"),
        ("org.jetbrains.kotlin", "kotlin-stdlib", "2.0.21"),
        ("org.jetbrains.kotlin", "kotlin-script-runtime", "2.0.21"),
        ("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
        ("org.jetbrains.intellij.deps", "trove4j", "1.0.20200330"),
        ("org.jetbrains", "annotations", "13.0"),
        ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.6.4"),
    ]
    compiler_jars = [dependency(*coordinate) for coordinate in coordinates]
    stdlib = compiler_jars[1]
    bundles = json.loads((HERE / "source-bundles.json").read_text())
    sources = []
    for revision, bundle in bundles.items():
        for entry in bundle["files"]:
            path = HERE / revision / entry["file"]
            if sha(path) != entry["sha256"]:
                raise SystemExit("Archived prototype source changed: " + str(path))
            sources.append(path)
        digest = hashlib.sha256(json.dumps(bundle["files"], separators=(",", ":")).encode()).hexdigest()
        if digest != bundle["candidateSha256"]:
            raise SystemExit("Source bundle record changed: " + revision)

    classes = output / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    compile_cp = os.pathsep.join(str(p) for p in jars + [stdlib])
    compiler_cp = os.pathsep.join(str(p) for p in compiler_jars)
    synth_jar = baseline / "synth/build/libs/synth.jar"
    run([str(java), "-XX:ActiveProcessorCount=2", "-Xmx1g", "-cp", compiler_cp,
         "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
         "-module-name", "synth", "-classpath", compile_cp,
         "-Xfriend-paths=" + str(synth_jar), "-jvm-target", "17", "-d", str(classes),
         *map(str, sources)], cwd=baseline, env=env)
    runtime_cp = str(classes) + os.pathsep + compile_cp
    run([str(javac), "-cp", runtime_cp, "-d", str(classes), str(HERE / "RollLooseHarness.java")],
        cwd=baseline, env=env)
    rendered = output / "rendered"
    run([str(java), "-XX:ActiveProcessorCount=2", "-Xmx2g", "-cp", runtime_cp,
         "RollLooseHarness", str(rendered), pin["sourceCommit"], bundles["r3"]["candidateSha256"]],
        cwd=baseline, env=env)
    (output / "reproduction.json").write_text(json.dumps({
        "baselineSourceCommit": pin["sourceCommit"], "candidateBundle": bundles["r3"]["candidateSha256"],
        "java": (version.stderr + version.stdout).strip(), "kotlinCompiler": "2.0.21",
        "baselineSourceFilesVerified": len(actual), "candidateSourcesVerified": len(sources),
        "renderDirectory": str(rendered),
    }, indent=2) + "\n")
    print("Reproduced audio:", rendered)
    print("Verify with: python3", HERE / "verify.py", "--render-dir", rendered)


if __name__ == "__main__":
    main()
