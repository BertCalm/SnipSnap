# Cosmic Breaks approved sample bank

The owner approved the 16-pad browser audition with “Sounds cool, continue.”
That approves the overall direction; it does not certify every macro corner,
phone timing, or MPC hardware playback. The integration freezes the default
**Low orbit** processed one-shots from Site source commit
`10857a750238566cfc3284a0bd788288203624ca`. The audition remains at
https://cosmic-breaks-lab.bertcalm.chatgpt.site.

`dsp.mjs` is the original JavaScript renderer copied without audio changes.
`node reference/cosmic-breaks/freeze.mjs` regenerates all sixteen assets and
`manifest.json` under `synth/src/main/resources/cosmic-breaks/v1/`. It uses the
same mono 44.1 kHz, four-second, signed PCM16 quantization as the audition's
Download WAV button. The recorded source SHA and per-WAV checksums identify the
frozen model. A future sound change gets a new asset version and another listen.

The browser and freeze script synthesize every sound; none contains the uploaded
video or pedal recordings. Trigonometric roundoff may vary between JS runtimes;
the checked-in WAVs, rather than a platform's math implementation, are the
factory source of truth. Tests verify those WAVs and native export read-back.

The one-shots already include compression, reverb and amp coloration. No second
finishing rack is applied, and no PadRecipe pretends to regenerate this bank
from a different Kotlin engine. The original shared-bus groove can sound
different from mixing independently processed one-shots. One velocity layer is
intentional; no new timbre layers have been auditioned.
