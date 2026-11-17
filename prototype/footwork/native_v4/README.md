# FOOTWORK native model 4: Tap contact and movement port

Model 4 ports the reference-guided contact and latest smooth-pickup study into
Kotlin. Kit version 4 uses it for Tap Jazz heel, toe, forward/back brush, shuffle,
flap, roll and riser. The other ten Tap roles and both other kits retain their
model-3 PCM. Models 1–3 remain explicitly renderable.

## Why it is built this way

- `FootworkTapData.kt` contains only the coarse spectra, contact-envelope
  parameters, filter coefficients and authored event plans. Generate it with
  `python3 prototype/footwork/tap_connected/native_data.py`. No reference PCM or
  recorded impulse response is stored in the renderer or repository.
- `FootworkTap.kt` generates the minimum-phase response with the existing audio
  FFT, then convolves fresh `Dsp.Noise` excitation. It keeps the spectral body
  connected instead of adding independent low-frequency impact layers.
- The house four-times band-limit/decimation chain replaces the Python filter
  approximation. Kotlin's seeded noise also differs. Waveform identity with the
  Python study is neither expected nor claimed; compare the published auditions.
- The brush now changes duration and response frequency slightly with pressure,
  in addition to level. The existing native proof caught amplitude-only brush
  layers. This addition preserves the hard-velocity settings; soft native brush
  shapes therefore differ slightly from the Python study.
- A bounded 64-entry private cache holds immutable contact PCM, keyed by role,
  velocity, take and travel. Returned single contacts are copied; mixing never
  modifies cache entries. The cache uses at most roughly 3 MiB. Full build/proof
  timings are recorded by the kit generator; no device render-time claim is made.

## Validation and remaining gates

Six focused synth tests pass, including cache eviction/caller mutation checks,
zero velocity, explicit older models, and all 552 untouched role/velocity/take
cells matching model 3. The shell proof passes all 648 cells: source dynamics and
repeat distinction, exact saved kit reload, playback across all 127 MIDI values
and repeat wraps, and SFZ import with byte-identical packed PCM.

MPC 3 export still lacks the chain-WAV slice map documented at HH1.4. Its PCM and
cycle fields are checked, but this **does not prove working hardware round robin**.
That gate remains blocked. No new MPC 3 download is represented as complete.

The native audition's pad and touch clips are resolved from saved playback
windows. `phrase-source.wav` is separately labeled: it exercises the native
multi-contact source scheduler, not a saved sequencer arrangement. The saved
roll/riser pads do exercise their complete native delivery path.

The native port still needs the owner's ear and device acceptance. Other Tap
body/object sources, Street/Industrial source identity, distinct-kit identity
and the outstanding articulation approvals remain open. Earlier favorable
phrase feedback is not approval of those complete kits.

## Reproduce

```sh
./gradlew --no-daemon :synth:test --tests com.snipsnap.synth.FootworkTest \
  :shell:test --tests com.snipsnap.shell.FootworkKitTest \
  :shell:generateFootworkKits -PfootworkOutput=/new/empty/native-v4
python3 prototype/footwork/native_v4/parity.py /new/empty/native-v4 \
  /path/to/contact-fit /path/to/smooth-pickups /tmp/parity.json
```

The parity report describes 72 native/Python cells across heel, toe, shuffle,
flap, roll and build. Its level and spectral distances are descriptive, not
thresholds that certify realism. Generated coefficient data and source code are
versioned alongside this ledger; private uploaded recordings are not required.
