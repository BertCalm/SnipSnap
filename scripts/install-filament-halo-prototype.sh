#!/usr/bin/env bash
# Development admission helper. Streams only the two named prototype programs;
# refuses existing destinations and never installs/uninstalls an app.
set -euo pipefail

usage() {
    cat <<'TEXT'
Usage: bash install-filament-halo-prototype.sh [--check-only] BUNDLE_DIR [PACKAGE_ID]

Extract curl-release-prototype.zip first. Install a compatible debuggable build,
open it once, then close it before importing. The default package is
com.snipsnap.app.brassgrow. Enable USB debugging and authorize ADB on the phone.
--check-only verifies the local bundle without contacting or changing a device.
Requires bash, python3, tar and (for device import) adb.
TEXT
}

if [[ "${1:-}" == --help || "${1:-}" == -h ]]; then usage; exit 0; fi
check_only=false
if [[ "${1:-}" == --check-only ]]; then check_only=true; shift; fi
if [[ $# -lt 1 || $# -gt 2 ]]; then usage; exit 2; fi
bundle_dir=$(cd "$1" && pwd)
package_id=${2:-com.snipsnap.app.brassgrow}
if [[ ! "$package_id" =~ ^[A-Za-z][A-Za-z0-9_.]+$ ]]; then
    echo 'Invalid Android package ID.' >&2; exit 2
fi

python3 - "$bundle_dir" <<'PY'
import hashlib, json, sys
from pathlib import Path
root = Path(sys.argv[1]).resolve()
manifest = json.loads((root / 'manifest.json').read_text())
assert manifest['prototypeVersion'] == 1 and manifest['voice'] == 'CURL'
assert manifest['workingTreeDirty'] is False, 'Use a clean-source audition bundle'
for name, expected in manifest['sha256'].items():
    path = (root / name).resolve()
    assert root in path.parents, 'Manifest path leaves bundle'
    assert hashlib.sha256(path.read_bytes()).hexdigest() == expected, name
for folder, name, version in [('A', 'Curl Shared Release', 2), ('B', 'Curl Halo Release', 3)]:
    sidecar = json.loads((root / folder / (name + '.instrument.json')).read_text())
    assert sidecar['version'] == version
    assert (root / folder / (name + '.xty')).is_file()
    assert (root / folder / (name + '_[TrackData]') / (name + '.xpm')).is_file()
print('Bundle hashes and both prototype programs verified.')
PY
if $check_only; then exit 0; fi

command -v adb >/dev/null || { echo 'adb is not on PATH.' >&2; exit 2; }
adb get-state >/dev/null
if ! adb shell "run-as $package_id test -d files"; then
    echo 'Open a compatible debuggable SnipSnap build once, close it, then retry.' >&2
    exit 1
fi

names=('Curl Shared Release' 'Curl Halo Release')
folders=('A' 'B')
for name in "${names[@]}"; do
    for suffix in '.instrument.json' '.xty' '_[TrackData]'; do
        if adb shell "run-as $package_id test -e 'files/Kits/Instruments/$name$suffix'"; then
            echo "Refusing to replace existing prototype: $name$suffix" >&2
            exit 1
        fi
    done
done

tar_items=()
for i in 0 1; do
    tar_items+=(-C "$bundle_dir/${folders[$i]}" "${names[$i]}.instrument.json"
        "${names[$i]}.xty" "${names[$i]}_[TrackData]")
done
adb shell "run-as $package_id mkdir -p files/Kits/Instruments"
tar -cf - "${tar_items[@]}" | adb shell -T "run-as $package_id tar -xf - -C files/Kits/Instruments"
echo 'Imported both prototypes. Open MAKE > INSTRUMENTS and play C4.'
echo 'Phone listening, timing and overload acceptance still need to be checked.'
