# Which document to trust

**OHM.** [`OHM.md`](OHM.md) records the dry coupled-string listening
instrument, versioned recipes and the control-authority revision. Its audition
compares the original and revised renderers directly. The revised sound is the
working baseline, with five auditioned recipes in the synth picker.

The tree has moved faster than some of the notes. When two of them
disagree, this is the order.

**Where the project stands.** The root [`README.md`](../README.md) is the
map of the modules. [`APP_PLAN.md`](APP_PLAN.md)'s opening table is the
milestone record. [`BENCH.md`](BENCH.md) is what only a phone or a Live
III can answer, and it says so when a row has been paused.

**On a device.** [`BENCH.md`](BENCH.md) is the ordered list, with a line
to write each answer on. [`../DEVICE_TEST_GUIDE.md`](../DEVICE_TEST_GUIDE.md)
is a shorter first session. [`../app/README.md`](../app/README.md) is the
per-feature detail and the build steps.

**Formats.** [`MPC3_FORMAT.md`](MPC3_FORMAT.md) is the native container,
and it keeps corpus facts separate from inferences. [`MPC_EXPORT.md`](MPC_EXPORT.md)
is the folder layouts. [`XPM_STRUCTURE.md`](XPM_STRUCTURE.md) is the MPC 2
program. The acceptance device in hand is the Live III
([`../reference/README.md`](../reference/README.md)).

**Dated notes.** [`superpowers/`](superpowers/) holds the plan and the
spec for a change, dated in the filename. They are not revised when the
tree moves on. [`CONCEPT.md`](CONCEPT.md)'s "deliberately v2" section is
the original MVP cut. [`SYNTH_UPGRADE.md`](SYNTH_UPGRADE.md) measures a
gap from before the preset library; its counts are that gap, not the
current roster.

**Wick.** [`WICK.md`](WICK.md) records the PLUCK/WOOD/BUBBLE listening prototype,
recipe contracts, musical phrases, held-loop strategy and private audition. The supplied
[`engineering specification`](WICK_SPEC.md) is preserved as the proposal;
the remaining voices and full preset library await listening.

**Brass GROW.** [`engineering/brass-grow-prototype.md`](engineering/brass-grow-prototype.md)
records the experimental single-source EMBOUCHURE BRASS mode, its saved-model
boundary, pad workflow and reproducible register/velocity checks. The published
audition remains a sound reference; the in-app prototype still needs device playing.

**Oven.** [`OVEN.md`](OVEN.md) records the approved ten-preset sonic baseline,
the phone synth integration and finite per-note resonance. Keyboard velocity
layers, shared resonance and phone/MPC hardware acceptance remain later work.

**Tangle.** [`TANGLE.md`](TANGLE.md) records the implemented experimental
engine, six controls, patch and kit contracts, and held-loop limits. The
[private audition](https://tangle-snipsnap-audition.bertcalm.chatgpt.site)
awaits the owner's listening verdict. The supplied
[`engineering specification`](superpowers/specs/2026-10-11-tangle-engine-engineering-spec.md)
is preserved unchanged as the proposal rather than an acceptance record.

**Flock.** [`FLOCK.md`](FLOCK.md) records the finite engine, SYNTH controls,
nine factory recipes, sixteen-pad FRESH TAPE starter and MPC 2/3 kit exports.
The owner confirmed revised FLOW is audible; saved model-1 recipes remain
replayable. Its
[private audition](https://flock-snipsnap-audition.bertcalm.chatgpt.site) compares
neighbor interaction with fixed detuning and independent motion. HOLD always
sets a finite duration. [The held RIBBON instrument](FLOCK_RIBBON_INSTRUMENT.md)
adds explicit saved playback and release through the existing phone/MPC instrument
path. Phone/MPC hardware checks remain open.
The separate RIBBON sustain study compares long continuous renders with captured
roughly six-second loops at three FLOW settings, without changing the finite presets.

**Osprey.** [`OSPREY.md`](OSPREY.md) records the finite five-shore SWELL proof,
recipe and audition contracts. HOLD extends the finite gesture; repeating
sea cycles and the owner's sonic acceptance remain open.

**Scanned Chain.** [`SCANNED_CHAIN.md`](SCANNED_CHAIN.md) describes the editable
render-to-pad voice, three presets, factory/drawn/sample shapes and complete
patch recall. The earlier [listening experiment](SCANNED_CHAIN_EXPERIMENT.md)
and its published comparisons remain available separately.

**Chamber.** [`CHAMBER.md`](CHAMBER.md) records the finite HYBRID/AQUARIUM
first-listen prototype, complete room recipes, passive feedback model and
playback limits. The [private audition](https://chamber-snipsnap-audition.bertcalm.chatgpt.site)
is published; the owner's listening verdict remains pending. The supplied [`engineering specification`](CHAMBER_SPEC.md)
remains the unchanged proposal; sustained HOLD and the wider library are later work.

**Orphica.** [`ORPHICA.md`](ORPHICA.md) records the microsound harp, approved
finite listening audition, phone controls and captured-sustain instrument
export. Phone and MPC listening checks remain open.

**Aquarium.** The accepted [six-effect audition](https://snipsnap-aquarium-pedals-audition.bertcalm.chatgpt.site)
is preserved by the shared native processor and standalone settings contract.
The [integration design](superpowers/specs/2026-10-18-aquarium-integration-design.md)
and [implementation plan](superpowers/plans/2026-10-18-aquarium-foundation.md)
separate that foundation from the planned pad rendering, performance routing
and controls. The foundation does not yet process app playback.

**Code review, October 2026.** [`CODE_REVIEW_2026_10.md`](CODE_REVIEW_2026_10.md)
records the whole-app review of 2026-10-17: the six guards that landed with
it (kit-name containment, a frame-capped share-door WAV decode, an XPN entry
ceiling, canonical SFZ containment with one copy budget, a NaN-safe DC mean,
recordings kept out of cloud backup) and the findings recorded for a design
or listening session. Its sound-quality findings are the review's claims,
not heard, and nothing in that change alters a shipped sound.
