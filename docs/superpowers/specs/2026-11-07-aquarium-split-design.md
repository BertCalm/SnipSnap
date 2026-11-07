# Aquarium on SPLIT

**Read against:** `22a6b40055192504f91a5a58f78e0ab01b53871e`

**Scope:** SPLIT's independent three-stem player and finite PRINT. The accepted
six-stage DSP, parameter registry, presets and model version stay unchanged.

The characteristic gesture is split a pad → reverse, solo or balance its stems
→ opt into the saved KIT environment → PRINT a fresh pass with a chosen tail.

## Routing and ownership

SPLIT owns a `PadEngine`, separate from the shared KIT host. Its three original
stem buffers still start together with `hitLayers`; desk faders still glide on
the sounding voices. The existing native sum and +/-1 clamp precede Aquarium;
its stereo output precedes the unchanged native PRINT tap. KIT's saved
`AquariumPerformanceSettings` supplies the full sound, unsigned seed, global
bypass and output gain. KIT supplies tempo. One environment follows the sum,
letting the stems interact in that environment.

**FX → USE KIT FX** explicitly opts this desk into that sound. The modal panel
supplies RESET TAIL, a 0..30-second print tail (default 3), queue/fault/stream
status and cumulative protected frames. Sound editing remains in KIT →
PERFORMANCE. Route OFF restores a dry bus. Stop, source replacement, focus loss
and background release use the existing panic policy. Foreground playback waits
for PLAY, which can retry a missing/disconnected stream. The screen pumps
retained controls and stops through `drainEnded` independently of the KIT host.

The player, stems, split/render jobs and pending pad chooser are keyed to the kit
directory. Kit switching disposes the outgoing owner; canceled work cannot
deliver old audio into the new desk. Synchronous separation can finish computing
after cancellation, but its canceled coroutine drops delivery.

## Saved choices

`split.json` version 1 stores only `aquariumFromKit` and `tailSeconds`. It does not
copy KIT FX values, save the temporary desk or rewrite kit metadata/source WAVs.
A missing file stays dry and creates no file. Unsupported or damaged settings
are preserved and reported, with route editing refused for that screen session.
Unreadable KIT sound metadata uses the existing dry fallback and visible warning.

One kit-scoped `SplitSettingsWrites` stages the latest accepted choice. Its
app-scope write holds `KitWrites.mutex` and survives navigation or a kit switch,
targeting the original directory. Queued saves persist the latest staged choice,
avoiding reversed completion of older snapshots. The writer refuses a removed
directory instead of recreating a deleted kit. A failed save reports failure;
it does not imply that the in-memory choice was persisted.

## Finite PRINT

PRINT freezes the stems, desk, route, sound and tempo at the tap. It renders a
fresh pass rather than recording a running loop's effect history.

1. Preflight source plus tail against the shelf's three-minute limit after
   conversion to 44.1 kHz. Refuse excess instead of silently trimming it. A
   patched print must also fit `NativeAquarium.maximumOutputFrames()` before
   mixed/output allocation. This bound does not guarantee it fits a phone heap.
2. Sum gains, solos, mutes and reversals with `Layers.render`. An unpatched print
   returns this exact legacy result, including channel count and floating overs.
3. For the patched route, duplicate mono or retain stereo and clamp the combined
   input to +/-1, matching the player's post-sum boundary. Nonfinite mixed samples
   become zero; corrupt source parity is not promised.
4. Prepare a fresh accepted `PerformanceBus` in the selected settings at the
   source rate. Process reusable 256-frame stereo blocks. Input becomes exact
   zero at the source endpoint; keep only the final block's valid prefix.
5. Add `floor(tailSeconds * sampleRate)` zero-input frames only when the opted-in
   route is globally enabled with a nonzero effect mix. Wet mute still clocks
   this tail. Global bypass/all-zero mixes add none.
6. Refuse faults, propagate cancellation and release the handle on the render
   owner. Save through the normal pad path or
   `SnipStore.import(preserveStereo = true)`. Keep the busy guard through the
   awaited shelf write so a second PRINT cannot race that landing.

`AquariumPerformanceProcessor` is the shared worker adapter extracted from
`OrbitAquariumProcessor`. ORBIT retains its previous dry-prepare then configure
behavior. SPLIT supplies an initial tempo to `NativeAquariumBus.createConfigured`:
validate the full settings, publish them **before** preparation, and start the
selected wet branch with the accepted fade from silence. Preparing dry and then
enabling would crossfade an untrimmed dry attack into a saved wet mute. A JNI case
requires silence from the first sample at saved wet gain zero.

Wet output retains fixed gain and stereo-linked 0.99 sample-peak protection with
50 ms release. There is no new normalization or end fade. Global bypass retains
the clamped dry signal. Later resampling can change sample peaks; WAV encoding
uses its normal PCM clamp.

Live playback uses the actual stream rate and native voice interpolation. PRINT
uses the source rate; storage then converts to 44.1 kHz. Tests compare fresh bus
behavior at a matching rate, without claiming sample-identical phone playback
and saved WAVs across different rates or accumulated loop history. A finite tail
can cut a decay; a wet print can have a loop seam. SNIPS and PAD keep stereo;
TAPE's editor reads mono. Baked audio receives FX again through enabled KIT FX.

## Verification ledger

- **Verified in the repository:** independent SPLIT engine, exact legacy
  `Layers.render`, grouped playback, post-sum clamp, saved KIT sound/tempo,
  stereo shelf import and kit-writing protocol. APIs were checked against the
  stated commit and this implementation.
- **Checks supplied:** shell routing/settings/tail/storage laws; native grouped
  stems versus fresh bus and PRINT with irregular block sizes; strict configured
  JNI preparation and allocation-free processing; real JNI renderer checks for
  stereo, tail/endpoint, wet mute, dry/bypass and cancellation; Compose checks for
  saved routing, stereo landing, kit switching with an accepted delayed save,
  and background release. Existing ORBIT cases guard the shared adapter.
  Execution results belong in the PR.
- **Not measured:** phone callback deadlines, recovery under device load, phone
  peak memory, physical audio interruptions or MPC playback.
- **Not heard:** attack feel, useful decay choices and seams need Josh's listening
  judgment. Held instruments and other players remain separate work.

## Why it is built this way

- One bus after the sum lets all stems inhabit the same space. Per-stem copies
  would change their interaction and multiply processing cost.
- Explicit opt-in preserves existing desks. KIT stays the sole sound owner.
- Fresh finite PRINT is reproducible from frozen inputs; arbitrary loop history
  would need a different recording contract.
- Preparing the chosen sound first protects the first attack and a saved mute
  without changing processor laws.
- The chosen tail trades possible cut decay and seams for predictable duration
  and storage cost.
