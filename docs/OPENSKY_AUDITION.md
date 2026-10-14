# OPENSKY audition spike

OPENSKY adapts the [XOpenSky concept brief](https://github.com/BertCalm/XO_OX-XOmnibus/blob/main/Docs%2Fconcepts%2Fxopensky_concept_brief.md)
into SnipSnap's offline sample-authoring model. This is a listening prototype,
not an entry in the phone's engine picker or a finished keyboard instrument.

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

This study does not implement attack-plus-loop packaging, note-off release,
velocity-zone playback or live macro control. Pitching a baked sample would
also change its shimmer timing; the native-note register renders do not test
that behavior. Phone render time, MPC loading and sustained keygroup playback
remain device checks. The original concept's XOmnibus coupling is outside the
spike.

Numerical checks establish deterministic output, bounded levels and recurrence.
The owner's listening verdict determines whether the voices, width and shimmer
are musically successful.
