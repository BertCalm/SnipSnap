# Crease C4 shared-cache result

Luthier REVIEW, 2026-11-06. Baseline `7dd9d090e`; accepted circuit snapshot
`907a6638699363afc2179df594d8a293ef822b86`.

The gesture stays press the actual onset, hold its motion, release anywhere,
follow the unfolding to silence. This experiment changes the representation
of recorded endings, not the patch or circuit. The owner reported that the
previous phone build works. This compact build needs its own listening pass.

## Measured result

The locked 32-component representation passes **3,251/3,251** comparisons:
all 1,628 retained development cases and 1,623 fresh positions. The fresh
positions were published and the quantized data hashes committed in
`259778f91` before capturing any new exact continuation. The retained Kotlin
JARs match every hash in the accepted capture receipt; their held trajectory
is bit-exact against the accepted capture. Public-prefix and complete-state
fork errors are zero; deterministic repeats are checked during capture.

| Quantity | Accepted full-tail cache | Shared cache |
|---|---:|---:|
| PCM | 33,547,698 bytes | 4,138,366 bytes |
| Accounted resident cache | at least 31.994 MiB PCM | 4,188,582 bytes / 3.995 MiB |
| Compressed audio payload | 1,692,344 bytes | 2,122,394 bytes |
| Existing cases passed | 1,628/1,628 | 1,628/1,628 |
| Additional fresh cases | — | 1,623/1,623 |
| Maximum normalized error RMS | .008489534 | .009128788 |
| Maximum absolute peak error | .022190716 | .024206594 |
| Maximum harmonic TV | .00012148 | .000249064 |

All original error limits remain: normalized RMS .031623, peak .025,
harmonic TV .02, added key-up step .001, finite unclipped output. The new
resident goal is 8 MiB; delivery remains capped at 12 MiB. Cache accounting
includes held/basis PCM, Float32 weights, coordinate/offset tables and an
8 KiB allowance covering the fixed native Reader/voice objects. The host
checks the allowance against actual C++ object sizes. This is not whole-app
RSS or JavaScript object/GC accounting. Android temporarily holds packed
input and a ShortArray while JNI copies it, then releases those loader
references. The browser also temporarily holds bounded decoded byte planes.
No decoded Float audio mirror or 309 reconstructed tails are retained live.

The fixed development sweep failed at 4, 8, 12, 16 and 24 components. The
24-component candidate failed four peak limits (maximum .030712985); it is
not promoted. Thirty-two was the first passing candidate, so 48 and 64 were
not selected or tuned on holdouts. Full per-position results are retained
in the machine report and downloadable reference archive.

## Representation and runtime

The held Short PCM is unchanged. Each release capture is shifted by an
integer nearest its carrier phase, with 89 samples of padding at each end.
A mean plus 31 principal components are fitted only to the 309 existing
cache tails. Each shared waveform is PCM16; its scale is folded into the
per-capture Float32 mixing coefficients. The locked component and coefficient
hashes are in `2026-11-06-crease-shared-cache-candidate.json`.

`crease-shared-pcm-v1` retains the existing gesture/loop/release metadata.
The lossless container holds `[held PCM, frame-major basis PCM]`. `shared`
contains component count, padded frame count, integer offsets, row-major
coefficients, coefficient hash, storage order and resident byte account.
Decoders validate dimensions and byte limits before allocation, validate
finite bounded coefficients and their Float32 hash, and validate compressed
and PCM hashes off the audio thread. JNI has a separate `createShared`
entry point; the full-tail format and entry point remain supported.

At each requested tail sample, the reader interpolates four basis samples
and takes the stored weighted sum. It never reconstructs a whole tail at
key-up. Carrier shifts, neighbouring-tail interpolation, the 221-frame
blend, gain .12, Float fade, eight fixed native voices, gesture tokens,
5 ms retirement, stop and lifecycle behavior remain unchanged.

Actual streaming JS and C++ readers match the independent offline
reconstruction oracle at all 3,251 cases with **zero Float32 sample error**.
Native output also matches 24 streaming JS references at 48 kHz in both
ending modes. Actual parity processing and synthetic shared-reader gesture
checks observe zero callback allocations/frees. Six native executables pass.
Host native release processing averaged **13.24–23.52 ms per audio second**
across two verification runs; Node's eight simultaneous unfolding test took
**180.48 ms per audio second**
with a finite non-clipping bus. The first native probe is preserved separately;
the second overlapped host memory pressure. These are loaded-cloud host measurements,
not phone callback deadlines, speaker latency or a physical stress result.

## Reproduction and delivery

Retain `testkit/crease-phone-release-r2` as immutable input. The experiment
uses a new ignored `testkit/crease-shared-cache-r1` directory:

```sh
python3 scripts/crease-release/shared_cache.py --output testkit/crease-shared-cache-r1
# Commit the selected hashes and fresh positions before the next command.
python3 scripts/crease-release/shared_cache.py --output testkit/crease-shared-cache-r1 --validate
python3 scripts/crease-release/shared_export.py
node scripts/crease-release/verify-reader.mjs testkit/crease-shared-cache-r1
python3 scripts/crease-release/native_fixtures.py testkit/crease-shared-cache-r1
node scripts/crease-release/verify-native.mjs testkit/crease-shared-cache-r1
python3 scripts/crease-release/verify_phone.py testkit/crease-shared-cache-r1
python3 scripts/crease-release/package_playable.py testkit/crease-shared-cache-r1
```

Native verification requires the configured host CMake build. Fresh validation
explicitly refuses different accepted Kotlin binaries rather than labeling a
new circuit as the original. Generation/capture scripts are research tools;
only the small PCM transport runs live. The phone admission tool checks the
current native source receipt, coefficient identity and complete manifest hash
as well as accuracy. Its eight admission/ownership tests also run under Python
optimization, so refusal does not depend on assertions. The JS oracle checker
streams one fixture at a time rather than retaining a multi-hundred-MiB file.

The isolated study excludes unused Confluence factory audio from its Java
resources. Normal sampler variants retain those resources. No synth, normal
instrument renderer or MPC export equation changes. A keyboard range and
main-app instrument landing remain future work after this compact study.

## Phone checks

Six cache decoder tests and eight build-admission tests pass, including the
Python optimized run. Isolated removal of the manifest, coefficient and
8 MiB guards makes the corresponding tests fail with guard-specific messages.
Four unchanged Android 10 emulator gesture tests pass: physical touch/Stop,
physical keyboard ownership, background/resume and finite accessibility action.
The first run passed three tests; keyboard injection was blocked by a confirmed
System UI ANR dialog after the cloud worker reached its memory limit. Recovering
the emulator and rerunning all four passed without weakening any assertion.

The R8 optimized x86 APK passes actual touch/hold/release, ending selection,
startup and background/resume smoke checks. Native Android compilation uses
the existing `-O2` flag. A paired emulator measurement uses byte-identical DEX
and native libraries, changing only the cache: native heap allocated falls
from 51,059 KiB (49.9 MiB) to 22,129 KiB (21.6 MiB), a 28.25 MiB reduction.
This is one emulator pair, including UI/runtime native allocations. The
primitive shared cache itself accounts for 3.995 MiB.

The freshly packaged arm64 APK is 9,683,532 bytes, SHA-256
`7e2bfef72c27e9b6402bdabff2236f37d89373bd7cfa37863195ab292636b9ac`.
Its bundled manifest and compressed cache match the verified artifacts. The
signature uses the same certificate as the accepted phone study, preserving
its separate `com.snipsnap.app.creasestudy` identity. The application source
snapshot is `3da84771f`; subsequent changes only package browser code and
record validation. The loader keeps the full-tail format for comparison.

The audition archives the accepted cache/manifest/measurements and five cached
FLAC references. Earlier studies and instrument audio remain intact. One old
raw JSON measurement is stored as lossless gzip to fit the hosting archive
limit; its decoded byte hash is checked. Browser decoder/worklet bundles are
generated from canonical sources, with versioned module URLs and fresh
manifest/payload requests for returning visitors.

## Why it is built this way

1. Integer alignment groups similar carrier shapes without an extra offline
   fractional resampling step, preserving the reader's existing interpolation.
2. Shared waveforms cut resident storage by about 87.5%; their additional
   arithmetic and slightly larger compressed download are explicit trades.
3. Keeping the held PCM exact protects the accepted onset and sustain. The
   measured approximation error is confined to the ending representation.
4. Freezing the first passing rank before new captures prevents the fresh
   positions becoming another set of tuning targets.
5. The one-key phone study proves memory and response before multiplying
   cache cost across pitches and velocities.

Ledger: **verified** source/binary identities, held identity, reader parity,
bounds and native lifecycle checks; **measured** Python approximation errors,
primitive cache storage and host processing cost; **proposed** physical-device
suitability; **heard** only the owner's earlier study, not this compact build.
