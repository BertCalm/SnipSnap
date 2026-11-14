# MURMUR kept instruments from MAKE

The current selected-source work has already qualified A69 compatibility, B27
metadata, representative native cells, the legacy12 held canary, all147 bank cells,
and saved-player previews. The owner kept the six printed previews of the three
selected PLUCK programs. Those results remain credited at their exact scope;
three settings are one PLUCK voice, not three new voices or all-velocity/device
listening approval.

This isolated source change adds a direct `MAKE → INSTRUMENTS → MURMUR PLUCK`
chooser, beside the existing CAIRN door. Previously these choices required
restoring a kept preset in SYNTH and opening its dedicated instrument sheet.
The existing SYNTH route remains unchanged.

The three stable IDs are `finite-hollow-wire`, `held-hollow-wire`, and
`approved-c2`. Each press resolves a fresh complete `MurmurKeptSources` factory
patch and freezes it with the existing `MurmurSelectedInstrumentMaker.Spec`.
The new picker calls the same serial native-cell export and descriptor-last fresh
admission as SYNTH. It does not modify DSP, source binding, cell recipes, the
native-touch grid, source range, output finishing, sampler release, presets or
saved models. HOLLOW WIRE keeps MIDI36–96; STEADY ROOT keeps MIDI36–54. No range is
expanded to0–127. No raw-reference gain is applied to factory output.

The app owns one cancellable background job across bench changes. Progress uses
the existing native-cell count, cancelled jobs use the maker's existing rollback,
and successful jobs refresh the instrument shelf so the row opens KEYS. Name
selection checks the `.xty`, TrackData directory and instrument sidecar, including
orphan names and symlinks; no existing namespace is overwritten. After the
exporter has committed, a late cancel does not reinterpret success as failure.

Three focused shell tests and two phone Compose checks are authored, **unrun**.
The shell tests cover source/ID/range preservation, refusing unknown or cancelled
work before file creation, and fresh naming/late-cancel publication semantics at
a synthetic export boundary. They do not claim a new native bank render or a
real saved instrument from the synthetic callback. Compose checks cover actual
bench reachability, stable dispatch and the busy guard; they do not execute the
real app export transaction.

Next gates require the coordinator's fresh source/resource grant: compile the new
shell/app source closure and run the three selected shell tests, compile the two
Compose checks, then normal selected-source app packaging/runtime as appropriate.
Do not rerender already-qualified unchanged native banks or update accepted audio
hashes. Device keyboard/MPC behavior, full resident-bank memory, broader root/touch
listening, additional genuine voices, authored RR, chord-tool delivery and the
complete engine standard remain open. No source push, merge, JVM, native render,
APK build, Site publication, or new owner/device approval ran during authoring.
