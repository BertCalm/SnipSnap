# HALOBAR six voices and performance

Status: implementation and software validation in progress. New bell voices and
all new performance examples await the owner's listening. This does not promote
unheard recipes into the accepted factory bank.

Repository read: `cadcad2c26a020607df06c27a016b4781f73c2b5`. Existing APIs verified:
`Halobar.render`, `Restrike.take`, `Robin.apply`, `Velocity.atVelocity`,
`InstrumentStore`, `KeyHit`, `InstrumentEngine`, `OneNote.writePackage`,
`ChordStabs.stabs`, `modalStabs` and `borrowedStabs`.

The characteristic gesture remains **mallet contact → pitched body → dark
rhythmic answers → moving halo**. Tubular bells changes the body within HALOBAR.

## Voice and design

Tubular bells is the sixth voice, following vibraphone, marimba, xylophone,
glockenspiel and crotales. It spans MIDI 48–84, centered on MIDI 60. Its
root-led spectrum is a musical approximation, not a measured tube or a solver
for the virtual pitch of orchestral chimes. Ratios are
1 : 2 : 2.756 : 4 : 5.404 : 8.933; gains are
1, .28, .12, .10, .06, .03. Base T60 values are 9, 6, 4.5, 3, 2, 1 seconds.
Contact ranges from 3 ms to .4 ms before register compensation, with a .12 ms
floor. These are shape constants. The metal-bar ratios reuse `Modes`' sourced
family; the added octave/root weighting and all losses are design choices.

The initial stronger inharmonic weights pulled the house detector 32.81 cents
flat at MIDI 53. The lower weights retain the partial family while giving the
root precedence. This is a model revision before owner acceptance; it does not
change the preceding five sources.

Nearest siblings: TESSERA already mixes bars and tubes; TINES already offers FM
bells. Neither requires a new engine here: HALOBAR owns the bounded dub-return
gesture, and this is one more source voice using that return.

Three directions considered:

| Direction | Gesture, novelty, risk and cost |
|---|---|
| Strong, SPLICE a tube-inspired body into HALOBAR | Same clear mallet-to-return gesture, a lower long bell source, no solver, one voice plus performance integration. Chosen. |
| MUTATE the tube under changing material loss | Evolving bell body, overlaps TESSERA/THAW, pitch and macro risks, a separate model round. |
| INVERT the return to re-excite the bell | Feedback plays the body, overlaps resonator instruments, needs a new stability proof, multiple rounds. |

```
notes + touch + take identity
  → unit-area contact force + seeded strike noise @4x
  → independently damped modes @4x
  → dry body + bounded dark echo return @4x
  → downsample → linear motion + finite halo @44.1k
  → DC removal → house level → finite tail fade
```

Existing controls retain their contract: TUNE chooses a sounding note; TOUCH
shortens contact; RATE chooses the shared 72 BPM division; RETURN changes echo
gain and darkness; SPACE mixes dry/returns; MOTION moves the return, with a
motor only on vibraphone; HOLD lengthens finite decay. Pair and corner probes
remain the existing HALOBAR tests. HOLD never asserts a seamless loop.

## Native repeat strikes and saved keys

`Halobar.renderTake` preserves the old four-argument `render` entry point.
Take zero calls the reference renderer. Later takes vary contact by 0–14%,
mode weights by ±4%, the noise seed and the existing re-strike force law.
Frequencies and damping coefficients do not detune. Seed and take determine
the variation. Later takes bypass both reference caches; normal rendering
retains the bounded completed and prepared-stem caches.

`HalobarPadMaker` captures each minor-third root throughout the voice's authored
register. Each root has two native velocity levels (.42 and 1), three strikes
per level, finite samples and an adjustable key-up fade. The soft captures also
retain .6 of house output level. The patch, seed and release are saved beside
the package. All six PCM24 captures per root remain in the package.

MAKE INSTRUMENT exposes this path for every HALOBAR voice. The saved phone
sidecar opts into version 4; existing versions retain their format. `KeyHit`'s
shared cursor cycles matching samples independently for each key and touch
window. Both the phone player and the JVM reference player use it. Opening a
new phone instrument resets its cursor. Sample files, roots and finite note-off
behavior survive a package reload.

MPC layer Cycle and Velocity are separate play modes. A combined phone grid
exports its first take per velocity window to XPM and MPC 3 keygroups. A
single-window cycle can use ordinary layer Cycle. The six captures stay in the
phone sidecar/package. **Combined velocity × round robin in MPC keygroups is
open.** Existing drum-pad Slice Motion is a separate, corpus-derived scheme;
its WAV slice-map/hardware gate does not prove keygroup behavior. Never infer a
passing export gate from the presence of six WAVs.

Manual reference: [MPC Software User Guide, Keygroup Play Modes](https://cdn.inmusicbrands.com/akai/MM28M20/MPCSoftware-UserGuide-v2.8.pdf).
The Cycle/Velocity distinction is documented; combination behavior has not been
verified on hardware.

## Chords, kit and percussion gestures

`HalobarChords` accepts the existing chord tool's triads, sevenths, modal and
borrowed chords. It shifts a voicing together by octaves where possible. A wide
edge voicing that cannot fit by one shift revoices individual tones by octaves;
it preserves pitch classes rather than clamping notes. The playable keygroups
remain polyphonic. The audition uses the actual PCM24 package reloaded through
`InstrumentEngine`, including velocity selection, repeat strikes and note-off.

HALOBAR SIX STUDY adds a separate shelf starter: six dry/dub pairs followed by
four tubular-bell scale answers. The accepted three-voice starter and twelve
factory recipes remain unchanged. Each of the six voices has four starting
recipes available in MAKE and the CLI; the new bell banks remain provisional.

Authored roll and riser examples use saved keygroups: a finite accelerating roll
with alternating touches, and a rising minor pentatonic phrase. Individual
mallet voices remain finite. There are no picked/thumbed string voices, so
STRUM does not apply. HALOBAR is melodic percussion, not a drum-kit engine;
the genre-drum-kit gate does not apply.

## Reproduce

```
./gradlew --no-daemon :synth:generateHalobarTubularAudition
./gradlew --no-daemon :shell:generateHalobarPerformance
./gradlew --no-daemon test
```

The performance generator writes six keygroup packages, a ZIP, explicit event
scores and performances of the exported samples. Audition timing reports fresh
renders and prepared SPACE edits separately; desktop figures do not establish
phone latency. Listening, device playback and MPC loading remain separate gates.

## Why it is built this way

- The sixth body shares HALOBAR's existing return so its musical controls stay familiar.
- A strong root makes lower bells usable for chords and sampled keyboard roots.
- Fresh mallet contact gives repeat strikes variety without detuning the whole note.
- Finite keygroups keep the strike and natural decay; key-up adds a controlled fade.
- MPC exports retain correct touch selection while the combined repeat format remains unproven.

## As-built evidence

The integrated source `057e1bed7ef6f14be3c062440f8fadc2ac1e1b5b` passed the complete nine-module
JVM CI gate (eight modules plus all three synthesis shards): 9,343 tests, zero failures, errors or skips. Android
assembly and device-test compilation passed; the development APK's v2 signature
verified. The six host-native CTest targets passed. Device instrumentation was
compiled, not executed.

All 335 earlier Float32 reference cases and 73 tubular audition cases replay
exactly on the same runtime. Six saved keygroups contain 426 distinct PCM24
captures over 71 roots; sixteen reference performances reload those exports.
The hosted chapter includes 68 lossless FLAC encodings checked against native
PCM16; comparisons reuse earlier clips. Static links and archives passed, while
browser QA was unavailable in the managed environment.

Ten tubular pitch cases measured a maximum absolute error of 2.759 cents.
Fresh desktop rendering measured median/p95 329.4/338.0 ms; prepared SPACE edits
13.6/15.7 ms. Fresh rendering exceeds the 50 ms target. These measurements do
not establish phone performance. The first prototype's heavier inharmonic
weights pulled the pitch detector 32.81 cents flat; the authored weights and
exact octave were corrected before these fixtures were captured.

[The validation receipt](../reference/halobar/six-native-validation.json) and
[Float32 fixture](../reference/halobar/tubular-native-parity.tsv) preserve the
software evidence. [Audition](https://halobar-native-audition.bertcalm.chatgpt.site/tubular-bells/)
and [saved-key performances](https://halobar-native-audition.bertcalm.chatgpt.site/tubular-bells/performance/)
are published with the existing owner-private audience.

The [MPC diagnostic bench](https://halobar-native-audition.bertcalm.chatgpt.site/tubular-bells/performance/HALOBAR-MPC-Velocity-RR-Bench.zip)
provides three programs, three MIDI scores and software expectations. Its
velocity-only and cycle-only controls isolate the combined candidate. The
production MPC/MIDI readers accept the files, but the candidate is not enabled
for production export. Physical firmware results are still needed. Owner
listening, phone playback and MPC loading remain open; engine acceptance is
in progress.
