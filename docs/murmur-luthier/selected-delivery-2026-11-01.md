# Selected Murmur sounds in the app and MPC kit

The 24 October export selected KEY MOTION 0, .5, .75 and 1. After the low-register
CHOIR loop was retained, the owner described both CHOIR examples as windy. The
28 October comparison reduced BREATH from .30 to .10 at C2/C3 while retaining
all other controls and the complete saved configuration. The owner's 31 October
feedback was "Better". It was general feedback, without separate per-note
verdicts. The subsequent instruction was to add the preferred settings to the
app's presets, publish a fresh APK, and generate an updated MPC kit.

`MurmurPresets.selected()` supplies those six exact saved recipes. They are
appended to the shared factory roster: 42 presets total, with the original 36
retained in order. `MurmurPresets.original()` keeps the historical audition and
its acceptance tests tied to the original bank. The new settings retain saved
render version 3; existing presets, kits and engine defaults do not change.
The complete heard patch JSON and twelve WAV hashes are retained as independent
test fixtures under `synth/src/test/resources/murmur-selected/`.

The FRESH TAPE menu adds **MURMUR SELECTED** (`murmur-selected`). The original
MURMUR starter remains available. The new sixteen-pad layout is:

| Pads | Contents |
| --- | --- |
| A01–A06 | KEY, MOTION .5, C3/Eb3/F3/G3/Bb3/C4 |
| A07–A10 | Four selected KEY settings at C4: MOTION 0/.5/.75/1 |
| A11–A14 | Original CLOSED CIRCUIT, SHORT ANSWER, OPEN THROAT, COPPER HUM |
| A15–A16 | Selected lower-air CHOIR at C2/C3, BREATH .10 |

Why it is built this way:

- App presets and the starter draw from the same six saved definitions, so
  the chosen macros, note, velocity and render configuration travel together.
- Appending the choices keeps existing preset and kit identities stable.
- Six melodic keys leave room for all six voice roles and the six heard cases.
- Both CHOIR sources retain their seamless held cycles. The drum-program pads
  play once with a saved 8 ms terminal fade, preserving the source recipe while
  avoiding an abrupt end. This is not an indefinitely sustaining MPC keygroup.

Rebuild the new MPC 2/MPC 3 package with:

```sh
./gradlew --no-daemon :synth:generateMurmurSelectedKit
```

The generator uses `PadRecipe.render`, checks preflight and both exported
programs through read-back, verifies that every exported WAV matches staging,
and saves per-pad recipes, a manifest, file hashes and the ZIP. The same starter
is available in the app. The phone build is an installable debug APK from CI,
with its source and artifact identity recorded on the published device page.

Verification scope: exact saved-recipe identity and all twelve raw/matched WAV
hashes, independent CHOIR continuation seams, the two saved delivery fades,
original bank and starter fixtures, shared preset discovery, and real-touch
selection of the new starter in the Android UI test. Full-suite, Android and
emulator results are reported from their completed runs. No physical phone or
MPC playback is claimed; those observations must come from the owner's devices.
