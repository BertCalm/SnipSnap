#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
KOTLINC_BIN="${KOTLINC_BIN:-kotlinc}"
mkdir -p build
"$KOTLINC_BIN" src/ConfluenceShim.kt src/ConfluenceVoice.kt src/IndependentSinger.kt -jvm-target 17 -include-runtime -d build/renderer.jar
javac -cp build/renderer.jar -d build src/TwoSingerAudition.java
python3 - <<'PY'
import json,subprocess
from pathlib import Path
sources=json.loads(Path('public-sources.json').read_text())['sources']
a,b=sources
subprocess.run(['java','-cp','build:build/renderer.jar','TwoSingerAudition','.',a['relativePath'],b['relativePath'],str(a['qualification']['medianHz']),str(b['qualification']['medianHz'])],check=True)
PY
python3 finalize.py
