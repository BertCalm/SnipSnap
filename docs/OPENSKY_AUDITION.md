# OPENSKY audition spike

OPENSKY adapts the [XOpenSky concept brief](https://github.com/BertCalm/XO_OX-XOmnibus/blob/main/Docs%2Fconcepts%2Fxopensky_concept_brief.md)
into SnipSnap's offline sample-authoring model. The accepted Round 3 voices
now have production recipes, a phone engine-picker entry, a factory starter
kit and a HALO held-instrument path. The audition page remains the listening
record for the original finite renders.

ANTHEM, SOAR and HALO use individually phased stereo unison voices; CRYSTAL
uses a harmonic bell source. RISE controls ascent and octave-shimmer send,
WIDTH controls detune and pan spread, GLOW controls room persistence, and AIR
controls upper-frequency openness. The note envelope precedes the wet path.
The room returns an octave-shifted part of its own output into bounded delay
feedback. All sound is rendered by the Kotlin engine, not synthesized in the
audition browser.

## Round 2: preset contrasts

The first listening round distinguished the voices, but the two recipes in each
voice mostly moved width and room controls over the same source. The owner's
verdict was that the presets sounded alike. R2 retains the four voice bases and
adds saved source and articulation settings: pulse/sine/octave blends, unison
balance, tone, attack/sustain/release, and bell partial strength and decay.
The dry voice bases remain unchanged as the room is revised in R3.

| Voice | First recipe | Second recipe |
| --- | --- | --- |
| ANTHEM | First Light: tight pulse brass | Full Column: wide saw/octave anthem |
| SOAR | Blue Hour: rounded sine-supported lead | Solar Arc: cutting saw/pulse lead |
| HALO | Soft Horizon: dark, slowly opening pad | Open Sky: bright layered pad |
| CRYSTAL | Sun Fleck: compact fundamental-led ping | Skylight: upper-partial ringing bell |

The Preset A → B clips use C4, velocity 1 and a 1.5-second gate for both
recipes, with a 650 ms silence gap. One gain applies across both segments.
Differences are authored before the shimmer, rather than made by changing
pitch or normalizing each sound separately. Both presets' dry/wet files in a
voice also share one gain. The manifest saves complete timbre settings.

The page identifies each listening round and versions its audio URLs by file
hash. Earlier listening notes remain available for export; each new round uses
a fresh note bank.

## Round 3: room and shimmer

The owner's next verdict was that the room and shimmer contributed little.
The R2 effect comparison used Soft Horizon's slow attack and long source
release, with RISE at 25% and AIR at 15%. That made it difficult to separate
the source from the room or the octave return. R3 retains the eight dry preset
recipes and revises the wet stage and its listening comparisons.

The dedicated effects study uses the same short excitation for dry, ordinary
room and room with shimmer. The source ends early so the remaining sound
belongs to the effect. Comparisons share one attenuation-only gain; isolated
returns keep that gain so their actual contribution remains visible and audible.

The wet model now uses eight cross-diffused hall delays, input diffusion and
predelay. GLOW maps to a nominal 0.6–7.5-second decay, with conservative
feedback limits; the octave output has a stronger return outside that loop.
AIR controls brightness without reducing the overall room return. Finite
renders retain up to 12 seconds after the source release, with a short final
fade. The pack's audio budget is 128 MiB to preserve these tails.

Room / shimmer is the page's first comparison. It marks the actual source end
and offers **Hear tail** to play from that point. GLOW tails varies only GLOW
over the same brief strike, with shimmer disabled.

## Reproduce

```sh
./gradlew :synth:test --tests com.snipsnap.synth.OpenSkyTest
./gradlew :synth:generateOpenSkyAudition
```

## SnipSnap integration

The production bank is `OpenSkyPresets`: the same eight source recipes and
four controls as the accepted Round 3 audition. The audition generator reads
this bank directly. A patch stores its renderer model, MIDI note, excitation
velocity, gate duration, wet mode and all thirteen timbre settings, together
with a complete four-control snapshot. JSON loading rejects missing or
malformed state. Macro edits, user-preset saves, pad recipes and rebuilds
retain the rest of that source definition.

SYNTH exposes OPENSKY / ANTHEM, SOAR, HALO and CRYSTAL, with NOTE, VELOCITY,
GATE and DRY/WET beside RISE, WIDTH, GLOW and AIR. Its audition preserves
stereo, so WIDTH and the room can be assessed while editing. FRESH TAPE's
OPENSKY starter has eight native-note Solar Arc pads in C minor pentatonic,
followed by the eight complete recipes. Every pad remains regenerable.

Production patch and key rendering applies one uniform attenuation only when
the output peak would exceed 0.8. Valid high-control wet sums can exceed full
scale, even though the accepted recipes do not. This ceiling prevents clipping
without changing the eight accepted renders; it adds no gain or time-varying
compression. The raw engine probes retain their separate instability checks.

CLI examples:

```sh
./gradlew :cli:run --args='synth OPENSKY SOAR --preset 2 --midi 60 --velocity 0.7 --gate 0.4 --dry --out testkit/OpenSky-Lead'
./gradlew :cli:run --args='synth OPENSKY HALO --preset 2 --instrument --release 1.2 --out testkit/OpenSky-Keys'
./gradlew :cli:run --args='synth OPENSKY HALO --patch my-opensky.json --midi 60 --wet --out testkit/OpenSky-Saved'
```

`--patch` reads a complete saved patch file and cannot be combined with
`--preset` or `--all`. Note and velocity overrides preserve its timbre;
`--gate`, `--dry` and `--wet` are OPENSKY authoring options. A held instrument
rejects a finite gate or a separate attack override: its onset belongs to the
saved source. The four voices render finite notes from MIDI 0–127; useful
musical registers still require listening.

HALO MAKE INSTRUMENT renders nine minor-third zones around its saved root,
from one octave below to one octave above. Supported roots are MIDI 36–96,
giving native samples in 24–108. Each stereo sample begins with the source's
real attack and a fresh room, then reaches a measured repeating sustain
period. Samples exceeding the 30-second convergence budget are refused.
Phone rendering is sequential to limit temporary heap use. The complete
package contains stereo WAVs with root/loop sampler metadata, an MPC 2 XPM,
an MPC 3 XTY and the phone instrument sidecar.

RELEASE (0.1–1.5 seconds) is the sample player's envelope after note-off. It
fades the whole wet sample; there is no separate source-off room-tail sample.
The finite authoring render retains its full source release and room tail.
Physical phone listening, render-memory behavior and hardware MPC loading
remain device acceptance checks; a JVM test alone does not compile Android UI.

The generator writes `testkit/opensky-audition/manifest.json` and PCM16,
44.1 kHz WAVs under its `audio/` directory. The page template is a test resource
so the generated folder can also be served as a standalone listening page.

Eight recipes provide two starting points per voice. The matrix includes
dry/wet pairs, same-note preset pairs, low/high registers, a voiced C-major chord, a rising phrase,
arithmetic mono folds, three steps of each macro and a wet HALO sustain study.
Each comparison family uses one attenuation-only gain with sample-peak
headroom; individual clips are not loudness-normalized. Dry clips are padded
with silence to match their wet partners. Mono folds retain the stereo gain.
The manifest records recipes, source hashes, WAV hashes and measured levels.

## Held sound and acceptance

The held renderer fits oscillator cycles and the pitch-shifter clock to one
period, then runs the wet state until independently synthesized output periods
converge. The audition contains one period and four exact repeats without
crossfades. It exposes musical repetition as well as the boundary. A one-frame
end-to-start sample difference describes waveform slope; independent-cycle
residuals provide the separate recurrence evidence.

The original audition's held clips are loop-only studies. The integrated
HALO key path now supplies attack-plus-loop packaging and a program release,
but no separate release sample, velocity-zone set or live macro control.
Pitching a baked sample would
also change its shimmer timing; the native-note register renders do not test
that behavior. Phone render time, MPC loading and sustained keygroup playback
remain device checks. The original concept's XOmnibus coupling is outside the
spike.

Numerical checks establish deterministic output, bounded levels and recurrence.
The owner's listening verdict determines whether the voices, width and shimmer
are musically successful.
