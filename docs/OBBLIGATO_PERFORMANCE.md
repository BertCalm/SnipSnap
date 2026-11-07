# OBBLIGATO: sustain and native range

Status: performance implementation, focused checks and published audition
complete; broader verification is recorded separately below.

Listen and download the four instruments at the private [sustain and range
audition](https://obbligato-sustain-range.bertcalm.chatgpt.site). Baseline: `22a6b40055192504f91a5a58f78e0ab01b53871e`.

The characteristic gesture is **flute-led opening → joined exchange → overlapping
replies while the key is held → release on note-off**.

## Scope

The performance renderer extends native notes from C4–C5 to C3–C6. It retains the
approved pipe voicing and longer handoffs inside the original register. Explicit
older renderer versions keep their original note limits and audio. Missing-model
JSON still means legacy renderer 5; missing playback means a finite phrase.

PHRASE keeps the existing finite HOLD gesture. HELD is an explicit saved playback
choice, with its own capture version and note-off release. The factory sixteen-pad
kit remains a set of finite performances. A held instrument is a separate delivery
through the existing phone sampler and MPC keygroup exporters.

This is an extension of the existing instrument. BORE supplies a held single
wind source, and FLOCK supplies interacting oscillator agents. OBBLIGATO retains
the particular air/reed pair, remembered answers, and relationship-specific
lead changes. Their social behavior is a control analogy; the bore sources remain
the physical model. No human lung or live inter-pad conversation is simulated.

## Design choice

| Direction | Gesture and tradeoff |
| --- | --- |
| Capture the existing duet | Keep the opening and a longer middle containing complete exchanges. Lowest risk to the approved sound; the repeated phrase must pass independent blend and lead-coverage checks. Chosen. |
| Author a periodic controller | Repeat a control score and seek source convergence. Clear phrase structure, but turbulence and independent pipe phases complicate recurrence. |
| Resynthesize periodic carriers | Repeat harmonic carriers under expression envelopes. Cheap to sustain, but risks losing the existing breath and reed character. |

The chosen path extends the existing `HeldLoop` capture idea. It adds explicit
patch provenance, bounded caching, cancellation during search, and complete
exchange coverage. Existing finite recipes do not enter this capture path.

```text
complete patch → two responding Bore.Player sources → conditioned sum and taps
              → keep original opening
              → select voiced middle before SETTLE, preserving both trading leads
              → one common blend and gain → attack-plus-loop sample
              → InstrumentEngine / MPC keygroup → note-off envelope
```

## Control contract

| Control | Sound quantity and required check |
| --- | --- |
| BREATH | Source operating pressure. Check capture and root at low and high values. |
| BOND | Relationship rules and interpolation. Play/Fight must keep both leads; Protect must expose its flute opening. |
| MISCHIEF | Readiness, effort and articulation. Check changed decisions and audible output without changing the requested root. |
| HOLD | Finite phrase duration in PHRASE. HELD stores the top setting and uses sampler sustain. |
| ROOT / VELOCITY | Native note and excitation, stored outside the macro map. Check the whole C3–C6 range, especially soft low notes. |
| RELEASE | Saved sampler note-off envelope. Software behavior is testable; MPC envelope timing still needs hardware confirmation. |

## Loop and output proof

The held source starts with a bounded ten-second performance. When its loop
search alone finds no safe cut, one fresh fourteen-second capture supplies a
second search. Validation, non-finite audio and cancellation do not trigger this
retry. Previously successful captures retain their exact audio. Capture ends
before the source's shared settling gesture. Play and Fight sustain sections must contain complete
changes of leader and actual foreground time for both sources. Protect retains
the original flute-led prefix and captures the supported pair afterward.

Loop length may be four to eight seconds. The initial four-to-six-second search
missed a safe native E-flat4 Harmony capture: independent endpoint analysis found
its two sources align near 7.5 seconds, with correlation .993 and local blend
loss below .1 dB. Extending the search retains the original correlation and
energy limits while retaining a complete musical exchange.

An assembled wrap is not a claim that the controller or bore states recur.
Copying original pre-loop material into the tail establishes waveform
continuation; it does not independently prove the blend is musical. The capture
must also report correlation against unmodified endpoint material, local blend
energy, level change, waveform steps, and actual lead coverage. Repeated-loop
playback and sampler note-off must be checked separately from those numbers.

One common gain applies to the completed duet and both actual taps. Source taps
are never independently normalized. Native alias filtering and decimation remain
the engine's existing four-times output path.

## Cost and delivery

A completed-note cache is bounded by eight entries and 32 MiB. Its key includes
all audio-affecting controls, note, strike, seed, renderer and capture revision;
name and release do not change source audio. Callers receive owned sample copies.
Cold renders and searches are cancellable and run outside the cache lock.
`SynthCaches.clearAll()` releases the completed duet and both source taps.

On the session's Java 17 runtime, six ordinary cache-cold captures took about
.89–1.16 seconds; five cache hits per case had medians of .32–1.03 ms, including
owned audio copies. The cases cover all four relationships at C4, Protect C3
and Harmony E-flat4. Soft Protect C3 uses the longer-source fallback and took
2.53 seconds cold, with a .57 ms cached median. These are single cold observations
and five cache hits, with no separate JIT warmup; the first case includes class
initialization. Cache hits reproduced the exact mixture, taps and loop markers;
source hashes remained unchanged during measurement. These are JVM timings,
not phone timings.
The first capture runs in the background; instrument zones render serially with
progress and cancellation. The house tens-of-milliseconds goal is achieved only
for cached playback preparation in this measurement.

Exports retain tested note bounds, loop starts, full source patches and sampler
release in the phone sidecar, MPC 2 and MPC 3 programs. Native roots are spaced
every minor third. Notes outside the measured range are not silently admitted by
a full-keyboard export default.

## Evidence ledger

- Verified: baseline finite source, complete patch JSON, existing `HeldLoop`,
  `Keys.obbligatoPad`, `ObbligatoPadMaker`, `KeyNote`, and `OneNote.writePackage`.
- Measured: 112 finite source captures across C3–C6, four relationships, two
  velocities and both finite HOLD endpoints. Both actual sources remain captured;
  measured root errors stay below 4.1 cents. The independent low-level probe
  records source RMS, onset, DC and pitch confidence.
- Verified: 53 immutable saved recipes across versions 1–10 retain their audio,
  PCM24 exports and JSON. Renderer 11 matches accepted version 10 within its
  original register. All thirteen Harmony instrument zones pass loop and export
  metadata checks.
- Verified: all 77 OBBLIGATO synthesis checks, six shell checks, six CLI checks
  and three global-cache checks pass. This includes twenty-four held relationship/note/velocity
  captures, independent endpoint and blend analysis, exact sampler preview,
  saved-patch overrides, cancellation and complete package metadata.
- Measured: the final seven-case timing probe records exact cache recall,
  finite samples and source additivity. Ordinary Protect C3 closes in 7.675 s
  with correlation .913, local mixture loss .249 dB and endpoint difference
  -2.444 dB. Soft Protect C3 uses the fourteen-second source and closes in
  7.996 s with correlation .990, local mixture loss .004 dB and endpoint
  difference -.552 dB. Every quality limit remains unchanged.
- Verified: 44 written listening tracks and all four thirteen-zone instruments.
  Independent checks validate hashes, complete repeated sustain passes, note-off
  tails, source additivity, loop markers, bounded keys and MPC/phone references.
  Browser playback, downloads, saved listening notes and phone-width layout pass.
  The audition contains 123 files totaling 150,050,795 bytes.
- Verified: regeneration after the soft-note repair retains all 44 listening
  recordings, raw performance hashes, 52 native sample files and all four
  complete instrument ZIPs byte-for-byte. Only two source fingerprints change.
- Verified: all 52 soft-strike native-bank captures succeed across the same
  thirteen roots and four relationships. Protect C3 and F-sharp3 use the longer
  source; the other fifty use the ordinary source. Together with the 52 exported
  main-strike sources, both tested playing levels cover every native zone.
- Pending: full JVM suite, app compiler and device checks, MPC hardware envelope
  confirmation, and the owner's listening verdict.
- Heard: the owner approved renderer 10 and authorized both performance extensions;
  this new held articulation and extended register have no listening verdict yet.

## Why it is built this way

- Keeping the original opening preserves the instrument's entry and Protect's flute.
- Keeping whole exchanges lets a held key continue the duet's musical gesture.
- Versioned recipes protect existing sounds when new playback choices are added.
- Joint blending preserves the players' balance rather than hiding a quiet source.
- A bounded cache makes repeated notes cheap without claiming instant cold rendering.
