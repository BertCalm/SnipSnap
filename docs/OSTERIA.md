# Osteria — an evolving note

> The owner accepted the six-voice bank's sound. Its twelve recipes are factory
> presets beside the original Note recipes, with a native layered starter, finite
> keygroups and a saved PLUCK STRUM. [On the default branch](#on-the-default-branch)
> says what came back from the recovery backup and what stays open; the listening
> notes below are the history.


Osteria adapts the [XOsteria architecture blueprint](https://github.com/BertCalm/XO_OX-XOmnibus/blob/main/Docs/engines/xosteria_architecture_blueprint.md)
to SnipSnap's deterministic offline renderer. Model 2 treats its four components
as spectral layers of one instrument: root, body, air and detail. They share one
fixed fundamental and one attack, sustain and release. Continuous shore motion,
centroid attraction, bounded sympathetic excitation and recent timbral history
change the note's color without scheduling further attacks or melodic intervals.

The first implementation, model 1, scheduled repeated attacks and an upper
C–G–C–G–C phrase. That made the result a small performance rather than an evolving
note. Explicit model 1 recipes retain that rendering behavior; new configurations
and presets use model 2. The audition keeps one previous-phrase comparison.

The five shores are imagined Atlantic, Nordic, Mediterranean, Pacific and
Southern palettes. They change harmonic spectra, air and damping continuously.
The model does not establish acoustic realism for the blueprint's named instruments.

## Controls and recipes

SHORE selects the overall palette; TENSION changes attraction between the layers;
WANDER sets excursions; MEMORY blends recent color into the current tone; ROOM
adds fixed tavern reflections; HOLD extends a finite held-note render. Root MIDI
(36–84) and excitation velocity are saved separately. Every render starts with
fresh oscillator, motion, filter, memory and room state.

`OsteriaPatch` saves initial shores, layer levels, seed, duration, independent
tavern shore, direction and renderer model. Macro editing retains that
configuration, and `PadRecipe` can regenerate it. The provisional Anchored Note,
Evolving Note and Lingering Color presets share C3, velocity 0.8 and seed 173.
The last two differ only in MEMORY. Listening acceptance remains pending.

Model 2 renders last 1–8 seconds. By default the simulated press ends 0.8 seconds before
the file ends, followed by one release. The standard six-second example holds
for 5.2 seconds; the one-second short press holds for 0.2 seconds. HOLD extends
the capture and its held portion to eight seconds. Explicit model 1 retains its
original 5–8-second duration bounds and phrased articulation.
Model 2's timbral travel uses the same elapsed-time path for a tap and a hold;
the eventual release time does not accelerate the coastal motion.

The saved `attackSeconds` and `releaseSeconds` fields shape that single
articulation. Defaults remain 0.035 and 0.8 seconds, preserving the approved
model 2 sound exactly. Attack accepts 0.005–1 second and release 0.02–2 seconds;
the total capture must leave at least 0.05 seconds before release. Missing fields
in earlier recipes select those defaults, and default-valued fields are omitted
when saving. Model 1 keeps its fixed legacy articulation. Velocity changes
excitation spectrum, attack response and level without changing the note's pitch.

Four provisional model 2 source families extend that behavior: BASS has a rounded,
fundamental-led body; PLUCK decays naturally after one bright excitation; REED
sustains a rounded, odd-led body with even partials and subtle seeded breath
variation; PAD builds a smooth body slowly. They retain the
same fixed root, four interacting layers and six macros. Their default
attack/release times in seconds are BASS 0.008/0.3, PLUCK 0.005/0.35,
REED 0.07/0.5 and PAD 0.65/1.2. Recipes save these values explicitly, and touch
editing can override them. Only QUARTET supports the legacy model 1 renderer.
These families await listening decisions; the three Note presets and existing
sixteen-pad bank still use QUARTET.

## Play in SnipSnap

The SYNTH picker labels QUARTET as NOTE and also exposes the four candidate
families as BASS, PLUCK, REED and PAD. The three presets use NOTE. ROOT, VELOCITY,
ATTACK and rendered RELEASE sit beside the six timbral macros. Editing, Scramble,
saved presets and reopening a pad retain the complete explicit recipe.

FRESH TAPE includes an OSTERIA starter with sixteen dry, finite one-shot pads:
three four-note preset rows at C3, E-flat3, G3 and C4, plus three velocity examples
and a short tap. The first twelve pads have separately rendered soft, medium and
hard excitation zones. Notes last three seconds including their authored release;
the tap lasts one second. Lifting a pad does not move that release.

`./gradlew :synth:generateOsteriaKit` exports MPC 3, an MPC 2 twin, native
`kit.json` and ZIP downloads under `testkit/`. Each main sample has its complete
editable recipe. Soft zones are saved WAVs without separate edit recipes;
editing a main sample does not regenerate its soft zones. The generator rebuilds
the complete authored bank deterministically.

## Reproduce the audition

```sh
./gradlew :synth:generateOsteriaAudition
./gradlew :synth:test --tests 'com.snipsnap.synth.Osteria*Test'
```

The task writes `testkit/osteria-audition/`: still, evolving and remembered
held-note comparisons, short and long articulation examples, four shared-note
layer taps, opposite-history comparisons and the previous phrase, plus nine
register/velocity and four attack/release comparisons. Each has
native and level-matched 24-bit stereo WAVs, a complete recipe and motion trace.
The layer taps come from one complete simulation and add back to its output.
Opposite journeys reach a common current shore so remembered color can be heard
separately from their final position.

Raw WAVs preserve renderer gain. Matched WAVs apply one gain for listening;
there is no automatic per-layer synthesis normalization. Listener verdicts and
notes stay in the browser and can be exported. Revised listening notes use a
separate storage key from the first audition.

Verification checks legacy rendering identity, shared articulation, the fixed
fundamental across the held duration, evolving spectral color, deterministic
recipe replay, stem reconstruction and causal memory behavior. These establish
mechanical behavior; the owner decides whether the tone sounds like one useful
instrument.

For the focused voice comparison, run
`./gradlew :synth:generateOsteriaVoiceAudition`. It writes
`testkit/osteria-voices-audition/`: four candidates at the same C3, velocity 0.8,
seed 173 and 5.2-second simulated press, plus the unchanged Evolving Note
reference. Four useful-register examples and four motion-disabled counterparts
complete the thirteen clips. All use the Evolving Note macros with ROOM, HOLD
and MEMORY at zero. Each family's source and articulation differ. The page uses
the same player, raw/matched WAV downloads, recipes, traces and local verdicts.
After listening feedback, production rendering and the candidate Matched files
apply the same voice balance after their common level adjustment: Reed -1.5 dB
and Pad +1.5 dB. Raw audition WAVs preserve the source output without that balance;
the other voices retain their existing levels.

## Eight preset proposals

`./gradlew :synth:generateOsteriaPresetAudition` writes
`testkit/osteria-presets-audition/`. Its eight complete recipes are listening
proposals kept outside the factory roster:

| Voice | First proposal | Second proposal |
|---|---|---|
| Bass | Anchor Bass | Roaming Bass |
| Pluck | Rounded Pluck | Bright Pluck |
| Reed | Still Reed | Remembered Reed |
| Pad | Warm Swell | Memory Drift |

The 27 clips comprise eight main proposals, eight register examples, eight
soft/hard touches and three memory-disabled controls. Main proposals share C3,
velocity 0.8, seed 173 and a 5.2-second press. Each preset has its own saved
attack and release, allowing articulation as well as tone to separate the pair.
Every clip is dry, with ROOM and HOLD at zero.
The accepted Reed -1.5 dB and Pad +1.5 dB balance applies after matching.

Register and touch examples render Bass directly at C2 and the other voices at
C4. Bass, Pluck and Reed use a three-second press; Pad retains 5.2 seconds before
its release. Soft and hard examples use velocities 0.35 and 1.0 on the second
proposal of each voice. Matching isolates excitation color; Raw retains the
authored level differences.

Roaming Bass, Remembered Reed and Memory Drift have memory-disabled counterparts.
Those preserve the entire saved patch, including MEMORY, and disable only the
probe's memory mechanism. Their recipes record that flag. Bright Pluck keeps
MEMORY at zero: its contrast is a faster onset, brighter source and longer
release than Rounded Pluck; both retain the same natural held decay. None of the proposals has
been promoted into the original Note bank or factory presets.

### Why it is built this way

The common pitch, touch and press make each pair comparable. Separate register
and touch files test practical use without hiding transposition or gain changes.
Memory controls retain the same actual journey so remembered color can be judged
on its own. The longer Pad press leaves room for its slow onset and travel.
Listening choices determine which recipes enter the next bank.

Revision 3 addresses the owner's report that each pair was too similar and
Reed's underlying tone sounded artificial. The former Bass pair started with
nearly the same spectrum; Warm Swell and Memory Drift converged to nearly the
same held tone. Each pair now contrasts initial and sustained color as well as
articulation:

| Pair | First direction | Second direction | Attack / release, seconds |
|---|---|---|---|
| Bass | Immediate, dark and root-led | Slower opening, stronger upper body and travel | 0.008 / 0.12; 0.18 / 0.30 |
| Pluck | Rounded, dark and compact | Crisp, bright and more open on release | 0.025 / 0.12; 0.005 / 0.35 |
| Reed | Compact hollow body | Slower opening into a fuller mixed body | 0.045 / 0.20; 0.18 / 0.50 |
| Pad | Earlier settling, dark and close | Slower opening, fuller movement and longer release | 0.28 / 0.55; 0.95 / 1.20 |

Memory Drift retains revision 2's spectral journey. Warm Swell now has a distinct
dark destination instead of arriving at the same color. All eight retain the
common main press, root, touch, dry rendering and accepted voice balances.
Every clip has revision metadata and new audio URLs to avoid stale previews.

Reed's source itself changes in production as well as the audition. The old
near-absence of even partials and fixed 850/2100 Hz odd-harmonic emphasis could
not be corrected by quieter output or a different journey. The new source adds
even-partial low body, rolls off the upper harmonics faster and replaces those
peaks with a broad lower body. Seeded aperiodic pressure gently changes excitation
and breath at the existing control rate, leaving root phase and articulation
alone. These are sound-design choices, not an acoustic-reed simulation. Existing
physical wind engines BORE and EMBOUCHURE remain the appropriate separate models.

The source cache and its capacity are unchanged; pressure is fresh local state
on every note. There is no per-sample solver or added table dimension. QUARTET,
BASS, PLUCK and PAD source construction is unchanged. Provisional Reed recipes
now render through the revised source; it has not entered the factory roster.

Checks require useful normalized harmonic separation in both an early and a
late held window, substantial Memory Drift development, Remembered Reed's
opening into its body, and restrained Reed treble across all five shores. Root,
finite output, mono compatibility, deterministic replay and causal memory remain
covered. These measurements support the audition; only the owner judges whether
the revised Reed character and pair distinctions are useful.

Revision 3's PCM24 audition measurement normalizes harmonic energy per FFT frame.
Late pair distances (4.2–4.9 seconds) change from 0.096 to 0.152 for Bass,
0.021 to 0.077 for Pluck, 0.104 to 0.154 for Reed and 0.013 to 0.341 for Pad.
These are spectral differences, not a perceptual scale. Early spectral contrast
does not increase for every pair: Pad's earlier distinction is its different
onset. Memory Drift retains an early-to-late distance of 0.302. Remembered Reed
retains 0.201 while ninth-and-higher harmonic power falls from 7.34% to 0.0445%
in the late window. Source and file fingerprints, pitch windows and all 54 WAVs
pass; mono folding retains at least 97.9% of their energy. The owner called this
revision better and asked to continue in this direction; individual factory
preset and bank selection remain pending.

The repository-wide JVM command was started for revision 3, then stopped
while running unrelated ARCO characterization sweeps. It is not a complete-suite
pass. The release checks are the complete focused Osteria suite, repository
convention checks, artifact validation and browser playback verification.
Revision 3 passed 33 focused Osteria tests and 42 convention tests. The live
browser check captures nonzero native audio from all eight main proposals,
checks transport and comparison tabs, and verifies that earlier reviews remain
separate from judgments of the revised audio. Desktop and 375-pixel mobile checks
pass without page errors or horizontal overflow.

Source-table banks now use an eight-entry cache keyed by voice, MIDI root, seed
and renderer model. Only finished, immutable tables are shared, with about
2.6 MB of Float payload at capacity; motion, history and filters start fresh on
every note. This avoids rebuilding the same spectra on a macro or touch edit.
It does not change the simulation, summation or output math.

A small same-machine JVM comparison used two warmups and three timed six-second
renders per voice. Warm medians changed from 692 to 517 ms for Bass, 596 to 505 ms
for Pluck, 570 to 497 ms for Reed and 581 to 498 ms for Pad. This is a 13–25%
improvement on that machine, not a phone benchmark or an instant-render claim.
At cache introduction, before the Reed revision, the thirteen-clip voice set
retained all 26 raw/matched PCM24 fingerprints exactly. That round passed thirty
focused Osteria checks and 42 repository convention checks. Its broader test run
caught two stale starter counts, now corrected, and was stopped after that known
failure rather than reported as a complete pass.

### Listening revision 4

Following the owner's approval of revision 3's direction, this round develops
Roaming Bass and the two Reeds. Anchor Bass, both Plucks and both Pads keep their
exact recipes and audio. The audition labels those five retained sounds and the
three revised sounds separately, with new URLs only for revised files. Listening
reviews follow audio fingerprints, so unchanged sounds keep their verdicts while
changed sounds retain earlier notes as earlier reviews.

Roaming Bass begins its layers at shore 2.8 instead of 2.0, then travels toward
its existing full-body destination. MEMORY rises from 0.40 to 0.60. Its normalized
early-to-late harmonic distance increases from 0.051 to 0.080. This trades some
immediate brightness for a more defined hollow-to-full progression; pair distance
still passes in both early and late held windows. Its attack, release, main press,
root, seed, touch and output balance are unchanged. Tried Pluck and Pad recipe
changes either weakened their existing progression or added too little value,
so their approved direction is retained.

Reed keeps revision 3's softened source tables. The existing seeded pressure now
also changes body damping and the bandwidth of quiet breath noise. A short breath
settles with the one shared onset. None of these paths changes oscillator phase
or schedules a new attack. The pressure factor remains positive and all filter
updates are convex one-pole steps; the existing sympathy bound is unchanged.
This is a restrained spectral refinement, not a physical reed simulation or a
claim that a measurement establishes acoustic realism.

An internal `reedPressureEnabled` probe flag allows a causal comparison while
shore travel, oscillator/noise seeds and articulation stay fixed. It is not a
patch macro or saved engine control. Audition recipes record it as enabled,
including the memory-disabled comparisons. In C2, C3 and C5 stationary probes,
pressure changes ratios within the normalized upper body, beyond any overall
level or root-to-upper gain change. Three measured windows per register give
maximum distances 0.001098, 0.001000 and 0.001453; the test also checks mean
movement, fixed pitch, restrained treble and unchanged travel. These small
numbers establish the mechanism; audibility and usefulness remain for the owner
to judge.

The existing eight-bank immutable source cache is unchanged. The added work is
four control-rate air coefficients per 5 ms block and one extra air-coefficient
interpolation per Reed role/sample. Pressure and filters remain local to each
render, with no new solver or table dimension. The previously measured roughly
half-second warm note render remains a playability limitation, not an instant
render claim.

Revision 4 passes 34 focused Osteria tests and 42 convention checks. Fifteen
raw/matched clip pairs retain their exact revision 3 fingerprints; twelve pairs
change and receive revision 4 URLs. All 54 PCM24 WAVs pass pitch-window, finite
output, headroom, DC, mono, fingerprint and source-provenance checks. The hosting
package restores all 54 WAVs byte for byte, and all 54 MP3 previews decode to
finite nonzero audio with a peak below 0.818. The common preview gain remains
+9.2 dB, and the original Note bank remains byte-identical. Browser checks verify
that retained sounds keep valid reviews while revised sounds archive earlier
reviews. These are the focused release checks; no complete repository-wide suite
pass is claimed for this round.
Live playback verification captures nonzero native audio from all eight main
sounds with Web Audio and gzip APIs disabled, and passes transport, comparison
tabs, review provenance, desktop and 375-pixel mobile layout checks. It reports
no page errors. Current-round listening acceptance remains pending.

## Earlier four-voice performance bank

At commit `526a685`, `./gradlew :synth:generateOsteriaPerformanceBank` built
`testkit/osteria-performance-bank/` from the eight candidate recipes.
They remain outside the factory preset roster, and the original Note bank is
unchanged. The layout is:

| Slots | Voice | Presets | Roots for each preset |
|---|---|---|---|
| A01–A04 | Bass | Anchor Bass, Roaming Bass | C2, G2 |
| A05–A08 | Pluck | Rounded Pluck, Bright Pluck | C3, G3 |
| A09–A12 | Reed | Still Reed, Remembered Reed | C3, G3 |
| A13–A16 | Pad | Warm Swell, Memory Drift | C3, G3 |

All sixteen pads have three independently rendered excitation layers, for 48
stereo 44.1 kHz/24-bit WAVs. Soft, medium and hard use source velocities 0.25,
0.60 and 1.00, mapped to MIDI 1–41, 42–84 and 85–127. Each root is rendered
directly. Every note keeps the full 5.2-second press plus its preset's release;
files last 5.32–6.40 seconds. Source macros, seed, motion, memory, articulation
and Reed/Pad balance remain those of the current audition.

The sampler applies incoming velocity at playback. To apply dynamics once, each
bank WAV divides its genuine production render by its nominal source velocity.
Pad mixer level is 1.0. Browser previews restore the nominal hit velocity, with
the same additional preview gain for every pad and touch. No per-layer peak
normalization is used. Every supplied zone recipe records its preparation gain,
and the native hit integration test compares decoded prepared WAVs through
`PadHit.resolve` with the original production PCM, including the voice balances.
The observed error stays below 2e-7. MPC velocity curves still need a hardware
audition; format validation does not establish hardware loudness parity.

The complete MPC ZIP shares one set of 48 WAVs between a native MPC 3 `.xtd`
track and its MPC 2 `.xpm` inside the matching `_[TrackData]` folder. It includes
native `kit.json`, all 48 full zone recipes and an index mapping them to the
exported samples. Readable sample stems identify the preset and root even when
an importer derives pad labels from filenames.

The bank page also prepares a SnipSnap import ZIP from that same verified
archive. Its two stored entries are the archive as `Osteria-Four-Voice.xpn` and
an eight-preset `presets.json` library. This follows the app's actual backup
import path: the sixteen velocity-layered pads arrive as playable samples,
while complete source recipes arrive as user presets in YOURS. Use a user preset
on a fresh, unlayered pad to edit its controls. The existing editing guard for
layered pads remains in force; editing one recipe does not rebuild all saved
zones automatically. Remembered Reed uses the user-preset alias REMEMBER REED
to fit the app's fourteen-character name limit.

The hosted browser pad view (not part of this repository) provides a 4×4 MPC pad layout, three touch choices, keyboard triggers, eight
native playback voices and Stop all. It plays complete finite samples rather
than following live key release. Previews are prepared as bounded native MP3
Blobs, so playback does not depend on Web Audio, gzip or HTTP range support.
One verified archive download supplies both save formats; the SnipSnap backup
wrapper is assembled locally without duplicating hosted WAVs.

The existing 27 listening comparisons remain available with their previous
native MP3 levels, recipes, traces and device-local reviews. Their individual
lossless WAV transports are replaced on the hosted page by the complete bank
download, keeping the combined site within its hosting budget. Original study
WAVs remain in their generated local audition directories and can be regenerated.
The new bank does not change the old Note kit or promote candidate factory presets.

The release checks pass two bank-authoring/export tests, two native import/hit
integration tests and 42 repository convention tests. Full artifact inspection
checks all 48 WAV fingerprints, formats, roots, recipe mappings, velocity ranges,
MPC sample references, duration and headroom. The maximum prepared WAV peak is
0.6602; mono retains at least 97.9% energy. All 48 decoded MP3s are finite and
nonzero; their RMS follows the intended nominal production levels within 3.1%.
All 54 earlier comparison MP3s retain their exact published bytes.

Local browser checks pass prepared-audio retry, rapid touch switching, keyboard
triggers, overlapping native notes, the eight-voice bound and Stop all. Ten actual
native audio captures cover all eight presets plus soft/hard dynamics. Archive
failure, cancellation and retry work while pads remain playable. Both downloaded
ZIPs pass CRC and SHA checks; the actual browser-built SnipSnap ZIP imports through
`ShelfImport` as sixteen pads, 48 zones and eight full user presets. Desktop and
375-pixel layouts pass without page errors or horizontal overflow. These are
software and format checks; MPC hardware and Android device audition remain pending.
The deployed page passes the same native playback, touch, keyboard, polyphony,
retry/cancellation, download and layout checks. The SnipSnap ZIP downloaded from
the live page also passes the actual full-bank importer check. The updated Android
debug build is a separate workspace artifact, since its universal APK exceeds the
site's per-file and remaining payload budget.
`:app:assembleDebug` passes with the current voice families and synthesis code.
The debug APK is at `app/build/outputs/apk/debug/app-debug.apk` (48,440,410 bytes,
SHA-256 `61a393c68ff1f79cc91c2cdbeac9a2ee65048e259eb3441d8e0dd38ebb9153d0`).
APK signature verification passes; installation and playback on an Android device
have not been tested in this environment.

### Why the bank is built this way

Two roots per sound test its useful register while keeping the instrument
compact. Genuine excitation layers preserve touch-dependent tone, and the
velocity preparation prevents duplicate attenuation in the sampler. Long notes
leave the approved color journeys intact. A shared WAV folder keeps both MPC
formats consistent, and the separate user-preset library makes synthesis editing
possible through SnipSnap's existing import and editing paths.

## Six-voice bank and Bowed addition

The current bank has six total voices: Note (`QUARTET` in saved recipes), Bass,
Pluck, Reed, Pad and Bowed. Two recipes per voice fill sixteen pads:

| Slots | Voice | Presets | Roots |
|---|---|---|---|
| A01–A04 | Bass | Anchor Bass, Roaming Bass | C2, G2 each |
| A05–A06 | Pluck | Rounded Pluck, Bright Pluck | C3 each |
| A07–A08 | Reed | Still Reed, Remembered Reed | C3 each |
| A09–A10 | Bowed | Warm Bow, Rosin Bow | C3 each |
| A11–A12 | Note | Anchored Note, Lingering Color | C3 each |
| A13–A16 | Pad | Warm Swell, Memory Drift | C3, G3 each |

Generate it with `./gradlew :synth:generateOsteriaPerformanceBank`, now writing
`testkit/osteria-six-voice-bank/`. The earlier four-voice output stays separate.
The twelve saved recipes include the original eight unchanged proposals and two
unchanged Note factory recipes. The three-preset Note factory roster remains
unchanged; the expanded bank is provisional. The original 27-clip study remains
a four-family comparison, and links to the new six-voice playable bank.

Bowed's gesture is a soft catch, a continuous string draw, travelling body and
rosin color, then a damped lift. This adapts a spectral string gesture within
Osteria's existing fixed-root model; it does not simulate ARCO's stick-slip bow
or waveguide. Both odd and even partials carry its body. Excitation moves their
spectral slope from approximately h^-1.45 toward h^-1.05, with h2/h4 body and a
soft upper rolloff. Quiet seeded friction changes excitation, damping and air
bandwidth without changing oscillator phase, triggering another onset, or
changing the shared motion and memory path. The default attack/release are
0.18/0.65 seconds, with continuous held energy and 0 dB voice trim. Warm Bow
uses a darker root-led coast; Rosin Bow opens the upper body and retains more
travel in memory. These shape choices still need listening judgment.

The immutable eight-bank source cache, key dimensions and bounded table payload
are unchanged. Friction and filter state start fresh for every note. The new
enum value is appended, preserving existing names and ordinals. Playback still
uses a 5.2-second authored press plus each preset's release, three excitation
zones and the existing once-only velocity preparation. There are still 48
24-bit stereo WAVs shared by MPC 2 and MPC 3. The SnipSnap backup wraps that
archive as `Osteria-Six-Voice.xpn` with twelve full user presets. `ANCHOR NOTE`
avoids the factory-name collision; `LINGER COLOR` and `REMEMBER REED` fit the
fourteen-character user-preset limit.

The implementation was reviewed against `526a685`. All 45 focused Osteria
synthesis tests, four native import/preset tests and 42 convention tests pass.
Bowed tests cover fixed roots at C2/C3/C5 and soft/normal touch, continuous
energy, normalized spectral distinction, causal friction, exact stem sums,
mono, silence, cancellation, and cold/warm/evicted-cache replay. Its held-body
spectral distances from Reed, Pad and Pluck measured 0.2144, 0.0734 and 0.2291
in the named Kotlin test. The two Bowed presets retain normalized harmonic
power distances of 0.0789 early and 0.1123 late in their held portions. These
are measured differences, not listening judgments.

Regenerating the earlier 27 comparisons preserves all 54 raw/matched WAV hashes,
and all 54 hosted comparison MP3s retain their previous bytes. Full six-voice
artifact inspection passes all 48 WAV hashes, source provenance, recipes, roots,
MPC sample references and velocity mappings. Maximum prepared peak is 0.66014;
mono retains at least 97.9% energy. All 48 MP3 previews decode to their exact
authored lengths; nominal production RMS differs by at most 3.01%, with at least
3.87 dB peak headroom. Every pad preserves soft < medium < hard levels.

Local browser checks capture actual native audio for all twelve presets and
soft/hard dynamics, and pass retries, rapid touch changes, keyboard triggers,
eight-note polyphony, Stop all, download cancellation and 375-pixel layout.
The actual browser-built backup imports through `ShelfImport` as sixteen pads,
48 velocity zones and twelve editable presets across all six voices.
The deployed six-voice page passes the same native-audio, touch, polyphony,
retry/cancellation, download and layout checks, and its downloaded backup passes
the real importer. Preview, preset and archive fetches bypass persistent HTTP
cache so reused pad URLs cannot retain audio or downloads from an earlier bank;
the bounded in-memory preview cache still serves repeated pad hits.
`:app:assembleDebug` and `:app:compileDebugAndroidTestKotlin` pass; APK signature
verification and DEX inspection confirm the sixth voice. The new debug APK is
48,442,526 bytes, SHA-256
`70f8d21af687619db12b9d09198cf45d94bf5f4e4b9d056134b916732fb72d8d`.
Android device and MPC hardware audition remain pending. Full repository tests
were not rerun for this voice addition; the scoped checks above are the evidence.

### Why the sixth voice is built this way

Continuous excitation gives Bowed a sustained role beside Pluck's decay. Its
mixed harmonic body and quieter friction distinguish it from Reed's air column
and Pad's slower even-partial swell. Reusing Osteria's color, motion and memory
keeps it one evolving note and avoids adding another synthesis engine. The
two Bowed presets expose darker and more textured directions for audition;
the risky choice is whether their spectral friction conveys a useful string
character to the listener.

## Playback and limits

The owner listened on a private hosted audition. Its address and the Python
packers that built it are not part of this repository; the generator tasks above
still write the WAVs, recipes and local listening pages under `testkit/`.

The browser plays files with simulated press and release times. It does not
follow a live key or pad release. HOLD does not produce a seamless loop, each
independently rendered pad starts with fresh history, and sample transposition
also changes the internal clock. Live release-following playback and a wider
preset roster remain separate from this finite-note prototype.

## On the default branch

Ported from the recovery backup on 2026-11-13. `Osteria.kt` and the preset,
candidate and bank sources come back unchanged, so the accepted renders are the
same: `OsteriaPerformanceBankTest` pins the exported WAV and recipe hashes against
the owner-auditioned manifest (`osteria-accepted-bank.tsv`), and `OsteriaStrumTest`
holds the thirteen factory recipes' JSON byte for byte and the approved DOWN and UP
strum WAVs to their published hashes.

- **Voices and presets.** NOTE (saved as `QUARTET`), BASS, PLUCK, REED, PAD and
  BOWED. The factory has 13 presets: the three original Note recipes and the ten
  other accepted recipes under voice-local names (Anchor/Roaming, Rounded/Bright,
  Still/Remembered, Warm/Drift, Warm/Rosin). Only display names changed.
- **Starters.** OSTERIA is the original sixteen-pad Note kit. OSTERIA SIX VOICES
  is the accepted bank: sixteen pads, 48 native excitation layers at velocities
  .25/.60/1.0 on MIDI 1–41/42–84/85–127.
- **Keys.** MAKE INSTRUMENT builds finite keys from any single-note model-2 sound:
  thirteen roots, MIDI 36–84 every four semitones, three touch layers, no loop, and
  the saved attack, length and release (so the sheet has no RELEASE). The package
  keeps every cell's recipe and WAV hash in its TrackData and is validated before it
  lands on the shelf; failures roll back. This is not crash-atomic.
- **STRUM.** PLUCK can save a STRUM: MINOR or MAJOR from `ChordStabs`, DOWN or UP,
  a 10–240 ms sweep, each tone struck once. A strum saves as OSTERIA patch version
  2; a single note stays version 1 with its original JSON. Version-1 recipes with a
  strum, version-2 recipes without one, and unknown fields are refused. Keys refuse
  a strum, and SINGLE NOTE restores them.
- **Old saves.** Model-1 phrase recipes still decode and render their phrase.
  Missing attack and release fields read as the original defaults.

Not ported: the hosted-audition packers, the direct test harnesses under
`tools/`, their recorded check results, the recovery manifest and the session
handoff notes. Phone playback, MPC hardware, live release, a seamless HOLD loop,
round robin and chord-tool keys on real OSTERIA samples remain open; see
[the acceptance gates](ENGINE_ACCEPTANCE_GATES.md#osteria-ported-from-the-recovery-backup-2026-11-13).
