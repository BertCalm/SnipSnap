# Dry Jungle: heel, ghost and ride revision

## Owner finding and diagnosis

The owner reported that 01/02 and 03/04 sounded alike, and that Dry Ride sounded more like a tambourine. Review baseline: `3ad7884514ddf484f1c689a6ffd23265ec8d2158`.

The previous heel and ghost only shifted controls around the approved sources. Their quieter mixer gains were absent from raw pad solos. The SKIN ride continuously excites high-frequency resonators with noise and the preset added SIZZLE 0.2. That is a plausible mechanism for the reported jingle/wash character, not an independent listening conclusion.

## Revision 2

The characteristic gesture is full kick → muted heel pickup → backbeat → light ghost stroke, with a stick-led ride pulse above it.

Only Dry Jungle source slots 02, 04 and 10 change. Approved slots 01 and 03, the other Jungle sources, and all Dub/Orbital sources retain their renders. Version 1 source recipes remain supported; starter banks now save version 2.

- **02 Heel Kick:** a lower-tuned, softer-contact kick at 60% of incoming physical velocity, very short body/click settings, a 1.5 kHz low-pass and 32 ms damping time constant. It should be a small, muted thud before mixer gain, rather than another full kick.
- **04 Ghost Snare:** a higher, lighter strike at 48% of incoming physical velocity, reduced body/wires, a 4.2 kHz low-pass and 22 ms damping. It should function as a short grace stroke before mixer gain.
- **10 Dry Ride:** a dedicated finite bow-strike source. Three lower inharmonic modes provide a defined ping; 33 denser upper modes supply the metallic decay, and a 9 ms bounded noise burst supplies stick contact. There is no continuing noise drive, rivet layer or added ambience. Mode radii are strictly below one; high modes decay faster. Touch changes spectral weighting, decay and level; deterministic takes vary mode tuning, phase, gain and stick excitation.

The ride is a source within this kit, not a new registered engine. SKIN and ONSET are the nearest reused families; the current SKIN ride cannot express this struck ping/plate balance without changing its shared renderer. Keeping the new source local protects existing presets and kits. Frequencies, balances and decay laws are authored shape choices, not measurements of a physical cymbal. It is an inharmonic modal analogy, not a geometric plate solver.

The ride renders a fixed 36 modes over 1.35 seconds at 176.4 kHz, followed by the house anti-alias filter, decimation to mono 44.1 kHz, DC removal and tail fade. Fixed gains preserve dynamics; there is no per-hit normalization. Its work is bounded independently of velocity and take. Heel/ghost treatments reuse the approved sources and add linear filtering/damping.

## Evidence and audition

`SporeJungleRevisionTest` checks old version-1 renders against the previously published PCM, tests raw-source heel/ghost level, tail and shape differences at every velocity/take, and compares all unaffected sources across recipe versions. Existing source, core parity and full-grid native/SFZ/MPC 3 delivery tests remain applicable. Software tests do not establish that the ride is perceptually convincing.

The audition adds raw 01→02 and 03→04 alternations, previous/revised buttons for each changed source, and a ride pattern rendered through saved-kit playback. Raw solos preserve source dynamics; kit grooves use pad mixer levels. All three formats are regenerated with complete grids. No reference recording is introduced.

The prior broad regression run exposed three integration omissions: starter counts in the READMEs, the exact starter-ID expectation, and the test's assumption that every synthesizer starter has an editable single-hit Patch. Counts and IDs now include the three synthetic kits; tests validate their actual whole-grid source recipes and nine-cell chains instead of inventing Patch metadata. This is an integration correction, not a waived gate.

The owner accepted this revision on 15 November 2026 with “Better, proceed”. Freeze Jungle 02, 04 and 10 at recipe version 2 alongside the previously approved 01/03 anchors. The owner subsequently clarified “I listened to all kits” and explicitly confirmed “Phone and hardware approved”. This closes sound and phone/hardware acceptance for the full three-kit set. Device approval is owner-reported; no model, firmware, timing measurement or detailed procedure was supplied. Automated browser decoding was not separately tested.

The pending Gradle run finished successfully in 17m 5s: all `SporeJungleRevisionTest` and `StarterKitsTest` cases passed, including rendering every starter, the exact starter ID list, legacy PCM replay and unchanged-source checks. The earlier focused core/source, shelf/convention and 486-cell delivery checks also passed. This closes the identified local regression failures; it is not a claim of a fresh full-suite CI result on the latest PR head.

## Measured revision renders

At velocity 1 / take 0, the time containing 90% of hit energy is 26.89 ms for the heel versus 82.74 ms for the approved kick; 17.57 ms for the ghost versus 54.90 ms for the approved snare. Heel and ghost raw peaks are 0.159 and 0.128, versus 0.644 and 0.566 for the anchors. Ride energy duration rises from 81.66 to 155.03 ms. These measurements use the generated PCM24 WAVs; they describe envelope separation, not perceptual acceptance.

## Why it is built this way

- Physical touch scaling and damping make heel/ghost identities survive raw solo playback; a mixer fader alone cannot do that.
- A short stick excitation and decaying modes give the ride a definite onset and ring without rivet noise.
- Different decay rates let the bright wash recede while the lower ping persists.
- Versioned source selection keeps old saved kits reproducible and leaves approved anchors intact.
