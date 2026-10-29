# Aquarium PERFORMANCE controls and kit storage

Read against SnipSnap `df675d004` on 2026-10-28. Extends the merged
[PadEngine backend](2026-10-26-aquarium-padengine-performance-design.md).
The accepted six-effect DSP, parameter registry, presets and render model are unchanged.

The characteristic gesture is play the kit → bring its shared Aquarium environment
in → adjust the sound while notes and tails continue → save that environment with the kit.

## As built

KIT has a collapsed PERFORMANCE group above THIS KIT, inside the existing scroll area.
The grid remains available while the editor is open. Its qualified scope is the shared
PadHost: KIT/PADS, beat/GROOVE and their native PRINT/BOUNCE, including banked count-in
clicks. Other engines/players retain their separate routing and are named in the panel.

LIVE ON/BYPASSED is independent of preset selection. Presets replace only the 52 sound
controls, preserving seed, gain and global enable. OUTPUT accepts linear 0..1 including
intentional wet mute, with a dB readout. With every effect mix zero the native exact-dry
path ignores wet output gain; the panel explicitly says DRY in that state. RESET TAIL
publishes the current settings with a sticky native reset, also allowing fault recovery.

The eleven accepted presets and complete Depth/Motion/Color surface share
`AquariumSoundControls` with PAD. Session-only per-effect mix memory survives bypass
and restore. The UI uses AquariumEditor for sound edits; no offline render context is
serialized into the live settings. The seed is displayed and preserved. Tempo comes from
the current kit, or zero/free time. Sound edits retain the backend's wet fade-down/adopt/
fade-up policy, not continuous filter automation. The panel discloses that individual
zero-mix stages retain paused history.

Changes audition on this host immediately. SAVE TO KIT is explicit. REVERT, collapsing
the group or leaving KIT returns to the host's current saved snapshot. A panel disposed
while save IO is in flight first reverts; a completed save subsequently updates any
remaining shared host. The save belongs to its original directory. Saving/failure status
is visible; controls cannot mutate the submitted snapshot during a save. Failure leaves
the draft available for retry. Backgrounding still uses the existing all-off behavior.

`PadHost` restores on its first request and whenever the saved JSON changes. Repeated
requests carrying unchanged saved metadata preserve a live draft. It publishes tempo and
settings separately from bank loading. Equal pad lists in the same folder skip bank
replacement, even when metadata has changed; replacing a bank would otherwise stop voices
and clear tails. The app and standalone GROOVE clock keys likewise depend on pads/tempo
rather than the whole Kit value, so live metadata saves cannot abandon recording/bounce.

The host polls native faults, queued control requests and protected-frame diagnostics
with its existing latency polling. Queued means waiting for publication, not confirmation
that the audio thread has adopted a snapshot. Diagnostics count reduced frames since the current stream preparation and reset
on stream restart; physical-device timing is not inferred from that count.

## Persistence and compatibility

`Kit.aquariumPerformance` is optional opaque JSON in `:kit`; `:synth` already depends on
`:kit`, so the kit codec cannot depend on AquariumPerformanceSettings. `:shell`'s
AquariumPerformance adapter performs strict typed decoding and explicit replacement.
The existing live codec pins complete controls, UInt32 seed, render model, enable, output
gain, transition policy and protection policy. It contains no source hashes, tail/fade
duration or stored BPM. The existing kit format version remains readable.

Missing metadata opens dry and does not write. Invalid or newer metadata also opens dry
with a warning, remains intact through ordinary pad/tempo edits, and is replaced only on
an explicit PERFORMANCE save. A damaged live setting must not hide an otherwise readable
kit from the shelf. SAVE reopens the latest KitBuilderModel under KitWrites.mutex,
updates only this field, and uses the usual atomic kit save/take archive. It does not
write samples or invent a source-backed PAD recipe. A removed directory is not recreated.

MPC kit/program exporters do not interpret this insert or bake the combined mix into
each pad. SnipSnap kit sidecars retain the optional live metadata where a sidecar is
supported; the MPC program remains unaffected. Native PRINT/BOUNCE captures live output.
Finite PAD Aquarium printing and its exported baked audio remain separate operations.

## Evidence ledger

- **Verified/read:** route owners and actual APIs at the base commit above, KitStore's
  optional-field codec, KitBuilderModel's atomic save/takes, KitWrites locking, PadHost's
  bank debounce and BeatPlayer's cancellation/print lifecycle. No DSP law changes.
- **Checks supplied:** JVM persistence/reopen and old-kit dry behavior; unsigned seed,
  mute/bypass, recent pad/tempo preservation, unchanged WAV bytes and archived settings;
  invalid/new metadata preservation and explicit replacement; removed-directory refusal.
  Compose checks cover live controls, seed/gain/bypass preservation, save/revert/reset,
  disabled controls during save and failure retry. A real Oboe/JNI host test restores
  saved wet mute, saves metadata during an already-sounding note and prints that note
  after the bank debounce window, then verifies revert. Current execution results are
  recorded in the PR validation report.
- **Unmeasured:** physical phone callback deadlines, power, device-loss behavior and MPC
  hardware playback. Those remain acceptance work, together with other mixer routes.
- **Heard:** no new listening verdict. The owner's accepted six-effect audition is the
  existing DSP evidence; this stage supplies controls and storage.

## Why it is built this way

- One live host lets fingers and the beat inhabit the same effect history; a preset does
  not silently change the global bypass or chosen level.
- One shared sound-control surface keeps the 52 parameter laws and physical readouts
  consistent with PAD without mixing finite print settings into a running bus.
- Explicit save/revert supports audition while preserving a clear saved kit state.
- Separating metadata from the bank and transport clock keeps a live save from cutting
  a note, losing a tail or abandoning a bounce.
- Opaque optional storage preserves future settings without introducing a circular module
  dependency or silently erasing data the current decoder cannot use.
