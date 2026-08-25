# The remaining work, scoped

Everything left between here and a shipped product, sized and ordered.
Written after the format milestone: both MPC generations' writers are
implemented, corpus-guarded, and — for drums — **hardware-verified on the
Live III**. Sizes are working sessions (S ≈ under a day, M ≈ 1–3 days,
L ≈ a week-plus of sessions).

## Where the project stands

| Surface | State |
|---|---|
| Capture/conditioning core (`:audio`) | done, tested — ring buffer, cleanup, transients, chopper, classifier, auto-place, pitch/scales/tuner, loudness, resampler + bake path |
| Kit pipeline (`:kit`) | done, tested — kit folders, preflight, balance, in-key, velocity layers, recipes, every export driver |
| Formats (`:xpm`, `:mpc3`) | done, tested, corpus-guarded — `.xpm`, keygroups, expansions, `.xpn`, `.xtd`, `.xty`, clips, `.xpj` projects; drums hardware-verified |
| Synthesis (`:synth`) | done, tested — seven engines, FX rack, recipes, groove, S5 instrument suite with loop points |
| Design | done — TapeOS system, six schemes, ten artboards, **two fully working phone-frame prototypes** (Oilslick, Clear) |
| Acceptance artifacts (`testkit/`) | done — 14 downloadable checks, from the diag kit to the one-file Session project |
| CLI (`:cli`) | done, tested — `snipsnap.jar`: chop → classify → place → export from any desktop; the classifier's real-audio calibration tool (`docs/CLI.md`) |
| View-models (`:shell`) | done, tested — scheme tables, peaks pyramid, tape-deck transport physics, voice allocation, chop review, kit builder, export wizard, personality system; `:app` binds Compose to these |
| **The Android app** | **M0 done** — `:app` scaffolded on Compose over all six modules; TapeOS theme, window shell, kit shelf, audible pad grid, scheme picker. M1 (capture) is next |
| Hardware verification | drums passed; keys, instruments, `.xpn`, tile, Session pending (user) |

The concept doc's "deliberately v2" list (velocity layers, expansions,
keygroups, synths) all shipped **in the core** while the app waited. The
consequence: the app's job shrank. It is UI, services, and wiring — every
hard algorithm it calls is already written and tested on a plain JVM.

---

## Part 1 — The Android app (`:app`) · L, the critical path

**Owner: the desktop session** (this environment cannot fetch the Android
SDK; the desktop can). This session supports: core API changes on request,
review, and everything else in this plan.

**The one architectural rule**, set on day one and now paid for: the app is
a *thin shell over proven code*. Every module is pure Kotlin/JVM targeting
Java 17 exactly so `:app` can consume them directly. No algorithm gets
written in the app layer; if a screen needs logic, it lands in a module
with tests first.

Since this plan was first written the shell got built for real: **`:shell`
now holds every screen's state machine** — tape-deck transport physics,
chop review, kit builder, export wizard, voice allocation, the scheme
tables, the personality gates — as tested pure Kotlin. Where a milestone
below says "wire to real buffers" or "port behaviour", the port already
exists; the milestone's job is binding Compose to it.

The UI spec is not a wireframe — it is two **working prototypes**
(`design/TapeOS Oilslick.dc.html`, `design/TapeOS Clear.dc.html`) with
every screen, interaction, gag and token live, plus `design/HANDOFF.md`
with the Android notes (Compose brushes, fonts, dp constants, personality
copy). Building a screen means porting behaviour that already runs, not
inventing it.

### M0 — Walking skeleton · M — ✓ done

Scaffold `:app` (Compose, minSdk 29), depend on all six modules. TapeOS
theme object: the six scheme token tables from `design/Main.dc.html`
(`t-chrome`…`t-clear`), the four fonts, bevel modifiers (raised/pressed/
sunken), the two-surface rule as composables (gray window chrome, dark
LCD). Navigation shell: the SNIPSNAP.EXE window, menu row, status bar.
Storage: kit folders under app files via `KitStore` — list, open, create.
**Exit test:** browse kits on a phone, tap pads, hear WAVs (interim
`SoundPool` is fine here), flip schemes in Tape Properties.

#### What M0 settled, and what it hands M1

The architectural bet paid: seven modules of pure-Kotlin DSP, never
compiled for Android until this milestone, render the whole factory kit
on-device at first launch. Nothing audio ships in the APK. `:app` holds
Compose bindings and three Android adapters and no algorithms — the one
new function in the app layer, the pad-grid coordinate mapping, arrived
test-first.

**Carried into M1, decided or measured here:**

- **First run is slow, and it is the synth.** Rendering sixteen pads
  measured ~24s on a memory-pressured emulator against 39ms on a warm
  desktop JVM — cold ART interpreting tight float loops, and first run
  is the one time none of it is compiled. Treat that number as an upper
  bound, not a clean measurement. The shelf now says what it is doing
  while it happens, which was the urgent half; the fix is a baseline
  profile or a smaller factory kit, and that is a product call.
- **`PadPlayer` is main-thread only, and now says so.** `load()` does
  disk IO on the main thread; the obvious fix — wrapping the load loop
  in `Dispatchers.IO` — silently breaks the guard that stops `SoundPool`
  from cross-wiring pads between kits. `check(Looper…)` fails loudly
  instead. M4 replaces the whole class with Oboe.
- **`ui-tooling` is deliberately absent** — it drags
  `androidx.compose.material` onto the debug classpath. A milestone that
  wants `@Preview` should re-add it with an `exclude`.
- **Untested by design:** `@Composable` functions and the `SoundPool`
  adapter. There is no Compose or Robolectric harness; M1 should decide
  whether to add one rather than inherit the gap silently.
- **Deferred, with reasons:** the KGP "loaded multiple times" warning
  (wants a repo-wide version catalog); IME padding in `NewTapeDialog`
  (latent at the verified screen size); glyph fallback for ▶ ■ ⟳, which
  no bundled font carries — it is invisible for ⚙ at menu size and will
  not be at M2/M4 transport size.
- **`design/HANDOFF.md` contradicts itself** on the menu row: "Menu row
  26" and "Hit targets ≥44". The rendered target measures 48dp, so this
  is a source inconsistency to resolve, not a defect to fix.

**The habit worth keeping:** four defects on this branch came from
looking at the running app and none from the test suite — clipped system
bars, "1 TAPES", a hard-clipped pad label, and a shelf that claimed to be
empty while filling itself. `scripts/m0-exit-test.sh` exists to make that
repeatable; M1's exit test should be written the same way, and run
before the milestone is called done rather than after.

### M1 — Capture · L, the riskiest milestone, do it second on purpose

- Foreground service (`mediaProjection` type) owning a `RingBuffer`;
  `AudioRecord` + `AudioPlaybackCaptureConfiguration` writer thread. The
  realtime-callback rules are documented in `ANDROID_CAPTURE.md` — SPSC,
  no locks or allocation on the audio thread.
- One consent dialog per session (Android 14+ constraint is designed in:
  sessions are long-lived, snips are free).
- The bubble: `SYSTEM_ALERT_WINDOW` overlay, one tap = snip via
  `snapshotSeconds`, drag-down = eject — exactly the prototype's Bubble
  behaviour, including the ring-as-tape-fill.
- Mic source (plain `AudioRecord`) — same session, no caveats.
- Share-sheet import: MP4/audio in via `MediaExtractor`/Media3, through
  `WavReader`/`Resampler` (the bake path the desktop session just built).
- Silence detection (`Loudness`) → the DIGITAL SILENCE dialog and the
  screen-recorder escape hatch, first-class per the concept's risk table.
- Quick Settings tile.

**Exit test:** snip YouTube audio from inside YouTube; snip the room from
the mic; share a video in — all three land as buffers in the kit flow.

### M2 — Tape deck (trim) · M

The drag-audio-under-a-fixed-needle editor, live twice already in the
prototypes with zoom, onset snap and the pencil rewind. Wire to real
buffers: waveform mips for render, `Transients` for snap targets, loop
preview, COMMIT → `Cleanup` → WAV in the kit folder. **Exit test:** a
YouTube snip becomes a clean one-shot on a pad, cut on the hit.

### M3 — Kit builder + chop shop · M

The grid screen against `Kit`/`KitAssembler`: assign from shelf, per-pad
name/colour/level/pan/tune/mute-group/one-shot, hold-to-preview. The chop
flow: `Chopper` slices, `Classifier` labels (dashed "NOT SURE" under the
confidence threshold), tap-to-cycle overrides, `AutoPlace` preview, SEND
TO GRID. `Balance` and `InKey` as kit actions. **Exit test:** one captured
bar becomes a playable, sensibly-laid-out kit in under a minute.

### M4 — Play mode · M

The latency milestone: Oboe/AAudio (the one new native dependency),
pre-loaded pad buffers, choke groups honoured, velocity from touch. Full-
screen LCD 4×4, landscape 8×2, BPM readout. Keys layouts (CHROMATIC/
SCALE via `Scales`) ride the same grid. **Exit test:** finger drumming
feels tight enough that you'd play it, on a mid-range phone.

### M5 — Synth + export wizard · M

- SYNTH screen: engine panels straight from the prototype (voice tabs,
  macro sliders, scope, SCRAMBLE, AUDITION, RENDER TO PAD) — the engines,
  recipes and regeneration are all core; the screen is knobs on `Patches`.
- Export wizard: SAF `ACTION_OPEN_DOCUMENT_TREE` to the card, `Preflight`
  as the checklist UI, format cycler over the drivers that already exist —
  program folder / expansion / `.xpn` / native `.xtd` / **Session
  `.xpj`** — dub progress, exact written path on success.

**Exit test — the product's definition of done:** hear it → snip it →
trim it → pad it → play it → write the card → it plays on the Live III.

### App risks (all bounded)

| Risk | Standing |
|---|---|
| Apps opting out of capture | designed for: silence detection + fallback path are specced, `Loudness` is written |
| Latency on cheap phones | isolated to M4; Oboe is the known cure; everything else tolerates jitter |
| SAF friction on SD cards | isolated to M5; zip/share fallback specced |
| Play Store policy | concept doc rules stand: never market against a named service |
| Format drift | retired — corpus-guarded writers, hardware-verified, golden tests |

---

## Part 2 — Hardware verification queue · user, ~one card session

In value order, artifacts already on the branch under `testkit/`:

1. **`SnipSnap Session.xpj`** — the headliner; exercises kit, all four
   instruments, organ sustain loops, and the sequence in one open.
2. **`SnipSnap MPC3 Keys.xty` + `Instruments/`** — keys in tune
   chromatically; EP soft layers darker; *held organ pads sustain forever*
   (the loop-point work proving itself).
3. **`SnipSnap Keys`** (MPC 2 keygroup) — the compatibility-path twin.
4. **`SnipSnap_Factory.xpn`** import + **`Expansions/`** tile.
5. **Velocity kit feel; Shuffle kit bank B.**
6. **The one save no corpus can supply:** build any drum program *on* the
   Live III, save it, drop it in `reference/golden/liveiii-36/` — settles
   what firmware itself writes, plus every remaining value question
   (`fineTune` units, per-pad colour dialects, firmware defaults).

## Part 3 — Core odds and ends · each S, any order, none blocking

- **Dual-generation drum export** — ✓ done: `Mpc3Exporter.exportTrack`
  grew `mpc2Twin` (and `Exporters.export` grew `dualGeneration`), writing
  the MPC 2 `.xpm` twin inside `_[TrackData]/` — the Timeless Glow layout
  the instrument suite already uses. One flag, one test, as scoped.
- **Instrument regeneration sidecar** — ✓ done: `instruments.json` at the
  suite root records engine, zones, velocity layers and the organ's loop
  points from the actual renders (which are deterministic), mirroring
  `kit.json`'s promise. Written by the generator, shipped in
  `testkit/Instruments/`, round-trip tested.
- **Expansion previews as MP3** — the documented convention; we ship WAV.
  Recommendation: keep WAV until a hardware check shows the browser
  ignores it; a pure-Kotlin encoder is not worth it, Android has
  `MediaCodec` when the app exists.
- **Prototype scheme ports** — `prototype/{thumplab,tapedeck,playmode}`
  are single-scheme; porting the six-token system is cosmetic polish.
- **Live III save ingestion** — when item 6 above lands, dissect it and
  diff against all four writers; promote any surprises into the corpus
  guards. (This session's standing job.)
- **MPC 2 hardware backlog** — stays parked; no One/Live II in hand, and
  MPC 3 loads the compatibility format anyway.

## Part 4 — Suggested order

```
desktop session : M0 → M1 → M2 → M3 → M4 → M5        (the app, in order)
this session    : CLI ✓ · :shell view-models ✓ · core support on demand ·
                  odds-and-ends S items · Live III save ingestion when it
                  arrives
user            : one card session (Part 2) — ideally before M5, so the
                  export wizard ships against fully verified formats
```

The dependency graph is honest: nothing in Part 3 blocks the app; the app
blocks nothing but itself; the card session sharpens M5 but doesn't gate
M0–M4. The product ships when M5's exit test passes — and every layer
under it is already proven.
