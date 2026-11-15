# TIMBER round 2 audition

Python/scipy sound-design prototype, not the Kotlin production kit. Authored resonances are hypotheses, not measured acoustic instruments.

Run `python3 prototype/timber-r2/render.py` from the repository. Outputs currently target `/workspace/timber-r2`. Dependencies: numpy and scipy.

144 strikes: four instruments × three gestures × three velocities × four deterministic takes. `touch` preserves one global gain; `matched` compares take 0 at equal RMS across velocities. WAVs are mono 44.1 kHz, 16 bit.

Assertions cover finite output, DC, repeatability, monotonic RMS across velocity, waveform residual after gain fitting, within-zone take spread <1.5 dB, and audible-signal contribution of the body in rakes (numerical ablation, not a listening result). Waveform distinction does not prove acoustic realism. The overall natural-touch peak is capped at 0.85.

## Why it is built this way

- Separate body modes and string tunings let each instrument have its own identity.
- Contact impulses excite wood and strings so gestures share a resonating instrument.
- Velocity changes contact duration and collision strength as well as level.
- Small deterministic contact variations avoid random level jumps.
- Natural and equal-RMS strips separate dynamics from changes in character.

## Open gates

Listening judgment, Kotlin implementation/parity, genre kits, roll/riser behavior, and velocity × round robin through save, playback and export remain open. This audition does not close the engine acceptance gates.

Render environment: numpy 2.3.5, scipy 1.17.0.
