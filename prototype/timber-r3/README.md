# TIMBER extended techniques audition

Status: Python/scipy audition, not the Kotlin engine. Base repository read: 23cde0d1c107039c23fd74ff5c3d2b359a7f6e75. This extends TIMBER's existing family; it does not introduce a new engine. The nearest roster families are PLUCK (pitched strings) and MURK (wooden resonators); the landing here is four additional percussion articulations.

The characteristic gesture is contact → wood and string excitation → damped resonance → silence. Palm uses broad contact and added damping; muted slap uses short string decay and scheduled board contacts; bridge uses narrow contact and stronger upper body modes; scrape uses a finite accelerating ridge-contact train. Contact laws are authored approximations, not solved hand dynamics or measured instrument geometry. The resonators are literal damped modes; excitation feeds them one way, with no feedback to the contact source.

Signal flow: velocity and take → gesture excitation at 176.4 kHz → body and string resonators → low-pass → resample to 44.1 kHz → DC removal/high-pass → tail fade → common audition attenuation. This is a scipy output stage, not a claim of parity with the Kotlin house output stage. No per-strike normalization; the equal-RMS comparison is explicitly separate.

Run `OPENBLAS_NUM_THREADS=1 python3 prototype/timber-r3/render.py`. Outputs go to `/workspace/timber-r3`. Dependencies are numpy and scipy. The fixed four profiles, four gestures, three velocities and four takes produce 192 strikes. The model has no exposed production macros or HOLD yet. A Kotlin landing must measure phone render time (target tens of milliseconds), cache resonator coefficients by profile/gesture/model version, and cache rendered buffers by profile/gesture/velocity/take/model version. Python throughput does not establish that budget.

Checks: finite output, bounded DC, deterministic repeat, increasing RMS across velocities, soft/hard waveform residual after gain fitting, all pairwise take residuals, within-zone RMS spread below 1.5 dB, and body contribution for scrapes. Distinct waveforms can result from onset differences; this is not proof of audible timbral distinction. Scrape takes share a winding texture and vary travel speed and strike position, so each take does not invent a different instrument surface.

Open: human listening for new techniques, aliasing reference comparison, Kotlin parity and performance, authored genre kits and rolls/risers, velocity and round robin through saving, playback and export. Production acceptance gates remain open. Josh liked round 2; these new sounds have not yet been judged by him. Risks: palm may be too quiet, bridge too pitched, scrape too buzzy, and muted slap too short. Listen in context before setting final pad balances.

## Why it is built this way

- The earlier body profiles preserve continuity with the audition Josh liked.
- Contact width and damping distinguish palm, nail and muted-string gestures.
- Scrape contacts drive both strings and wood, tying the rasp to the instrument.
- One common gain preserves differences in natural strength; matched strips isolate velocity character.
