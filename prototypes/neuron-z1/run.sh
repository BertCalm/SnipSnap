#!/usr/bin/env bash
set -euo pipefail
prototype_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repository_dir="$(cd -- "$prototype_dir/../.." && pwd)"
output_dir="$repository_dir/build/neuron-z1"
mkdir -p "$output_dir/checks"
cd "$repository_dir"

# Keep both invocations serial. The first creates original regression fixtures;
# pinned hashes then establish that those fixtures match the published audio.
bash ./gradlew --no-daemon --max-workers=3 -I "$prototype_dir/prototype.init.gradle" \
  :synth:test --tests com.snipsnap.synth.MorphProofTest
cp synth/build/test-results/test/TEST-com.snipsnap.synth.MorphProofTest.xml "$output_dir/checks/"
python3 "$prototype_dir/verify.py" original
bash ./gradlew --no-daemon --max-workers=3 -I "$prototype_dir/prototype.init.gradle" \
  :synth:test --tests com.snipsnap.synth.MorphSpaceRevisionTest \
  --tests com.snipsnap.synth.StringsTest
cp synth/build/test-results/test/TEST-com.snipsnap.synth.MorphSpaceRevisionTest.xml "$output_dir/checks/"
cp synth/build/test-results/test/TEST-com.snipsnap.synth.StringsTest.xml "$output_dir/checks/"
python3 "$prototype_dir/verify.py" all
