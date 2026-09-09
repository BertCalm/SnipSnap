# The six features, planned to done

The ROI-ranked feature list, mapped onto concrete work. This is the
product-facing cut of the plan; [`APP_PLAN.md`](APP_PLAN.md) stays the
authoritative milestone view of the app build — work items below reference
its milestones rather than restating them.

**Owners.** `APP` = the desktop session building `:app` (Android SDK,
Compose, services). `CORE` = the cloud session (pure Kotlin/JVM modules —
everything testable without a device). `USER` = hardware checks on the
Live III. Sizes: S ≈ under a day, M ≈ 1–3 days, L ≈ a week-plus.

**The shape of the whole plan in one sentence:** every feature's backend is
done; the CORE column below is a short list of small items that shrink the
APP column further, the APP column is M0–M5 wearing feature names, and the
USER column is one card session.

**Every `USER` row below is collected, in run order, in
[`BENCH.md`](BENCH.md)** — grouped by what you need in your hands (the
phone alone, the phone and the Live III, and the one firmware save that
unblocks the most), with a line to write each answer on. Work from there
rather than hunting these rows one at a time; results come back here.

---

## F1 — The capture-to-export shell (the MVP loop)

**Done:** the entire logic layer. `:shell` holds the trim transport
(`TapeDeckModel` + `PeaksPyramid`), grid state (`KitBuilderModel`), export
flow (`ExportWizardModel` over the shared `Exporters`), pad voices
(`VoiceAllocator`), scheme/type/layout/motion tables, and the personality
gates — all tested. Capture buffer, cleanup DSP, and every writer were
already done and hardware-verified for drums.

**The milestones (= APP_PLAN M0–M5), reconciled against the app on 2026-09-07 — the ticks below name what exists and where:**

| # | Work | Size | Exit test |
|---|---|---|---|
| F1.1 | ✓ done: M0 walking skeleton — `TapeTheme` + the ten-tab `MenuRow`, `KitsScreen` (the shelf over `KitShelf`/`KitStore`), `KitScreen` (the 4×4 grid over `PadPlayer`), `PropertiesScreen` (the live scheme picker) | M | browse kits, tap pads, hear WAVs, flip schemes |
| F1.2 | ✓ done: M1 capture — the always-listening ring (`MicSessionService`, a foreground session with ARM / SNIP / EJECT, `BubbleOverlay`, the TAPE screen's retroactive snip) now reads either source: ARM TAPE (the mic) or ARM INSIDE (another app's audio via MediaProjection consent + `AudioPlaybackCapture`, stereo folded to the mono ring). Dead-air detection (`SilenceWatch`, :audio): three seconds of digital zeros while the phone reports music playing means the app on top opts out, and the TAPE JAM box says so. The platform ending a projection (lock screen, the stop chip) is a routine end with its own toast. The quick-settings tile (`SnipTileService`): SNIP while armed, opens the app to arm otherwise — arming needs a visible Activity (background FGS starts and the consent dialog both), so the tile hands over rather than pretending. Bench: the emulator can't do playback capture; a real phone proves the INSIDE path | L | snip YouTube from inside YouTube (bench); snip the room (✓); share a video in (F3.2) |
| F1.3 | ✓ done: M2 tape deck — `TapeScreen` over `TapeDeckModel` and `PeaksPyramid`, `TapeVoice` for audition, a snip handed to CHOP | M | a YouTube snip becomes a clean one-shot, cut on the hit |
| F1.4 | ✓ done: M4 play mode — `PlayScreen` over `VoiceAllocator`, `PadPlayer` on SoundPool (Oboe deliberately not wired — `PadPlayer`'s own note: effort on a component the pads don't need yet). The exit test is a bench row: USER | M | finger drumming feels tight on a mid-range phone (✓ bench 2026-09-08, 8–11 ms exclusive) |
| F1.5 | ✓ done: M5 export wizard — `ExportScreen` over `ExportWizardModel`, the format cycler (kit, expansion, MPC SESSION (`.xpj`)), SAF create-document through `MainActivity`; "the Live III plays it" stays a bench row | M | the card writes (✓); the Live III plays it (bench) |

M3 is feature F2 below. Risks and their standing: APP_PLAN's table.

## F2 — One-tap Instant Kit

**Done:** the full pipeline (`Transients` → `Chopper` → `Classifier` →
`AutoPlace` → `KitAssembler`), the review screen's whole state machine
(`ChopReviewModel`: tap-to-cycle chips, NOT SURE, placement preview,
SEND TO GRID), and the first real-audio proof (the CLI chopped the factory
groove: kick→A01, hats choking, 92 BPM detected).

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| F2.1 | ✓ done: CHOP screen — `ChopScreen` over `ChopReviewModel` (tap-to-cycle chips, NOT SURE, placement preview, SEND TO GRID, the MELODIC toggle, the teach path). Not drawn: the defrag-grid progress gag — polish, bench | APP | M | one captured bar → playable, sensibly-laid-out kit in under a minute |
| F2.2 | ✓ done: The one tap — INSTANT KIT beside COMMIT on TAPE: the selection (or the whole deck) chopped with the defaults and landed on the grid without the review (`InstantKit`, `:shell`: `ChopReviewModel.chop` by hits → `sendToGrid` → `fromChop`, exactly CHOP's own untouched result; a capture with no hit refused in words); the kit opens on KIT, CHOP can still open it later to argue with the chips | APP | S | capture 8 s of a break, tap once, play the kit — a bar of break lands the kick on A01 with the hats choking, pinned by `InstantKitTest` |
| F2.3 | ✓ done: calibration harness — a labeled-corpus test: WAVs + expected classes under `reference/calibration/`, a report of confusion + per-threshold sensitivity; tune `Classifier` against it | CORE | S | thresholds justified by real captures, not synthetic renders |
| F2.4 | Calibration corpus — a dozen real captured hits (phone captures, not renders), labeled by ear | USER | S | F2.3 has something true to chew on |

`snipsnap classify` is the collection tool for F2.4 — it prints the
features next to every verdict.

## F3 — Share-sheet import

**Done:** the file half. `WavReader` (any PCM/float rate/depth) + the sinc
`Resampler` are the working import path — the CLI is this feature running
on desktop today. The risk table's opt-out mitigation depends on this.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| F3.1 | ✓ done: the share sheet's door — `SEND` (audio/\*, video/\*) and `VIEW` (content/file, audio/\*, video/\*) intent filters on `MainActivity`, now `singleTask` so a share while the app is open lands in the running instance through `onNewIntent`; `ShareInbox` holds the URI until `App` imports it, and consumes it so a rotation never imports twice; the file lands as a snip through `SnipStore.import` (`:shell`, tested: mono, the MPC rate through the sinc resampler, no trim/normalize/doctor — what was shared is what's on the tape — capped at three minutes with the toast saying so), which TAPE finds first by its own source priority; TAPE re-resolves on a `reloadRequest` when the share arrives with the deck already up; with no kit open the first on the shelf opens, and with an empty shelf the deck plays the snip on its own — TAPE no longer needs a kit for one, only for its longest-sample fallback. **Written blind for CI's compiler; the desktop session runs it** | APP | S | share a WAV from a file manager: it is on the deck, the toast names its length |
| F3.2 | ✓ done: the decode — `MediaDecode` (`:app`): a WAV (sniffed by its RIFF/WAVE head, or by MIME) goes straight through `WavReader`; anything else through `MediaExtractor` (first audio track) and `MediaCodec` (the synchronous loop, 16-bit or float PCM through `Pcm` (`:audio`, tested — the same symmetric 16-bit scale `WavReader` reads with, non-finite floats scrubbed), the rate and channel count read off the codec's *output* format because the track's claim can differ), capped at ten minutes before `SnipStore.import` caps shorter; refusals in words — no audio track, no decoder on this phone, a file that could not be opened. The app-side half of F3.3's `DecodeContract`; the instrumentation test against the encoded twins in `reference/fixtures/decode/` is the desktop session's to run. **Written blind** | APP | M | share an MP3 and a screen-recorded MP4: both land on the deck; the six encoded twins pass `DecodeContract.verify` |
| F3.3 | ✓ done: demux conformance fixtures — tiny known-content WAV fixtures + a contract test the app's decode output must pass (rate, channels, sample accuracy) | CORE | S | app-side decode verified against ground truth without an SDK |
| F3.4 | ✓ done: the TAPE JAM box (`Copy.CAPTURE_BLOCKED`) now fires from F1.2's silence detection, once per verdict, as well as from a denied RECORD_AUDIO. The screen-recorder path it points at still needs F3.2's demux to land the recording | APP | S | blocked capture shows the honest fallback, in voice |

## F4 — Synth starter kits

**Done:** the most finished feature. Seven engines + FX, `SynthKits` /
`ThumpKits` / `Shuffle` are **main-source** (the app can call them
directly), recipes rebuild kits bit-for-bit from `kit.json`, SCRAMBLE is
bounded macro rolls, and two generated kits are hardware-verified.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| F4.1 | ✓ done: starter-kit registry — `StarterKits` in `:shell`: name → builder → blurb → seed policy, wrapping the main-source builders so the FRESH TAPE menu is data-driven | CORE | S | registry renders every kit through assemble→preflight in a test |
| F4.2 | ✓ done: NEW KIT menu — `KitsScreen`'s FRESH TAPE menu over `StarterKits`, seeded starters badged REROLLS, a fresh seed per pick, lands on KIT | APP | S | first-run user has a playable kit in 30 s, empty grid never shows |
| F4.3 | ✓ done: SYNTH screen — `SynthScreen`: macro panels over `Patches`, SCRAMBLE, SEND TO PAD (the recipe riding the pad) | APP | M | prototype's Thump Lab behaviour, on device |

Also yields rights-clean Play Store demo content for free.

## F5 — In-key capture

**Done:** `Pitch`/`Scales`/`Tuner`/`InKey` — detection, nearest-in-key
retune via the MPC's own tune fields, refusal to touch unpitched material.
Shipping today as the CLI's `--key`.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| F5.1 | ✓ done: key grammar promoted — `KeySpec` (Am / F#m / "Eb major" / Dminpent parsing) moved from `:cli` into `:audio`, beside `Scales` where it belongs; CLI delegates | CORE | S | one parser, two consumers, same tests |
| F5.2 | ✓ done: kit key field — optional `key` on `Kit`/`kit.json` so the choice persists with the folder | CORE | S | round-trips through `KitStore`; absent = no key, old kits unaffected |
| F5.3 | ✓ done: Key picker + pad tune readout — the KIT screen's KEY door beside the texture doors: twelve root chips and five scale chips set the key at once (`KeyPicker`, `:shell`; metadata in `kit.json`), OFF clears it, IN KEY moves every tonal pad's tune fields (`retuneTonalPads`), the tonal pads' tune readout under it, the key in the LCD header; retune-on-assign: `KitBuilderModel.assign` tunes a TONAL pad into the kit's key through the pad's own tune fields when one is set — audio untouched, the kick untouched, an unpitched hit never corrected. The keyed treatments (TUNE, BODY) now read a key set on the phone | APP | S | set C, assign a 227 Hz note: it lands on A3 by its tune fields; the kick assigned after it carries no tune; every chip pair is a real key and the label round-trips; the readout names each tonal pad in slot order |

## F6 — One-file kit sharing (.xpn)

**Done:** `XpnPackager` writes the real-archive layout deterministically;
`xpn` is in the `Exporters` fan-out; `SnipSnap_Factory.xpn` is built and
waiting.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| F6.1 | Hardware import check — does the Live III's expansion import accept `SnipSnap_Factory.xpn`? (Part 2 queue, item 4) | USER | S | yes/no + exact error text if no |
| F6.2 | ✓ done: `XpnImporter` — read an `.xpn` back into a kit folder (unzip, parse the program, resolve bare sample names); free CLI `import` command; the receive half of sharing | CORE | S | pack → import → re-export round-trips; a foreign commercial `.xpn` imports |
| F6.3 | ✓ done: the kit leaves and comes back — SHARE on the KIT screen packs the open kit as one `.xpn` (`XpnPackager`, preflight's refusal in words) into the share cache and hands it to the chooser through a `FileProvider` (`ShareOut`); the share door's filters now take ZIPs and octet-streams too, and `ShelfImport` (`:shell`, tested) reads the *bytes* to tell a `.xpn` (any ZIP with `.xpm` programs → `XpnImporter.importAll`), a backup (a ZIP of `.xpn`s → `KitBackup.restore`) and an MPC container apart, lands every kit through a hidden staging folder, and moves each onto the shelf under a name nothing there holds ("FUNK 2", `kit.json` renamed to match) — an import never overwrites a kit. **Written blind for CI's compiler** | APP | S | a packed kit lands beside its original as "FUNK 2" with every sample; a backup lands both kits; the chooser opens on SHARE |

F6.2 doesn't wait on F6.1: importing serves the app-to-app share loop even
if the hardware importer says no (folders remain the hardware path).

---

# Wave 2 — the next six, plus a bench of medium bets

Brainstormed after wave 1's CORE column landed, ranked the same way:
by how much backend already exists. Tier 1 items are ≥90% built —
each is a thin construction over tested modules. The same owner codes
apply.

## W1 — Steal the rhythm (groove capture)

The chop pipeline already knows where every hit was (`Slice.sourceFrame`),
what it is, where it landed, and the tempo. That is a pattern: reconstruct
the source performance as an `Mpc3Clip` — hardware-verified, already
carried by `.xtd` and `.xpj` — so a chopped break arrives on the MPC
**already sequenced in its own rhythm**. Likely the app's single best demo
moment, above Instant Kit.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| W1.1 | ✓ done: `CapturedGroove` (`:kit`) — hits + classes + placement + BPM → `Mpc3Clip`: quantize source frames to pulses at the detected tempo (straight or unquantized as captured — keep both), notes on the pads the slices landed on | CORE | S | reconstructed clip's note times match detected onsets within a 16th's tolerance; note pads match placement |
| W1.2 | ✓ done: wire-through — `chop --groove` embeds the clip in `xtd`/`xpj` exports; `ChopReviewModel.grooveClip()` for the app | CORE | S | CLI chop of the factory groove render exports a clip that mirrors the render's own pattern |
| W1.3 | Bench check — does the reconstructed clip *feel* like the break on hardware | USER | S | play the clip beside the source capture; rhythm matches |

## W2 — One note → a playable instrument

MPC keygroups pitch the sample themselves, so one captured tonal snip +
`Tuner`'s root detection + the keygroup writers (both generations,
corpus-corrected) = sample a single note of anything, play it
chromatically. Near-zero new code; in-key capture's payoff squared.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| W2.1 | ✓ done: `OneNote` builder — snip → detected root → one-zone `KeygroupProgram` (full key range), refused with a reason when no confident pitch | CORE | S | known-pitch tone → program with right root; noise → clear refusal |
| W2.2 | ✓ done: CLI `keys <note.wav>` — one-note instrument to `.xty` + `.xpm` twins | CORE | S | artifacts land, detected root printed |
| W2.3 | ✓ done: App action — MAKE INSTRUMENT on the PAD SHEET (and MAKE PAD beside it), the instrument written to `Instruments/` beside the kits — and, since 2026-09-07, *played*: every keygroup package gets a sidecar (`InstrumentStore`, `:kit`) the phone reads, the shelf lists INSTRUMENTS, and KEYS plays one on the 4×4 (`InstrumentEngine` in `:shell`: zones, loops held while the finger is down, the release on let-go, eight voices; `KeysLayout` for root-on-A01, chromatic or scale, octaves) | APP | S | long-press a tonal pad → instrument on the shelf → KEYS plays it |
| W2.4 | Bench — plays in tune chromatically from one sample | USER | S | ears |

## W3 — The reverse loop: MPC → phone → MPC

We already *read* the native format (`Acvs`, `Mpc3Project`, checked
against 59 real projects). An `Mpc3Importer` mirroring `XpnImporter`
(`.xtd` + `_[TrackData]/` → kit folder) turns the app into a round-trip
editor for kits the MPC itself saved — the biggest strategic item on
either wave: it doubles what the product is.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| W3.1 | ✓ done: `Mpc3Importer` — standalone drum `.xtd` + data folder → kit folder (levels, pans, tunes, mute groups, velocity layers, colours where present); missing samples refused by name; keygroup tracks refused with a reason | CORE | M | our export → import round-trips; a commercial `.xtd` from `reference/golden/` parses (sample-missing errors listed, not crashed) |
| W3.2 | ✓ done: CLI `import` learns `.xtd` — dispatch by magic bytes, not extension | CORE | S | both archive kinds import through one command |
| W3.3 | ✓ done: an MPC track through the same share door — `ShelfImport` reads a gzip head as an MPC 3 container (`Mpc3Importer.import`, or `importProject` when `Mpc3Project.isProject`), and a ZIP holding `.xtd`/`.xpj` is unpacked (every entry checked to stay inside, bounded against a zip bomb) so the `_[TrackData]/` folder arrives beside the track. A bare `.xtd` shared alone carries no samples and is refused by name — the honest answer, since one shared file cannot bring a folder | APP | S | a track exported by `Mpc3Exporter`, zipped with its folder, lands with its pad; the bare container is refused in words; a `../` entry is refused before anything is written |
| W3.4 | The Live III firmware save (Part 2 item 6) becomes this feature's fixture as well as the corpus's | USER | S | the round-trip claim tested against firmware's own output |

## W4 — Evil-twin bank

`Shuffle.withRemixBank` re-treats any arranged pads through seeded FX and
records fx-only recipes — it never cared whether the audio was synthesized.
One action: bank B becomes your kit's evil twins.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| W4.1 | ✓ done: `KitBuilderModel.remixBankB(seed)` — bank A read back as arranged pads, `withRemixBank`, twins written to slots 17–32 with recipes; reroll replaces | CORE | S | any kit gains a bank B; same seed reproduces; recipes recorded |
| W4.2 | ✓ done: CLI `remix <kit-dir> [--seed N]` | CORE | S | works on a chopped kit |
| W4.3 | ✓ done: App action — EVIL TWINS on the KIT screen's action row (beside TAKES + BIN and KEY; the texture doors moved to a second row): one tap lights bank B with seeded re-treatments of bank A through `KitBuilderModel.remixBankB` via `KitShelf.evilTwins`, a fresh seed every press so the second press rerolls (the button says so, the header says A+B), the toasts the copy already had | APP | S | one tap, bank B lights up |

## W5 — Ghost notes from one capture

`Velocity.soften` renders darker soft variants (it is how the harp's soft
layers exist) and the velocity-zone pipeline is done end to end. Captured
one-shots get real ghost notes, not just quieter ones.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| W5.1 | ✓ done: `KitBuilderModel.addGhostLayers(slot)` — soften into 1–2 soft zones under the main sample; reversible (clear layers) | CORE | S | zones valid, soft renders measure darker (centroid), pad reverts cleanly |
| W5.2 | ✓ done: CLI `chop --ghosts` — layers on every one-shot pad | CORE | S | chopped kit exports with velocity zones |
| W5.3 | ✓ done: App toggle on the pad sheet — GHOSTS on the PAD SHEET over `addGhostLayers` / `clearGhostLayers` | APP | S | quiet hits sound soft on hardware (bench) |

## W6 — BPM + key metadata everywhere

Trivial and overdue (a CONCEPT.md v2 item). Also buys time-stretch for
free: the MPC warps loops itself when the tempo metadata is right.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| W6.1 | ✓ done: `Kit.tempoBpm` (optional, like `key`) — stamped by chop when confidence clears the bar; `.xpj` export uses it for the project tempo | CORE | S | round-trips; project master tempo = detected tempo |
| W6.2 | ✓ done: loop-class sample names carry the tempo (`Loop_92bpm_01`); kit name suggestion gains bpm/key when known | CORE | S | names match detection; off when detection isn't confident |

## Wave-2 bench of medium bets (planned, not queued)

| Item | What | Owner | Size | Note |
|---|---|---|---|---|
| W7 seamless sustain loops | ✓ done (CORE wave 3): `LoopCut` — whole-period loop search after the attack, a baked crossfade when the raw seam isn't clean, honest refusals | CORE | M | hold a captured string, it sings forever |
| W8 melodic chop | ✓ done (CORE wave 3; the CHOP screen's MELODIC toggle is X1.3) | CORE | S–M | a vocal run becomes an instrument-ish kit |
| W9 takes + the 30-day bin | ✓ done (CORE wave 3, `TakesBinScreen` on the phone as X2.3) | CORE + APP | S | trust feature; model in `:shell` |
| W10 teach-the-machine | ✓ done in core (wave 3: `TeachLog`, the harness ingests it), wired into CHOP behind the consent switch SETUP draws (X4.4) | CORE + APP | S+S | ordinary use becomes classifier training data; needs a consent switch |
| W11 one-file backup | ✓ done (CORE wave 3: `KitBackup`, CLI `backup`); the phone's share/backup action is X3.3, open | CORE | S | retention insurance |
| W12 pad mini-waveforms | ✓ done: `PadPeaks` (`:shell`, tested) reads every pad once per kit edit off the main thread and keeps 44 peak columns per slot, nothing more; `KitScreen`'s cells draw them centred in the pad's class colour under the name, a missing or unreadable file drawing nothing and breaking nothing | APP | S | perceived-polish per effort champion |

**Rejected, with reasons:** stem separation (heavy ML, off-brand for an
honest tool); our own time-stretch (the MPC warps better — W6 ships the
metadata instead); cloud kit library (rights, servers, moderation — the
growth loop stays peer-to-peer `.xpn`).

---

# Wave 3 — the bench comes off the bench

Wave 2's medium bets promoted to planned work, plus two follow-ons that
waves 1–2 opened up. Same ranking rule; X6 stays last because it's the
wave's only real DSP.

## X1 — Melodic chop (was W8)

Grid-chop a phrase, `Pitch` each slice, lay the slices out **low → high**
so the pads play like an instrument — a vocal run or a bass line becomes
something you can perform.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| X1.1 | ✓ done: melodic placement in `ChopReviewModel` — pitched slices sorted ascending onto the pads, unpitched appended in capture order; per-row pitch cached | CORE | S | out-of-order tones land in ascending pad order |
| X1.2 | ✓ done: CLI `chop --melodic` | CORE | S | a scrambled scale chops into a playable run |
| X1.3 | ✓ done: the CHOP screen's MELODIC toggle beside the classic layout | APP | S | MELODIC next to the classic layout |

## X2 — Takes + the 30-day bin (was W9)

Kit-is-a-folder makes history nearly free. Every save archives the
previous `kit.json` as a take; cleared samples go to a bin instead of
oblivion ("EJECTED. THE BIN KEEPS IT 30 DAYS" has been in the copy since
day one — now it's true).

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| X2.1 | ✓ done: takes — `save()` archives the outgoing `kit.json` under `.takes/`, capped and rotated; `takes()` lists, `restoreTake(n)` rolls back | CORE | S | edit → save → restore → the earlier kit is back |
| X2.2 | ✓ done: the bin — deletes move to `.bin/` stamped with when; `binContents()`, `purgeBin(olderThanDays = 30)`, `emptyBin()` | CORE | S | a cleared pad's WAV is recoverable for 30 days |
| X2.3 | ✓ done: `TakesBinScreen` — TAKES + BIN off the KIT action row, restore and the 30-day countdown | APP | S | the copy's promise, visible |

## X3 — One-file backup (was W11)

Every kit as an `.xpn` inside one archive; restore feeds them back
through `XpnImporter`. Retention insurance and the "new phone" story.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| X3.1 | ✓ done: `KitBackup` (`:kit`) — backup(kitsRoot) → one zip of per-kit `.xpn`s (preflight-blocked kits skipped and named); restore(zip) → kit folders | CORE | S | backup → wipe → restore round-trips every clean kit |
| X3.2 | ✓ done: CLI `backup` / `restore` | CORE | S | works on a folder of chopped kits |
| X3.3 | ✓ done: BACKUP on the KITS screen — `KitBackup.backup` into the share cache as "SnipSnap Shelf <date>.zip", handed to the chooser like SHARE; the toast counts what packed and what preflight skipped; the same file shared back in restores every kit through `ShelfImport` | APP | S | one file leaves the phone with everything on it; shared back, every kit lands |

## X4 — Teach the machine, data path (was W10's CORE half)

Every chip override is a labeled example. Log **feature vectors + labels
only** (never audio — rights-clean by construction) and let the
calibration harness eat them.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| X4.1 | ✓ done: split `Classifier` — `classify(Features)` beside `classify(Snip)`, so a feature vector is testable without its audio | CORE | S | both paths agree on every corpus render |
| X4.2 | ✓ done: `TeachLog` (`:shell`) — jsonl of {features, label} from `ChopReviewModel`'s overridden rows; reader for the harness side | CORE | S | overrides round-trip; a log line re-classifies |
| X4.3 | ✓ done: harness ingestion — overrides.jsonl in `reference/calibration/` scored alongside the WAVs | CORE | S | logged corrections show up in the confusion report |
| X4.4 | ✓ done: Consent switch + wiring in the app — SETUP's TEACH THE MACHINE row (OFF / ON, off by default, remembered like the scheme), the consent line under it in the copy's own words plus what ON actually logs; CHOP reads the boolean, the toasts are TEACHING ON / OFF | APP | S | off by default; nothing leaves the device either way |

## X5 — Multisample keys (new)

`keys` grows from one note to several: each pitched capture becomes a
zone at its detected root, zones tiling at the midpoints — a real
multisampled instrument from a handful of notes.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| X5.1 | ✓ done: `OneNote.multiProgram` — roots detected and sorted, zones tile without gaps, duplicate roots resolved, one bad note refuses by name | CORE | S | three tones → three tiled zones, roots right |
| X5.2 | ✓ done: CLI `keys a.wav b.wav c.wav` | CORE | S | the multisample lands dual-generation |

## X6 — Sustain loops on captured notes (was W7)

The organ's whole-period loop cut, generalized to real audio: find a
loop of whole periods in the sustain, trim the sample to end exactly on
the loop boundary (both formats' idiom loops to sample end), and bake a
short crossfade when the raw seam isn't clean enough. Hold a captured
string and it sings forever.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| X6.1 | ✓ done: `LoopCut` (`:audio`) — pitch-tracked whole-period loop search in the sustain region, seam scored by periodicity, crossfade baked when needed; unpitched/too-short refused | CORE | M | seam periodicity error under 1% of signal energy on a vibrato-laden tone; noise refused |
| X6.2 | ✓ done: `keys --loop` — one-note and multisample instruments gain sustain loops (both formats' loop idioms already ship) | CORE | S | the instrument's layers carry loop points |
| X6.3 | Bench — a held pad sustains with no audible seam | USER | S | ears, the only judge that counts |

## X7 — Whole-project import (new)

W3's importer said "project import is a later step"; this is the step.
Every drum track inside an `.xpj` becomes its own kit folder — a whole
MPC session's kits, editable on the phone.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| X7.1 | ✓ done: `Mpc3Importer.importProject` — each drum-type track parsed by the same instrument walk, samples from `_[ProjectData]/`, names unique-ified; keygroup tracks skipped and named | CORE | S–M | our Session-style export round-trips into N kits; commercial `.xpj`s parse |
| X7.2 | ✓ done: CLI `import` handles `.xpj` — imports every drum kit inside, reports each | CORE | S | one command, whole session |

---

# Wave 4 — closing the loops we half-opened

Three waves in, the ranking question shifts from "what's nearly free" to
"what closes an open loop." Y1 fixes a leak in the product's core promise;
Y2–Y3 multiply the hardware payoff of things already built; Y4–Y5 take the
FX rack and the kit folder to their last unreached surfaces; Y6 turns test
machinery into the bench tool the format work still needs.

## Y1 — Total recall: kits remember their beat and their source

Two holes in "the folder is the kit": `chop --groove` embeds the rhythm at
export time only (chop today, export tomorrow — groove gone), and both
importers **drop the clips** they find (an MPC kit round-trips minus its
patterns). Also: chopped pads carry no provenance.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| Y1.1 | ✓ done: `groove.json` sidecar — the captured clip persisted beside `kit.json` (notes, bars, tempo); written by chop, read by `Exporters`, auto-embedded in every native export | CORE | S | chop → export *later* → the groove is still there |
| Y1.2 | ✓ done: Importers keep clips — `Mpc3Importer` reads `sharedClipMap` entries back into `groove.json` | CORE | S | MPC kit with patterns round-trips with its patterns |
| Y1.3 | ✓ done: Chop provenance — per-pad `source` gains file, sourceFrame, lengthFrames | CORE | S | every chopped pad says where it came from |

## Y2 — Pattern variations: four grooves per kit

`sharedClipMap` is a **list** — commercial tracks carry 4 entries, ours 1
(hardware-accepted). Generate mechanical variations of the captured groove
— as-captured, quantized-tight, half-time, sparse — and ship clips A–D:
load a chopped break, flip between four ready patterns.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| Y2.1 | ✓ done: `GrooveVariations` — pure `Mpc3Note`-list transforms (quantize, half-time, thin, velocity-flatten), named clips | CORE | S | each variant provably derived: note counts/times follow the rule |
| Y2.2 | ✓ done: Writer takes a clip **list** (≤4), corpus-guarded; chop `--groove` ships the four | CORE | S | key-path guard still green; payload carries 4 clips |
| Y2.3 | Bench — the Live III's clip list shows and plays all four | USER | S | flip patterns on hardware |

## Y3 — The session builder: `snipsnap project`

`SessionProjectGenerator` builds the fixed factory session as test code.
Generalized: N kit folders + M instrument packages → one `.xpj`, kits on
tracks with their grooves, instruments beside them, mixer wired.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| Y3.1 | ✓ done: `SessionBuilder` (`:kit`) — stage kit folders + `.xty` instrument packages into `_[ProjectData]/`, hand `Mpc3ProjectWriter` the track list; grooves from `groove.json` ride onto the sequence | CORE | S–M | two kits + an instrument → one `.xpj` our reader accepts with the right track types |
| Y3.2 | ✓ done: CLI `project <kit-dir>... [--keys pkg...] [--name]` | CORE | S | one command, whole session on the card |
| Y3.3 | ✓ done: the export wizard's format cycler carries MPC SESSION (`.xpj`) — KITS + GROOVES | APP | S | the wizard's biggest format, one tap |

## Y4 — Pad treatments: the FX rack pointed at one pad

Remix bank B proves the whole path (FX on captured audio, fx-only recipes,
re-treatability); there's just no way to *crush the snare*. Named
treatments over the existing chains, recorded as recipes, reversible via
the bin.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| Y4.1 | ✓ done: `KitBuilderModel.treatPad(slot, treatment, amount, seed)` — the Shuffle treatment table exposed singly + amount-scaled; original WAV binned, recipe recorded; `untreatPad` restores | CORE | S | treat → audibly different, recipe present; untreat → original bytes back from the bin |
| Y4.2 | ✓ done: CLI `treat <kit-dir> <pad> <treatment> [--amount] [--seed]` | CORE | S | crush A02 from the terminal |
| Y4.3 | ✓ done (wave UU): the PAD SHEET TREATMENT card's second row — TAIL · SLAP · WASH · PUNCH over `KitBuilderModel.characterPad` (whole-pad, layers included, bin-backed) | APP | S | one tap per character |

## Y5 — The preview renderer: every kit listenable before loadable

The kit's WAVs plus its groove is an offline sampler render away from a
demo. Every expansion `[Previews]/`, every `.xpn`, and the app's "share as
audio" story get *the kit playing its own beat*.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| Y5.1 | ✓ done: `KitPreview` (`:kit`) — mix pad WAVs at clip note times (levels, choke honoured); groove absent → a two-bar default pattern over the core classes | CORE | S | rendered preview is non-silent, right length, deterministic |
| Y5.2 | ✓ done: Wire-through — expansion + `.xpn` previews use it when a groove exists; CLI `export` gains `--preview`; app share-as-audio hook | CORE | S | `[Previews]/<Kit>.xpm.wav` is the kit's own beat |
| Y5.3 | Bench — does the expansion browser play it (the open WAV-vs-MP3 question) | USER | S | noted either way |

## Y6 — `snipsnap diff`: the corpus guard as a bench tool

The key-path machinery that keeps the writers honest lives in test code.
As a CLI verb — structured diff of two MPC files, either generation — it
becomes the instrument for the firmware-save job and the permanent
"why won't this file load" tool.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| Y6.1 | ✓ done: `MpcDiff` (`:mpc3`) — key paths only-in-A / only-in-B / value-differs (schema-aware: arrays as `[*]`, sentinel-tolerant), for ACVS payloads and MPC 2 XML alike | CORE | S–M | our export vs a golden file reproduces the known documented deltas |
| Y6.2 | ✓ done: CLI `diff <a> <b> [--values]` — grouped, readable, exit 1 when different | CORE | S | `diff ours.xtd firmware.xtd` is the whole liveiii-36 dissection |
| Y6.3 | The firmware save lands → run Y6.2, promote surprises into the corpus guards | CORE+USER | S | the standing job, now one command |

**Below the line (wave 5 candidates):** kit merge into banks A/B, batch
chop over a folder, auto slice-count. Standing rejects unchanged.

---

# Wave 5 — product polish: musical, workflow, interop

Four waves of format archaeology bought a pipeline that speaks every MPC
dialect; this wave makes it *nicer to use*. Two musical items (swing, auto
slice-count), two workflow (batch chop, kit merge), two interop/delight
(MIDI bridge, expansion tile art). All six are small, none touches the
corpus-guarded containers beyond machinery that already exists.

Build order puts the art renderer **first** — its look is a taste call,
so the prototyping loop (Z6.2) starts early and runs beside the rest of
the wave instead of gating the end of it.

## Z1 — Swing: the MPC's soul

`GrooveVariations` ships captured/tight/half/sparse and not the single
most famous thing an MPC does. Swing lives at quantize, the way the
hardware does it: snap to the 16th grid, then push every even ("and")
16th late by the swing amount — 50% is straight, 66% is triplet feel,
the classic MPC range is 54–75. A seeded humanize jitter rides along.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| Z1.1 | ✓ done: `GrooveVariations.swing(clip, percent)` — quantize-then-push on even 16ths, velocities untouched; `humanize(clip, amount, seed)` — bounded seeded jitter on times; when a swing is asked for, the "Tight" slot becomes the swung clip (four-slot budget respected) | CORE | S | even 16ths land late by exactly `(pct−50)/50 × 240` pulses, odd 16ths and velocities untouched; same seed, same jitter; 50% is a no-op |
| Z1.2 | ✓ done: CLI `chop --swing PCT` (with `--groove`); range-checked 50..75 | CORE | S | the exported clip list carries the swung variant; the payload guard stays green |

## Z2 — MIDI bridge: grooves that leave the ecosystem

`groove.json` ↔ Standard MIDI File. Export and every DAW opens the
captured rhythm — and the MPC's own browser plays `.mid` too; import and
beats programmed anywhere become kit grooves. SMF is ~200 lines of pure
JVM, no deps, and our clips are already 960 PPQ — MIDI's own favourite
resolution.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| Z2.1 | ✓ done: `MidiGroove` (`:kit`) — SMF format-0 writer + reader: clip notes ↔ note-on/off pairs (velocity 0..1 ↔ 0..127), tempo meta event from the kit's BPM, 960-division header; unreadable files refused with a reason | CORE | S–M | write → read round-trips every note, time, length, velocity; a hand-built DAW-style fixture `.mid` imports correctly |
| Z2.2 | ✓ done: CLI wire — `export --export mid` writes `<Kit> <Variant>.mid` per stored groove; `import <file.mid> --into <kit-dir>` lands a DAW beat as the kit's groove | CORE | S | chop --groove → export mid → files a DAW opens; import a `.mid` → native exports carry it |

## Z3 — Auto slice-count: stop making the user guess

`--slices 16` is a guess we make the user confirm. The onset-strength
ranking inside `Chopper` already knows how strong every hit is: find the
knee in that curve and keep what's above it.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| Z3.1 | ✓ done: `Chopper` auto mode — slice count chosen at the largest relative drop in sorted onset strength (bounded 2..64); CLI default becomes auto when `--slices`/`--grid` are absent, explicit values behave exactly as today | CORE | S | an 8-hit synthetic break auto-chops to 8; a dense roll stays bounded; `--slices N` output is byte-identical to before |

## Z4 — Batch chop: the crate-digging verb

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| Z4.1 | ✓ done: CLI `chop-all <folder>` — every audio file in the folder through the chop pipeline with shared options; per-file failures named and non-fatal; one summary table (file → kit → pads → tempo); exit 1 only when nothing succeeded | CORE | S | a mixed folder (good WAVs + one broken file) yields kits for the good ones and names the bad one; also the calibration corpus mass-run tool |

## Z5 — Kit merge: bank B, earned not invented

The complement of remix: remix invents a bank B, merge earns one from
another kit.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| Z5.1 | ✓ done: `KitMerge` (`:kit`) — B's bank-A pads onto the target's slots 17–32: samples copied under re-prefixed stems, colours/mute groups/velocity layers/recipes carried, A's groove and key kept; refuses when A's bank B is occupied (unless replacing is asked for); CLI `merge <a> <b> [--out DIR] [--replace]` | CORE | S | merged kit preflights clean; both sources untouched; B's audio byte-identical under its new stems |

## Z6 — Expansion tile art: the blank square on the hardware browser

`ExpansionWriter` has carried an `artworkPng` parameter since wave 0 and
nothing fills it — every expansion we export is a blank tile on the Live
III. Procedural cover art: deterministic, seeded, rendered headless via
`java.awt` — no new dependencies. **The look is a taste call, so the
renderer is parameterized and the prototyping loop is the feature:** a
standalone CLI verb regenerates a PNG in one command, you iterate on
style/scheme/seed, and the winning direction becomes the export default.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| Z6.1 | ✓ done: `KitArt` (`:shell`) — cover renderer: kit name + waveform/pad-grid motifs drawn from the kit's own samples and classes, palette from `Schemes`, style + seed + size parameters; deterministic bytes for identical inputs; CLI `art <kit-dir> [--style NAME] [--scheme NAME] [--seed N] [--size PX] [--out FILE]` for the iteration loop | CORE | S–M | same inputs → identical PNG; valid dimensions; every style renders every starter kit without error |
| Z6.2 | ✓ done: prototype loop — **verdict (2026-08-25): waveform and rings won**; waveform is the export default, rings the runner-up one `--art rings` away | USER+CORE | S | the default style was chosen by eye |
| Z6.3 | ✓ done: wire-through — waveform tiles render by default into expansion/`.xpn` exports (CLI and export wizard alike; artwork plumbed through `Exporters`); `--art` picks, `--no-art` opts out | CORE | S | exported expansion carries the tile; the Live III browser shows it (bench) |

**Below the line (wave 6 candidates):** multi-sequence projects — the
four groove variations as verse/chorus sequences in one `.xpj` (waits on
the Y2.3 bench verdict); vintage 12-bit SP mode (treat's crush covers
most of it); CLI audio playback (the preview WAV already auditions
everywhere). Standing rejects unchanged.

---

# Wave 6 — kits become music

The format pipeline is done and polished; this wave's lens shifts to
arrangement, feel, key, and collections. One corpus-archaeology piece
(AA1 — it leads, the probe gates its writer), two renders (AA2, AA5),
three musical brains (AA3, AA4, AA6); everything after AA1 is
independent. Build order: AA1 → AA6 → AA3 → AA4 → AA2 → AA5.

## AA1 — Multi-sequence projects: verse/chorus on hardware

Four groove variations ship per kit, but a `.xpj` carries one sequence —
the variations ride the clip list the bench hasn't confirmed. Sequences
are the corpus-standard mechanism (every commercial project carries
several, named), and our own corpus rule already says grooves ride the
sequence's `trackClipMaps`. Promote the variations to named sequences:
the hardware's sequence switcher *is* the pattern flip.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| AA1.1 | ✓ done: Corpus probe — how commercial `.xpj`s encode multiple sequences (the sequence list's keys, names, per-sequence `trackClipMaps`, lengths); findings recorded in docs/MPC3_FORMAT.md | CORE | S | probe notes + the key paths, before any writer code |
| AA1.2 | ✓ done: `Mpc3ProjectWriter` multi-sequence — a sequence list (name + per-track clips each); `SessionBuilder` ships the four variations as four named sequences; corpus-guarded like everything else | CORE | M | key-path guard green; our reader sees four sequences; `diff` vs a golden project shows only documented deltas |
| AA1.3 | Bench — load the project, flip sequences on the Live III | USER | S | verse/chorus flips on hardware |

## AA2 — Session mixdown: the whole beat as one WAV

`KitPreview` renders one kit playing its beat; `SessionBuilder` stages
several kits with grooves. Connect them: the entire session mixed to one
stereo WAV — the demo beside every `.xpj`, the app's share-as-audio for
sessions.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| AA2.1 | ✓ done: `SessionMixdown` (`:kit`) — each kit's groove rendered via `KitPreview` at the session tempo, summed, peak-limited; CLI `project --mixdown` writes `<Name>.wav` beside the `.xpj` | CORE | S | non-silent, deterministic, as long as the longest kit render; a solo-kit session mixes to that kit's own preview |

## AA3 — Key guess: the capture names its own key

`--key Am` assumes the user knows the key. Per-slice pitch already
exists; a chroma histogram over the tonal slices scored against the
scale table picks root + scale, with a confidence gate and an honest
refusal when nothing tonal is there.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| AA3.1 | ✓ done: `KeyGuess` (`:audio`) — pitch-class histogram from pitched slices → best (root, scale) + confidence; drums-only material refused with a reason | CORE | S–M | a scrambled A-minor arpeggio → Am; DrumSynth kit → refusal |
| AA3.2 | ✓ done: Wire — chop stamps `kit.key` when confidence clears the bar (and says so); `--key auto` asks for it explicitly and errors when the guess can't clear | CORE | S | chop of tonal material lands a keyed kit; `--key auto` retunes without naming a key |

## AA4 — Tempo-fit: loops repitched, SP-style

A 95 BPM loop in a 92 BPM kit drifts. The classic sampler answer is
repitch — resample by the tempo ratio, pitch rides along, zero
artifacts, and *the* revered lo-fi move. The sinc `Resampler` already
does the work.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| AA4.1 | ✓ done: `TempoFit` (`:audio`) — repitch a snip by `from/to` tempo ratio; chop `--fit-tempo BPM` applies it to LOOP-class slices (detected tempo → target), loop stem names restamped to the new tempo | CORE | S | a 95→92 fit scales duration by exactly the ratio; the name says `92bpm`; one-shots untouched |

## AA5 — Pack builder: N kits, one expansion

Our expansion carries one kit; commercial packs carry a catalog under
one tile. Turn chop-all's output into a shippable product.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| AA5.1 | ✓ done: `PackBuilder` (`:kit`) — many kit folders → one expansion (every program under `Programs/`, samples arranged the way real multi-program packs do it, per-kit previews, one manifest/XML); pack tile = the title over the kits' combined waveform (small `KitArt` extension); CLI `pack <kit...> --title NAME [--out] [--xpn]` | CORE | S–M | a 3-kit pack has every program loadable and round-trips through `import`; blocked kits skipped and named, backup-style |

## AA6 — Groove transfer: steal the feel, not the notes

The MPC's own legendary feature (groove templates): extract the
timing-and-velocity deviations from any clip — a captured break, an
imported `.mid` — and apply them to another kit's quantized grooves.
Pure note-list math; composes with swing and humanize.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| AA6.1 | ✓ done: `GrooveFeel` (`:kit`) — `extract(clip)`: per-16th-position timing offsets + velocity shape; `apply(template, clip)`: quantized notes take the template's pocket; CLI `feel <kit-dir> --from <kit-dir or .mid>` rewrites the kit's variations with the donor's feel | CORE | S | extract-from-swung applied to straight reproduces the swing offsets; velocities follow the donor's shape; same donor, same result |

**Below the line (wave 7 candidates):** auto velocity-stacking from
similar slices (needs the calibration corpus's similarity ground truth);
kit doctor auto-fix (Preflight's honest refusal is the feature until
proven otherwise); phase-vocoder time-stretch (repitch is cheaper and
more authentic). Standing rejects unchanged.

---

# Wave BB — hardening: trust nothing that crossed the card

Six feature waves grew the attack surface: SnipSnap now parses five
formats people feed it from *outside* — `.xpn` zips, ACVS gzips, `.mid`,
WAVs, `kit.json` — and writes files whose names come from inside those
files. The contract this wave enforces: **any bytes in → a typed refusal
or a valid result out, quickly; never a traversal, an OOM, a hang, a
stack overflow, or a raw stack trace.** BB1 is a real vulnerability and
leads; the rest is depth behind it. This wave adds no user-facing
features — it makes the ones we shipped safe to point at a stranger's
file.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| BB1 | ✓ done: path-traversal hardening — a shared `SafePath` centralises the defense that was scattered and implicit. `basename()` flattens any import-derived sample name (both importers already stripped paths ad hoc — including the corpus's legit `../Samples/Kick.wav` shared-pool references, so flatten, don't refuse); `child(dir, name)` is the *enforced* invariant that every read and write of such a name stays inside its folder, catching any future regression. Not a live escape — both importers were already defended — but now explicit, tested, and regression-locked | CORE | S | a `.xpn`/`.xtd` with `../../../pwned` in a sample name flattens to a basename inside the destination, nothing escapes; a legit `../Samples/` reference still imports; `SafePath` unit tests pin the guard |
| BB2 | ✓ done: resource ceilings — `Acvs.read` caps inflated size (a gzip bomb is refused, not inflated); zip reads cap per-entry and total uncompressed bytes and entry count; `MidiGroove` caps event count; `WavReader` checks the declared data size against the file before allocating | CORE | S | a 10 KB gzip/zip bomb refuses in milliseconds under a fixed heap; a WAV claiming 4 GB of samples refuses instead of allocating |
| BB3 | ✓ done: fuzz harness — seeded mutation fuzzing (byte-flips and truncations of valid fixtures) across `WavReader`, `Acvs`, `MidiGroove`, `XpnImporter`, `Mpc3Importer`, `MpcDiff.load`; every input yields a typed failure or a valid parse within a time bound, never an uncaught `AIOOBE`/OOM/hang. Hostile fixtures committed under `reference/fixtures/hostile/` | CORE | M | N seeded mutations per reader all end in a typed outcome inside the budget; the harness reruns deterministically |
| BB4 | ✓ done: atomic saves — `KitStore`, `GrooveStore`, `TeachLog` write to a temp file and rename (atomic on the same volume), so a process killed mid-save never leaves a half-written `kit.json`; loaders skip torn entries in `.takes/`/`.bin/` rather than throwing | CORE | S | a truncated `kit.json` written mid-save is detectable and the prior file survives; a garbage `.takes/` entry doesn't break `takes()` |
| BB5 | ✓ done: CLI catch-all + hostile sweep — `Cli.run` catches any unexpected `RuntimeException` and prints one honest line at exit 1 (no stack trace to the user); a sweep test runs every command against the hostile corpus and asserts a clean typed exit every time | CORE | S | no command ever prints a Java stack trace or exits non-{0,1,2} on a hostile file |
| BB6 | ✓ done: import metadata validation — duplicate pad slots, out-of-range levels/tunes/velocities, and self-referential or out-of-order velocity layers in an imported program are clamped where harmless and refused-with-a-name where not, before a `Kit` is built | CORE | S | a program with two pads on slot 1, a 9.0 level, and a layer pointing at a missing sample is refused with the specific reason |

**Below the line (post-hardening):** signed-kit provenance (overkill for
a sampler); sandboxed decode (the JVM readers are already
allocation-bounded once BB2 lands). Standing rejects unchanged.

---

# Wave CC — deeper hardening: numbers and the format contract

Wave BB secured the untrusted-*structure* surface (traversal, bombs,
torn writes, crash-on-parse). This wave secures the untrusted-*number*
surface and the format contract itself. The lead is real: a float WAV
decodes samples straight from bytes with **no non-finite guard anywhere**
(`grep isNaN/isFinite` across `:audio` and `:kit` returns nothing), so a
corrupt or hostile float WAV carrying `NaN`/`±Inf` poisons the whole
pipeline — `peak()` goes `NaN`, normalization multiplies every sample by
`NaN`, and the *exported* WAV is all-NaN garbage. CC1 closes that; the
rest deepens the guarantees around it.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| CC1 | ✓ done: NaN/Inf sanitization — float `WavReader` maps every non-finite sample to 0 as it decodes; `WavWriter` refuses (or scrubs) any non-finite sample so no export can carry one; peak/normalization in `Cleanup` and the render path treat a non-finite input as 0, not as a poison value. The one untrusted-input class BB1–BB6 missed, because it arrives as a number, not a shape | CORE | S–M | a float WAV full of NaN/±Inf imports as clean silence; no export (WAV, preview, mixdown) ever contains a non-finite sample; a NaN in one pad can't silence a whole normalized kit |
| CC2 | ✓ done: overflow guards in frame math — `KitPreview.totalFrames`, `SessionMixdown`, `Resampler` output length and any `frames * channels` / `* 2` computed in `Int` are done in `Long` or bounded, so no validated-but-extreme input (a slow tempo, a long loop) yields a negative array size or a wrapped index | CORE | S | a kit at the tempo/length extremes renders or refuses cleanly, never `NegativeArraySizeException` |
| CC3 | ✓ done: golden snapshots — the factory kit exported to every format has its exact bytes committed under `reference/golden/snapshots/`; a test asserts byte-equality, so any silent format drift from a refactor fails loudly and on purpose (regenerate step documented) | CORE | S | exports match the committed goldens to the byte; a deliberate writer change fails the snapshot until the golden is refreshed |
| CC4 | ✓ done: round-trip property fuzz — seeded random valid kits + grooves → export (folder / xtd / xpj / xpn) → re-import → assert every pad, level, tune, mute group, colour and note survives; N seeds, deterministic. Catches *writer* bugs the reader fuzz (BB3) structurally cannot | CORE | M | N seeded kits round-trip with no lost or corrupted pad/note across every re-importable format |
| CC5 | ✓ done: temp-file leak audit — every `createTempFile`/`createTempDirectory` (`KitBackup`, `XpnPackager`, `PackBuilder`, `SessionBuilder`, the CLI) sits in a `try/finally`; a forced-failure test proves an exception mid-operation leaves no temp residue behind | CORE | S | inject a failure into each temp-using path; no stray temp file or dir survives |
| CC6 | ✓ done: preflight ⇒ export invariant — a kit that passes `Preflight.check` must always export without throwing; the two must never disagree. A property test fuzzes kits, keeps the preflight-clean ones, and asserts every one exports to every format | CORE | S | no preflight-clean kit throws on export in any format |

**Below the line (post-CC):** audio watchdog/timeouts on the DSP path
(the JVM work is already bounded and synchronous); differential fuzzing
against a second MPC-format implementation (none exists to diff against).
Standing rejects unchanged.

---

# Wave DD — deeper: parser robustness

Waves BB and CC hardened the untrusted-input surface against attacks and
bad numbers. This wave goes after **silent mis-parsing** — where an import
succeeds but is quietly wrong — and the parser coverage gaps underneath.
The lead is a real bug: `XpnImporter` reads `.xpm` programs with regular
expressions, so legitimate XML variety (attribute order, extra attributes,
whitespace, CDATA, comments) drops pads with no error. Not padded to six —
each item is a real gap.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| DD1 | ✓ done: robust `.xpm` parsing — replace the regex program parser in `XpnImporter` with a proper, XXE-safe DOM parse (the one `MpcDiff` already uses for MPC 2 XML), so attribute order, extra attributes, whitespace, CDATA and comments no longer silently drop pads — and numeric character references (`&#233;`, `&#x2764;`) resolve for free, which the hand-rolled `xmlUnescape` never did | CORE | M | a valid `.xpm` with reordered/extra attributes, a comment, a CDATA sample name and a numeric-entity program name imports every pad correctly; the golden corpus still imports unchanged |
| DD2 | ✓ done: JSON parser property + fuzz — `parse(write(v)) == v` over seeded random `JsonValue` trees; direct mutation fuzz asserting typed-refusal-or-valid; and the edge cases a hand-rolled parser gets wrong (exponents, `-0`, huge/tiny numbers, `\u` escapes and surrogate pairs, duplicate keys). The most load-bearing parser in the codebase, currently the least adversarially tested | CORE | M | N random trees round-trip; every mutation ends typed-or-valid; the number/unicode edge cases parse to the right values |
| DD3 | ✓ done: DSP invariant properties — over seeded synthetic audio: `Chopper` slices are in-bounds, ordered, and cover to the end; `Classifier` always returns a valid `DrumClass` with confidence in `0..1`; extracted features are never NaN. The pipeline's contracts, pinned as properties instead of examples | CORE | S | thousands of synthetic inputs hold every invariant, no NaN, no out-of-range |
| DD4 | ✓ done: WAV chunk-variety lock — a test proving a WAV carrying `LIST`/`fact`/`JUNK`/`cue` chunks before and after `data` reads correctly (`WavReader` already skips unknown chunks; this pins it so a refactor can't quietly lose real-world-file support) | CORE | S | a multi-chunk WAV decodes to the same samples as its bare-`data` twin |

**Below the line (post-DD):** a from-scratch streaming XML/JSON pull parser
(the DOM + hand-rolled parsers are bounded and sufficient); schema
validation of every payload field against the corpus (the key-path guard
and golden snapshots already cover drift). Standing rejects unchanged.

---

# Wave EE — iconic: features people tell each other about

A different lens than ROI: not what's nearly free, but what deepens the
product's identity — the tape metaphor, the crate-digging ritual, the
hardware lineage. Two sound-character items (eras, wear), two creative
acts (the answer, the beat tape), one identity artifact (the J-card).
All pure-JVM CORE on machinery that already exists; all compound (wear
shows on the J-card, eras colour the beat tape, the answer joins the
session). Build order **EE2 → EE1 → EE3 → EE4 → EE5**: eras give wear
its saturation/filter primitives, and the J-card wants everyone else's
output.

## EE2 — The Time Machine: era-faithful sound

"Vintage mode" was rejected in wave 5 as a knob crush already covered.
As a *suite* it is a different thing: the specific math of specific
machines is what beatmakers pay real money for. Named eras, honest
"inspired-by" language, recipe-recorded and reversible like every
treatment.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| EE2.1 | ✓ done: `Eras` (`:synth`) — named era chains built from exact primitives: `sp1200` (12-bit truncation at 26.04 kHz with the aliasing that implies), `mpc60` (12-bit non-linear companding, its input filter), `tape` (saturation + wow + HF loss), `phone` (band-limit + companding). Each a deterministic Snip→Snip with an `amount`; recipes recorded | CORE | M | each era measurably does its math (bit depth, rate, spectrum); deterministic; recipe round-trips |
| EE2.2 | ✓ done (era ages the *files*, bin-backed, so every render and export carries it): Wire — CLI `era <kit-dir> <era> [--amount] [--pads]` (bin-backed undo like `treat`); preview/mixdown render through it; era rides the kit as metadata | CORE | S | era → audibly/measurably different kit; undo restores byte-identical originals |

## EE1 — Tape wear: the kit as a living tape

The product pretends to be a tape deck; make the metaphor real. Opt-in
per kit: plays and saves accrue mileage in a wear ledger, and the kit's
*rendered sound* ages — wow/flutter, a whisper of hiss, HF rolloff, the
rare dropout. **The maximum is the feature:** wear follows
`w = 1 − exp(−mileage/K)` — patina physics, fast at first, asymptotic at
"well-worn", never "ruined". Hard caps at `w = 1.0`: flutter ≤ ±6 cents,
hiss ≤ −48 dBFS, HF shelf never below 8 kHz, dropouts rare and **never
on a strong hit** (downbeats structurally protected). Two guarantees:
wear is a render/export-time recipe over pristine WAVs (originals never
rewritten; wipe the ledger, the tape is new), and a worn kit must still
pass preflight and the CC6 export invariant — tested, not hoped.
Automatic accrual stops at the ceiling; a deliberate `--wear` override
can push past it (chosen destruction is a treatment, earned patina is
capped — FULL personality may comment).

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| EE1.1 | ✓ done: `TapeWear` (`:synth`) — the wear chain (flutter via slow resample wobble, hiss, HF shelf, protected-dropout logic) parameterised by `w`, deterministic per (kit, mileage); the caps as constants with tests | CORE | M | w=1 renders inside every cap; downbeat protection holds; deterministic; w=0 is bit-identical passthrough |
| EE1.2 | ✓ done: Wear ledger — `wear` block in `kit.json` (mileage, enabled, K); accrual hooks in `KitBuilderModel` (play/save) and CLI; `w` derived, never stored | CORE | S | ledger round-trips; accrual saturates on the curve; wiping it restores the new-tape sound |
| EE1.3 | ✓ done (previews wear as one tape pass; exports stage a worn twin; project brings each kit in worn per its own ledger, mixdown included): Wire — preview, mixdown and audio exports render through the kit's wear when enabled; `--no-wear` bypass; a worn kit passes preflight + CC6 across every format | CORE | S | worn preview ≠ pristine preview; every export gate stays green |

## EE3 — The Answer: chop a break, get the B-side

The magic-moment demo. The kit already knows its key (KeyGuess), its
groove, its feel. `answer` generates the complement: an S5 synth
bassline **in the detected key**, playing a counter-pattern derived
from the groove's gaps (the pocket inverted — GrooveFeel backwards),
landed as a keys track + its groove in the session.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| EE3.1 | ✓ done (landed in `:shell`, the compose-everything layer — `:synth` would have needed new main-source deps on `:mpc3`/`:xpm` for no gain): `Answer` (`:synth`) — counter-pattern derivation (notes in the groove's gaps, root-and-fifth weighted toward the key, donor feel applied), rendered through an S5 bass patch to a keygroup instrument + clip; seeded, rerollable | CORE | M | answer notes avoid the kit's strong hits, sit in the detected key, follow the feel; same seed same answer |
| EE3.2 | ✓ done (keys tracks gained `clips` in the project writer — same name-keyed trackClipMaps idiom as drum tracks; bench row: hear a keys clip play on the Live III): CLI `answer <kit-dir> [--seed]` — builds the instrument + groove and (with `project`) lands both in one session | CORE | S | chop a tonal break → answer → one .xpj where the break plays with a bassline that fits |

## EE4 — SIDE A: the beat tape

The output stops being kits and becomes a finished artifact: N kits →
one continuous render — each kit playing its patterns for a few bars,
chained with tape-stop and pull-up transitions (a tape stop is a repitch
ramp to zero; we own that math) — plus tracklist, cover with spine
label, and the whole thing as an `.xpj`.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| EE4.1 | ✓ done: `BeatTape` (`:kit`) — arrangement (bars per kit, pattern rotation), transition DSP (tape-stop ramp, pull-up), continuous mixdown; deterministic | CORE | M | N kits → one WAV with audible, sample-accurate transitions at the right bars; deterministic |
| EE4.2 | ✓ done: CLI `sidea <kit-dir>... --title [--bars]` — WAV + tracklist.txt + cover (KitArt spine variant) + session `.xpj` in one folder | CORE | S | one command → a postable beat tape folder |

## EE5 — The J-card: every kit gets its cassette insert

Everything a J-card needs is already tracked — provenance, key, tempo,
classes, groove, art, and (post-EE1) the wear mileage. Render it as a
printable card: pad tracklist, groove as pixel notation, the kit's art,
the tape's history.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| EE5.1 | ✓ done: `JCard` (`:shell`) — a KitArt-family renderer producing the fold-ready card PNG (front/spine/back panels): art, name, pad list with classes and sources, groove notation in PixelType, key/tempo/mileage | CORE | S–M | deterministic card for the factory kit; every starter kit renders; long names and 32-pad kits fit |
| EE5.2 | ✓ done (`pack` lands per-kit cards under `[J-Cards]/`, `.xpn` twin included): CLI `jcard <kit-dir> [--out]`; expansion export drops the card beside the artwork | CORE | S | one command → the kit's insert; the pack builder gets pack cards for free |

**Below the line (wave FF candidates):** generative autoplay
environments (app-side by nature); sample-archaeology annotated
waveforms (nice, not iconic); anything needing network or licensing.
Standing rejects unchanged.

---

# Wave FF — the crate: from songs to sessions

The EE lens continued: identity-deepening, compound, pure-JVM CORE.
This wave completes the crate-digging ritual at both ends — finding
the breaks *inside* full songs, and bouncing what you made back
through the machine — and grows the Answer into a band. Build order
**FF1 → FF3 → FF2 → FF5 → FF4**: the Dig is the headline, Resample
is small and compounds with everything, then the Band, then the two
utilities.

## FF1 — The Dig: breaks found inside full songs

`chop` assumes you hand it a break; the ritual starts earlier. Honest
DSP, no ML: windowed percussive-vs-harmonic scoring (onset density,
spectral flatness, low-band punch) over whole songs, candidate
sections ranked, the best chopped straight through the pipeline with
provenance ("bars at 1:32 of Track 07").

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| FF1.1 | ✓ done: `BreakFinder` (`:audio`) — windowed section scoring (onset density, flatness, harmonic ratio, punch), merged into candidate regions with times and a rank; deterministic | CORE | M | a synthetic song (verse with pads/melody, 8-bar drum break, outro) yields the break's exact region as candidate #1; pure-tone and pure-noise material yields no false break; deterministic |
| FF1.2 | ✓ done: CLI `dig <file-or-folder> [--top N] [--chop]` — list candidates with timestamps and scores; `--chop` sends each winner through the chop pipeline, source/offset provenance stamped | CORE | S–M | dig on a folder names each song's break; --chop lands kit folders whose provenance says where in which song they came from |

## FF3 — The Resample ritual

The most MPC gesture there is: bounce what you have and chop it
again. The kit's own performance — era, wear, treatments baked in —
re-enters the pipeline as source material; generation loss becomes a
creative tool. Nearly free: the pipeline eats its own output.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| FF3.1 | ✓ done: CLI `resample <kit-dir> [--name] [--slices] [--wear W]` — render the kit playing its groove (wear/era in the sound), re-chop the render into a NEW kit folder, provenance stamped ("generation 2 from <kit>"); source kit untouched | CORE | S–M | resample → a new kit whose pads come from the old kit's performance; generation counter increments on a second pass; source kit byte-identical throughout |

## FF2 — The Band: the Answer grows sidemen

Keys tracks carry clips now (EE3 built the road). Chord stabs and a
counter-perc line join the bassline — three seeded, key-locked
complements, each its own track in one `.xpj`.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| FF2.1 | ✓ done (the original test groove hatted every off-16th and correctly got NO shaker - the honest refusal is itself pinned): `Answer.band` (`:shell`) — the stab line: sparse triads (root-third-fifth from the kit's scale) on gaps the bass leaves open, rendered through a Tonewheel stab to a keygroup + clip; the shaker line: a Velvet CHIP tick pattern on off-16ths the groove leaves free; both seeded off the same reroll seed | CORE | M | stabs avoid both the kit's strong hits and the bass's own notes; every stab tone is in the key; same seed, same band; different seed, different band |
| FF2.2 | ✓ done: Wire — `answer --band` persists all three (answer.json grows a `band` list); `project` lands each as its own keys track playing its clip | CORE | S | chop a tonal break → answer --band → one .xpj with kit + bass + stabs + shaker tracks, each with notes |

## FF5 — The Mix Doctor

Preflight's musical sibling: it checks the sound, not the format.
Findings first, `--fix` applies only the safe subset via the DSP we
already own.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| FF5.1 | ✓ done (two findings taught by the tests: a -9dB shelf cannot un-sub a sub-heavy pad, so the carve became a real high-pass; and hats/snares are bright by trade, so they never count toward a buildup): `MixDoctor` (`:shell`) — findings from real measurement: low-band masking between two busy low pads, two hats outside a mute group, harsh-band buildup, a pad far louder than the kit's median, DC/rumble; each finding names the pads and the numbers | CORE | M | a deliberately sick kit (boomy kick + boomy bass loop, clashing hats, one screaming pad) draws exactly those findings; a healthy starter kit draws none |
| FF5.2 | ✓ done (outlier bar sits at 4x, not 2.5x - a chopped break's slices legitimately spread to ~3x): CLI `doctor <kit-dir> [--fix]` — print the findings; `--fix` applies the safe subset (complementary low carve via `:synth` Eq, level trim toward the median, mute-group suggestion applied), bin-backed like every treatment | CORE | S–M | doctor → named findings; --fix → re-run reports the fixed ones gone; undo path restores byte-identical |

## FF4 — More-like-this

The classifier's features exist per slice already; nearest-neighbour
over them across everything you've made.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| FF4.1 | ✓ done: `Similar` (`:audio`) — a compact feature vector per WAV (the extractor's own features, normalized), cosine/euclidean distance, ranked matches | CORE | S | a snare's nearest neighbours in a mixed library are the other snares; identical file distance 0; deterministic order |
| FF4.2 | ✓ done: CLI `similar <pad-or-wav> <library-root> [--top N]` — resolve the target (a kit pad like `A02` in a kit dir, or any .wav), scan the library's kits, print ranked matches with kit/pad names | CORE | S | pointed at a snare pad, the top matches are snares from other kits, named well enough to go grab them |

**Below the line:** real stem separation (needs ML we won't fake with
EQ tricks); networked anything; app-side autoplay. Standing rejects
unchanged.

---

# Wave GG — the hardware feels it

Three lanes in the user's chosen order: **hardware-deep** (what the
Live III does with our files), then **performance realism**, then
**the library**. The format probes are done: both generations carry
per-pad volume envelopes and a filter slot we write as fixed defaults
today (GG1 is pure metadata), and the `.xpj` has 32 song slots whose
`items` step schema the corpus only shows *empty* — so GG3 splits
into safe plumbing now and a bench-fed writer later. Build order
**GG1 → GG2 → GG3 → GG4 → GG5 → GG6 → GG7**.

## Lane 1 — hardware-deep

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| GG1 | ✓ done (the round-trip test caught XpnPackager building its own Pad slots without the shape - fixed and covered): Pad shape as metadata — `KitPad` gains nullable `attack`/`decay`/`cutoff`/`resonance` (null = the format's own default, so goldens stay byte-stable); both writers substitute set values into their existing fields (`VolumeAttack`/`VolumeDecay`/`Cutoff`/`Resonance` in `.xpm`; `ampEnvelope`/`filterData.value0` in `.xtd`); kit.json round-trips; importers read them back; KitPreview approximates the envelope so you can hear a tighten before the card; CLI `shape <kit> <pad> [--attack] [--decay] [--cutoff] [--res] [--reset]`. Bench row: decay 0.3 audibly shortens a pad on the Live III in both generations | CORE | M | unshaped kits export byte-identical to before; a shaped pad's values land in both formats' fields; round-trip through kit.json + import; worn preview shortens audibly |
| GG2 | ✓ done: Fills — `GrooveVariations.fill(base, kit)`: the last bar of every 4 gets a fill (density ramp into the downbeat, rolls built from the kit's own snare/hat at rising velocities, 32nds near the turn); deterministic, seeded; joins the standard variation set so it lands as one more switchable sequence | CORE | S–M | non-fill bars byte-equal the base; the fill bar is denser toward beat 4, uses only pads the kit has, velocities ramp; same seed same fill |
| GG3 | GG3.1 ✓ done (named song slot, name-only-byte proven); **GG3.2 bench-blocked**: Song mode — GG3.1: song-slot plumbing (`Mpc3ProjectWriter` can name song 1 and carry items when given; `sidea`/`project` name it after the session) — safe because empty-items output is byte-identical to today's. GG3.2: the items writer, **blocked on corpus**: save a 2-step song on the Live III, drop the `.xpj` in `reference/`, and the step schema stops being a guess | CORE | M | GG3.1: named song, all-else-identical output, reader accepts; GG3.2 lands only after the bench capture |

## Lane 3 — performance realism

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| GG4 | ✓ done (verdict: NO layer-cycling field exists in either generation - honest refusal; the .xtd layer's real per-hit random fields wired instead as `shape --humanize`, MPC 3 only, MPC 2 byte-identical with or without): Round-robin probe — the `.xtd` pad block carries `sliceIncrement`/`sliceCycleLength`/`sliceIncrementRngSeed`; probe what the corpus does with them and whether `.xpm` has a twin; if a real alternate-hit mechanism exists, wire subtle variant renders (ghost-layer style) through it; if not, an honest documented refusal | CORE | S–M | probe findings documented either way; if wired: alternating hits survive export+import; if refused: the refusal names the missing field |
| GG5 | ✓ done: Ghost-note grammar — `GrooveVariations.ghosted(base, kit)`: low-velocity snare ghosts on the e/a around the backbeat (classic funk grammar), seeded, never colliding with existing notes, velocities ≤ 0.35 so `--ghosts` soft zones actually voice them | CORE | S–M | ghosts sit only on empty off-positions around beats 2/4, at ghost velocity; the backbone is untouched; same seed same ghosts |

## Lane 2 — the library

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| GG6 | ✓ done: The Crate — `crate <root>`: an index of every kit pad's feature vector + class, cached in `.crate-index.json` keyed by file+mtime (second run extracts nothing); `--dupes` (Similar distance ≈ 0 across kits), `--pick CLASS --top N` (best of a class across everything), `--build NAME` (the picks become a new kit folder via auto-place) | CORE | M | index caches (proven by a no-reextract second run); a planted duplicate is found; picks are the right class; the built kit passes preflight |
| GG7 | ✓ done: Liner notes — `notes <kit-dir>`: the kit's story as prose (dug from which song at what timestamp, resample generation, wear mileage, key/tempo, classes, groove, treatments) written to `liner-notes.txt`; expansion export drops it beside the J-card | CORE | S | the notes name the kit's actual provenance; deterministic; expansion carries it |

**Below the line:** velocity-curve fields (fold into a later shape pass
if the corpus shows them varying); pad insert FX (big corpus surface,
none captured yet). Standing rejects unchanged.

---

# Wave HH — differentiators: chains, air, lineage, pockets, the label

The headliner is a correction that became a feature: GG4 said "no
round-robin field exists" — wrong in an interesting way. The PSK kit
in the corpus is built on **sample chains + Slice Motion** (MPC 3
firmware): one long WAV of concatenated takes per pad, 8 layers
mapping velocity windows to `sliceIndex`, `sliceIncrement=1` +
`sliceCycleLength=2..4` stepping to the next take per hit — 8 velocity
layers × up to 4 round robins, the real MPC 3 scheme. The probes are
done: the `.xtd` encoding is fully recoverable from the corpus; the
one missing piece is where slice *boundaries* live (not in the `.xtd`
— its pool entries carry only tempo/root/key metadata — so they're
embedded in the chain WAV). **Bench capture, cheap:** chop any sample
into a few slices in Sample Edit on the Live III, save, drop the WAV
(+ the kit using it) in `reference/` — one file reveals the chunk.
Build order **HH1 → HH2 → HH3 → HH4 → HH5**.

## HH1 — Slice Motion: chains, round robin, the break pad

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| HH1.1 | ✓ done (also caught the XpmWriter empty-layer slots inheriting the chain window - fixed in the same pass): Chain plumbing — `KitPad` gains a nullable `chain` block (slice boundary list + cycle length; the boundaries are OURS, we build the chains); kit.json round-trips it; `Mpc3TrackWriter` writes chain pads the PSK way (per-layer `sliceIndex`, per-instrument `sliceIncrement`/`sliceCycleLength`); `KitPreview` steps through slices on repeated hits so cycling is audible today; GG4's "no round-robin" wording corrected in docs and comments | CORE | M | a chain pad's `.xtd` fields match the PSK shapes; kit.json round-trip; preview of 3 repeated notes plays 3 different slices; non-chain kits byte-identical |
| HH1.2 | ✓ done (take one is the untouched original, deliberately first, so the MPC 2 fallback plays pristine; treat/era/doctor refuse chained pads - a rewrite would orphan the boundaries): Round robin for our kits — `robin <kit> <pad> [--takes N]`: N subtle deterministic variant renders (seeded micro level/pitch/start jitter) concatenated into a chain WAV, pad marked to cycle; undo restores the single take from the bin | CORE | M | robin → chain WAV of N takes + cycling metadata; preview alternates takes; --undo byte-identical |
| HH1.3 | ✓ done: The break pad — `chop`/`dig --chop` gain `--break-pad`: one extra pad carrying the whole source as a chain whose slices are the chop's own boundaries, `sliceIncrement 1`, cycle = slice count — tap through the break on one pad (the workflow MPC users build by hand) | CORE | S–M | the pad's chain boundaries equal the chop slices; preview taps through in order; provenance stamped |
| HH1.4 | **Bench-blocked:** the WAV slice chunk — decode the Sample-Edit capture, embed the slice map in our chain WAVs, and the hardware steps. Until then chain pads export with the honest note that on hardware they play but may not cycle | CORE | S–M | after the capture: our chain WAV's chunk byte-matches the idiom; Live III steps through slices (bench) |

## HH2 — The Air: every dig yields two crates

The Dig scores every window and keeps the break; the inverse selection
is free — the most tonal, least percussive stretch becomes a companion
texture kit (LOOP/TONAL pads, long cuts).

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| HH2 | ✓ done (gates: score ≤ 0.30 + flatness ≤ 0.12, then trimmed against every break candidate so window overlap can't leak): `BreakFinder.air(...)` (lowest composite score + tonal + non-silent, merged sections) + `dig --air`: the best texture section chopped into a texture kit ("<Song> Air"), pads classed LOOP, provenance stamped | CORE | S–M | on the synthetic song, air lands inside the pad sections and never overlaps the break; a drums-only file honestly yields no air |

## HH3 — The Lineage: the library knows its genealogy

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| HH3 | ✓ done (KitMerge now stamps mergedFrom on both sides - the one gap in the record; origins print only at roots since resample inherits stamps): `Lineage` (`:shell`) — walk the crate's provenance (dig song/at, resampledFrom/generation, merge/remix parents, importedFrom) into a family tree; `lineage <kit> [--root <crate>]` prints it, `--png` renders a KitArt-family tree card | CORE | M | dig→chop→resample→resample yields the full chain in order; a merged kit shows both parents; deterministic |

## HH4 — Pocket files: feels as tradeable artifacts

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| HH4 | ✓ done (a bottled pocket applies byte-identically to feeling from the donor kit itself): `.pocket` files — GrooveFeel templates serialized (offsets + accents + name); `feel <kit> --save x.pocket` and `feel <kit> --from x.pocket`; `pack` ships the kits' pockets under `[Pockets]/` | CORE | S | save→from round-trips to the same applied timing; a pocket from kit A moves kit B the way A's groove would |

## HH5 — The Label: run your own imprint

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| HH5 | ✓ done (append-only catalog: re-runs change nothing, departures keep their entry marked "(gone)"): `label <root> --init NAME [--prefix XYZ]` — label.json at the crate root; catalog numbers assigned in stable order (XYZ-001…), `catalog.txt` written; J-card spines, liner notes and pack tiles wear the catalog number when the kit is under a labeled root | CORE | S–M | init → stable numbering that survives re-runs and new kits (existing numbers never move); the J-card spine shows the number |

**Below the line:** sample-pool metadata (tempo/root/key on pool
entries — the PSK shape; nice, but changes golden bytes for cosmetic
gain); networked trading (pockets and tapes stay files); classifier
self-tuning (waits on the F2.4 real-audio corpus). Standing rejects
unchanged.

---

# Wave II — the grid deepens: velocity × round robin

HH1's chains were single-zone; the PSK corpus kit's real scheme is the
full grid, and a fresh probe (2026-08-28) pinned its exact geometry:
**8 layers per pad, all referencing the one chain WAV, velocity zones
loudest-first (L0 = 122–127 … L7 = 0–16), and the base `sliceIndex`
grading with intensity** — softest zone anchors at slice 0, rising to
slice 8 at the top; each zone cycles 2–4 takes from its anchor
(`sliceIncrement 1`), one shared `sliceIncrementRngSeed` per pad. The
chain is a *dynamics-graded* sequence of takes, soft→hard, and each
velocity zone taps in at its intensity point. One documented oddity:
Akai's own `sliceInfo` is NOT a per-slice window (loud layers span the
whole chain, soft layers window the last ~12%) — our per-zone windows
are a more precise instance of the same encoding, chosen so map-less
firmware degrades to honest velocity switching.

Our grids cap at **4 zones** (the format allows 8; the MPC 2 `.xpm`
has 4 layer slots, and 4 keeps every zone representable on both
generations — same cap as velocity layers). Hardware robin audibility
still rides HH1.4's slice-map capture, like every chain.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| II1 | ✓ done (toPlay became the one projection every exporter shares, replacing three hand-rolled copies): Grid model — probe findings written into MPC3_FORMAT.md; `ChainInfo` gains `zones: List<ChainZone>?` (velStart/velEnd/baseSlice/cycle; 2..4 zones, contiguous soft-first tiling of 0..127, each window inside the slice count); kit.json round-trips; `KitPad` still refuses `velocityLayers` + `chain` together (zones ARE the layers now) | CORE | S–M | probe documented; store round-trip; overlapping/gapped/out-of-range grids refused |
| II2 | ✓ done (goldens proved non-grid exports byte-identical through the SliceStart change): Grid writers — `.xtd`: one filled layer per zone, loudest first, the PSK field shapes (`sliceIndex` = zone base, `sliceIncrement 1`, `sliceCycleLength` = zone cycle, zone velocity windows, per-zone `sliceInfo` windowing the base take); `.xpm`: per-layer `SliceStart`/`SliceEnd` windows into the one chain WAV — real velocity switching on MPC 2, no robin, the generation's honest ceiling | CORE | M | grid layer fields match the PSK pattern; single-zone chains and non-chain kits byte-identical (goldens) |
| II3 | ✓ done (per-lane counters: each zone cycles independently, single-zone lane = slot exactly as before): Grid preview — velocity picks the zone, repeated hits cycle takes within it (per slot-and-zone counters), so the full grid is audible before the card | CORE | S | soft and hard notes play different windows; repeats at one velocity alternate takes; single-zone behavior unchanged |
| II4 | ✓ done (soften normalizes peak, so quiet comes from an explicit level grade 0.55→1.0 beside the tone grade): `robin --zones N` — the graded chain from one take: N velocity zones × T takes, soft zones rendered softer *and darker* (the ghost-layer math), robin jitter within every zone, chain laid out soft→hard like the PSK's; bin-backed undo byte-identical; the audio-door guards from HH1.2 hold | CORE | M | N×T slices with zone grading (soft zone measurably quieter and darker); both generations export; undo byte-identical |

**Below the line for II:** 8-zone grids (MPC 3-only; revisit if the
4-zone cap ever pinches); wrap-around cycle semantics (the PSK's
overlapping zone windows suggest the firmware may wrap — undecidable
without the HH1.4 bench, so our windows don't overlap).

---

# Wave JJ — the kit escapes the MPC

A kit folder is already a clean, self-describing thing; two small
plain-text writers make every SnipSnap kit loadable in nearly any DAW
or free sampler. The happy surprise: **SFZ has native round robin**
(`seq_length`/`seq_position`) and velocity ranges, so chains and grids
export to SFZ *fully* — sample-offset windows into the one chain WAV,
takes actually cycling — richer than the MPC 2's own fallback. Reach,
not depth: the same crate, playable everywhere.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| JJ1 | ✓ done: SFZ writer (`:kit`) + export format `sfz` — pads → regions at key 36+slot−1 with level (dB re the MPC default), pan, tune, mute groups as `group`/`off_by` chokes, one-shot mode; velocity layers → `lovel`/`hivel`; shape → `ampeg_*`/filter opcodes using the preview's own documented approximations; humanize → `*_random`; chains and grids → real round robin: `seq_length`/`seq_position` with `offset`/`end` windows into the chain WAV, zones as velocity ranges | CORE | M | deterministic text; a grid pad yields zones × takes regions with the right windows, ranges and seq positions; every pad feature mapped or honestly skipped |
| JJ2 | ✓ done (one group per grid ZONE - seqLength lives on the group and zones may cycle differently): DecentSampler writer + format `ds` — `.dspreset` XML, one group per pad (`seqMode="round_robin"` when chained), `loVel`/`hiVel`, `start`/`end` windows, tuning/pan/volume carried | CORE | S–M | deterministic; chain pads carry seq positions; velocity zones carry ranges; plain kits stay plain |
| JJ3 | ✓ done (boundaries must come from offset AND end+1 - the test caught interior windows smearing without the ends): SFZ importer + `import x.sfz` — regions → pads by key, `lovel`/`hivel` → velocity layers, samples copied into the kit folder, provenance stamped; unknown opcodes ignored (that's the format's own rule); non-WAV samples and out-of-range keys named and skipped, never fatal | CORE | M | JJ1's own output round-trips to an equivalent kit; a small foreign fixture imports; hostile/malformed files refuse honestly |

**Below the line for JJ:** Kontakt/EXS (binary, no spec); Renoise;
`.dspreset` import (DS users author in DS); FLAC/AIFF sample support
(the kit folder is WAV by design).

---

# Wave KK — the Arranger: songs, not loops

Everything the kit plays is one repeating pattern, but it already owns
five-plus variations (captured, tight/swing, half, sparse, fill,
ghosted), an Answer, a band, and a mixdown engine — the ingredients of
an arrangement. A structure grammar turns them into a song.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| KK1 | ✓ done (the rng draws before deriving, so the stream never depends on what the kit supports): `Arranger` (`:shell`) — the structure grammar: intro (sparse), theme (captured), variation (tight/swing or ghosted), the turn (fill bar), outro (half, fading), section lengths in bars, seeded choices deterministic per seed; honest refusals when the kit has no grooves | CORE | M | deterministic plan per (kit, seed); every section clip is one of the kit's own stored variations; bar totals add up |
| KK2 | ✓ done (sequence names carry the order - "01 intro".."06 outro" - so flipping IS performing): `arrange <kit-dir>` — the plan lands as switchable sequences in the `.xpj` (the AA1 multi-sequence writer), song slot named for the arrangement (GG3.1); the printed map shows section order and bars | CORE | S–M | the `.xpj` carries the sections in plan order and the reader accepts it; re-running with the same seed is byte-identical |
| KK3 | ✓ done (the Answer's root is re-detected from the stored note the OneNote way; tapeStop/pullUp promoted from internal): `arrange --mixdown` — the song as one WAV: sections rendered through KitPreview and stitched with SIDE A's tape-stop/pull-up transitions at the turns, the Answer (and band) riding under sections that want them | CORE | M | mixdown length matches the plan; the fill section is measurably denser; deterministic |

**Below the line for KK:** hardware song-mode *steps* (still
GG3.2-bench-blocked — sequences are the performable substitute);
arrangement-aware wear (a song is one long play; the ledger already
counts it as such).

---

# Wave LL — mutate, the ear, the session

Three lanes, in order. **Mutate** (the user's own): sound design by
recombination — one hit from many parents, distinct from `treat`/`era`
which transform a single sound. **The Ear**: learn a *performance*
from any recording, not just sounds. **The Session**: the whole ritual
as one gesture, orchestrating everything shipped.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| LL1 | ✓ done: `mutate <kit> <pad> --with <src>…` — one hit from many parents, three moves: **stack** (transient-aligned layering with an honest polarity check — a layer that measurably cancels gets flipped, and the output says so), **splice** (`--splice [--at ms]`: the pad's transient crossfaded into the source's body — the classic mash), **split** (`--split [--hz N]`: pad below the crossover, source above). Sources are pad refs (`A03`, `other/kit:B02`) or WAVs. Bin-backed like every audio door, chained/layered pads refused; the recipe (mode, sources, split, flips) rides the pad so the sound stays regenerable; provenance stamps the parents so `lineage` shows a hit with two of them | CORE | M | splice: output's transient window correlates with the pad, tail with the source; split: low band provably from the pad, high from the source; polarity: a deliberately inverted twin gets caught and flipped; same recipe, same bytes; `--undo` byte-identical |
| LL2 | ✓ done (the spin recorded in the recipe beside the parent it dealt): `mutate --roulette [--seed N]` — the crate picks the partner: Similar finds the nearest same-class-adjacent sound across the library (`--root`), or pure chance with `--wild`; seeded, deterministic, the pick named in the output and the recipe | CORE | S | same seed, same partner and bytes; the pick is never the pad itself; refusal on an empty crate |
| LL3 | ✓ done (the classifier chop trusts, pointed at time; the test caught the frame-zero blind spot — chopped breaks start ON the hit — fixed with the hot-open guard): The Ear — `learn <beat.wav> --into <kit>`: transcribe a recorded *beat* into a clip for your kit — onsets detected, each hit coarsely classed (kick/snare/hat by band energy at the onset), mapped onto the kit's own pads, velocities from the hits' dynamics; confidence gating with named uncertainty; needs a confident tempo to place the grid | CORE | M | a synthetic DrumSynth beat transcribes to the exact pattern (classes, positions, count); a toneless file refuses honestly; low-confidence hits are marked, not invented |
| LL4 | ✓ done (no pads needed — a pocket is timing and accent alone, so it works standalone): `learn --pocket x.pocket` — the recording's *feel*, bottled: the transcription through `GrooveFeel.extract` into a `.pocket` file — steal a real drummer's timing from the record itself | CORE | S | the pocket from a synthetically swung beat carries the swing (offbeat offsets match the rendered push); applies to a kit like any pocket |
| LL5 | ✓ done (doctor runs before the robins on purpose — a chained pad refuses rewrites; dig learned to forward --groove/--ghosts): The Session — `beat <song.wav>`: the whole ritual as one verb — dig the break (+ air), chop `--break-pad --groove --ghosts`, robin the core pads, the Answer + band, doctor `--fix`, arrange `--mixdown`, j-card + notes; every step honest about skips (no break, no key, nothing to roll on); one song in, a release folder out | CORE | M | on the synthetic song: the folder holds kit + air + arrangement + mixdown + inserts; each skipped step is named; deterministic |
| LL6 | ✓ done (grooveless kits left off AND named in the tracklist itself — the honest sleeve note): `album <root> --title NAME` — the crate's release: the label's kits (or crate picks) each arranged into a song, mixdowns sequenced onto SIDE A/SIDE B, catalog numbers, j-cards and liner notes riding — one folder, a releasable cassette | CORE | M | two-kit crate → two songs across two sides with tracklist, inserts and catalog numbers; deterministic; a kit without a groove is skipped and named |

**Below the line for LL:** spectral-domain mutation (phase-vocoder
morphs — big DSP, small honesty); learn-from-polyphonic-music (the
Ear does beats, not mixes with bass and vocals — refusals say so);
the Room (queued as a future lane).

---

# Wave MM — the Capture Doctor

The app's real input is a phone mic in a room; captures arrive with
mains hum, clicks and dropouts, and hiss, and nothing shipped so far
treats them. The Capture Doctor is `doctor`'s sibling with a different
patient: **`doctor` treats the mix** (masking, levels, DC), **the
Capture Doctor treats the capture** (defects the microphone and the
room put there). Same house rules: measure first, act only on what
measurably exists, name everything, and leave clean audio untouched —
byte-identical, not "processed gently".

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| MM1 | ✓ done: `CaptureDoctor.detectHum` + `removeHum` — Goertzel probes at 50 and 60 Hz against their ±4 Hz neighbors; only a tone standing out 4× and clearing −80 dBFS is hum (kicks' broadband sub never qualifies, and a clean beat returns null). RBJ notches at Q 30 take the fundamental and every *standing* harmonic (up to 4, each re-probed before it's touched). 60 Hz detects as 60; the fixture's fundamental drops > 20 dB while a snare-band probe moves < 5% | CORE | M | a drums+50 Hz fixture: hum down ≥ 20 dB at 50/100/150 Hz, the drums' own spectrum elsewhere within tolerance; a clean fixture returns byte-identical; 60 Hz detected as 60, not 50 |
| MM2 | ✓ done: `repairClicks` — per-block derivative outliers (8σ, 0.05 floor) clustered into regions ≤ 96 frames, repaired by interpolation; exact-zero dropout runs (8–900 frames, live neighbors) bridged **first**, since a dropout's edges read as clicks on the raw signal. Three guards earned by the tests: the follow test (post-region RMS ≤ 3× pre — an onset persists, a click dies), an **isolation guard** (the region's peak derivative must stand 4× over its ±64-frame surround; without it, silence-diluted block σ flagged 9 phantom clicks inside hat noise), and the refusal reading **absolute** full-scale jumps rather than σ flags — wall-to-wall spikes raise their own σ so nothing flags, and the distortion refusal never fired until the ceiling went absolute | CORE | M | planted clicks in a tone and in a beat get found and repaired (residual small at the sites, elsewhere untouched); a clean drum hit's transient is never flagged; the too-damaged refusal fires |
| MM3 | ✓ done: `measureFloor` + `expand` — the floor is the 10th percentile of 50 ms-window RMS in dBFS (null on very short audio; under −60 means clean and callers leave it alone), and the gate is a 2:1 downward expander easing in below floor + 12 dB with a 12 dB depth cap, instant attack, 80 ms release, channels linked. The planted −44 dB hiss reads within the honest band, the noise tail recedes > 6 dB but never past the cap, and drum peaks move < 5% | CORE | S–M | drums over −40 dB noise: the gaps drop by ≥ 6 dB, drum peaks within 5%; a clean fixture unchanged; the floor report matches the planted noise level |
| MM4 | ✓ done: `clean(snip)` composes the visit — hum first (a hum lifts the floor reading), then clicks/dropouts, then the floor — every leg gated by its own detector, untouched input returned **as the same object** with "clean - nothing done". The verb: a WAV gets its findings printed and a `<stem> Clean.wav` twin (`--in-place`/`--out`/`--overwrite`); a kit sends every plain pad through `replaceAudio` (bin-backed, `clean` recipe stamped, layered/chained/distorted pads skipped by name) and `--undo` restores every cleaned pad byte-identical; `--dry` doctor-style; `chop --clean` (forwarded by `dig`) scrubs the capture before the first slice — the test kit carries under a third of the raw chop's 50 Hz energy. One finding: a click in near-silence fails the follow test *after* the notch (the filter's own ringing "follows"), so real clicks are hunted on program material — which the fixtures now honestly are | CORE | S–M | the CLI round trip on a dirty WAV and a dirty kit; nothing-to-clean touches nothing and says so; `--undo` byte-identical; `chop --clean` yields a cleaner kit than `chop` on the same dirty source |

**Below the line for MM:** spectral de-noise → promoted to NN2;
de-reverb → its honest first step (the tail-knee trimmer) promoted to
NN3, the real thing still below; auto-clean-on-capture in the app
(the app session wires the same `CaptureDoctor`, M-milestone work).

---

## Wave NN — the Deep Clean (CORE)

MM's Capture Doctor works in *time*: the expander can only duck the
gaps between hits, and during a hat tail the hiss rides along
untouched because the frame as a whole is loud. The Deep Clean works
in *frequency*: hiss lives in different bins than the cymbal energy,
so it can be pulled out from **underneath** the drums. Same house
rules as MM, doubled down — the known failure mode of spectral gating
is *musical noise* (per-bin gates flickering open on random noise
peaks), and every design choice below exists to keep the cure quieter
than the disease: the profile is learned from the capture's own
quietest moments, gains are smoothed in both time and frequency, and
attenuation is capped so noise recedes but never vanishes into warble.
De-reverb proper stays below the line (blind deconvolution — the
drum's decay and the room's decay are entangled by construction); its
honest first step ships instead: a measured knee where the hit's own
decay hands off to the room's slower one, and a gentle fade from there.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| NN1 | ✓ done: `Spectral` on the classifier's own `Fft` (which gained the inverse it never needed — the conjugate trick, one butterfly both ways): 1024-point Hann frames every 256 samples, windowed on analysis *and* synthesis so per-bin gains can't click at frame edges, overlap-add normalized by the constant the squared window tiles to (1.5 at this hop). Two doors: `forEachFrame` (analysis only) and `process` (per-frame gains, conjugate mirror kept honest) | CORE | M | a pure tone lands its energy in the right bin; forward→inverse round-trips within float tolerance; STFT analyze→synthesize with unity gains reconstructs the beat within tolerance (start/end edges included); Parseval sanity on a noise frame |
| NN2 | ✓ done: `CaptureDoctor.denoise` — fingerprint from the quietest tenth of frames (fewer than 8, or a floor under −60 dBFS, → null), per-bin downward gating at fingerprint ×3 toward a −12 dB gain floor, gains smoothed across ±2 bins and eased shut over frames with instant open. The margin is 3× *by measurement*: at 2×, Rayleigh-spread noise peaks popped the instant-open gate ~4% of frames and the average gain floated well off the floor — the deep clean under a loud burst measured only ~5 dB; at 3× a pure-noise bin opens ~0.1% of the time and the cap is actually reached | CORE | M | beat + hiss: the noise-only tail recedes ≥ 6 dB and never past the cap; hiss *under* a loud 6 kHz burst drops while the burst's own bin moves < 5%; drum peaks within 5%; a clean beat returns null; anti-warble: the attenuated tail's level variance stays bounded |
| NN3 | ✓ done: `CaptureDoctor.trimRoomTail` — post-peak envelope (10 ms RMS windows in dB), two-line fit hunting the knee, validity gauntlet: ≥ 18 dB under the peak, hit at least 2× steeper, tail still decaying (flatter than −3 dB/s is a floor, the de-noiser's patient) and mostly alive; the hit's own slope continues from the knee as a fade capped at −24 dB. Three fixture-taught lessons: fit only to the last *live* window (post-tail silence flattens every slope it touches); hunt the best knee among *valid* candidates (a real envelope has three regimes and the raw SSE optimum sits on the tail-to-floor corner); the two-lines-beat-one criterion is a coarse sanity floor (0.8) — dry hits die in the gauntlet with no valid candidate at all | CORE | M | a hit convolved with a decaying-noise room IR: knee found near the true handoff, post-knee tail energy drops ≥ 6 dB, the hit's body byte-identical before the knee; the dry hit comes back null; the trim never cuts, always fades |
| NN4 | ✓ done: `clean --denoise` swaps the floor leg to the spectral de-noiser (never both), named in summary and recipe (`denoised`); `clean <kit> --deroom` runs the knee per one-shot pad (fade recorded as `deroomKneeMs`, LOOP pads named and left alone, `--undo` byte-identical); `chop --clean --denoise` forwarded by `dig`; `--denoise` without `--clean` refused with a pointer | CORE | S–M | CLI round trips: on the same hissy burst fixture the expander leaves under-the-burst hiss standing (within 20%) while --denoise cuts it under half; --deroom trims the roomy pad, names the LOOP it skips, rides the recipe, --undo byte-identical; summaries honest on clean input |

**Below the line for NN:** true de-reverb (blind deconvolution / WPE —
still a research project; the bench fixture that would ground it is a
phone-mic capture of real hardware in a real room, wanted in
`reference/` regardless); a learned noise profile passed between takes
("this room's fingerprint" as a file, like pockets bottle grooves);
NN2 inside the app's capture path (rides the app session's auto-clean).

---

## Wave OO — the Sculptor (CORE)

The Torso S-4 as lodestar: a *sculpting sampler* turns hits into
**matter** — granular clouds, detuned swarms, endless evolving
stretches — with generative sequencing underneath. What we can't steal
is the performance surface (macro knobs and live modulation belong to
the app session); what we can steal is the engine room. Everything
before OO turned captures into kits; the Sculptor turns kits back into
material, and it composes with the whole shop: sculpt a texture, era
it through tape, roulette picks the grain source, the Answer plays
under the drone.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| OO1 | ✓ done: `Granular.render` — one seeded scheduler: grain size, density (onsets jittered ±50%), position fixed or crawling, per-grain wander and detune (a resampled linear-interp read), Hann window, equal-power spray. Every draw comes from the seed in schedule order, so a texture is a recipe — same seed, same bytes; the output peak is normalized to the source's own, so a cloud of a quiet sound is a quiet cloud | CORE | M | duration exact; same seed → identical bytes, different seed → different; a cloud pointed at the 220 Hz half of a two-tone source renders 220, pointed at the 2 kHz half renders 2 kHz; a scrub crawls from one to the other; pitch spread measurably widens the spectrum of a pure-tone source; spray 0 → channels identical, spray 1 → not |
| OO2 | ✓ done: `sculpt <wav> \| <kit-dir> <pad>` — cloud (position 0.35, ±5% wander), scrub (0→1 crawl), swarm (±7 semitones at higher density); four seeded takes as a "<Name> Sculpt" kit, LOOP by declaration, `sculptedFrom` provenance + regenerable recipe per pad; `--seconds`/`--seed`/`--out`/`--name`/`--overwrite`. The result is a real kit — it previews, exports, eras and resamples like any other | CORE | M | each mode's kit renders with 4 LOOP pads + provenance + recipe; the same seed rebuilds the same bytes; mode geometry proven by probe (cloud holds its position's spectrum, scrub opens low and closes high, swarm wider than the cloud of the same tone); the kit exports |
| OO3 | ✓ done: `Stretch.stretch` / `freeze` + the verb — 4096-point Hann frames, analysis crawling at 1/factor of the synthesis pace, every phase replaced with a seeded random one while every magnitude is kept (phase carries *when*, magnitude carries *what*); freeze nails the analysis to one instant, defaulting to the source's loudest 50 ms. L/R draw different phases from one seed — a decorrelated stereo field; peak normalized to the source's own. `stretch <wav> --by N \| --freeze [--at s] [--seconds N]`, twins landing beside the source | CORE | M | duration ≈ source × factor; a tone stays at its own frequency through the stretch; steady input → steady output (level variance bounded — no pulsing); freeze reproduces the frozen instant's spectrum throughout and finds the loudest moment on its own; seeded determinism |
| OO4 | ✓ done: `euclid <kit-dir>` — Toussaint's Bjorklund, textbook onset-first forms (E(3,8) the tresillo, E(5,8) the cinquillo), k,n[,rotation] per class flag, house pattern by default (tresillo kick, backbeat snare via rotation 2, 16th hats); hits on the kit's own pads through the Ear's stand-in map, skips named; structural accents (downbeat 0.95 / quarter 0.85 / rest 0.7); saved through `GrooveVariations.standard` so the variations and native exports carry it | CORE | S–M | E(3,8) and E(5,8) land on the textbook pulses in pulse arithmetic, rotation shifts them; accents tiered as specified; the groove saves with the standard variations and rides an .xtd export; an all-LOOP kit refuses honestly, the skip named; deterministic |

**Below the line for OO:** live macro morphing between sculpt states
(app-session work — the CLI has no knobs to sweep); the Séance's
`mutate --morph` and `smear` (a natural small follow-up wave on the
same spectral door); grain-level filters and the S-4's effect console
(space/prism — our eras already carry that torch); `sculpt --keys`
(a texture as a playable drone keygroup via LoopCut — wants care
around loop points on stochastic audio, so it earns its own item
rather than riding this one).

---

## Wave PP — the Split (CORE)

The research verdict (2026 sweep): the highest-value modern DSP this
codebase can honestly own is **median-filter mask separation** — one
machine, two tellings. Fitzgerald's HPSS: median-filter a spectrogram
*across time* and harmonic content survives, *across frequency* and
percussive content survives; turn the two into soft masks that sum to
one and the parts sum back to the input — **perfect reconstruction is
the exit test**. Aalto's STN (Fierro & Välimäki, DAFx 2021 / JAES
2023) runs the same machinery in two rounds and splits audio into
sines + transients + noise — the anatomy of a drum hit. Everything
rides the NN1 `Spectral` door: masks are exactly the per-bin gains
`process` already applies. The flagship is `dig --unearth`: the break
pulled out from UNDER the song — drums isolated even where bass and
keys play over them — instead of hoping a clean stretch exists.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| PP1 | ✓ done: `Separate.hpss` — one analysis pass on the `Spectral` door, medians across time (harmonic) and frequency (percussive), Wiener masks with a shared floor summing to exactly one; harmonic + percussive reconstructs the input within float tolerance. One physics lesson recorded: a boomy kick's sub is a *held tone* and rightly leans harmonic — the vertical promise is about attacks and noise, so drums-read-as-drums is asserted on hats/snares/claps | CORE | M | masks sum to one, so harmonic + percussive reconstructs the input within float tolerance; on a drums+pad synthetic mix the percussive part holds the hat energy (probe) and the harmonic part holds the tone; snappy drums land overwhelmingly percussive, a chord overwhelmingly harmonic; deterministic |
| PP2 | ✓ done: `Separate.stn` — the fuzzy ratio of time-median to the median pair, raised-cosine ramps between named thresholds (sines ≥ 0.8, transients ≤ 0.2), masks still summing to one, single-resolution telling of the papers (two-resolution refinement below the line). `dissect` lands Sines (TONAL) / Transient (PERC) / Air (LOOP) as a "<Name> Dissected" kit, provenance + recipe per pad, energy shares printed | CORE | M | the three parts sum back to the input within tolerance; a held tone lands in sines, broadband bursts carry > 50% of the transient part's energy in their own windows, hiss probes into noise; the CLI round trip checks classes, provenance, recipes, the body singing in Sines and the attack fronting the Transient pad |
| PP3 | ✓ done: `split <song.wav>` — Drums.wav + Music.wav beside the source, verdicts with measured shares ("mostly drums" at ≥ 65%), both halves always written (even a drums-only song has a body the harmonic telling honestly claims). One boundary lesson: masked halves can locally overshoot full scale even when the song doesn't, and the 24-bit boundary would clip silently — both halves now scale by one stated factor when either overshoots, still summing to the song | CORE | S–M | CLI round trip: the chord probes into Music, the halves sum back to the song through the files; drums alone read "mostly drums" |
| PP4 | ✓ done: `dig --unearth` — the dig scores and chops the percussive layer, `--air` cuts from the music layer, pads stamped `unearthed`. The test landed the claim in a *stronger* form than planned: on a song whose chord never stops, the plain dig honestly reads "no break heard" — no stretch of the raw mix scores as drums at all — while `--unearth` finds and chops the buried break, its pads carrying ≥ 10 dB less chord than a plain chop of the very same section | CORE | M | a synthetic song with drums fully overlaid by loud pads: plain dig "no break heard", --unearth finds and chops it, tonal bleed ≥ 10 dB down vs a plain chop of the same section; provenance says unearthed |

**Below the line for PP:** KAM (kernel additive modeling — HPSS's
generalized successor, heavier for modest gain here); using the
percussive layer inside `learn` (the Ear listening through the mask)
and `--key auto` (the tonal layer only) — natural follow-ups once PP1
exists; vocal isolation (needs repetition-based methods like REPET, a
different machine).

---

## Wave QQ — Time, Done Right (CORE)

Stacked on the Split. PGHI ("Phase Vocoder Done Right", Průša &
Søndergaard 2017) reconstructs phase from the magnitude spectrogram's
own gradients, integrating along a heap ordered by magnitude — still
the DSP baseline neural vocoder papers compare against in 2026, and
buildable on our FFT in an afternoon of care. On top of it, Driedger &
Müller's hybrid TSM: HPSS-split the signal, phase-vocode the harmonic
half with big frames, plain overlap-add the percussive half with tiny
ones — the known-best classical time-stretch, transients kept crisp.
That gives `fit-tempo` the move it's always lacked: change the tempo
and NOT the pitch. And PGHI's spectrogram inversion opens the Séance's
`morph` properly: interpolate two parents' magnitudes, let PGHI invent
coherent phases for the sound between them.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| QQ1 | ✓ done: `Pghi` — phase from the magnitude spectrogram's own gradients, heap-integrated loudest-first, random phase below tolerance, disjoint islands restarting coherently; geometry shared with `Spectral` so `forEachFrame` magnitudes invert straight back. Two calibration findings earned on a numpy known-phase rig rather than guessed: the gradient coefficients are each direction's *reciprocal* (time γ/(aM), frequency −aM/γ — the first draft had them swapped, a 5% error reading as slow drift), and our start-referenced frames need a −π-per-bin center term. `stretch --clear` rides it | CORE | M–L | inverting unmodified magnitudes rebuilds a tone at 95%+ coherence and a beat with every onset in place; the clear stretch keeps a sine a >20× narrow line and provably narrower than the paulstretch wash; seeded determinism |
| QQ2 | ✓ done — with the wave's headline finding: the planned Driedger hybrid was **built and then retired by measurement**. The hybrid's unaligned OLA+vocoder sum smeared a kick's attack rise from 33 ms to 51 ms while plain PGHI matched the original's 33 ms — the hybrid exists to fix the classic vocoder's transient smear, and PGHI doesn't have the disease. `Retime` ships as the PGHI vocoder with TempoFit's double/half refusal; `retime <wav> --to BPM [--from]` + `chop --fit-tempo BPM --keep-pitch`. (Dig doesn't forward --fit-tempo today, so nothing new to forward) | CORE | M | duration is the ratio's; the 440 bed stays 440 (no repitch); hits land at their new times; attack rise within 12 ms of the original; out-of-range refused; CLI round trip + riding rule |
| QQ3 | ✓ done: `mutate --morph [--amount 0..1]` — both parents' magnitude spectrograms transient-aligned, interpolated bin-wise, PGHI phases; length and level interpolate; amount in the recipe, undo through the same bin | CORE | M | amount 0 stays the pad, 1 becomes the parent (probe ratios); halfway both parents sing in ONE hit — a single onset, the not-a-crossfade proof; --amount without --morph refused |

**Below the line for QQ:** real-time PGHI (app-session territory);
formant-preserving pitch shift (needs envelope/cepstral lifting —
worthwhile, its own item once QQ1 exists); `smear` (transient removed,
keeping the wash — trivial once PP2's transient mask exists, candidate
for a small dessert wave with `sculpt --keys`).

---

## Wave RR — the Restoration (CORE)

The Capture Doctor's missing limbs, from the declipping literature
(SPADE, Kitić et al., Inria — still the reference family in the 2020
large-scale evaluation) and the de-reverberation one (WPE). Declipping
treats clipped samples as *missing data with a known lower bound* (the
clip level tells you the truth was louder) and finds the sparsest
spectrum consistent with the surviving samples — iterative hard
thresholding plus a consistency projection, all machinery we own.
WPE models late reverb as a linear prediction from earlier STFT frames
and subtracts it — per-band normal equations, a few iterations.
Single-channel WPE is real but tuned for speech; it ships as an
explicit opt-in with synthetic exit tests, and its real-world verdict
waits on the phone-mic reference capture.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| RR1 | ✓ done: `detectClipping` trusts only **flat-top runs** (digital clipping repeats the very same value; even a low sine's crest — six samples "at" its peak — bends by orders of magnitude more than [CLIP_FLATNESS]); `declip` is Kitić's A-SPADE ADMM ported from a numpy rig that validated every step — windowed frames, unitary-DFT analysis, a dual pulling the k-sparse model toward the constraints, tight-frame synthesis rebuilding only the pinned runs. `clean --declip` runs first; the `repairClicks` refusal refers its clipping patients here. **Recalibrated honestly:** consistent sparsity earns ~3 dB + rebuilt peaks on sustained tonal material and almost nothing on clipped noise slivers (noise has no sparse structure to infer); the plan's 10 dB bar rode headline metrics and Gabor dictionaries — that upgrade is below the line. Also caught: a dual-free consistency test converges to sparse-consistent wrong answers, and per-sample de-windowing explodes at window edges | CORE | M–L | tonal-hits fixture clipped at −6 dB: ≥ 2 dB SNR gain (best-gain matched), peaks past the ceiling, reliable samples byte-identical; loud-not-clipped sine and clean beat read null; the visit names the rebuild |
| RR2 | ✓ done: `deverb` — single-channel WPE, per-band delayed linear prediction (delay 2, order 10, three variance-weighted refinements, complex normal equations solved with partial pivoting), per-bin suppression capped at −10 dB, too-short audio untouched; `clean --deverb`, an opt-in with no detector ("how roomy is too roomy" is taste). One conjugation lesson caught by the test: with normal equations built from conj(tap)·x the prediction is the PLAIN product — the conjugated form flipped subtraction into addition and made the room 6 dB LOUDER | CORE | M–L | the tail knee's convolved fixture: the tail recedes > 2 dB while the direct sound holds within 1.5 dB; a dry fast hit keeps its peak within 5%; the visit names the leg. Real-world verdict still waits on reference/ |
| RR3 | ✓ done: `checkup [dir]` — one measured card per capture (hum, flat-tops, clicks/dropouts or the named refusal, floor + verdict, the room knee with both slopes, WPE-predictable energy share), every number from the detectors `clean` trusts, read-only; an empty reference/ says what it's waiting for | CORE | S | empty-folder honesty; the hummy capture's 50 Hz and the clipped one's ceiling appear on the card; nothing written |

**Below the line for RR:** bandwidth extension for lo-fi captures
(spectral band replication-style — fun, but the eras deliberately go
the other way); packet-loss-style inpainting of longer gaps (the
dropout repair's big sibling; SPADE machinery again, wants real
fixtures first); learned room fingerprints shared between takes
(pairs with the NN below-line noise-profile idea).

---

## Wave SS — the Hardening (CORE)

The last adversarial passes (BB security, CC robustness, DD parsers)
predate everything from EE onward. Since then the CLI grew from ~20 to
44 verbs and :audio grew five DSP engines — and the hostile-file sweep
still exercises five verbs. Found while scoping: `Pghi.stretch` has no
output ceiling where `Stretch` and `Granular` cap theirs — a
`stretch --clear --by 100` on a long file is an OOM, not an error.
Same house rules as BB/CC: a hostile input earns a named refusal and a
clean exit, never a stack trace, never a hang, never NaN on disk.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| SS1 | ✓ done: the BB5 junk corpus against every file-taking verb grown since (clean with all legs, split, dissect, sculpt, stretch ×3 modes, retime, dig --unearth, checkup) plus a broken-kit corpus (garbage kit.json, orphaned sampleFiles, a junk pad WAV inside a real kit) against twelve kit-door verbs. All 27 pairings already exited cleanly — the BB-era catch-all held across the new surface — and are now guarded invariants, each pair named on failure | CORE | M | every (verb × hostile) pair exits 0..2 with no throw; the junk-kit corpus likewise; failures named per pair |
| SS2 | ✓ done: the degenerate matrix — {one sample, tiny, silence, pure DC, full-scale square, low rate, stereo} × {Granular, Stretch ×2, Pghi, Separate ×2, Retime, CaptureDoctor with every leg, trimRoomTail, deverb}: finite audio or a named IllegalArgumentException, and silence in means silence or refusal out, never invented sound. All 70 pairings held on the first run; now guarded | CORE | M | the full matrix passes: outputs finite, refusals named; silence never becomes noise |
| SS3 | ✓ done: `Pghi.stretch` gained the MAX_OUT_SEC cap its sibling always had (a ×100 factor on a long file was an OOM waiting, not an error) and only synthesizes the frames the capped output needs; Separate documents its input-bound memory posture — nothing there multiplies the input | CORE | S | 10 s at ×100 asks for 1000 s and gets exactly 300; the cap is the output length, not silent mid-frame truncation |
| SS4 | ✓ done: the whole visit at once — one recipe naming every leg (the `deverbed` key was the gap this test exposed), recipes stable through KitStore save/load, one `--undo` byte-identical across all legs, the treated kit exporting and the .xpn round trip lossless in audio (the format carries no recipes; kit.json does). Two fixture truths: clipping must come LAST in the chain or the flat tops un-flatten, and the synth hat's first-difference noise legitimately trips the MM2 distortion refusal by name | CORE | S–M | the all-flags visit stamps one recipe naming every leg and undoes byte-identical; recipes verbatim through the store; export/import clean |

**Below the line for SS:** timing-based fuzz (bounded-runtime property
harness — wants a budget runner); differential fuzz against the golden
snapshots (CC3 already guards the exports); a hostile-audio corpus of
real-world broken captures (grows in reference/ as they appear).

---

## Wave TT — the Calibration (CORE)

The reference capture delivered the finding the CLI existed to
surface: chopped end to end it classified **zero kicks** — a phone
across a room rolls off the sub, and the kick rule *requires* the sub
(`lowRatio > 0.55`, centroid under ~130 Hz), so real kicks arrive
gutless and file as SNARE/PERC. The fix is not loosening thresholds
(that would wreck the synthetic truth the 863 tests pin down) but
**context**: measure the capture's own bass reach once, and when the
sub is provably rolled off, judge a kick by what survives the mic —
its darkness, its shape, its single attack — at honest confidence.
F2.3's labeled-corpus harness (reading `reference/calibration/`)
finally gets fed.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| TT1 | ✓ done: `CaptureProfile.measure` — the share of sub-1 kHz energy below 150 Hz, once per capture; the reference kept 0.0009 against 0.83 for full-range synthetics, a thousandfold gap the boundary sits inside. `classify(features, profile)`: under a provably rolled-off profile a PERC is re-judged by what survived — dark centroid (< 700 Hz, the real kicks' knock clusters at 350–670), low high band, near-tonal flatness — and decay decides: punchy → KICK, sustained → TONAL, between → PERC. Confidence capped 0.5–0.7; no profile = byte-identical. Fixture lessons recorded in docs/CALIBRATION.md | CORE | M | phone-sim kick → KICK with profile only; snare/hat/clap/tom unchanged; full-range profile a no-op; sustained note promoted TONAL; every prior classifier test untouched |
| TT2 | ✓ done: `chop` measures the profile before classifying and prints the honest line; the reference re-chop names **ten KICKs at 0.51–0.60 where yesterday it named zero**, A01 a kick again; every full-range chop test untouched | CORE | S–M | reference re-chop shows kicks + the line; a full-range synthetic break chops identically |
| TT3 | ✓ done: the F2.3 harness gains a permanent phone-sim corpus (labeled by construction) guarding the rule per label; docs/CALIBRATION.md names the `reference/calibration/<label> NN.wav` on-ramp and keeps every fixture lesson taught (pure-sub kicks can't lose their sub, ~24 dB/oct chains, sustained notes defeat naive rolloff assumptions, bursts belong to the kick gate alone) | CORE | S | harness green on the phone-sim corpus; docs name the on-ramp; empty real corpus an honest skip |

**Below the line for TT:** real isolated-drum captures from the bench
(each Live III drum alone, close-miked and room-miked — the labeled
truth that would let thresholds be *fit* rather than reasoned); a
`--profile` override flag; profile-aware `learn` (the Ear listening
through the same context); per-device profiles remembered like
pockets.

---

## Wave UU — Sound design, both directions (CORE + APP)

The review that started this wave found the shop's sound design living
almost entirely in the CLI — 45 verbs of tested DSP the phone never
reached — while the phone's own pad sheet drew four era segments and
nothing else. Two lanes, run together: **new DSP in the core** (the
Séance's smear, the plan's own oldest below-the-line item) and **the
phone catching up** (the rack's characters and the pad shape, both of
which existed for months as terminal-only doors). Every core item
lands with its exit test on the JVM; the app items ride existing
Compose patterns over tested `:shell` state.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| UU1 | ✓ done: `Separate.smear(snip, amount)` — the STN transient mask (`stnMasks`, now computed once and shared with `stn`) taken away per bin by `amount`, one analysis and one synthesis; the result peak-matched to the source (capped at +12 dB of makeup, so a bare click is not shouted back up); amount 0 is the input object itself; deterministic | CORE | S–M | on the tone+clicks+hiss fixture the clicks stop standing out of the wash (prominence over a control window collapses) while the tone and the hiss survive; graded in amount; a held tone passes through; a bare click's residue stays under the source's peak; stereo channels smear identically |
| UU2 | ✓ done: `Smear` as a rack section — `FxChain.smear` (AMOUNT), order reverse → **smear** → eq → …, JSON section absent unless set so every old recipe is byte-stable; `Treatments.EXTRA` holds `smeared` (and any future character) *outside* `Shuffle.TREATMENTS`, whose index order every saved bank-B seed depends on | CORE | S | round-trip through JSON; old recipes unchanged; unknown macro refused; a smeared snare carries less of its energy in the first 20 ms and records a smear-only recipe; `treat <kit> A02 smeared` works unchanged |
| UU3 | ✓ done: `KitBuilderModel.characterPad(slot, name, amount)` — the whole-pad door `eraPad` already was, generalized (`rewriteEveryFile`): every referenced file, layers included, binned, one fx-only recipe (name + AMT); `unEraPad` undoes either row; AMT 0 a no-op that validates the name first | CORE | S | a layered snare's every zone re-renders and every file lands in the bin; `PadSheet.read` names the segment; undo restores every file byte-identical; a typo is refused even at AMT 0 |
| UU4 | ✓ done: the PAD SHEET's second TREATMENT row — `PadSheet.CHARACTER_SEGMENTS` (TAIL · SLAP · WASH · PUNCH → `smeared` / `slapback` / `washed` / `punched`), `treatmentFor` speaking both rows, `read(recipe)` lighting the right segment for either recipe shape; the phone ruling extended (a `reversed` twin or a CLI `crushed` reads "TREATED: CRUSHED" on the provenance line, never NONE); `applyTreatment` undoes-then-reapplies through the bin for both rows and *stacks* on a recipe the bin cannot restore (a twin, a CLI treat) instead of refusing with the ghosts toast | APP | S–M | shell: both rows map to real names, inverse mappings hold, `read` on era / character / unmapped / fx-only / null; app: one tap per character, AMT re-applies, the toast names the segment |
| UU5 | ✓ done: `PadShape` (`:kit`) — the shape's one reading (attack ramp ≤ 0.4 s, decay fade over d × length, cutoff 20 Hz..20 kHz exponential, resonance 0..12 dB) shared by `KitPreview`, `SfzWriter` and the phone; `PadFilter` (`:synth`) renders the filter half through the TPT SVF, peak-held; `ShapeAudition` (`:shell`) composes both for a single HIT | CORE | S | envelope: no shape is the same object, attack silent-then-full, decay trims at the shaped length, mappings match the SFZ writer's figures; audition: cutoff 0.5 keeps 110 Hz and kills 6 kHz, resonance lifts the cutoff tone without exceeding the source's peak, decay + filter compose; SFZ and preview output unchanged (their suites) |
| UU8 | ✓ done: SCULPT and STRETCH on the KIT screen — `TextureKits` (`:shell`) is the one door both verbs come through (the CLI `sculpt` now calls it; its output and folders are unchanged): four seeded takes as a texture kit of its own, LOOP pads, provenance and recipes; `Spec.Sculpt` (cloud/scrub/swarm), `Spec.Stretch` (the whole hit slowed BY a factor, clamped so no take outruns a minute, the effective factor recorded), `Spec.Freeze` (four instants: the loudest, then ¼ ½ ¾ in); the panel's data (kinds, modes, one `Knob` each — LENGTH / BY / HOLD, exponential); `KitShelf.texture` names the tape "<Pad> Sculpt / Stretched / Frozen" and `App.texture` renders it like FRESH TAPE and opens it | CORE + APP | M | shell: sculpt takes are LOOP with provenance/recipe and same-seed bytes; stretch takes run source × factor and clamp to a minute with the real factor in the recipe; freeze takes sit at the named instants (a quarter in is the low tone, three quarters the high); the panel's knobs open at their defaults; CLI sculpt tests unchanged. App: SCULPT ▸ / STRETCH ▸ on the KIT action row open the panel; GO lands and opens the new tape |
| UU7 | ✓ done: MUTATE on the PAD SHEET — `MutateSheet` (`:shell`): the four moves in the verb's order, one knob per move (AT 5..2000 ms and HZ 40..8000 exponential, MIX linear; STACK none), partners = this kit's other pads or ROULETTE's deal off the shelf (guided, seeded by the tap count, seed recorded like `--roulette`), `read(recipe)` naming the move and parents; the card draws the moves, a mini grid of partner tags four to a row, the ROULETTE line, the knob, MUTATE and UNDO; GHOSTS pads refused with their own line before the verb's own refusal | CORE + APP | M | shell: knobs round-trip their defaults and read in plain units, halfway on HZ is the geometric middle, partners never include the pad, a pad partner mutates with the CLI's own `Kit:A02` label and undoes byte-identical, a deal is seeded and its seed lands in the recipe, other recipes read as unmutated; app: pick a move and a pad, MUTATE, the line says what the pad now is |
| UU6 | ✓ done: the SHAPE card on the PAD SHEET — ATTACK / DECAY / CUTOFF / RES steppers writing `KitPad.attack/decay/cutoff/resonance` as metadata through the debounced `editPadMetadata` door (no WAV touched, no take per nudge), value column reading OFF / FULL / OPEN while a field is the format's default, RESET clearing all four; HIT auditions `ShapeAudition`, so a tighten is heard before the card | APP | S | shaped pad exports through both generations' fields (GG1's suite, unchanged); HIT plays the approximation; RESET returns the pad to byte-identical `kit.json` |

**Below the line for UU:** MUTATE's other-kit pads and WAV parents on
the phone (a kit picker — the shelf's ROULETTE covers the cross-kit
case for now); the *clear* stretch (PGHI phases) on the phone — the
wash is the texture people want, the clear one is a CLI flag; SCULPT / STRETCH as pad-sheet actions
(they make *new* kits, so they belong on the KIT screen, not the
sheet); formant-preserving pitch shift and `sculpt --keys` (still the
Séance's next two core items); the remix bank rolling `smeared` (would
change every saved seed — a versioned table if ever).

---

## Wave VV — the room and the tape (CORE + APP)

Sound design that is *composition*, not new theory: every item here is
a few existing passes in a new order, landing as a named character on
the pad sheet (rows two and three) or a MUTATE move. The point of the
wave is six new sounds in a week and the one UI change the later waves
also need — the card growing past one row of characters.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| VV1 | ✓ done: ROOM OF ITSELF — `mutate --room [--amount]`: the pad convolved with a partner's tail (FFT convolution on the classifier's own `Fft`), MIX 0..1 dry/wet, peak matched to the pad; the fifth MUTATE move, so the phone's card gets it with a MIX knob and ROULETTE can deal the room | CORE + APP | M | a click through an exponentially decaying noise tail decays with that tail's time constant; MIX 0 is the pad; the recipe records the room's label and mix; undo byte-identical |
| VV2 | ✓ done: GHOST — `Separate.ghost` (the noise layer alone: `1 − amount·(s + t)` on the STN masks, peak matched with the smear's capped makeup), a `Ghost` rack section after SMEAR, the `ghosted` character | CORE | S | tone and clicks vanish, hiss survives and is brought up; a ghosted kick is not a kick; never above the source's peak |
| VV3 | ✓ done: TAPE STOP and TAPE START — `Motion`, last in the rack: a variable-speed head with a linear-interpolated read, STOP the capstan letting go over up to 2 s (pitch and level fall to silence, length kept), START the reel spinning up over up to 1.5 s (the sound arrives late); the `stopped` and `started` characters | CORE | S | STOP: crossings fall by half and the end is silent; START: crossings and level climb; both zero is the input object |
| VV4 | ✓ done: the pad sheet's third row — GHOST · STOP · START · FLIP over `characterPad`; `reversed` now lights FLIP (AMT grades its spring tail) | APP | S | every segment names a real character; the inverse mappings hold |
| VV5 | ✓ done: GENERATION LOSS — `Dub`, a rack section beside CRUNCH: GENERATIONS (0..12) bounces through the `tape` and `mpc60` eras at half strength, peak matched at the end; the `dubbed` character | CORE | S | the copy's likeness to the source (zero-lag correlation) falls with every generation; a kick dubbed at the default depth is still a kick; zero is the input object; a dozen passes never exceed the source's peak; deterministic |
| VV6 | ✓ done: BANDED SMEAR — `Separate.smear(aboveHz)` leaves every bin under the floor at unity; SMEAR gains a FLOOR macro (0 = the whole band, else 80 Hz..6 kHz exponential); the `skimmed` character | CORE | S | the tone under the floor keeps its level within 5 %; the clicks above it still go; a negative floor is refused |
| VV7 | ✓ done: SWELL — `Swell`, first in the rack: the hit's own head stretched to RISE (0..1.5 s) through the wash, backwards, faded in, under the hit's peak, then the hit itself bit for bit; the tail budget is measured from the swelled sound so a swell is never cut as a tail; the `swelled` character | CORE | S | output = rise + the untouched hit; the arrival's last quarter is louder than its first; RISE 0 and a rise under one window are the input object |
| VV8 | ✓ done: the pad sheet's fourth row — SKIM · DUB · SWELL; short rows keep the chip width | APP | S | every segment names a real character |

**Below the line for VV:** the capture-room variant of ROOM (the
capture doctor's measured tail as the impulse — wants the phone-mic
reference capture first).

---

## Wave WW — in key (CORE + APP)

The on-thesis wave: sound design that knows the kit's key. Harder DSP
over the spectral door, each with a real exit test.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| WW1 | ✓ done: PAD FROM ANYTHING — `PadFromAnything` (`:kit`): stretch far (the clear stretch for a pitched source so its note stays a line, the wash for an unpitched one), the sample = the wash's first second (the arrival) + four seconds (the body) with the seam baked as a 0.75 s crossfade into the material before the loop start, written through the one-note package writer as a keygroup with loop points and a 0.6 release, both generations; DEPTH (×8..×100, giving where a minute or the loop demand it, the depth used reported) and BLOOM (0..1 s arrival ramp); an unpitched source is a drone at C3, never refused; CLI `pad <wav> \| <kit> <pad> [--depth] [--bloom]`; the pad sheet's PAD FROM ANYTHING card (`PadMaker` knobs, MAKE PAD ▸ INSTRUMENT beside MAKE INSTRUMENT's own door) | CORE + APP | M | the last frame equals the frame before the loop start and the level agrees across the wrap; the body of a 220 Hz source still detects 220 Hz; A3 at the root; a burst is a drone at C3 at the pad's ceiling; depth gives (8 s at ×40 → ×7.5, 50 ms → ×100), blips and 31 s sources refused; export lands `.xty` + `_[TrackData]/` with the loop in the `.xpm`; same seed same bytes |
| WW2 | ✓ done: SPECTRAL RETUNE — `Retune` (`:audio`): one long, fine FFT over the body finds the partials (local maxima 12 dB above their neighbourhood, within 40 dB of the loudest) and the tonalness (their share of the band's energy); each partial's ratio to its nearest in-key note is a plateau on the frequency axis joined by straight lines; every `Spectral` frame is resampled through the map in the log domain (a lobe keeps its parabolic shape, so the phase integration reads the moved peak), one correction pass trims each ratio by where the partial actually landed, and `Pghi` reinvents the phases; AMOUNT how far, peak matched, per-seed; refused in words when the classifier hears a kick/snare/clap/hat or the tonalness is under 0.5; CLI `retune <kit> <pad> [--key] [--amount] [--seed] [--undo]`; `KitBuilderModel.retunePad` (the kit's key, or the nearest semitones without one); the pad sheet's TUNE segment, row four | CORE + APP | M–L | four partials 38..70¢ off C major land within 5¢ of A3/C5/E6/C7, the bell still decays, peak matched; kick, snare, hat, clap refused and named, a tom and hiss judged on their own; AMT 0 the same object, AMT ½ halfway, in key untouched; stereo stays stereo, same seed same bytes; D minor pentatonic sends 1290 Hz up to F6 |
| WW3 | ✓ done: TRANSPLANT — `Transplant` (`:audio`): both sounds folded into one energy-weighted long-term spectrum read in BANDS log-spaced bands (4..64, default 16), the band-by-band difference one fixed set of per-bin gains through `Spectral` (nothing moves in time), capped ±24 dB, peak matched, all measurement; the sixth `Mutate` move (`--transplant [--bands N]`, recipe `bands`), the MUTATE card's sixth chip with a BANDS knob (the chips now three to a row) | CORE + APP | M | a snare through a hum: the 10 ms envelope correlates > 0.95 with the snare and less with the hum, the 16-band shape correlates > 0.85 with the hum and more than with the snare, length and peak the snare's; 64 bands fit the hum's formant closer than 4; stereo stays stereo, same bytes twice; bounds refused |
| WW4 | ✓ done: BODY — `Body` (`:audio`): two-pole resonators at the key's chord tones (root 1.0, fifth 0.5, third 0.3) over three octaves from C2, each octave softer, the hit the mallet; DECAY the T60 (0.05..4 s), AMOUNT dry→body, the result the hit plus the decay, peak matched, no seed; no key → the hit's own note or C, never refused; the keyed family (`Keyed`, `KitBuilderModel.keyedPad`, recipe `{"keyed", "key", "amount", "seed", "decay"}`) now holds TUNE and BODY; CLI `body <kit> <pad> [--key] [--decay] [--amount] [--undo]`; the pad sheet's fifth row | CORE + APP | M | a click through BODY in A minor detects A and its loudest partial is an A; T60 measured 0.3 s and 1.2 s at those knobs; the modes are root/third/fifth in order, root loudest, no key → root and fifth; AMT 0 the same object; stereo stays stereo, same bytes |
| WW5 | ✓ done: WOBBLE — `Wobble` (`:synth`): the TPT state-variable low-pass swept by a cosine that opens on the onset and closes half a division later, the division a note value (1/1..1/16, default 1/8) at the kit's tempo; AMOUNT the depth over 120 Hz..6 kHz, peak matched, no seed; the keyed family's third member (`--rate`, `--bpm` on the CLI, the kit's tempo or the preview's 92 on the phone); CLI `wobble <kit> <pad>`; the pad sheet's fifth row | CORE + APP | S | quarters at 120 sweep every 0.50 s and eighths every 0.25 s by the brightness swing of white noise, quarters at 90 every 0.67 s; bright at the onset, dark half a division in; RATE snaps to divisions; AMT 0 the same object; stereo stays stereo, same bytes |
| WW6 | ✓ done: ATTACK KEPT, TAIL ETERNAL — `Eternal` (`:audio`): the first KNEE (30 ms) copied bit for bit, the tail's spectrogram resampled through a hyperbolic map (speed 1 at the knee, `τ₀·ln(1 + τ/τ₀)` after, τ₀ solved so the source's end lands on the knob) and reinvented by `Pghi`, a 5 ms seam into the resynthesis, the tail at the real tail's peak; TAIL the knob (0.5..30 s, AMT exponential on the phone), a longer tail refused rather than sped up; the keyed family's fourth (it reads nothing of the kit but escapes the rack's tail budget); CLI `eternal <kit> <pad> [--tail] [--knee] [--seed] [--undo]`; ETERNAL closes the pad sheet's fifth row | CORE + APP | S | the first 30 ms sample-equal and the length knee + knob; a chirp's pitch reads the map: at speed just past the knee, most of the way through at half the tail, on the top at the end, monotone; refusals in words; AMT ↔ seconds at both ends; stereo stays stereo; same seed same bytes |

---

## Wave XX — the crate as an instrument (CORE + APP)

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| XX1 | ✓ done: DRIFT TOWARD THE CRATE — `Mutate.drift`: the guided roulette finds the neighbour, MORPH blends AMOUNT of the way toward it, the morph's recipe carrying the spin and a `drift` flag; CLI `drift <kit> <pad> [--amount] [--seed] [--root] [--undo]`; the MUTATE card's DRIFT button beside ROULETTE (the card flips to MORPH so MIX is the knob it read), a drifted pad reading as DRIFT | CORE + APP | S | drift's bytes equal roulette then morph by hand; same seed same bytes; the sheet's one tap records the MIX amount and reads back as DRIFT; the verb round-trips through the bin and refuses an empty crate |
| XX2 | ✓ done: BREEDING — `Breed` (`:shell`): pad by pad, A's pad meets B's on the same slot (or B's first of the class); every synth macro (engines agreeing) and rack macro is A's, B's or the average by a seeded coin, a one-sided rack section comes along half the time; synth pads re-render, captured pads run through the crossed rack, the rest come over verbatim; the audit re-throws the coin up to six times until the child classifies as its parent's audio does, else keeps A's pad and says so; a new folder, parents untouched, `bredFrom` stamped; CLI `breed <a> <b> [--out] [--name] [--seed]` | CORE | M | factory × lucky-dip: every child pad classifies as its mother's does, crossed pads differ from her, same seed same bytes, another seed another kit; a captured kit against a rackless kit is all kept and both parents stay byte-identical; a taken destination and an unsafe name refused |
| XX3 | ✓ done: DE-SAMPLE — `Desample` (`:synth`): every THUMP voice's macro space on a three-level grid, rendered once and measured by the classifier's extractor, the hit's nearest by `Similar`'s distance, a coordinate descent refining the macros; the distance always told, past 0.45 named far; `KitBuilderModel.desamplePad` (kindred voices first, the patch riding the pad, refused when far unless forced); CLI `desample <wav> \| <kit> <pad>`; the pad sheet's DE-SAMPLE card; the spec `docs/DESAMPLE.md` | CORE + APP | L | a grid-point THUMP kick returns its own macros at distance 0 and renders back the same bytes; an off-grid snare refines under 0.08 and never loses to the grid; kick, snare and hat captures land within the bound on kindred voices; hiss is named far; the builder swaps a kick for a kick patch's render and refuses hiss unless forced; the verb prints and writes a patch |

---

## Wave YY — outside (CORE + APP)

The Outsidify idea, SnipSnap's way: the phone plays a sound out — its
speaker into the room, or the headphone jack or a USB interface into a
pedal, an amp, a spring tank — listens to what comes back, finds *when*
it came back, and bakes the return as the pad. No live loop, no
feedback mode (the app renders offline; a feedback loop is a
performance surface, below the line): two moves, both bin-backed
rewrites with the recipe riding the pad.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| YY1 | ✓ done: the trip measured — `Outside` (`:audio`): `align` cross-correlates the return against the send by FFT and reads the lag of the strongest match as the latency, polarity allowed to flip and reported, confidence the normalized correlation, standout the match's height over the correlation's own RMS; `reamp` cuts the return at the arrival, keeps the tail while it still sounds (the floor measured off the room *before* the arrival — what the pre-roll is for — a 0.6 s hold bridging a delay pedal's repeats, the cut landing where the quiet began), restores polarity, peak matches to the pad, MIX dry to wet; refusals in words for a clipped return, a silent room, an arrival that doesn't stand out | CORE | M | a copy 1234 frames late is found to the frame at confidence over 0.9, upside down is reported; a 48 kHz stereo return with an echo still lands on the direct arrival within two frames; silence, a clip and a stranger are refused and named; the cut keeps an echo past the hit and closes when it dies; MIX 0 is the pad; the same trip is the same bytes |
| YY2 | ✓ done: the room as a room — `Outside.probe` (a two-second exponential sine sweep at half scale, faded) and `Outside.impulse` (Farina's inverse filter, the sweep reversed with a 6 dB/octave tilt, convolved with the return; a wire deconvolves to a unit impulse so the room's gain reads true; the response cut from 2 ms before its peak while the tail sounds) | CORE | M | a direct path at 0.4 and an echo at 0.2 come back at 0.4 and 0.2 within 0.03, the latency to within two frames, the floor between the taps under 0.04; a wire is 1.0; silence is refused |
| YY3 | ✓ done: the card as data — `OutsideSheet` (`:shell`): REAMP (MIX opens wet) and ROOM (WET opens at ROOM OF ITSELF's half); `send` (the pad's own audio, or the sweep at its rate), `preRollFrames` / `listenFrames` (0.25 s of room first, then the send, then three seconds of tail and a second's latency allowance); `apply` — REAMP through `replaceAudio` with an `outside` recipe (the trip's lag past the pre-roll, confidence, the flip, MIX), ROOM through `Mutate.apply`'s ROOM with the deconvolved impulse as the one parent `outside:room` and the `outside` block riding inside the mutate recipe, so the MUTATE card reads it as a ROOM whose parent is the room; `read`, `statusLine`, `undo` (the original out of the bin, both stamps cleared). Found and fixed on the way: `Mutate.alignToOnset` trimmed a parent that starts *on* its hit to its second event (the detector credits nothing to frame zero) — a head already within a tenth of the peak before the "first" onset now stays | CORE | M | REAMP: the pad is the return on the hit, the echo kept, peak matched, the recipe says 20 ms late, undo byte-identical; ROOM: the pad plus the echo, the direct path the pad itself 2 ms in, the recipe a mutate ROOM with `outside:room`, undo byte-identical; a silent room refuses in words and never reaches the bin; a two-tap parent through Mutate's ROOM keeps its direct path |
| YY4 | ✓ done (blind for CI's compiler; the desktop session runs it): the phone — `OutsideSession` (`:app`): one blocking trip on IO, an `AudioRecord` (UNPROCESSED where offered — a source with echo cancellation would remove exactly the send) filling the return, a `MODE_STATIC` `AudioTrack` playing the send once the pre-roll is in, out of whatever the output route is; the pad sheet's OUTSIDE card (two chips, the knob, the status line off the recipe, SEND whose label is the trip's stage — LISTENING…, SENDING… — and UNDO); refusals first and in words: GHOSTS on, the mic not granted (ARM on KITS grants it), the tape rolling. **Written blind in the cloud session; the desktop session compiles and runs it** | APP | M | on a phone: SEND on the speaker in a room reamps a kick with the room on it, the toast names the trip in ms; ROOM on the same pad convolves it with that room; the jack into a pedal and back reamps through the pedal; a silent input refuses in words and the pad is untouched |
| YY5 | ✓ done: the room kept — `Rooms` (`:shell`, tested): a ROOM trip's measured impulse onto the shelf under `Rooms/` beside the kits (no `kit.json`, so never a kit) as a WAV with a sidecar saying how it was measured (the trip, how sure, from which pad, when), named "KIT ROOM" then "KIT ROOM 2"; `MutateSheet.Partner.Room` as the MUTATE card's third kind of parent (`room:NAME` in the lineage, a `room` block in the recipe), so any pad on any kit plays inside a room measured once; `OutsideSheet.Outcome.impulse` rides a ROOM trip and `keep` puts it away. Phone (blind): KEEP ROOM on the OUTSIDE card after a ROOM trip, the rooms as chips on the MUTATE card, the kept room the partner at once | CORE + APP | S | a kept room lists back as measured, fresh names, forgotten is gone, a silent room is refused; another kit's pad through MUTATE ▸ ROOM with the kept room grows by the room's slap and the recipe names `room:FUNK ROOM`; a REAMP has nothing to keep and says so |

**Below the line for YY:** FEEDBACK (Outsidify's third mode — a live
send/return loop with gain; performance territory, not a render); a
calibration trip (a click out, the latency stored, so SEND can pre-cut
without correlating — unnecessary while `align` finds it every time).

---

## Wave DDD — the pad sheet folds (CORE + APP)

The design pass over the app's recent features (canvas: *Pad Sheet v2
and Rooms*, 2026-09-07) found the PAD SHEET had outgrown its wireframe:
one 1,800-line scroll of ten steppers and six cards, with OUTSIDE, MAKE
and the pad nav accidentally fixed *below* the scroll. Direction A won —
the design language's own rule, group boxes with pixel legends — with
two ideas borrowed from the two-screen direction (the open box persists
across pads; the trip's reels turn on an LCD strip) and one correction
on the shelf (a forgotten room goes to the bin, like every delete).

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| DDD1 | ✓ done: the boxes as data — `PadSheetBoxes` (`:shell`, tested): five boxes in order (TREATMENT, SHAPE, MUTATE, OUTSIDE, MAKE), `toggle` (one open at a time, the open one tapped closes), and the closed strip's one-line summary read from what the pad already carries — `PadSheet.read` ("CRUSH · 35%"), the shape fields it sets ("ATK 200 MS · CUT 632 HZ"), `MutateSheet.read` ("ROOM × FUNK ROOM"), `OutsideSheet.read` or the trip's stage while one is out, MAKE's doors or what DE-SAMPLE made; `UNTOUCHED` when nothing does; `SUMMARY_CHARS` (44) the label budget every strip and stage fits | CORE | S | ORDER and legends; toggle opens, switches, closes; an untouched pad reads UNTOUCHED on four benches; each recipe reads as its strip, shouting, inside the budget; the stage wins while a trip is out |
| DDD2 | ✓ done: the phone (blind for CI's compiler) — `GroupBox` (`:app`): an etched box (`Modifier.etchedBox`, the window's own colour with the sunken edge) with the pixel legend on the line, a 44dp strip reading the summary with a drawn chevron, content only while open; `PadSheetScreen` puts every card below the everyday controls inside one, hoists the open box to `App` (remembered per kit, so A03 ► keeps the bench), moves OUTSIDE / MAKE / EJECT *into* the scroll where they belonged, and pins the pad nav under a 2dp rule; `ReelsStrip` turns two drawn reels on an LCD strip beside the trip's stage while OUTSIDE is out, the box's strip in lcd-alt | APP | M | closed, the sheet fits one screen; one box open, the nav stays put; a ROOM trip shows the reels and the strip reads LISTENING…; the open box survives ◄ ► |
| DDD3 | ✓ done: rooms go to the bin — `Rooms.forget` moves a room's WAV and sidecar under `Rooms/.bin/` stamped with when, `binned` lists them with the days left, `unforget` brings one back under a fresh name, `sweepBin` empties what slept past `BIN_DAYS` (30); the KITS screen's ROOMS section (blind): a row per kept room — Rock Salt name, the measurement, its length on an LCD — held to press and reveal FORGET → BIN in the bin's red; `App` sweeps the bin at start | CORE + APP | S | forgotten is binned not gone, 29 days on the sweep leaves it, 31 days on it goes; unforget lands beside the newer room; the bin folder is never a room; the copy shouts |
| DDD4 | ✓ done: the bin's door on the phone (blind) — THE SHELF's ROOMS section grows an IN THE BIN list: a row per forgotten room in the bin's own LCD-dark line, `daysLeft` counting down (the last two in `warn`, as TAKES + BIN counts a pad), RESTORE in lcd-alt through `Rooms.unforget`, the toast naming the room back on the shelf; `KitShelf.binnedRooms` / `restoreRoom`. The design boards that decided the wave land under `design/pad-sheet-v2/` with a README, and `DESIGN_GAP.md` gets a dated refresh naming what the old handoff no longer covers | APP | S | a forgotten room shows under IN THE BIN with its days; RESTORE puts it back beside the live rooms and the toast says so; the copy shouts |
| DDD5 | ✓ done: the crate with intent — `MutateSheet.Partner.Other` (`:shell`, tested): a pad picked on another kit of the shelf as the MUTATE parent, the CLI's own `Kit:A03` label in the lineage so it reads as a deal would, an `otherKit` block in the recipe; `otherKits` (the shelf minus this kit, a broken folder skipped) and `padsOf` for the picker. Phone (blind): under the ROOMS chips on the MUTATE card, ANOTHER KIT · PICK ITS PAD — the shelf's other kits two to a row, the picked kit's pads four to a row; absent on a one-kit shelf | CORE + APP | S | the other kits list without this one; a picked pad morphs through the verb's door with `Soul:A03` in the lineage and the recipe naming the kit; an empty slot refuses in words; undo is the original |
| DDD6 | ✓ done: the last parent kind — `MutateSheet.Partner.Wav` (`:shell`, tested): a file picked off the phone as the MUTATE parent, `hold` writing its audio under the cache as a WAV (write-then-rename, one held at a time, a silent file refused in words) and labelling it with the name the file came with, exactly what `snipsnap mutate --with hit.wav` puts in the lineage; the recipe records nothing beyond that, as the CLI does. Phone (blind): an A FILE button on the MUTATE card opens the system picker on any audio, the pick decoded the way a share is (`MediaDecode`), the button then reading the file's name; a refusal reads NOT A PARENT in the decoder's words. `Copy.fileRefused`. Every parent kind the CLI verb takes is now a thumb's reach. |

**Below the line for DDD:** a swipe between pads on the sheet (today the
buttons are the only way, and the middle cell says so); emptying the
rooms' bin by hand (the sweep at start is the only door; TAKES + BIN's
EMPTY THE BIN NOW is per kit).

---

## Wave FFF — the landing reads back (CORE + APP)

DESIGN_GAP's first undrawn item: the share door's landing was a busy
line and a two-second toast, and the toast held counts only - which kit
was skipped, and why, went nowhere. SHARE and BACKUP hand off to the
chooser the same way, a backup's refused kits named only inside the
file's own report. The language already had the answer: the honest
little message box (TAPE JAM + [FINE]). The rule: the toast stays the
one-line voice for a clean landing; the box appears when there is more
to read than a line holds, and stays until read.

| Item | Status |
|---|---|
| FFF1 | ✓ done: the box as data — `LandingNote` (`:shell`, tested): `landed` (null when nothing was skipped - the toast suffices; else the toast's line as the title, SKIPPED lines first in the warn colour with the door's own reason, LANDED lines after), `backedUp` (the same for preflight's refusals, PACKED lines after), `refused` (the file's name and the refuser's words, kept until read), lines folded past `MAX_LINES` into "+N MORE" (trouble if any folded line was), the house casing (`shout`: Locale.ROOT, no trailing stop). `Copy.NOTHING_LANDED`. |
| FFF2 | ✓ done: the phone (blind for CI's compiler) — `MessageBox` (`:app`): the capture-blocked dialog generalised - scrim, raised bevel, the title in LCD type, the lines in a sunken field (trouble in `warn`, three lines each - the shelf's own refusal needs them, as the board showed), [FINE]; the scrim and the button dismiss, the box swallows the tap. `App` shows it for a landing with skips, a backup preflight refused part of (behind the chooser, read on the way back), and every import refusal - in place of the toast, never beside it. A clean landing and a full backup keep their toasts. |

**Below the line for FFF:** the SHARE hand-off itself (the chooser is the
system's - nothing of ours to draw); a box line that opens the skipped
kit's folder; the same box for EXPORT's preflight, which has its own card.

---

## Wave GGG — the trip catches up to its own drawing (APP)

Drawing OUTSIDE's trip state by state on the design canvas (page three,
built after wave FFF) found the code one step behind its own language in
two small places, noted on the board for a follow-up. This wave closes
both.

| Item | Status |
|---|---|
| GGG1 | ✓ done: the measured line reads on an LCD, not pixel text — `OutsideCard` (`:app`, blind for CI's compiler): once a trip measures ("ROOM · 23 MS LATE · 87% SURE"), the status sits in the same 28dp LCD strip the reels use at rest, in `lcdInk`; before a trip the guidance line stays plain pixel text, as the board's idle state shows. `OutsideSheet.statusLine` itself is unchanged and already tested. |
| GGG2 | ✓ done: KEEP ROOM lights — `ActionButton` gains `lit` (`:app`, blind): an amber rim over the ordinary bevel, [PrimaryAction]'s own dress on a working-surface button, shown only while `enabled` too, so a trip in flight or nothing yet measured still reads as the plain dimmed bevel. `OutsideCard` passes `lit = canKeep`. |

Nothing below the line — the board named exactly these two, and both are closed.

---

## Wave HHH — GRAIN FIELD and SURFACE, drawn (APP)

The design canvas's last two screens with no board at all — both arrived
whole from another branch. GRAIN FIELD held up as built. SURFACE didn't:
its one-line readout (latency, X/Y, up to four corner weights, TILT, the
pad name) ran to roughly double the frame's width in MORPH mode and
clipped mid-digit at 390 - the code's own comment already knew the line
"can outrun a narrow screen" and ordered latency first for exactly that
reason, but a lost pad name is still a lost pad name.

| Item | Status |
|---|---|
| HHH1 | ✓ done: GRAIN FIELD drawn — four boards on page six (loading, idle, DUET without the mic, DUET armed), from `GrainFieldScreen.kt` as it stands. No defect: every label fits, the scatter and both cursor rings read clearly. |
| HHH2 | ✓ done: SURFACE drawn, its readout fixed — three boards on page seven (XY, MORPH at the readout's longest, no pad). `SurfaceScreen`'s readout `TapeText` now allows a second line (`maxLines = 2`, `:app`, blind); latency still leads by design, so it and every mid-crowding case survive, and two lines fits every case this app ships except the rare worst one (MORPH + tilt + a shared stream + a long pad name), noted below the line. |

**Below the line for HHH:** a truly lossless SURFACE readout for the rare
worst case above would need a restructure (a second dedicated line for
identity, say), not a parameter — left for whoever hits it for real.

---

## Wave ZZ — the phone reads (APP)

The import door (F3) made the core's listening verbs reachable from the
phone; this wave puts three of them on the TAPE deck, each a different
reading of the same tape. Nothing new in the core: the Ear (LL3), the
pocket (LL4) and the dig (FF1) already exist and are tested; the wave is
the `:shell` seams that wrap them for the phone, and the buttons.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| ZZ1 | ✓ done: READ AS GROOVE — `ReadGroove.read` (`:shell`, tested): the selection (or the whole deck) heard by the Ear at its own tempo, sure hits mapped onto the open kit's pads (kindred stand-ins: a snare for a clap, an open hat for a closed one), landed as the captured base with the standard variations rewritten and an existing PROG E riding along untouched; GROOVE opens on it. Refusals in the Ear's own words: no confident tempo, no beat heard, nothing that maps; no kit open | APP | S | a two-bar synthetic beat reads onto kick/snare/hat pads at ~100 bpm and lands as the base with E intact; a tone refuses |
| ZZ2 | ✓ done: DIG — `Dig.best` (`:shell`, tested): `BreakFinder`'s best candidate as the deck's own frames; the TAPE button sets IN and OUT to it and parks the head at IN, so INSTANT KIT is the next tap; no break heard is said, never an empty selection | APP | S | a pad–break–pad song digs to the break's frames; silence digs to nothing |
| ZZ3 | ✓ done: STEAL THE FEEL — `ReadGroove.feel` (`:shell`, tested): the tape's timing and accent per 16th (`GrooveFeel.extract` over the Ear's hits) poured over the kit's captured base and landed as PROG E (an explicit re-fork), the pocket kept under `Pockets/` on the shelf, fresh-named on collision; refuses with no pattern to pour on or too few positions played | APP | S | the feel of a beat lands as E over a straight base; the pocket file round-trips; no base refuses |

Bench (phone): beatbox a bar into ARM TAPE, SNIP, READ AS GROOVE — the kit
plays it back; share a whole song in, DIG, INSTANT KIT — the break is a kit.

## Wave AAA — the sample carries its own sheet (CORE)

MPC compatibility, the one enhancement that waits on no bench: a pitched
or looped WAV carries its root note and sustain loop inside itself, so
loaded on its own at the MPC — from the card's browser, outside any
program — it arrives tuned and looping instead of at C3 and one-shot.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| AAA1 | ✓ done: `SmplChunk` (`:audio`, tested) — the RIFF `smpl` chunk's root note and one forward loop (frames, exclusive end; the chunk's inclusive end converted both ways); `WavWriter.write(…, smpl =)` opt-in, the chunk between `fmt` and `data`, the RIFF size honest, a write without one byte-identical to before; `WavReader.readSmpl` reads it back or null, a malformed sheet never a wrong one. `OneNote.writePackage` writes every zone's sheet from the program (root per keygroup, loop per layer), so one-note, multisample and PAD FROM ANYTHING packages all carry it | CORE | S | root and loop round-trip; audio untouched; RIFF size counts the chunk; refusals for a root off the keyboard and a loop past the sample; a looped zone's WAV reads back its root and loop, a pluck's the root alone |
| AAA2 | Bench: load a zone WAV from `Held Keys_[TrackData]/` on the Live III on its own (not the program) — does it play at its root and loop? Note either way; if the MPC ignores the sheet, the sheet still costs nothing | USER | S | yes/no on hardware |

## Wave BBB — the Hardening, round two (CORE)

Every door the phone grew after the SS waves — the import store, the
Ear and the dig on the deck, INSTANT KIT, the pad peaks, the silence
watch, the PCM conversion, the sampler sheet — meets the same hostile
inputs and degenerate shapes the older verbs had to. Same house rules:
a valid result or a named refusal, no other throwable, silence never
invents, nothing climbs out of a folder. All three harnesses held on
the first run, sharpened corpus included; they are guarded invariants
now, each pairing named on failure.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| BBB1 | ✓ done: the degenerate matrix, round two — `DegenerateDoorsTest` (`:shell`): {one sample, tiny, silence, DC, full-scale square, low rate, stereo, white noise} × {`ReadGroove.read`, `ReadGroove.feel`, `Dig.best`, `SnipStore.import`, `InstantKit.build`, `SilenceWatch.feed`}: a valid result (a reading with a tempo, a find inside the tape, a readable snip, a kit with pads) or an `IllegalArgumentException` in words; silence never becomes a beat, a break or a kit; `PadPeaks` shrugs at junk, truncated, empty and missing pad files; `Pcm` turns any bytes into finite samples inside the rails | CORE | S | every pairing passes; failures named per pairing |
| BBB2 | ✓ done: the sampler sheet under mutation — `FuzzTest` runs `WavReader.readSmpl` and `read` over thousands of seeded mutations of a sheet-bearing WAV: a typed refusal or a valid result, and a returned loop always inside the audio the file holds | CORE | S | no untyped throwable in the batch, inside the hang bound; no loop past the audio |
| BBB3 | ✓ done: the CLI hostile sweep extended — `learn`, `beat`, `pad` and `restore` join the file-taking verbs, and the corpus gains a zero-channel WAV, a three-channel WAV, a RIFF size past the moon, a sheet whose loop runs past the audio, and a backup zip whose entries climb with `../`; every verb exits 0..2 and nothing lands outside its folder (`KitBackup.restore` flattens entry names, `XpnImporter` runs through `SafePath`) | CORE | S | every (verb × hostile) pair exits cleanly; no escaped file anywhere |
| BBB4 | ✓ done: the sidecars under mutation, round three — `SidecarFuzzTest` (`:shell`) reads the kit, the grooves, the instrument, the pocket, the teach log, the pad recipe and the fx chain back under two kinds of damage: torn bytes (flips, truncation, zero and 0xFF runs) and rewritten trees (a random node swapped for null / a string / a huge, negative or fractional number / an empty array or object / a boolean, or a key dropped); a valid parse or a typed refusal, the teach log never throwing at all, and the parser refusing a nesting attack in words. Found and fixed: `GrooveStore` trusted its casts (a torn `lengthPulses` was an NPE, a string `bars` a `ClassCastException`); `KitStore` cast chain boundaries. Both read through the typed accessors now | CORE | S | no untyped throwable in any batch, inside the hang bound |
| BBB5 | ✓ done: the containers under mutation, round four — `ContainerFuzzTest` (`:kit`) damages *inside* the wrapper so the mutation reaches the reader that matters: the `.xpm` program XML inside a sound `.xpn` (round one tore the zip, and the XML parser never saw a bad byte), the JSON payload inside a sound ACVS container, the `.sfz` text (lines dropped, doubled, tokens swapped for `1e300` / `../../escaped` / empty), the backup zip, the answer sidecar, the WAV header walker, and the two name parsers over any string; `SidecarFuzzTest` adds the three pad-sheet readers (never throw at all) and the label. Found and fixed: `AnswerStore` trusted its casts (a dropped `seed` was an NPE) — typed accessors now. Nothing climbs out: every batch checks no `escaped.*` landed anywhere | CORE | S | no untyped throwable in any batch, inside the hang bound; no escaped file |

## Wave CCC — the Surface (APP + CORE)

A pad you *play the sound of*, not the sound: the open kit's first pad
loops under a finger and where the finger is drives pitch, filter and
drive — two axes, three with a pinch, four as a vector morph between
corner states — with the phone's roll riding along. PRINT is the SP-404
move: the master bus is copied into RAM while you play and lands on
TAPE as one plain sample, so the performance costs nothing to play back
later. The arithmetic (axes, pinch depth, corner weights, the one-pole
smoother) is pure JVM and tested; the engine is the repo's one native
library — Oboe under a C++ callback, because a finger wants its sound
under 10 ms and AudioTrack's blocking-write clock is the opposite trade.
The Android side is written blind for CI's compiler, as :app always is.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| CCC1 | ✓ done: `TouchSurface` (`:shell`) — `read(mode, touches, w, h, previous)`: X across, Y up, both on the rails; XYZ takes Z as the first two fingers' distance over the pad's diagonal and *holds* it when the pinch finger lifts (letting go of a knob is not turning it to zero); MORPH weights the puck bilinearly over A (top-left) B C D, each corner exactly 1 at its corner, the centre a quarter each, always summing to 1; `Smoother` the one-pole `y += k(x−y)` with `coefficient(cutoffHz, rateHz)`; `SmoothedReading` for the painted puck | CORE | S | corners, centre, rails, the held Z, the sum-to-one sweep, the coefficient at one time constant, a zero-size pad refused in words |
| CCC2 | ✓ done: the engine — `app/src/main/cpp`: `SurfaceEngine` on Oboe (LowLatency, Exclusive, float stereo, two bursts); a `SpscRing<ControlFrame, 64>` from the UI thread to the callback (a full ring drops the frame, the next is milliseconds away), corner states as atomics, a sample swap by pointer handshake (the audio thread frees nothing); `ParameterSmoother` per macro at ~10 Hz and the gate at 50 Hz, per sample; one looping voice with linear interpolation repitched by file rate over stream rate times ±1 octave, a soft clip with its make-up baked in, a trapezoidal SVF whose trig runs every 32 samples; `PrintBuffer` armed on the UI thread to a frame ceiling, written by the callback while Recording, the callback always making the last write (Stopping → Done), read on the UI thread only after Done | APP | M | bench: no dropouts through a fast gesture on a mid-range phone; a route change restarts the stream (needsRestart); the print reads back as what was heard |
| CCC3 | ✓ done: the bridge — `NativeSurface` (JNI, `libsnipsnap_surface`), `SurfaceEngine.kt` the owner (start/stop/close idempotent, stereo folded to mono on load, `armPrint`/`stopPrint` as a `Snip` at the device rate), `TiltSource` (gravity along X: flat 0.5, a quarter turn 0 or 1); Gradle: NDK pinned, CMake 3.22.1, prefab, `com.google.oboe:oboe:1.9.0` — the only native dependency | APP | S | the app assembles in CI with the native library; a print lands via `SnipStore.import` |
| CCC4 | ✓ done: SURFACE on the menu — `SurfaceScreen`: XY / XYZ / MORPH segments, PRINT / STOP PRINT, the LCD pad (grid, crosshair, puck, the pinch ring in XYZ, a weight bar per corner in MORPH), fingers in press order so a second finger never steals the puck, a frame loop that smooths at screen rate, paints from the smoothed value and sends the same value to the engine; a print lands on TAPE with "PRINTED n S TO TAPE." and TAPE re-reads (`importCount`); no kit → "OPEN A KIT. THE SURFACE PLAYS ITS FIRST PAD." | APP | M | bench: play, print, find the print on TAPE and chop it; MORPH corners audibly distinct; the roll of the phone moves resonance |
| CCC5 | ✓ done: the shared fallback — `SurfaceEngine::start` opens Exclusive first and, on an outright refusal, Shared (one mixer stage more latency, still playing); `isShared()` crosses the bridge and the screen says "SHARED STREAM. A LITTLE MORE LATENCY." | APP | S | bench: a device with the exclusive path held by another app still plays |
| CCC6 | ✓ done: the corners are yours — `SurfaceStore` (`:shell`, tested, fuzzed): `surface.json` beside the kit with the pad the surface plays and four `Corner`s (pitch, cutoff, resonance, drive, each 0..1, refused in words); `Corner.from(mode, reading, tilt, corners)` is the engine's own macro map (XY / XYZ / the MORPH blend), so SET A..D on the screen captures the sound under the last touch as a corner, pushes it to the engine and saves; defaults are the engine's; a torn file reads as the defaults, said aloud | CORE + APP | S | round-trip byte-stable; a corner at A is A; the centre is the average; refusals named; the fuzz batch holds; bench: set four corners, leave, come back, morph between them |
| CCC7 | ✓ done: PAD ◄ ► — the surface plays any of the kit's pads, by slot, wrapping, the choice remembered in `surface.json`; the readout names it MPC-style (A01..) | APP | S | bench: step through the kit; reopen, the same pad is under the finger |
| CCC8 | ✓ done: PRINT → PAD — the print's destination toggles → TAPE / → PAD; → PAD opens SYNTH's own slot chooser (now shared) and the print lands through `KitBuilderModel`: `assign` on an empty slot ("Surface Print", unclassed), `replaceAudio` on a taken one (the original in the bin); a layered or chained pad is dimmed in the chooser (SYNTH's own rule) and a refusal the model still raises (the kit changed under the chooser) is said in words with the print kept, a disk failure named as such with the print still pending; cancelling the chooser sends the print to TAPE rather than losing it; the kit's identity bumps so KIT and the surface reload | APP | S | bench: print to an empty pad, hear it on KIT; print over a taken pad, find the original in the bin; cancel, find the print on TAPE |
| CCC9 | ✓ done: LATCH and BARS — LATCH keeps the loop sounding where the finger left it (the last held reading is what the engine and the puck get), so one hand sets corners while the other is free; BARS cycles FREE / 1 / 2 / 4 / 8 bars at the kit's tempo (`PrintLength`, `:shell`, tested), the print armed to exactly that length so it stops itself on the bar and drops onto the groove grid; a kit without a tempo prints free and the button says NO TEMPO | CORE + APP | S | bench: latch, lift, the loop holds; 2 BARS at 92 BPM prints 5.2 s and lands trimmed to the bar |

## Wave EEE — M4, the pads on the native engine (APP + CORE)

The latency milestone the app plan named first and shipped last: PLAY's
pads leave SoundPool for the native engine the Surface brought. Every
pad semantic stays on the JVM under test — which layer a velocity taps,
which slice a chain steps to, level and pan as the MPC means them, tune
as a ratio — and the native voice only ever hears "this sample, these
frames, these gains, this speed". The engine reports each voice's end,
so the allocator learns of an ending when it happens instead of from a
timer. KIT's grid keeps the SoundPool player until the native voice has
been heard on a phone; then it follows in one small PR.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| EEE1 | ✓ done: `PadHit` (`:shell`) — `resolve(pad, velocity, hitIndex, framesOf)`: the layer whose range holds the MIDI velocity (the loudest as fallback), the chain slice for the hit (a zone's own base and cycle, the last slice to the end), left/right gains as the SoundPool player mapped them so a kit sounds as it did, tune as `2^((coarse + fine/100)/12)`; a sample the engine never loaded is no hit, refusals in words | CORE | S | whole pad, pan/velocity, tune, layers and the gap fallback, single-zone chain stepping, the zone grid, the null and the refusals |
| EEE2 | ✓ done: `PadEngine` (`app/src/main/cpp`) — 32 voices, each a windowed, repitched, linearly interpolated read of a bank sample (mono or stereo), a 5 ms choke fade and a 20 ms panic fade, the quietest fading voice stolen first and the oldest after; the bank built on the UI thread and adopted whole by the callback (every voice silenced and reported), the same pointer handshake as the Surface; commands and endings on two lock-free rings; `OboeOutput.h` the one way both engines open a stream (Exclusive, then Shared) | APP | M | bench 2026-09-08: **a bank swap mid-roll is silence, not a crash** — verified against a live voice (a SURFACE print on a pad, since no stock pad rings long enough); no dropouts through a minute of two-thumb rolling; **a choke is a fade, not a click** — a closed hat cuts a ringing open one clean |
| EEE3 | ✓ done: PLAY over it — `PadEngine.kt` (bank read off the main thread through `WavReader`, hits through `PadHit`, `@Synchronized`, `close` idempotent); the reap timer and its two constants gone, replaced by a frame loop that drains the endings ring into `VoiceAllocator.voiceEnded`; the allocator built at `MAX_VOICES` so the status line and the engine cannot drift; a hit nothing loaded for is handed back to the allocator at once; ON_STOP still panics | APP | S | bench 2026-09-08: **the exit test passed** — tight enough to play, 8–11 ms with no `SHARED` (the exclusive path opened), VOICES counts down as one-shots end, a kit swap over a sounding voice is silence, a closed hat cuts an open one with no click, a gate pad stops on the lift and a one-shot plays out. **Every line of the row is answered** |
| EEE4 | ✓ done: KIT's grid onto the native engine — `KitScreen` swaps `PadPlayer` for the same `PadEngine` PLAY and KEYS share (`:app`, blind for CI's compiler): a tap has no release, so a hit is effectively one-shot regardless of the pad's own gate metadata, exactly as the SoundPool preview always played out fully; a `VoiceAllocator` still exists so a mute group still chokes on this screen and a stolen or choked voice is stopped rather than left ringing | APP | S | bench: KIT's pads sound the same as PLAY's |
| EEE5 | ✓ done: the engines under test — `app/src/main/cpp/test`, a host-built target (no NDK, no device; Oboe headers only, its two linked entry points stubbed) that drives both callbacks by hand: the ring keeps order and drops when full; the smoother glides, settles and snaps; the print buffer fills to its ceiling, refuses a re-arm while the callback may write, and lands Done on the callback; the pad engine plays the window at its gains, repitches by the ratio, reads stereo as stereo, fades a choke and an all-off, reports every ending, silences and reports every voice on a bank swap, never plays a stale command by index, waits for the retiree before a second swap, steals the oldest at the cap and says so; the Surface is silent until gated, prints the mono bus, and morphs between its corners. A third CI job (`native-tests`) runs it | CORE | S | 17 cases green on the host and in CI; every bug Copilot found in the native code now has a case that would have caught it |
| EEE6 | ✓ done: KEYS on the native engine — `KeyHit` (`:shell`, tested): the zone that covers the note, the speed from the root, the zone's loop (an empty loop plays once, the JVM engine's rule), the gain at `VOICE_LEVEL`, the release as milliseconds; `PadEngine` learns a loop (`loopStart` on the command; the voice wraps from its last frame back, a note-off is a Stop with the release as its fade; a native case proves the wrap and the release); `InstrumentPlayer` rewritten over `NativePads` (the bank read off the main thread, eight voices the oldest stolen, a route change reopened on the next key); KEYS loads it in a `LaunchedEffect` on IO. The AudioTrack thread the app owned for keys is gone; `InstrumentEngine` stays the JVM reference the map is checked against | CORE + APP | S–M | bench: a held note sustains through its loop and lets go over the release; a chord of eight; OCT ± mid-note is silence, not a stuck note |
| EEE7 | ✓ done: the bench's one number — both engines answer `latencyMillis()` (Oboe's `calculateLatencyMillis`, shared through `OboeOutput.h`, UI thread only per Oboe's own note about data callbacks; -1 with no stream, a native case); `StreamFacts.latency` (`:shell`, tested) turns it into words, counting a zero or negative reading as no answer rather than a miraculous one and naming the shared path either way; PLAY's header and SURFACE's readout poll it once a second off the frame clock — "VOICES 3/32 · 9 MS", "— MS SHARED", `NO STREAM` | CORE + APP | S | bench: the number is there to write down, and it says which path the stream took |
| EEE8 | ✓ done: the native engines read in full — four doors the review found and a case for each. A reading that is not a number is now refused at the Surface's door rather than latched for ever: the smoothers and the filter carry state across callbacks, so one NaN (a gravity sensor reporting one, and TILT is resonance in XYZ) killed the surface for the session; `clamp01` passed NaN through, since `NaN < 0` and `NaN > 1` are both false. A pad voice's speed and gains come through a door too — a zero speed froze a voice on one frame for ever and a negative one walked the read off the front of the buffer, which `render` never guarded (the suite segfaults without the fix). `PadEngine::stop` now sweeps: a closed stream takes its voices and its queued commands with it, so a route change does not resume notes mid-sample seconds later or leave the allocator holding ids. `PrintBuffer::clear` refuses a live print on the same terms `arm` has since the first round. `TiltSource` keeps its last reading rather than pass a NaN on | CORE + APP | S | 24 native cases green; each of the three engine fixes has a case that fails (or crashes) without it |
| EEE9 | ✓ done: SPLIT — one sound on three faders. `Separate.stn` has been in `:audio` since the anatomy lesson and only `snipsnap dissect` could reach it; SPLIT puts it on the phone. `Layers` (`:shell`, tested): three strips, each a level, a REVERSE, a mute and a solo; solo silences the others and mute wins over solo; and an exact offline render. Because the STN masks sum to one, **the desk is transparent at rest** — three faders at unity render the source back sample for sample, and a case says so (a 2% gain error trips two). That is also why PRINT needs no capture at all, unlike SURFACE's: the mix is determined by the three buffers and the desk, so a print is `Layers.render` — deterministic, and provably the sound itself when nothing is touched. The engine side (EEE8's successor): reverse as a direction flag, `hitLayers` publishing the group atomically, `setGain` gliding a sounding voice, `loadSnips` banking audio already in hand. The screen is reached from KIT's action row on the pad you want taken apart — a menu of twelve fits no phone, and SPLIT is something you do *to a pad* | CORE + APP | M | bench: sines backwards under a forward transient; a fader move is a glide, not a click; PRINT at rest is the pad you started with |
| EEE10 | ✓ done: the interim voice retired — GROOVE's roll and its editor taps move to the same `PadEngine` the other four screens share, with a `VoiceAllocator` beside them (a groove tick can cross several notes in one frame, and a hat should choke against its own mute group on the roll exactly as under a finger); backgrounding now stops the *sound* and not merely the transport. `PadPlayer` — M0's one-SoundPool-per-kit interim, main-thread by necessity — is deleted with its last caller, and the comments and docs that named it now name what is actually there | APP | S | bench: the roll sounds like the grid sounds like PLAY, because it is one engine; a busy bar does not outrun its voices |

## Sequence

```
CORE wave 1: ✓ all six landed (2026-08-24) — XpnImporter, KeySpec
  promotion, Kit.key, StarterKits registry, decode fixtures + contract,
  calibration harness. F2.3's harness idles until F2.4's corpus fills.

CORE wave 2: ✓ all six landed (2026-08-24) — groove capture,
  Mpc3Importer, one-note instrument, twin bank, ghost layers,
  bpm metadata

CORE wave 3: ✓ all seven landed (2026-08-24) — melodic chop,
  multisample keys, takes + the 30-day bin, one-file backup, the
  teach-the-machine data path, whole-project import, sustain loops.

CORE wave 4: ✓ all six landed (2026-08-25) — total recall, pattern
  variations, session builder, pad treatments, preview renderer,
  diff tool. Bench rows (Y2.3/Y5.3/Y6.3) and app hooks remain.

CORE wave 5: ✓ all landed (2026-08-25) — art renderer + CLI, swing,
  auto slice-count, chop-all, kit merge, MIDI bridge, and the art
  wire-through (Z6.2 verdict: waveform default, rings runner-up).
  Remaining on the bench: ✓ W12 pad waveforms landed ·
  the Live III showing the tile (rides the next card session)

CORE wave MM: ✓ all landed (2026-08-28) — the Capture Doctor. Hum
  detected before it's notched (Goertzel standout, harmonics counted),
  clicks and dropouts repaired behind three honest guards (dropouts
  first, the follow test, isolation, an absolute damage ceiling that
  refuses distortion), the noise floor measured then gently expanded,
  and the whole visit composed as `clean` — WAV twin or kit treatment
  door, --undo byte-identical, `chop --clean` (via dig) scrubbing the
  capture before the first slice. Clean audio comes back the very same
  object, every time.

CORE wave TT: ✓ all landed (2026-08-29) — the Calibration. The
  reference capture's zero-kick finding fixed with context, not
  looser thresholds: CaptureProfile measures how much sub survived
  the chain, and only a provable rolloff re-judges the PERC shelf -
  dark + punchy is a kick, dark + sustained is a note, sub-certain
  confidence that says so. The reference re-chop names ten KICKs
  where it named none; the phone-sim corpus guards it; the labeled
  on-ramp is documented for the isolated-drum captures that will turn
  reasoning into fitting.

CORE wave SS: ✓ all landed (2026-08-29) — the Hardening. The junk
  corpus meets all 44 verbs' file doors and a broken-kit corpus meets
  the kit doors (the BB-era catch-all held - now guarded, not
  assumed); the degenerate-audio matrix pins every new engine to
  finite-or-named-refusal; the clear stretch got its missing ceiling;
  and the all-legs clean visit round-trips one recipe, one undo,
  byte for byte. One real gap found and closed: the recipe recorded
  every leg but the room's.

CORE wave RR: ✓ all landed (2026-08-29) — the Restoration. Flat-top
  clip detection + A-SPADE declipping (ported from a numpy rig,
  recalibrated honestly: ~3 dB on tonal material, nothing to infer
  from clipped noise), WPE deverb as a capped opt-in (the conjugation
  lesson recorded), and the checkup scorecard turning reference/ into
  an instant verdict the day the phone-mic capture lands.

CORE wave QQ: ✓ all landed (2026-08-29) — Time, Done Right. PGHI
  (coefficients calibrated on a known-phase rig, not guessed), the
  clear stretch beside the wash, retime / --keep-pitch (the Driedger
  hybrid built and retired by measurement - PGHI alone keeps attacks
  at the original's rise), and mutate --morph conjuring the sound
  between two parents in one onset.

CORE wave PP: ✓ all landed (2026-08-29) — the Split. The median-
  filter mask engine (HPSS + fuzzy STN, masks summing to one so every
  separation proves it lost nothing), dissect's anatomy kits, split's
  song-scale twins with honest verdicts, and dig --unearth - which
  landed stronger than planned: a break the plain dig cannot hear at
  all comes out clean from under a chord that never stops.

CORE wave OO: ✓ all landed (2026-08-28) — the Sculptor: the Torso
  S-4's engine room, minus the knobs. The grain engine (seeded,
  deterministic, level-honest), sculpt's cloud/scrub/swarm texture
  kits, paulstretch + freeze (random phases, kept magnitudes, stereo
  by decorrelation), and Bjorklund euclid grooves through the standard
  groove door. Hits become matter; the performance surface stays with
  the app session. Below the line: sculpt --keys, the Seance's
  morph/smear.

CORE wave NN: ✓ all landed (2026-08-28) — the Deep Clean. The
  classifier's FFT gained its inverse and a proper STFT (Spectral);
  spectral de-noise pulls hiss from underneath the drums (fingerprint
  from the capture's own quietest frames, 3x gate margin calibrated
  against Rayleigh spikes, -12 dB cap, instant-open eased-shut gains -
  the anti-warble trifecta); the tail knee ships as de-reverb's honest
  first step (two-line envelope fit, validity gauntlet, a fade never a
  cut); all wired as clean --denoise / --deroom and chop --clean
  --denoise via dig. True de-reverb stays below the line, still
  wanting a phone-mic room capture in reference/.

CORE wave LL: ✓ all landed (2026-08-28) — mutate, the ear, the
  session. Mutate (recombination: transient-aligned stacks with an
  honest polarity check, splices, band splits; recipes regenerable,
  lineage showing two parents) with the crate roulette; the Ear
  (learn: the chopper's onsets + chop's own classifier pointed at
  time — the test caught the frame-zero blind spot on exactly the
  chopped-break shape, fixed with the hot-open guard) with --pocket
  bottling a real drummer's swing off the record; and the session
  capstones: beat (the whole ritual as one verb, every skip named)
  and album (the labeled crate released across two sides, catalog
  numbers on the tracklist). 814 tests. One process note: an LL3
  commit briefly claimed green off a stale test XML - the follow-up
  commit documents the real failure and the fix.

CORE wave KK: ✓ all landed (2026-08-28) — songs, not loops. The
  structure grammar over the kit's own variations (honest skips: no
  turn without a roll pad, tight when nothing whispers), the arrange
  verb landing numbered switchable sequences in the .xpj (flip
  01..06 in order - that's the song; same seed, byte-identical
  project), and the mixdown stitching sections with SIDE A's own
  pull-up and tape stop, the Answer's bass under the body when the
  kit has one. 801 tests. Bench row: flip the arranged sequences on
  the Live III and hear the song.

CORE wave JJ: ✓ all landed (2026-08-28) — the kit escapes the MPC.
  SFZ writer (native seq_length/seq_position round robin: chains and
  grids export FULLY, richer than the MPC 2 fallback and waiting on
  no bench), DecentSampler writer (one group per grid zone), and the
  SFZ importer (our own output round-trips - identity, layers, chains
  and grids data-equal; the test caught boundary reconstruction
  needing region ends, not just offsets; foreign files get the sfz
  courtesy, hostile ones honest refusals). 797 tests.

CORE wave II: ✓ all landed (2026-08-28) — the velocity × round-robin
  grid, decoded from a fresh PSK probe (zones loudest-first, base
  sliceIndex grading with intensity, per-zone cycles, one seed per
  pad, and Akai's own sliceInfo proven NOT a per-slice window): the
  ChainZone model with the 4-zone cap (MPC 2 parity), both writers
  speaking the grid (the .xpm as slice-window velocity switching —
  that generation's honest ceiling), the per-lane preview, and
  robin --zones rendering the graded chain from one take (quieter
  AND darker soft zones, pristine top anchor, byte-identical undo).
  791 tests. Hardware robin audibility still rides HH1.4's capture;
  bench row: a --zones 3 pad on the Live III velocity-switching and
  (post-capture) cycling.

CORE wave HH: ✓ all landed (2026-08-28) except HH1.4 (bench-blocked
  on the Sample-Edit capture) — chain plumbing with the GG4 verdict
  corrected in docs and code (round robin IS chain-based Slice
  Motion), robin (rendered takes, take one pristine first, undo
  byte-identical, every other audio door refusing chained pads),
  the break pad (chop's own cuts as a tap-through chain), the Air
  (the inverse dig, trimmed to never overlap the break), lineage
  (merge now stamps both parents; origins print only at the roots),
  pockets (.pocket files applying byte-identically to feeling from
  the donor kit), and the label (append-only catalog, numbers never
  move, the J-card spine wears them). 787 tests. Bench rows: the
  HH1.4 slice-chunk capture (chop a sample in Sample Edit, save,
  drop WAV + kit in reference/), then a robin'd pad audibly
  alternating takes on the Live III.

CORE wave GG: ✓ all landed (2026-08-27) — pad shape as metadata
  (both generations' own envelope/filter fields, goldens untouched),
  fills, song slot 1 named (GG3.2's step writer waits on a Live III
  capture with a saved 2-step song in reference/), the round-robin
  probe's honest refusal + humanize through the .xtd's real per-hit
  random fields, ghost-note grammar, the crate, liner notes. 766
  tests. Bench rows: decay 0.3 audibly shortens a pad (both
  generations), humanize 0.5 sounds sane, and the GG3.2 capture.

CORE wave FF: ✓ all landed (2026-08-27) — the Dig (breaks found
  inside full songs, no false positives on tone/noise/silence), the
  resample ritual (generation-counted, source proven untouched), the
  Band (stabs + shaker, honest refusals when the groove leaves no
  room), the mix doctor (measured findings, safe fixes; two thresholds
  were taught by the tests, see the rows), and more-like-this. 751
  tests. The doctor's carve and outlier bar carry the wave's honest
  findings.

CORE wave EE: ✓ all landed (2026-08-27) — the Time Machine eras,
  tape wear (the ledger, the capped patina chain, the render-time
  wire-through), the Answer (counter-pattern bassline in the key, in
  the gaps, with the feel), SIDE A (the beat tape with tape-stop and
  pull-up transitions), and the J-card. 737 tests. Bench rows for the
  Live III: a keys clip playing from a sequence (EE3), a worn export
  A/B'd against a pristine one (EE1), an era'd kit (EE2).

CORE wave DD: ✓ all landed (2026-08-26) — robust .xpm DOM parse
  (fixed a real silent pad-drop + numeric entities), JSON parser
  property+fuzz (found it refuses duplicate keys - stricter than
  assumed), DSP invariant properties, WAV chunk-variety lock. 704
  tests. DD1 was a real bug; the rest are property/robustness locks.

CORE wave CC: ✓ all landed (2026-08-26) — NaN/Inf sanitization
  (which also found peak() poisoned by Inf), tempo overflow guards,
  golden snapshots, round-trip property fuzz, temp-leak lock, and the
  preflight-implies-export invariant. 695 tests. CC1/CC2 were real
  bugs; CC3-CC6 are regression locks that hold the line.

CORE wave BB: ✓ all landed (2026-08-25) — SafePath traversal
  hardening, LimitedRead resource ceilings, the mutation-fuzz harness
  (which caught a real MidiGroove crash), atomic saves, the CLI
  catch-all + hostile sweep, and import metadata validation. 686 tests.
  Findings were honest: BB1 and BB6 were already defended (basename
  flattening; the Kit constructor's invariants) - the wave made those
  defenses explicit, central, and regression-locked; BB2/BB3 closed
  real DoS and crash gaps.

CORE wave 6: ✓ all landed (2026-08-25) — multi-sequence projects
  (probe first; the four variations arrive as switchable sequences),
  groove transfer, key guess, tempo-fit, session mixdown, pack
  builder + whole-pack import. Bench row open: AA1.3 sequence flip
  on the Live III (rides the next card session).

APP (reconciled against the app 2026-09-07 — the milestones landed
  without their rows being ticked; this is the honest remainder):
  ✓ M0 (F1.1) · ✓ F4.2 new-kit menu · ✓ W3.3 open-.xtd (through the share door)
  ✓ M1 (F1.2): the mic ring, ARM INSIDE over MediaProjection, dead-air
    detection, the QS tile · ✓ F3.4 TAPE JAM on a blocked source
    · ✓ F3.1/F3.2 import (the share door, the on-phone decode)
  ✓ M2 (F1.3)
  ✓ M3 (F2.1) · ✓ F5.3 key picker · ✓ W2.3 MAKE INSTRUMENT · ✓ W5.3 GHOSTS
    · ✓ X1.3 MELODIC · ✓ X2.3 TAKES + BIN · ✓ KEYS (the phone plays the
    instruments it makes) · ✓ W4.3 EVIL TWINS button · ✓ X4.4 consent row
    · ✓ F2.2 INSTANT KIT
  ✓ M4 (F1.4, SoundPool not Oboe)
  ✓ M5 (F1.5 + F4.3) · ✓ Y3.3 SESSION export · ✓ F6.3 share flow · ✓ X3.3
    share/backup action
  ✓ W12 pad waveforms on the KIT grid
  ✓ wave ZZ (the phone reads): READ AS GROOVE, DIG, STEAL THE FEEL on TAPE
  ✓ wave AAA1 (the sample carries its own sheet): smpl root + loop in
    every instrument zone WAV · bench: AAA2 standalone load on the Live III
  ✓ wave BBB (the Hardening, round two): the new doors under the
    degenerate matrix, the sheet under fuzz, four more verbs in the sweep
  ✓ BBB4 (the Hardening, round three): every JSON sidecar under torn
    bytes and rewritten trees; the groove reader's casts made typed
  ✓ BBB5 (the Hardening, round four): the containers damaged inside the
    wrapper (xpm in xpn, payload in ACVS, sfz, backup zip, answer, WAV
    header, name parsers, pad sheets, label); the answer reader made typed
  ✓ wave CCC (the Surface): the tactile pad over Oboe — XY / XYZ / MORPH,
    the roll of the phone, PRINT to resample the gesture onto TAPE; then
    the shared fallback, SET A..D corners in surface.json, PAD ◄ ►,
    PRINT → PAD through SYNTH's door, LATCH and BARS · bench: dropouts,
    the route change, the print heard back, a print on a pad, a bar-locked
    print on the grid
  ✓ wave EEE (M4, the pads on the native engine): PadHit on the JVM,
    PadEngine under Oboe, PLAY over it with endings reported, not timed ·
    bench 2026-09-08: ✓ answered in full on hardware — tight enough to
    play, 8–11 ms exclusive, endings reported, choke and gate right;
    EEE4 moves KIT's grid across too
  ✓ EEE5 (the engines under test): both native callbacks driven by hand
    on the host, 17 cases, a third CI job
  ✓ EEE6 (KEYS on the native engine): KeyHit on the JVM, a looping
    voice in PadEngine, InstrumentPlayer over NativePads; the last audio
    thread the app owned retired
  ✓ EEE7 (the bench's one number): the device's own latency on PLAY and
    SURFACE, so "does it feel tight" has a figure beside it
  ✓ EEE8 (the engines read in full): doors on the Surface's control
    values and on a pad voice's speed and gains, a closed stream that
    takes its voices with it, a print that cannot be freed under the
    callback — 24 native cases
  ✓ EEE9 (SPLIT): the anatomy lesson on three faders — Layers on the
    JVM, reverse and atomic group starts and gliding faders in the
    engine, and a print that is a render rather than a recording
  ✓ EEE10 (the interim voice retired): GROOVE onto PadEngine, and
    PadPlayer deleted — every screen that makes a sound is native now

CORE+APP wave UU: ✓ all landed (2026-09-06) — sound design, both
  directions. The smear (STN transient mask, peak-matched) as a rack
  section and the `smeared` character; the pad sheet's second
  TREATMENT row over the new whole-pad `characterPad` door; the pad
  shape's one reading (PadShape) shared by preview, SFZ and the phone,
  and a SHAPE card whose HIT honestly auditions it; then MUTATE on the
  pad sheet — the four moves, a partner off the grid or the crate's
  deal, one knob, undo; then SCULPT and STRETCH on the KIT screen —
  one door (TextureKits) for the CLI verb and the phone's TEXTURE
  panel, a pad becoming a tape of its own. Below the line: other-kit
  parents on the phone, the clear stretch.

CORE+APP wave VV: ✓ all landed (2026-09-07) — the room and the tape.
  ROOM OF ITSELF as the fifth MUTATE move (FFT convolution, a WET
  knob, the crate can deal the room); GHOST, MOTION (tape stop /
  start), DUB (generation loss) and SWELL as rack sections and
  characters; the smear's FLOOR; the pad sheet's third and fourth
  rows.

CORE+APP wave WW: ✓ all landed (2026-09-07) — in key. WW1 landed:
  PAD FROM ANYTHING, one hit held forever in every note, the clear
  stretch keeping a note's line and the wash carrying a drum as a
  drone, the seam baked, both generations, the pad sheet's card and
  the `pad` verb. WW2 landed: SPECTRAL RETUNE, every partial talked
  into the kit's key through the spectral door with PGHI phases, the
  `retune` verb and the pad sheet's TUNE segment. WW3 landed:
  TRANSPLANT, the pad's attack wearing the parent's long-term tone as
  the sixth mutate move, BANDS its resolution. WW4 landed: BODY, a
  bank of resonators tuned to the key and struck by the hit, the
  keyed family's second member and the pad sheet's fifth row. WW5
  landed: WOBBLE, a filter sweep synced to a note division at the
  kit's tempo, the family's third. WW6 landed: ATTACK KEPT, TAIL
  ETERNAL, the first 30 ms bit for bit and the tail slowed toward a
  frozen instant. Wave WW complete.

CORE+APP wave XX: ✓ all landed (2026-09-07) — the crate as an
  instrument. XX1 landed: DRIFT TOWARD THE CRATE, roulette then morph
  as one verb and one button. XX2 landed: BREEDING, two kits' recipes
  crossed by a seeded coin into an audited child kit. XX3 landed:
  DE-SAMPLE, the nearest THUMP patch to a capture off a pre-rendered
  grid, the distance always told. Wave XX complete.

APP F6.3 / X3.3 / W3.3 (2026-09-07): the kit leaves and comes back,
  blind for CI - SHARE packs the open kit as one .xpn and BACKUP the
  whole shelf, both out the chooser through a FileProvider; the share
  door reads kit files by their bytes and `ShelfImport` (tested) lands
  .xpn packs, backups and zipped MPC tracks on the shelf under names
  nothing there holds. Every APP row in the plan is now ticked; what
  remains is the bench.

APP F3 (2026-09-07): share-sheet import landed blind — F3.1's intent
  filters, `ShareInbox` and the `singleTask` door, `SnipStore.import`
  (tested) as the landing, TAPE's reload request; F3.2's `MediaDecode`
  over `MediaExtractor`/`MediaCodec`. The desktop session runs it and
  the decode-contract twins.

CORE+APP wave YY: CORE landed (2026-09-07) — outside, the Outsidify
  idea. YY1: the trip measured, `Outside.align` and `reamp` — the
  return found by cross-correlation wherever the audio stack put it,
  polarity restored, the tail kept while it sounds against the room's
  own floor. YY2: the room as a room, a sweep out and Farina's inverse
  back, the impulse response ROOM OF ITSELF wants. YY3: the OUTSIDE
  card as data, REAMP and ROOM through the same bin-backed doors as
  every treatment; Mutate's hot-open parent bug found and fixed on the
  way. YY4 written blind for the desktop session: `OutsideSession` and
  the pad sheet's OUTSIDE card. YY5 (2026-09-07): the room kept —
  `Rooms` on the shelf, a ROOM trip's impulse as a reusable MUTATE
  parent for any pad on any kit; KEEP ROOM on the OUTSIDE card and the
  rooms as chips on the MUTATE card, blind for CI.

CORE+APP wave DDD: ✓ all landed (2026-09-07) — the pad sheet folds.
  Direction A off the design canvas: five group boxes with pixel legends
  under the everyday controls, closed strips reading what the pad
  carries (`PadSheetBoxes`, tested), one open at a time and remembered
  per kit, the pad nav pinned, the trip's reels turning on an LCD strip;
  and a forgotten room goes to the bin for 30 days like every delete,
  from a ROOMS section on the shelf - and comes back from it: IN THE BIN
  rows with the days left and RESTORE (DDD4). The canvas's boards live
  under design/pad-sheet-v2/ and DESIGN_GAP.md says what the old handoff
  no longer covers. And the crate gains intent: a pad picked on another
  kit of the shelf is a MUTATE partner, ANOTHER KIT chips under the ROOMS
  row (DDD5); then the last parent kind the CLI verb takes and the phone
  lacked, a file off the phone through the system picker, held as a WAV
  and labelled with its own name (DDD6). Blind for CI's compiler.

CORE+APP wave FFF: ✓ all landed (2026-09-07) — the landing reads back.
  The share door's trouble no longer dies in a toast: a kit file that
  landed with skips, a backup preflight refused part of, and every import
  refusal open the language's honest little message box (`LandingNote`,
  tested; `MessageBox`, blind) - skipped first, in the warn colour, with
  the door's own reason, [FINE] to close. A clean landing keeps its toast.

APP wave GGG: ✓ all landed (2026-09-08) — the trip catches up to its own
  drawing. The two gaps the OUTSIDE trip boards found: the measured line
  now reads on an LCD strip like every other readout, and KEEP ROOM
  lights with an amber rim the instant a trip has something to keep,
  `ActionButton`'s new `lit` state (blind for CI's compiler).

APP wave HHH: ✓ all landed (2026-09-08) — GRAIN FIELD and SURFACE, drawn.
  Both arrived from another branch with no board ever. GRAIN FIELD held up
  as built; SURFACE's readout didn't - MORPH's longest case clipped
  mid-digit at 390, so `TapeText` now allows it a second line.

APP wave III: ✓ all landed (2026-09-08) — the SHARE/BACKUP chooser
  hand-off, drawn. DESIGN_GAP.md's last "still undrawn" line: SHARE
  packing (every door dimmed, PACKING… in the status bar), the system
  chooser itself (schematic - SnipSnap owns nothing past
  `Intent.createChooser`), the clean return and the no-receiver return;
  BACKUP's empty-shelf gate (dimmed before the busy lock, no chooser
  ever offered) and a partial backup's message box. No code defect:
  `shareKit`/`backupShelf` already route every branch through the app's
  two existing answer shapes.

APP wave JJJ: ✓ all landed (2026-09-08) — SPLIT, drawn. EEE9 arrived
  from another branch with no board ever, same as GRAIN FIELD and
  SURFACE before it: not split (SPLIT lit amber, the desk disabled at
  rest), working (the spectrogram pass), the desk playing (a fader
  reversed, another muted, STOP lit), and a hot print's toast on the
  →TAPE path. Held up as built - the lit/dimmed/enabled vocabulary and
  the fader's own unity tick and knob already match the code exactly.

APP wave KKK: ✓ all landed (2026-09-08) — KEYS, drawn. Grown alongside
  INSTRUMENTS on the shelf with no board ever, same pattern as GRAIN
  FIELD, SURFACE and SPLIT: CHROMATIC idle (root bottom-left, ascending
  left-to-right and bottom-to-top; an unmapped key dims to ink3 rather
  than vanishing), a key held (only the box's fill jumps, the label's
  ink colour untouched), MAJOR picked (the whole grid relabels to the
  scale, not merely redecorated). Held up as built - no code fix. Three
  screens found in the same archaeology pass remain undrawn:
  `PadCaptureScreen`, `SnipsScreen`, `DeletedKitsScreen`.

USER (one card session, value order — ideally before M5):
  Session .xpj → native keys + instruments → MPC 2 keys →
  F6.1 .xpn import + expansion tile → velocity/bank B →
  the Live III drum-program save (now double-duty: corpus + W3.4's
  round-trip fixture) → W1.3 groove-clip feel · W2.4 one-note tuning
  … and F2.4's dozen labeled captures whenever the phone can capture
```

Dependency truths: nothing in the CORE column blocks anything; M0 gates
every APP feature item; F6.1 gates only the *claim* that `.xpn` loads on
hardware, not the share loop; the product ships when M5's exit test passes.
