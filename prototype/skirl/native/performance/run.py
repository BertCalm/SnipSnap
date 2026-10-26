#!/usr/bin/env python3
"""Compare archived finite Skirl replay against an optimized JVM classpath.

The classpaths may contain directories and jar wildcards. Build each source
revision separately before running; this script never builds or changes them.
"""
import argparse
import glob
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

HERE = Path(__file__).resolve().parent


def expanded_classpath(value):
    entries = []
    for entry in value.split(os.pathsep):
        matches = sorted(glob.glob(entry))
        if not matches:
            raise ValueError(f"Classpath entry does not exist: {entry}")
        entries.extend(str(Path(match).resolve()) for match in matches)
    return os.pathsep.join(entries)


def run_java(classpath, classes, name, arguments, destination):
    command = ["java", "-Xmx2g", "-cp", str(classes) + os.pathsep + classpath, name, *arguments]
    with destination.open("w") as stream:
        subprocess.run(command, stdout=stream, check=True)


def benchmark(path):
    text = path.read_text()
    voices = {}
    for line in text.splitlines():
        match = re.fullmatch(r"(ROLL|WHIRR) millis=(\[.*\]) median=([\d.]+) "
                             r"preparation=([\d.]+) synthesis=([\d.]+) output=([\d.]+)", line)
        if match:
            voices[match[1]] = {
                "warmMillis": json.loads(match[2]), "warmMedianMillis": float(match[3]),
                "separateInspectMillis": dict(zip(("preparation", "synthesis", "output"),
                                                   map(float, match.group(4, 5, 6)))),
                "warmTargetMillis": 50, "warmTargetMet": float(match[3]) <= 50,
            }
    if len(voices) != 2:
        raise ValueError(f"Incomplete benchmark output: {path}")
    return {"configuration": text.splitlines()[0], "voices": voices}


def parity(path):
    rows = {}
    for line in path.read_text().splitlines():
        case, frames, production, raw, diagnostics = line.split("\t", 4)
        if case in rows:
            raise ValueError(f"Duplicate parity case: {case}")
        rows[case] = {
            "frames": int(frames), "productionFloatSha256": production,
            "prelevelFloatSha256": raw,
            "acousticDiagnosticsSha256": hashlib.sha256(diagnostics.encode()).hexdigest(),
        }
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-classpath", required=True)
    parser.add_argument("--candidate-classpath", required=True)
    parser.add_argument("--output", type=Path, default=Path("testkit/skirl-performance"))
    parser.add_argument("--repeats", type=int, default=15)
    args = parser.parse_args()
    if args.repeats < 1:
        parser.error("--repeats must be positive")
    output = args.output.resolve()
    classes = output / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    baseline = expanded_classpath(args.baseline_classpath)
    candidate = expanded_classpath(args.candidate_classpath)
    subprocess.run(["java", "com.sun.tools.javac.Main", "-cp", baseline, "-d", str(classes),
                    *(str(HERE / name) for name in ("BenchSkirl.java", "ParitySkirl.java",
                                                    "BenchResampler.java"))], check=True)
    results = {}
    for label, classpath in (("baseline", baseline), ("candidate", candidate)):
        print(f"Rendering {label} benchmark and 106 finite replay cases", flush=True)
        run_java(classpath, classes, "BenchSkirl", [str(args.repeats)], output / f"{label}-benchmark.txt")
        run_java(classpath, classes, "ParitySkirl", [str(HERE / "cases.tsv")], output / f"{label}-parity.tsv")
        results[label] = {"benchmark": benchmark(output / f"{label}-benchmark.txt"),
                          "cases": parity(output / f"{label}-parity.tsv")}
    expected = {line.split("\t")[0] for line in (HERE / "cases.tsv").read_text().splitlines()}
    complete = all(set(result["cases"]) == expected for result in results.values())
    results["caseCount"] = len(expected)
    results["allCasesPresent"] = complete
    results["exactFiniteReplay"] = complete and results["baseline"]["cases"] == results["candidate"]["cases"]
    results["limitations"] = ("Sequential JVM runs on the current machine; no Android claim. "
        "Model 1 is requested explicitly when supported. Five JVM warmup renders, then warm-cache "
        "timings; inspection is a separate diagnostic render. No cold-process latency claim.")
    (output / "report.json").write_text(json.dumps(results, indent=2) + "\n")
    print(f"Exact finite replay: {results['exactFiniteReplay']}; report: {output / 'report.json'}", flush=True)
    if not results["exactFiniteReplay"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
