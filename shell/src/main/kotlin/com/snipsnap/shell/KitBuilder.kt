package com.snipsnap.shell

import com.snipsnap.audio.AutoPlace
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.kit.AtomicFile
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Names
import com.snipsnap.xpm.PadNoteMap
import java.io.File
import java.util.Locale

/** [KitBuilderModel.nextStem]'s bound. A kit holds 128 pads, so this is ample headroom. */
private const val MAX_STEM_ATTEMPTS = 999

/**
 * The KIT screen: the 4×4 grid over a kit folder, every edit non-
 * destructive and every assignment trivially draggable — the pledge
 * `AutoPlace` makes, kept at the editing layer.
 *
 * A kit *is* its folder (`kit.json` + WAVs); this model edits the
 * in-memory [Kit] and writes WAVs eagerly (a pad you can hear is a file
 * that exists), but `kit.json` only on [save] — [dirty] tells the UI when
 * the two disagree.
 */
class KitBuilderModel private constructor(
    val kitDir: File,
    kit: Kit,
) {

    var kit: Kit = kit
        private set

    var dirty: Boolean = false
        private set

    val name: String get() = kit.name

    fun pad(slot: Int): KitPad? = kit.pad(slot)

    /** The visible bank (16 pads) as slots [first..first+15]; A = 1. */
    fun bank(bankIndex: Int = 0): List<KitPad?> {
        require(bankIndex in 0..7) { "bank 0..7 (A..H), got $bankIndex" }
        val first = bankIndex * 16 + 1
        return (first until first + 16).map { kit.pad(it) }
    }

    /** The empty-kit LCD line, or null once anything is placed. */
    val emptyStateLine: String? get() = if (kit.pads.isEmpty()) Copy.EMPTY_KIT else null

    /**
     * Drop a snip on a pad: the WAV is written into the kit folder named
     * by pad and class (`A03_HatClosed_01.wav`), the class brings its
     * colour and mute group, and any previous occupant's file is removed
     * if nothing else references it.
     *
     * [source] is provenance, freeform (see [KitPad.source]) — e.g.
     * [SnipStore.provenanceTag] when this assign came from an existing snip
     * file (the SNIPS shelf's → PAD), so a later "USED" badge can read it
     * back off the pad instead of guessing. That helper tags both `"file"`
     * (display) and `"capturedAtMillis"` (the snip's own immutable capture
     * time, unaffected by a later rename — [SnipStore.isUsedBy] is the
     * matching read side). Defaults to empty: a live capture (GRAB/HOLD off
     * the mic ring) never touched a snip file and has nothing honest to tag
     * here.
     * [knownRootMidi] lets a synthesized note use its authored root instead of
     * pitch detection, while retaining the kit's normal in-key transposition.
     */
    fun assign(
        slot: Int,
        snip: Snip,
        drumClass: DrumClass = DrumClass.UNKNOWN,
        displayName: String? = null,
        source: Map<String, String> = emptyMap(),
        knownRootMidi: Int? = null,
    ): KitPad {
        require(snip.frameCount > 0) { "won't assign an empty snip" }
        require(knownRootMidi == null || drumClass == DrumClass.TONAL) { "a known root requires tonal audio" }
        val knownTune = knownRootMidi?.let { tuningForKnownRoot(it) }
        val stem = nextStem(slot, drumClass)
        WavWriter.write(File(kitDir, "$stem.wav"), snip)

        // Retune on assign (F5.3): with a key set, a tonal pad lands in it
        // through the tune fields the MPC pad already has - audio
        // untouched, the kick untouched, an unpitched hit never corrected.
        val tune = knownTune ?: kit.key?.takeIf { drumClass == DrumClass.TONAL }?.let { key ->
            com.snipsnap.audio.Tuner.inKey(snip, key.rootSemitone, key.scale)?.let { it.tuneCoarse to it.tuneFine }
        }
        val previous = kit.pad(slot)
        val pad = KitPad(
            slot = slot,
            sampleFile = "$stem.wav",
            displayName = displayName
                ?: String.format(Locale.ROOT, "%s %02d", AutoPlace.nameFor(drumClass), classCount(drumClass) + 1),
            drumClass = drumClass,
            colorHex = AutoPlace.colorFor(drumClass),
            muteGroup = AutoPlace.muteGroupFor(drumClass),
            tuneCoarse = tune?.first ?: 0,
            tuneFine = tune?.second ?: 0,
            source = if (knownRootMidi == null) source else source + mapOf(
                "rootMidi" to knownRootMidi.toString(),
                "rootNote" to com.snipsnap.audio.Scales.nameOf(knownRootMidi),
                "rootOrigin" to "authored",
            ),
        )
        kit = kit.copy(pads = kit.pads.filter { it.slot != slot } + pad)
        previous?.let { deleteIfUnreferenced(it) }
        dirty = true
        return pad
    }

    /** An authored equal-tempered note needs only whole-semitone key placement. */
    fun tuningForKnownRoot(rootMidi: Int): Pair<Int, Int> {
        require(rootMidi in 0..127) { "known root must be MIDI 0..127" }
        val key = kit.key ?: return 0 to 0
        val target = com.snipsnap.audio.Scales.nearestInKey(
            com.snipsnap.audio.Scales.midiToHz(rootMidi), key.rootSemitone, key.scale,
        )
        return (target - rootMidi) to 0
    }

    /**
     * BACK ONTO (RE-TRIM, `docs/RETRIM.md`): [snip] — a fresh cut of the
     * pad's own tape — replaces the pad's audio, and the pad keeps
     * everything that is metadata rather than file: class, name, colour,
     * level, pan, tune, choke, one-shot and SHAPE. [cut] is `Retrim.tag`'s
     * three keys for the new cut; they overwrite the old ones and the rest
     * of the pad's provenance stays.
     *
     * What does NOT carry: a treatment (its recipe is dropped — the sound
     * was baked into the old file, which [assign] bins as it would for any
     * replaced pad, and stacking it silently onto the new cut is the kind
     * of surprise the SMEAR bug taught) and the stamps that described the
     * old audio's processing (`mutatedWith`, `outside`, `desampled`). The
     * caller names the treatment left behind (`Retrim.treatmentLeft`) in
     * the toast. A velocity-layered pad (GHOSTS, or STACK THE TAKES — the
     * model can't tell them apart, and either rides on the old file) and a
     * round-robin chain (its boundaries index the old file) are refused,
     * the same way [treatPad] and [smearPad] refuse them; `Retrim.of`
     * says so before TAPE ever opens. [save] archives a take, so UNDO on
     * the sheet is the TAKES room, as for every other replacement.
     */
    fun backOnto(slot: Int, snip: Snip, cut: Map<String, String>): KitPad {
        val old = kit.pad(slot) ?: throw IllegalArgumentException("no pad on slot $slot")
        require(old.velocityLayers.isEmpty()) { "pad $slot is velocity-layered - clear GHOSTS (or the stack) before re-trimming" }
        requireNotChained(old, "re-trimming")
        val source = old.source - LEFT_WITH_OLD_FILE + cut
        assign(slot, snip, old.drumClass, old.displayName, source)
        update(slot) {
            it.copy(
                colorHex = old.colorHex,
                level = old.level,
                pan = old.pan,
                tuneCoarse = old.tuneCoarse,
                tuneFine = old.tuneFine,
                muteGroup = old.muteGroup,
                oneShot = old.oneShot,
                attack = old.attack,
                decay = old.decay,
                cutoff = old.cutoff,
                resonance = old.resonance,
                humanize = old.humanize,
            )
        }
        return kit.pad(slot)!!
    }

    /** Long-press clear: the pad empties; its file goes if nothing shares it. */
    fun clear(slot: Int) {
        val pad = kit.pad(slot) ?: return
        kit = kit.copy(pads = kit.pads.filter { it.slot != slot })
        deleteIfUnreferenced(pad)
        dirty = true
    }

    /**
     * Drag a pad to another slot. An occupied target swaps — dragging is
     * how layouts get fixed, and losing a pad to a move would be theft.
     */
    fun move(fromSlot: Int, toSlot: Int) {
        if (fromSlot == toSlot) return
        val from = kit.pad(fromSlot) ?: return
        val to = kit.pad(toSlot)
        val moved = kit.pads.filter { it.slot != fromSlot && it.slot != toSlot }.toMutableList()
        moved += from.copy(slot = toSlot)
        to?.let { moved += it.copy(slot = fromSlot) }
        kit = kit.copy(pads = moved)
        dirty = true
    }

    /**
     * Per-pad edits — name, colour, level, pan, tune, mute group,
     * one-shot. [KitPad]'s own validation guards the ranges; the slot and
     * sample stay what they were.
     */
    fun update(slot: Int, transform: (KitPad) -> KitPad): KitPad {
        val pad = kit.pad(slot) ?: throw IllegalArgumentException("no pad on slot $slot")
        val edited = transform(pad)
        require(edited.slot == pad.slot) { "update can't move pads — use move()" }
        require(edited.sampleFile == pad.sampleFile) { "update can't swap samples — use assign()" }
        kit = kit.copy(pads = kit.pads.map { if (it.slot == slot) edited else it })
        dirty = true
        return edited
    }

    /** Adopt already-written printed files while retaining every unrelated pad field. */
    internal fun replacePrintedPad(slot: Int, expected: KitPad, replacement: KitPad): KitPad {
        require(kit.pad(slot) == expected) { "the pad changed while the treatment was rendering; try again" }
        require(replacement.slot == slot && replacement.copy(
            sampleFile = expected.sampleFile,
            velocityLayers = expected.velocityLayers,
            recipe = expected.recipe,
        ) == expected) { "a printed treatment can change only audio files, velocity layers and its recipe" }
        // This fingerprint names the previous current WAV, not its provenance.
        // Even REMOVE and UNDO adopt different audio; exports fingerprint that
        // result again. Keep the caller guard strict before clearing this key.
        val adopted = replacement.copy(source = replacement.source - "rawSha256")
        kit = kit.copy(pads = kit.pads.map { if (it.slot == slot) adopted else it })
        dirty = true
        return adopted
    }

    /** Existing board callers retain the same guarded adoption door. */
    internal fun replaceBoardPad(slot: Int, expected: KitPad, replacement: KitPad): KitPad =
        replacePrintedPad(slot, expected, replacement)

    /**
     * Set or clear the kit's key. Choosing a key never retunes anything by
     * itself — that's [retuneTonalPads], an explicit action.
     */
    fun setKey(key: com.snipsnap.audio.KeySpec?) {
        if (kit.key == key) return
        kit = kit.copy(key = key)
        dirty = true
    }

    /** Set or clear the kit's tempo — what the preview, the arranger and WOBBLE read. */
    fun setTempo(bpm: Float?) {
        if (kit.tempoBpm == bpm) return
        kit = kit.copy(tempoBpm = bpm)
        dirty = true
    }

    /** Store a complete live snapshot without rewriting or printing any sample. */
    fun setAquariumPerformance(settings: com.snipsnap.synth.AquariumPerformanceSettings) {
        val value = settings.toJsonValue()
        if (kit.aquariumPerformance == value) return
        kit = kit.copy(aquariumPerformance = value)
        dirty = true
    }

    /**
     * IN KEY: retune every TONAL pad onto the nearest note of the kit's
     * key, via the tune fields the MPC pad already has - audio untouched,
     * unpitched pads and non-tonal classes never "corrected". A validated
     * authored-root stamp retains exact synth tuning; other audio follows
     * the pitch detector. Returns the slots that moved. No key set, nothing happens.
     */
    fun retuneTonalPads(): List<Int> {
        val key = kit.key ?: return emptyList()
        val moved = mutableListOf<Int>()
        for (pad in kit.pads) {
            if (pad.drumClass != DrumClass.TONAL) continue
            val authoredRoot = pad.source.takeIf { it["rootOrigin"] == "authored" }
                ?.get("rootMidi")?.toIntOrNull()?.takeIf { it in 0..127 }
            val tune = if (authoredRoot != null) tuningForKnownRoot(authoredRoot) else {
                val snip = com.snipsnap.audio.WavReader.read(File(kitDir, pad.sampleFile))
                val detected = com.snipsnap.audio.Tuner.inKey(snip, key.rootSemitone, key.scale) ?: continue
                detected.tuneCoarse to detected.tuneFine
            }
            if (tune.first == pad.tuneCoarse && tune.second == pad.tuneFine) continue
            kit = kit.copy(
                pads = kit.pads.map {
                    if (it.slot == pad.slot) it.copy(tuneCoarse = tune.first, tuneFine = tune.second) else it
                },
            )
            moved += pad.slot
        }
        if (moved.isNotEmpty()) dirty = true
        return moved
    }

    /**
     * EVIL TWINS: bank B becomes seeded FX re-treatments of bank A, one
     * twin per pad — [com.snipsnap.synth.Shuffle.withRemixBank] over the
     * kit's own audio. Twins keep their source's colour and mute group
     * (so the hats still choke in bank B), carry fx-only recipes, and are
     * stamped [TWIN_OF] with the pad they twin, so a later remix knows
     * which bank-B pads are its own to replace. Rerolling with a new seed
     * replaces the bank — but never a pad the user put there themselves
     * (capture, SNIPS → PAD or a chop landed on B): [ownPadsOnBankB]
     * non-empty refuses, with the pads named. Returns bank-B slots.
     */
    fun remixBankB(seed: Int): List<Int> {
        val bankA = (1..16).map { slot ->
            pad(slot)?.let {
                ArrangedPad(
                    com.snipsnap.audio.WavReader.read(File(kitDir, it.sampleFile)),
                    it.drumClass,
                )
            }
        }
        require(bankA.any { it != null }) { "bank A is empty - nothing to remix" }
        val own = ownPadsOnBankB()
        check(own.isEmpty()) { "bank B holds your own pads (${own.joinToString { PadBanks.tag(it) }}) - a remix would wipe them" }

        (17..32).forEach { clear(it) }
        val remixed = com.snipsnap.synth.Shuffle.withRemixBank(bankA, seed)
        val added = mutableListOf<Int>()
        remixed.drop(16).forEachIndexed { i, twin ->
            twin ?: return@forEachIndexed
            val slot = 17 + i
            val source = pad(slot - 16) ?: return@forEachIndexed
            val stem = nextStem(slot, twin.drumClass)
            WavWriter.write(File(kitDir, "$stem.wav"), twin.snip)
            kit = kit.copy(
                pads = kit.pads + source.copy(
                    slot = slot,
                    sampleFile = "$stem.wav",
                    displayName = "${source.displayName} B",
                    recipe = twin.recipe,
                    velocityLayers = emptyList(),
                    source = source.source + (TWIN_OF to PadBanks.tag(slot - 16)),
                ),
            )
            added += slot
        }
        dirty = true
        return added
    }

    /**
     * Whether [pad] is one of EVIL TWINS' own: stamped [TWIN_OF], or — for
     * a twin dealt before the stamp existed — the shape a twin has and
     * nothing else on bank B did until now: the bank-A pad's name plus
     * " B", with a recipe, sixteen slots up from a pad that exists.
     */
    fun isTwin(pad: KitPad): Boolean = isTwin(kit, pad)

    /**
     * Bank-B slots holding a pad that is not a twin — the user's own,
     * landed by capture, SNIPS → PAD or a chop — which [remixBankB]
     * refuses to wipe and the KIT screen warns about before trying.
     */
    fun ownPadsOnBankB(): List<Int> = ownPadsOnBankB(kit)

    /**
     * A chop's arrangement landed onto one [bank] of THIS kit, in the
     * order SEND TO PADS would have laid it, instead of into a new kit:
     * the second page a user builds by hand rather than the twins tray.
     * The bank must be empty (nothing here decides which of two sounds
     * a slot keeps), and a bank holds sixteen, so an arrangement wider
     * than that lands its first sixteen entries and the caller says how
     * many did not fit ([arranged] entries past the bank, non-null).
     * Each pad lands through [assign] — the same door capture and
     * SNIPS → PAD use — so its class name, provenance (`origin=chop`,
     * the tape keys) and in-key retune are the ones any pad gets.
     * Returns the slots landed on, in order.
     */
    fun landArranged(arranged: List<ArrangedPad?>, bank: Int): List<Int> {
        val slots = PadBanks.slots(bank)
        val taken = kit.pads.filter { it.slot in slots }
        require(taken.isEmpty()) { "bank ${PadBanks.letter(bank)} is not empty: ${taken.joinToString { PadBanks.tag(it.slot) }}" }
        val landed = mutableListOf<Int>()
        arranged.take(PadBanks.SIZE).forEachIndexed { i, entry ->
            val pad = entry ?: return@forEachIndexed
            if (pad.snip.frameCount == 0) return@forEachIndexed
            val slot = slots.first + i
            if (pad.takes.isEmpty()) {
                assign(slot, pad.snip, pad.drumClass, pad.displayName ?: pad.drumClass.name.replace('_', ' '), source = pad.source)
            } else {
                // A folded pad (CHOP's FOLD DOUBLES): every take end to end
                // through the same door, then the chain that steps through
                // them — what KitAssembler does for SEND TO PADS.
                val all = listOf(pad.snip) + pad.takes
                var at = 0L
                val boundaries = all.map { t -> at.also { at += t.frameCount } }
                assign(slot, Robin.concat(all), pad.drumClass, pad.displayName ?: pad.drumClass.name.replace('_', ' '), source = pad.source)
                update(slot) { it.copy(chain = com.snipsnap.kit.ChainInfo(boundaries, cycle = all.size)) }
            }
            // A gate pad (a ghost: hold the pad, hold the room) stays a gate
            // through this door too, and a balanced pad keeps the mixer level
            // the balancer set — both as they do through KitAssembler's.
            if (!pad.oneShot || pad.level != null) {
                update(slot) { it.copy(oneShot = pad.oneShot, level = pad.level ?: it.level) }
            }
            landed += slot
        }
        return landed
    }

    /**
     * Ghost notes: soft velocity zones rendered darker (not just quieter)
     * under the pad's main sample. [softZones] 1 or 2. Reversible with
     * [clearGhostLayers].
     *
     * A synth pad (its recipe carries a [com.snipsnap.synth.Patch])
     * re-renders each zone at velocity via `Velocity.atVelocity` — a soft
     * ghost genuinely struck softer, not the main take low-passed. A
     * captured pad has no patch to re-render, so it still reads
     * `Velocity.soften` on the main take, the only thing there is to do
     * with a frozen recording.
     */
    fun addGhostLayers(slot: Int, softZones: Int = 1): KitPad {
        require(softZones in 1..2) { "1 or 2 soft zones, got $softZones" }
        val pad = kit.pad(slot) ?: throw IllegalArgumentException("no pad on slot $slot")
        require(!PrintedPads.has(pad.recipe)) { Copy.boardRemoveBeforeSoftHits(PrintedPads.label(pad.recipe)) }
        require(pad.velocityLayers.isEmpty()) { "pad $slot is already velocity-layered" }
        val main = com.snipsnap.audio.WavReader.read(File(kitDir, pad.sampleFile))
        // Velocity.layerAt makes the same patch-vs-captured (and
        // patch+fx-falls-back-too) call Robin's zone grid and StarterKits'
        // VELOCITY starter make - resolved here, not per zone, per
        // Velocity.atVelocity's own KDoc (the scan behind brightnessSpec
        // is O(5×n), best paid once for the whole stack). Skipped
        // entirely for a patch+fx pad: layerAt's fx gate discards
        // whatever this resolves to anyway (Task 5b).
        val padRecipe = Breed.recipeOf(pad)
        if (padRecipe == null) Breed.warnIfCorruptRecipe(pad, slot, "ghost layers")
        val patch = padRecipe?.patch
        val patchFx = padRecipe?.fx
        val brightnessSpec = if (patch != null && patchFx == null) {
            com.snipsnap.synth.Velocity.brightnessSpec(patch)
        } else {
            null
        }
        // Also resolved once - this is layerAt's format probe (a full
        // patch.render(), thrown away), the expensive half of the same
        // hoist brightnessSpec just got (Task 5b).
        val useAtVelocity = com.snipsnap.synth.Velocity.canUseAtVelocity(main, patch, patchFx)

        val amounts = if (softZones == 1) listOf(0.55f) else listOf(0.7f, 0.4f) // softest first
        val windows = StackTakes.windows(softZones)
        // Free names, not `_v1`/`_v2` blindly: a stale render left on disk
        // (or a stacked sibling's copy) must not be overwritten.
        val names = freeLayerNames(pad.sampleStem, softZones)
        val layers = buildList {
            amounts.forEachIndexed { v, amount ->
                // amount is a softening depth (0 untouched, 1 softest);
                // layerAt (like atVelocity) speaks velocity (1 untouched,
                // 0 softest).
                val darker = com.snipsnap.synth.Velocity.layerAt(
                    main, patch, patchFx, 1f - amount, brightnessSpec, useAtVelocity,
                )
                WavWriter.write(File(kitDir, names[v]), darker)
                add(com.snipsnap.kit.KitLayer(names[v], windows[v].first, windows[v].last))
            }
            add(com.snipsnap.kit.KitLayer(pad.sampleFile, windows.last().first, windows.last().last))
        }
        return update(slot) { it.copy(velocityLayers = layers) }
    }

    /**
     * STRIKE FX, the audition: the three layers a KEEP would write, rendered
     * from the pad's source and handed back. Writes nothing.
     */
    fun strikeFxAudition(slot: Int, room: Float, slap: Float): List<com.snipsnap.audio.Snip> {
        val pad = kit.pad(slot) ?: throw IllegalArgumentException("no pad on slot $slot")
        requireNotChained(pad, "applying STRIKE FX to")
        require(!PrintedPads.has(pad.recipe)) { Copy.boardRemoveBeforeSoftHits(PrintedPads.label(pad.recipe)) }
        require(pad.recipe == null || Breed.recipeOf(pad) != null) { Copy.STRIKE_FX_KEEPS_HISTORY }
        return com.snipsnap.synth.StrikeFxBank.render(com.snipsnap.audio.WavReader.read(strikeFxSource(slot)), room, slap)
    }

    /**
     * STRIKE FX, KEEP: the pad's sample becomes DRY (bottom), ROOM (middle)
     * and ECHO (top, the pad's own file). DRY is the source's bytes copied
     * as they are. A pad that already carries a bank is re-rendered from its
     * DRY layer in place, so the echo never feeds the next keep. Soft hits or
     * stacked takes on the pad are replaced.
     */
    fun strikeFxLayers(slot: Int, room: Float, slap: Float): KitPad {
        val pad = kit.pad(slot) ?: throw IllegalArgumentException("no pad on slot $slot")
        requireNotChained(pad, "applying STRIKE FX to")
        require(!PrintedPads.has(pad.recipe)) { Copy.boardRemoveBeforeSoftHits(PrintedPads.label(pad.recipe)) }
        val padRecipe = Breed.recipeOf(pad)
        require(pad.recipe == null || padRecipe != null) { Copy.STRIKE_FX_KEEPS_HISTORY }
        val banked = com.snipsnap.synth.StrikeFxBank.isStrikeFx(padRecipe) && pad.velocityLayers.size == 3
        val sourceFile = strikeFxSource(slot)
        val source = com.snipsnap.audio.WavReader.read(sourceFile)
        val bank = com.snipsnap.synth.StrikeFxBank.render(source, room, slap)
        val recipeJson = (padRecipe ?: com.snipsnap.synth.PadRecipe(layers = com.snipsnap.synth.StrikeFxBank.layers(room, slap)))
            .copy(layers = com.snipsnap.synth.StrikeFxBank.layers(room, slap)).toJsonValue()

        // Fresh names on a first keep: the old soft files are still referenced
        // until update(), so freeLayerNames skips them. They are deleted only
        // after update, as clearGhostLayers does, so a failed write leaves the
        // pad as it was.
        val oldSoft = if (banked) emptyList() else pad.velocityLayers.map { it.sampleFile }.filter { it != pad.sampleFile }
        val names = if (banked) pad.velocityLayers.take(2).map { it.sampleFile } else freeLayerNames(pad.sampleStem, 2)
        // DRY: the source's own bytes, never re-encoded. On a re-keep the DRY
        // file is the source already.
        val dryFile = File(kitDir, names[0])
        if (!banked) sourceFile.copyTo(dryFile, overwrite = true)
        WavWriter.write(File(kitDir, names[1]), bank[1], allowNonMpcRate = true)
        WavWriter.write(File(kitDir, pad.sampleFile), bank[2], allowNonMpcRate = true)

        val windows = StackTakes.windows(2)
        val files = names + pad.sampleFile
        val labels = com.snipsnap.synth.StrikeFxBank.NAMES
        val layers = files.mapIndexed { i, f -> com.snipsnap.kit.KitLayer(f, windows[i].first, windows[i].last, labels[i]) }
        val kept = update(slot) { it.copy(velocityLayers = layers, recipe = recipeJson) }
        // Replaced layer files go to the bin, not deleted: a pad whose recipe lost its bank
        // (era, MPC round trip) still has the real DRY among them.
        oldSoft.forEach { moveToBin(it) }
        return kept
    }

    /** STRIKE FX, CLEAR: DRY goes back over the pad's file, ROOM and DRY files are deleted, the bank leaves the recipe. */
    fun clearStrikeFxLayers(slot: Int): KitPad {
        val pad = kit.pad(slot) ?: throw IllegalArgumentException("no pad on slot $slot")
        requireNotChained(pad, "applying STRIKE FX to")
        require(!PrintedPads.has(pad.recipe)) { Copy.boardRemoveBeforeSoftHits(PrintedPads.label(pad.recipe)) }
        val padRecipe = Breed.recipeOf(pad)
        require(com.snipsnap.synth.StrikeFxBank.isStrikeFx(padRecipe) && pad.velocityLayers.size == 3) { "pad $slot has no STRIKE FX to clear" }
        val softFiles = pad.velocityLayers.map { it.sampleFile }.filter { it != pad.sampleFile }
        File(kitDir, softFiles[0]).copyTo(File(kitDir, pad.sampleFile), overwrite = true)
        val remaining = try {
            padRecipe!!.copy(layers = null).toJsonValue()
        } catch (e: IllegalArgumentException) {
            null // the bank was all the recipe held
        }
        val cleared = update(slot) { it.copy(velocityLayers = emptyList(), recipe = remaining) }
        softFiles.forEach { File(kitDir, it).delete() }
        return cleared
    }

    /** The audio STRIKE FX reads: a kept bank's DRY layer, else the pad's own file. */
    private fun strikeFxSource(slot: Int): File {
        val pad = kit.pad(slot) ?: throw IllegalArgumentException("no pad on slot $slot")
        val banked = com.snipsnap.synth.StrikeFxBank.isStrikeFx(Breed.recipeOf(pad)) && pad.velocityLayers.size == 3
        return File(kitDir, if (banked) pad.velocityLayers[0].sampleFile else pad.sampleFile)
    }

    /**
     * STACK THE TAKES: the pad's real prior takes as its soft velocity
     * zones, [softTakes] in the caller's order, softest first, 1..
     * [StackTakes.MAX_SOFT] of them, each a [BinEntry] of THIS pad's own
     * file (`priorTakes`). Every take is COPIED out of the bin to a free
     * `${stem}_vN.wav` — never `restoreFromBin`, which deletes the bin
     * file: a take-restore reads history, it doesn't spend it, the same
     * copy-don't-consume rule `restoreLiveAudioAsOf` keeps. The live
     * sample stays the loudest zone (`KitPad.init` demands it). No
     * recipe: layers aren't a treatment, exactly as [addGhostLayers].
     * Undo is [clearGhostLayers], which deletes every layer file that
     * isn't the live one — the copies, not the bin sources.
     *
     * Nothing here touches level: takes differ in loudness and length and
     * this door keeps them as they are. The picker says so in words.
     */
    fun stackTakes(slot: Int, softTakes: List<BinEntry>): KitPad {
        require(softTakes.size in 1..StackTakes.MAX_SOFT) { "1..${StackTakes.MAX_SOFT} soft takes, got ${softTakes.size}" }
        require(softTakes.map { it.file }.toSet().size == softTakes.size) { "the same take can't fill two zones" }
        val pad = kit.pad(slot) ?: throw IllegalArgumentException("no pad on slot $slot")
        require(!PrintedPads.has(pad.recipe)) { Copy.boardRemoveBeforeStack(PrintedPads.label(pad.recipe)) }
        require(pad.velocityLayers.isEmpty()) { "pad $slot is already velocity-layered - `clearGhostLayers($slot)` before stacking" }
        requireNotChained(pad, "stacking")
        // The entries must be THIS kit's bin's: `originalName` alone is a
        // label anyone could forge, and copying a file from anywhere else
        // into the kit folder is not what "a prior take" means.
        val binDir = File(kitDir, BIN_DIR).canonicalFile
        for (t in softTakes) {
            require(t.originalName == pad.sampleFile) { "${t.file.name} is a take of ${t.originalName}, not of pad $slot's ${pad.sampleFile}" }
            require(t.file.canonicalFile.parentFile == binDir) { "${t.file.name} isn't in this kit's bin" }
            require(t.file.isFile) { "${t.file.name} isn't in the bin any more" }
        }
        val windows = StackTakes.windows(softTakes.size)
        val names = freeLayerNames(pad.sampleStem, softTakes.size)
        val layers = buildList {
            softTakes.forEachIndexed { v, take ->
                take.file.copyTo(File(kitDir, names[v]), overwrite = false)
                add(com.snipsnap.kit.KitLayer(names[v], windows[v].first, windows[v].last))
            }
            add(com.snipsnap.kit.KitLayer(pad.sampleFile, windows.last().first, windows.last().last))
        }
        return update(slot) { it.copy(velocityLayers = layers) }
    }

    /**
     * [count] layer filenames `${stem}_vN.wav` that nothing in the kit
     * references and nothing on disk occupies, lowest N first. Bounded
     * like [nextStem]: past [MAX_STEM_ATTEMPTS] candidates this fails
     * loudly rather than hanging an onClick.
     */
    private fun freeLayerNames(stem: String, count: Int): List<String> {
        val referenced = kit.pads
            .flatMap { listOf(it.sampleFile) + it.velocityLayers.map { l -> l.sampleFile } }
            .map { it.lowercase() }
            .toSet()
        val out = mutableListOf<String>()
        var n = 1
        while (out.size < count && n <= MAX_STEM_ATTEMPTS) {
            val name = "${stem}_v$n.wav"
            if (name.lowercase() !in referenced && !File(kitDir, name).exists()) out += name
            n++
        }
        require(out.size == count) { "no free layer name for $stem after $MAX_STEM_ATTEMPTS candidates" }
        return out
    }

    /**
     * The FX rack pointed at one pad: re-render its sample through a named
     * treatment (reversed / crushed / slapback / washed / punched), the
     * original safely in the bin, the fx-only recipe recorded. Treatments
     * stack; [untreatPad] pops the most recent one.
     */
    fun treatPad(slot: Int, treatment: String, amount: Float = 1f): KitPad {
        val pad = kit.pad(slot) ?: throw IllegalArgumentException("no pad on slot $slot")
        require(pad.velocityLayers.isEmpty()) {
            "pad $slot is velocity-layered - `clearGhostLayers($slot)` before treating"
        }
        requireNotChained(pad, "treating")
        val original = com.snipsnap.audio.WavReader.read(File(kitDir, pad.sampleFile))
        val treated = com.snipsnap.synth.Treatments.apply(treatment, original, amount)

        moveToBin(pad.sampleFile)
        WavWriter.write(File(kitDir, pad.sampleFile), treated.snip)
        return update(slot) { it.copy(recipe = treated.recipe) }
    }

    /**
     * Rewrite one pad's audio through [transform], bin-backed like every
     * treatment — the mix doctor's fixes and future processors all use
     * this one door. Layered pads are refused: a transform tuned on the
     * loud zone would lie on the soft ones.
     */
    fun replaceAudio(
        slot: Int,
        recipe: com.snipsnap.json.JsonValue.Obj?,
        transform: (Snip) -> Snip,
    ): KitPad {
        val pad = requireRewritable(slot)
        val original = com.snipsnap.audio.WavReader.read(File(kitDir, pad.sampleFile))
        val processed = transform(original)
        require(processed.frameCount > 0) { "a rewrite must leave audio behind" }
        moveToBin(pad.sampleFile)
        WavWriter.write(File(kitDir, pad.sampleFile), processed)
        // The imported audition's fingerprint describes the previous WAV.
        // Keep its provenance, but never carry that current-file claim onto
        // regenerated or treated audio; exporters fingerprint the new file.
        // An unlabelled audio replacement also cannot retain a printed inventory:
        // its hashes describe the old sound. Other legacy recipes keep the
        // existing preserve-on-null behavior; an explicit new recipe wins.
        return update(slot) {
            it.copy(
                recipe = recipe ?: it.recipe?.takeUnless(PrintedPads::has),
                source = it.source - "rawSha256",
            )
        }
    }

    /**
     * DUST: the tape's own hiss and room under one pad (`docs/DUST.md`),
     * [amount] 0..1 through `com.snipsnap.audio.Dust.apply` with [print],
     * the dust of [tape] (a bare file name on the SNIPS shelf, recorded
     * in the recipe so DO IT AGAIN and the pad sheet can name it). The
     * same shape as [smearPad] in every rule: restore-first when the bin
     * holds the original (so re-dusting, or dusting an aged pad, never
     * stacks), amount 0 takes an existing dust off and touches an
     * undusted pad not at all, layers and chains refused, bin-backed
     * through [replaceAudio].
     */
    fun dustPad(slot: Int, amount: Float, tape: String, print: com.snipsnap.audio.Dust.Print?): KitPad {
        require(amount in 0f..1f) { "amount is 0..1, got $amount" }
        require(amount <= 0f || print != null) { "dusting at $amount needs the tape's print" }
        require('/' !in tape && '\\' !in tape) { "tape is a bare file name: '$tape'" }
        val pad = kit.pad(slot) ?: throw IllegalArgumentException("no pad on slot $slot")
        require(pad.velocityLayers.isEmpty()) { "pad $slot is velocity-layered - `clearGhostLayers($slot)` before dusting" }
        requireNotChained(pad, "dusting")
        val dustedNow = PadSheet.readDust(pad.recipe) != null
        val restorable =
            PadSheet.unTreatState(pad, binContents().map { it.originalName }.toSet()) == PadSheet.UnTreat.READY
        val current = if (restorable && (amount > 0f || dustedNow)) untreatPad(slot) else pad
        if (amount <= 0f) return current
        val recipe = com.snipsnap.json.JsonValue.Obj(
            linkedMapOf<String, com.snipsnap.json.JsonValue>(
                "verb" to com.snipsnap.json.JsonValue.Str("dust"),
                "amount" to com.snipsnap.json.JsonValue.Num(amount.toDouble()),
                "tape" to com.snipsnap.json.JsonValue.Str(tape),
            ),
        )
        return replaceAudio(slot, recipe) { snip -> com.snipsnap.audio.Dust.apply(snip, print!!, amount) }
    }

    /**
     * TAPE SPLICE's own candidate list for one pad: every recoverable
     * prior take of its file, newest first — [binContents] filtered to
     * just this pad's own history. The live sample itself isn't in this
     * list; it's always an implicit, always-available candidate the UI
     * adds on its own.
     */
    fun priorTakes(slot: Int): List<BinEntry> {
        val pad = kit.pad(slot) ?: throw IllegalArgumentException("no pad on slot $slot")
        return binContents().filter { it.originalName == pad.sampleFile }
    }

    /**
     * SPLICE: joins [head]'s first [headFrames] frames to [tail]'s frames
     * from [tailFrames] onward — [com.snipsnap.audio.TapeSplice.join]'s own
     * seam-aware crossfade, baked in only when the raw cut would click.
     * Bin-backed like every rewrite ([replaceAudio]): whatever's live now
     * is itself binned first, so the splice is undoable from THE BIN like
     * any other pad rewrite.
     */
    fun splicePad(slot: Int, head: Snip, headFrames: Int, tail: Snip, tailFrames: Int): com.snipsnap.audio.TapeSplice.Spliced {
        val spliced = com.snipsnap.audio.TapeSplice.join(head, headFrames, tail, tailFrames)
        val recipe = com.snipsnap.json.JsonValue.Obj(
            linkedMapOf<String, com.snipsnap.json.JsonValue>(
                "splice" to com.snipsnap.json.JsonValue.Obj(
                    linkedMapOf<String, com.snipsnap.json.JsonValue>(
                        "crossfaded" to com.snipsnap.json.JsonValue.Bool(spliced.crossfaded),
                    ),
                ),
            ),
        )
        replaceAudio(slot, recipe) { spliced.snip }
        return spliced
    }

    /**
     * SMEAR: the pad through `Smear.process` at [amount], through the
     * generic [replaceAudio] door — not an era, so not `eraPad`; the
     * recipe is the `{"verb":"smear","amount"}` idiom [PadSheet.readSmear]
     * reads. [replaceAudio] always reads the current on-disk audio, so a
     * pad already wearing its own SMEAR is restored first (the previous
     * take back out of the bin) rather than smeared twice over. AMT 0 on
     * an unsmeared pad touches nothing. Lifted from PAD SHEET's inline
     * rewrite so DO IT AGAIN can replay it; the screen calls this now.
     */
    fun smearPad(slot: Int, amount: Float): KitPad {
        require(amount in 0f..1f) { "amount is 0..1, got $amount" }
        val pad = kit.pad(slot) ?: throw IllegalArgumentException("no pad on slot $slot")
        require(pad.velocityLayers.isEmpty()) { "pad $slot is velocity-layered - `clearGhostLayers($slot)` before smearing" }
        requireNotChained(pad, "smearing")
        // The mirror of the era branch's own guard, and the other half of
        // September UAT finding 20: this used to ask `readSmear` alone, so
        // smearing an *aged* pad stacked the stretch onto the aged audio and
        // then wrote SMEAR's recipe over the era's - the card naming one
        // treatment while the file carried two. `unTreatState` reads both
        // shapes. Layers are already refused above, so `untreatPad`'s
        // single-file restore is the whole pad here.
        //
        // `amount > 0f || smearedNow` is what keeps AMT 0 from becoming
        // destructive outside this family. At 0 nothing is being smeared, so
        // there is nothing for a restore to make room for: an era or a
        // character must be left exactly as it is. A pad that IS smeared
        // still comes back at 0 - that is what "no smear" has always meant
        // here, and it predates the widened guard. NONE is the explicit way
        // to take any treatment off (finding 13); a slider must not do it
        // silently on the way past zero.
        val smearedNow = PadSheet.readSmear(pad.recipe) != null
        val restorable =
            PadSheet.unTreatState(pad, binContents().map { it.originalName }.toSet()) == PadSheet.UnTreat.READY
        val current = if (restorable && (amount > 0f || smearedNow)) untreatPad(slot) else pad
        if (amount <= 0f) return current
        val recipe = com.snipsnap.json.JsonValue.Obj(
            linkedMapOf<String, com.snipsnap.json.JsonValue>(
                "verb" to com.snipsnap.json.JsonValue.Str("smear"),
                "amount" to com.snipsnap.json.JsonValue.Num(amount.toDouble()),
            ),
        )
        return replaceAudio(slot, recipe) { snip ->
            val smeared = com.snipsnap.audio.Smear.process(snip, amount)
            // Smear folds to mono; a stereo pad stays stereo on disk so
            // its format never changes underneath TAPE SPLICE's own check.
            if (snip.channels == 2 && smeared.channels == 1) {
                val stereo = FloatArray(smeared.frameCount * 2)
                for (i in 0 until smeared.frameCount) {
                    stereo[i * 2] = smeared.samples[i]
                    stereo[i * 2 + 1] = smeared.samples[i]
                }
                Snip(stereo, 2, smeared.sampleRate)
            } else {
                smeared
            }
        }
    }

    /** DE-SAMPLE's honest refusal: the nearest patch is a stranger; the match says how far. */
    class Far(val match: com.snipsnap.synth.Desample.Match) :
        IllegalArgumentException(
            "no patch is near: the nearest is ${match.patch.engine.lowercase()}/${match.patch.voiceName.lowercase()} " +
                "at distance %.2f".format(java.util.Locale.ROOT, match.distance),
        )

    /**
     * DE-SAMPLE: the pad replaced by the nearest synth patch's own render,
     * the patch riding the pad as its recipe so the sound is a synth pad
     * from here on - bin-backed like every rewrite. The search starts on
     * the voices kindred to the pad's class. A far match ([Far]) is
     * refused unless [evenIfFar]; the match is returned either way it
     * goes ahead.
     */
    fun desamplePad(slot: Int, evenIfFar: Boolean = false): com.snipsnap.synth.Desample.Match {
        val pad = kit.pad(slot) ?: throw IllegalArgumentException("no pad on slot $slot")
        val original = com.snipsnap.audio.WavReader.read(File(kitDir, pad.sampleFile))
        val match = com.snipsnap.synth.Desample.nearest(
            original,
            voices = com.snipsnap.synth.Desample.voicesFor(pad.drumClass),
            name = pad.displayName,
        )
        if (match.far && !evenIfFar) throw Far(match)
        val recipe = com.snipsnap.synth.PadRecipe(patch = match.patch).toJsonValue()
        replaceAudio(slot, recipe) { match.patch.render() }
        update(slot) { it.copy(source = it.source + mapOf("desampled" to "%.2f".format(java.util.Locale.ROOT, match.distance))) }
        return match
    }

    /**
     * Age one pad through a Time Machine era — every file it references
     * (velocity layers included, unlike single-sample treatments: an era is
     * whole-kit character, so a layered snare ages in all its zones).
     * Originals go to the bin; the era recipe rides the pad.
     */
    fun eraPad(slot: Int, era: String, amount: Float = 1f): KitPad {
        val pad = kit.pad(slot) ?: throw IllegalArgumentException("no pad on slot $slot")
        if (amount <= 0f) return pad
        return rewriteEveryFile(pad, "aging") { original ->
            val aged = com.snipsnap.synth.Eras.apply(era, original, amount)
            aged.snip to aged.recipe
        }
    }

    /**
     * One of the rack's named characters (`Treatments.names`) over every
     * file the pad references — the pad sheet's second row. The same door
     * shape as [eraPad]: layers included, originals binned, the fx-only
     * recipe (name + AMT) riding the pad — where [treatPad] rewrites one
     * sample and refuses layers, this is whole-pad character. AMT 0 is a
     * no-op that touches neither the file nor the bin. Undo is
     * [unEraPad], the same "every file back out of the bin" either row needs.
     */
    fun characterPad(slot: Int, character: String, amount: Float = 1f): KitPad {
        val pad = kit.pad(slot) ?: throw IllegalArgumentException("no pad on slot $slot")
        // Validate the name before the AMT-0 exit: a typo must not read as "no treatment".
        com.snipsnap.synth.Treatments.chain(character, amount)
        if (amount <= 0f) return pad
        return rewriteEveryFile(pad, "treating") { original ->
            val treated = com.snipsnap.synth.Treatments.apply(character, original, amount)
            treated.snip to treated.recipe
        }
    }

    /**
     * The pedal library's board over every file the pad references
     * (docs/superpowers/specs/2026-10-21-pedal-library-and-board-design.md):
     * the picks rendered as passes around the amp from the audio on disk,
     * originals binned, the `pedals` recipe riding the pad. The same door
     * shape as [characterPad]; the caller restores first ([unEraPad]) when
     * the bin can, so picks never stack, as the sheet does for a chip. A
     * board at AMT 0 everywhere is a no-op that touches neither the file
     * nor the bin. A WIDE (or any compound) board pad refuses, as it refuses
     * a chip: the two boards do not share a key or a store. Undo is [unEraPad].
     */
    fun pedalPad(slot: Int, board: com.snipsnap.synth.PedalBoard): KitPad {
        val pad = kit.pad(slot) ?: throw IllegalArgumentException("no pad on slot $slot")
        BoardSheet.read(pad.recipe)?.let {
            throw IllegalArgumentException("pad $slot carries a ${it.type.name} board - remove it before adding pedals")
        }
        if (board.isBypass) return pad
        // A synth pad that still carries its patch is re-rendered from it with the pedals around its own
        // landing chain, the pedals in front of the amp (docs/superpowers/specs/2026-10-27-pedals-in-front-
        // of-the-amp-design.md), and keeps the patch so the next tap, undo and replay can do it again. A
        // velocity-layered pad keeps the old way: its soft layers are softened copies of the main take, not
        // renders of the patch, so they could not follow the main file in front of the amp.
        synthRecipeForPedals(pad)?.let { recipe ->
            val withPedals = recipe.copy(pedals = board)
            return rewriteEveryFile(pad, "pedals") { _ -> withPedals.render() to withPedals.toJsonValue() }
        }
        return rewriteEveryFile(pad, "pedals") { original ->
            board.process(original) to com.snipsnap.synth.PadRecipe(pedals = board).toJsonValue()
        }
    }

    /**
     * Whether pad [slot]'s pedals sit in front of its own amp: the PEDALS card draws the pad's amp
     * between the AMP and POST passes only when this is true
     * (docs/superpowers/specs/2026-10-30-pad-pedalboard-card-design.md). A pad that carries pedals
     * answers from its recipe ([PedalSlate.aroundTheAmp]); one that carries none answers where
     * [pedalPad] would put the next one, which reads its file. False for an empty slot.
     */
    fun pedalsAroundTheAmp(slot: Int): Boolean {
        val pad = kit.pad(slot) ?: return false
        PedalSlate.aroundTheAmp(pad.recipe)?.let { return it }
        return synthRecipeForPedals(pad)?.fx?.isBypass == false
    }

    /**
     * The pad's synth recipe with no pedals on it, when the pedals can go in front of its amp: a patch, no
     * velocity layers, no named treatment, no compound board, and the recipe still renders the file on disk.
     * That last check matters because an audio replacement with no recipe keeps the old one
     * ([replaceAudio]'s preserve-on-null), so a patch can outlive the sound it made; re-rendering it then
     * would throw the pad's real audio away. Null for every other pad, which keeps the after-the-amp way.
     */
    private fun synthRecipeForPedals(pad: KitPad): com.snipsnap.synth.PadRecipe? {
        if (pad.velocityLayers.isNotEmpty()) return null
        val recipe = Breed.recipeOf(pad) ?: return null
        if (recipe.patch == null || recipe.treatment != null || recipe.board != null) return null
        val plain = recipe.copy(pedals = null)
        val onDisk = com.snipsnap.audio.WavReader.read(File(kitDir, pad.sampleFile))
        return plain.takeIf { rendersTo(it.render(), onDisk) }
    }

    /** The same sound up to the WAV's own quantization: same shape, no sample further apart than [RENDER_MATCH]. */
    private fun rendersTo(rendered: Snip, file: Snip): Boolean {
        if (rendered.channels != file.channels || rendered.sampleRate != file.sampleRate) return false
        if (rendered.frameCount != file.frameCount) return false
        for (i in rendered.samples.indices) if (kotlin.math.abs(rendered.samples[i] - file.samples[i]) > RENDER_MATCH) return false
        return true
    }

    /** The keyed family's honest refusal: the pad is a drum or noise, not a note. */
    class Unpitched(message: String) : IllegalArgumentException(message)

    /** What TUNE snaps to: the kit's key, or every semitone when none is set. */
    fun retuneKey(): com.snipsnap.audio.KeySpec = kit.key ?: Keyed.NO_KEY

    /** "C MAJOR", or "THE NEAREST SEMITONES" when no key is set — for the toast and the recipe. */
    fun retuneKeyLabel(): String = kit.key?.label?.uppercase() ?: Keyed.NO_KEY_LABEL

    /** TUNE: [keyedPad] with the retune. */
    fun retunePad(slot: Int, amount: Float = 1f, seed: Long = 0): KitPad = keyedPad(slot, "retuned", amount, seed)

    /**
     * The keyed family ([Keyed.NAMES]) over one pad: the treatment reads
     * the kit's key (or does without, its own way) and rewrites every
     * file the pad references, bin-backed like every treatment. AMT is
     * how far; 0 leaves the pad as it is. A refusal ([Unpitched], in the
     * treatment's own words) comes before anything is touched.
     */
    fun keyedPad(slot: Int, name: String, amount: Float = 1f, seed: Long = 0, dials: Keyed.Dials = Keyed.Dials()): KitPad {
        val pad = kit.pad(slot) ?: throw IllegalArgumentException("no pad on slot $slot")
        Keyed.require(name)
        require(amount in 0f..1f) { "amount is 0..1, got $amount" }
        if (amount <= 0f) return pad
        val context = keyedContext()
        val main = com.snipsnap.audio.WavReader.read(File(kitDir, pad.sampleFile))
        Keyed.refusal(name, main, context, amount, dials)?.let { throw Unpitched(it) }
        var label = ""
        val rewritten = rewriteEveryFile(pad, "keyed treatment") { original ->
            val done = try {
                Keyed.apply(name, original, context, amount, seed, dials)
            } catch (e: Keyed.Refused) {
                throw Unpitched(e.message ?: "not a note")
            }
            label = done.keyLabel
            done.snip to com.snipsnap.json.JsonValue.Obj(
                linkedMapOf<String, com.snipsnap.json.JsonValue>(
                    "keyed" to com.snipsnap.json.JsonValue.Str(name),
                    "key" to com.snipsnap.json.JsonValue.Str(label),
                    "amount" to com.snipsnap.json.JsonValue.Num(amount.toDouble()),
                    "seed" to com.snipsnap.json.JsonValue.Num(seed.toDouble()),
                    "decay" to com.snipsnap.json.JsonValue.Num(dials.decay.toDouble()),
                    "division" to com.snipsnap.json.JsonValue.Str(dials.division),
                    "tail" to com.snipsnap.json.JsonValue.Num((dials.tail ?: com.snipsnap.audio.Eternal.tailFor(amount)).toDouble()),
                    "knee" to com.snipsnap.json.JsonValue.Num(dials.knee.toDouble()),
                ),
            )
        }
        lastKeyLabel = label
        return rewritten
    }

    /** What the keyed family reads off this kit: its key (maybe none) and its tempo (the preview's default without one). */
    fun keyedContext(): Keyed.Context = Keyed.Context(kit.key, kit.tempoBpm ?: com.snipsnap.kit.KitPreview.DEFAULT_BPM)

    /** The key label the last [keyedPad] read — what its toast names. */
    var lastKeyLabel: String = ""
        private set

    /** The whole-pad rewrite both [eraPad] and [characterPad] share: every referenced file, bin-backed, one recipe. */
    private fun rewriteEveryFile(
        pad: KitPad,
        doing: String,
        transform: (Snip) -> Pair<Snip, com.snipsnap.json.JsonValue.Obj>,
    ): KitPad {
        requireNotChained(pad, doing)
        val files = (listOf(pad.sampleFile) + pad.velocityLayers.map { it.sampleFile }).distinct()
        var recipe: com.snipsnap.json.JsonValue.Obj? = null
        for (f in files) {
            val original = com.snipsnap.audio.WavReader.read(File(kitDir, f))
            val (rewritten, r) = transform(original)
            moveToBin(f)
            WavWriter.write(File(kitDir, f), rewritten)
            recipe = r
        }
        return update(pad.slot) { it.copy(recipe = recipe) }
    }

    /**
     * The whole kit through one era — the Time Machine's main gesture.
     * Returns how many pads aged. [slots] narrows it; null means every pad.
     */
    fun eraKit(era: String, amount: Float = 1f, slots: List<Int>? = null): Int {
        val targets = kit.pads.map { it.slot }.filter { slots == null || it in slots }
        require(targets.isNotEmpty()) { "no pads to age" }
        if (amount <= 0f) return 0
        targets.forEach { eraPad(it, era, amount) }
        return targets.size
    }

    /** Undo an era or a character on one pad: every file it references comes back out of the bin. */
    fun unEraPad(slot: Int): KitPad {
        val pad = kit.pad(slot) ?: throw IllegalArgumentException("no pad on slot $slot")
        requireNotChained(pad, "un-aging")
        val files = (listOf(pad.sampleFile) + pad.velocityLayers.map { it.sampleFile }).distinct()
        var restoredAny = false
        for (f in files) {
            if (restoreFromBin(f) != null) restoredAny = true
        }
        require(restoredAny) { "nothing to restore for pad $slot - the bin holds no earlier take of it" }
        return update(slot) { it.copy(recipe = recipeAfterUndo(pad)) }
    }

    /**
     * What a pad's recipe is once its treatment is undone. Pedals on a synth pad kept its patch, so undo gives
     * the pad back its patch recipe rather than nothing: the sheet's restore-then-apply, the next pedal tap and
     * the CLI's `--undo` can still render in front of the amp. Every other undo clears the recipe, as before.
     */
    private fun recipeAfterUndo(pad: KitPad): com.snipsnap.json.JsonValue.Obj? =
        Breed.recipeOf(pad)?.takeIf { it.patch != null && it.pedals != null }?.copy(pedals = null)?.toJsonValue()

    /** Undo the last treatment: the previous audio comes back out of the bin. */
    fun untreatPad(slot: Int): KitPad {
        val pad = kit.pad(slot) ?: throw IllegalArgumentException("no pad on slot $slot")
        requireNotChained(pad, "un-treating")
        restoreFromBin(pad.sampleFile)
            ?: throw IllegalArgumentException("nothing to restore for pad $slot - the bin holds no earlier take of it")
        return update(slot) { it.copy(recipe = recipeAfterUndo(pad)) }
    }

    /** Back to a single-sample pad; the soft renders are deleted. */
    fun clearGhostLayers(slot: Int): KitPad {
        val pad = kit.pad(slot) ?: throw IllegalArgumentException("no pad on slot $slot")
        require(!PrintedPads.has(pad.recipe)) { Copy.boardRemoveBeforeSoftHits(PrintedPads.label(pad.recipe)) }
        val softFiles = pad.velocityLayers.map { it.sampleFile }.filter { it != pad.sampleFile }
        val cleared = update(slot) { it.copy(velocityLayers = emptyList()) }
        softFiles.forEach { File(kitDir, it).delete() }
        return cleared
    }

    // ---------- the wear ledger ----------

    /**
     * Opt the kit into aging. An existing ledger keeps its mileage — the
     * tape remembers even while the deck was off. [k] retunes the curve
     * when given; null keeps what the ledger has.
     */
    fun enableWear(k: Double? = null) {
        kit = kit.copy(
            wear = kit.wear?.copy(enabled = true, k = k ?: kit.wear!!.k)
                ?: com.snipsnap.kit.WearLedger(k = k ?: com.snipsnap.kit.WearLedger.DEFAULT_K),
        )
        dirty = true
    }

    /** Aging off; the ledger (and its mileage) is kept, just not applied. */
    fun disableWear() {
        val wear = kit.wear ?: return
        kit = kit.copy(wear = wear.copy(enabled = false))
        dirty = true
    }

    /** Wipe the mileage — and because wear never rewrites audio, that IS a new tape. */
    fun resetWear() {
        val wear = kit.wear ?: return
        kit = kit.copy(wear = wear.copy(mileage = 0.0))
        dirty = true
    }

    /** The app's play hook: every pad hit or preview spin logs mileage. */
    fun recordPlays(count: Int = 1) {
        require(count > 0) { "plays must be positive, got $count" }
        val wear = kit.wear?.takeIf { it.enabled } ?: return
        kit = kit.copy(wear = wear.copy(mileage = wear.mileage + count))
        dirty = true
    }

    /**
     * Write `kit.json`. The moment the folder and the model agree again.
     * The outgoing `kit.json` is archived as a take first — every save is
     * a point you can roll back to. A save that persists real edits is a
     * pass of the tape too: when the ledger is on, it accrues a mile.
     * [accrueWear] false is for ledger management itself — resetting the
     * mileage must not put the first mile straight back on.
     *
     * Refuses to write if [kitDir] no longer exists: `KitStore.save`
     * unconditionally `mkdirs()`s its target, so a caller holding a model
     * whose folder was renamed or deleted out from under it (a stale
     * dispose-time flush racing a rename, most concretely — see
     * `PadSheetScreen`'s teardown `DisposableEffect`) would otherwise
     * resurrect a ghost directory containing nothing but this `kit.json`.
     * Every caller that legitimately creates a *new* kit ([create],
     * [fromChop]) already `mkdirs()`s [kitDir] before its first [save], so
     * this never fires for them.
     */
    fun save(accrueWear: Boolean = true): File {
        require(kitDir.isDirectory) { "kit folder no longer exists: $kitDir" }
        if (accrueWear && dirty) {
            kit.wear?.takeIf { it.enabled }?.let {
                kit = kit.copy(wear = it.copy(mileage = it.mileage + 1))
            }
        }
        archiveTake()
        val file = KitStore.save(kit, kitDir)
        dirty = false
        return file
    }

    // ---------- takes ----------

    /**
     * Archived takes, oldest first. A take that won't parse — a process
     * killed mid-archive leaves exactly that — is skipped, not surfaced: a
     * torn entry must never break the history or a rollback.
     */
    fun takes(): List<File> =
        File(kitDir, TAKES_DIR).listFiles { f: File -> TAKE_NAME.matches(f.name) }
            ?.sortedBy { it.name }
            ?.filter {
                try {
                    KitStore.read(it); true
                } catch (e: Exception) {
                    false
                }
            } ?: emptyList()

    /**
     * Why TAKES and THE BIN cohabit on one screen (name-and-find
     * followups) despite being named as two separate things now
     * (`TakesBinScreen.kt`'s own header/card titles): they are genuinely
     * different data with different lifecycles — TAKES is `kit.json`'s own
     * save history, count-capped at [MAX_TAKES] and never time-expired
     * (nothing is ever deleted to create one, see [archiveTake]); THE BIN
     * is ejected/displaced pad AUDIO, kept [BIN_KEEP_DAYS] days unless
     * a pad still needs it to undo ([binHolds]), and independently
     * deletable early ([emptyBin]) — but a take is JSON, not audio: it
     * captures pad settings and sample FILENAMES, never sample BYTES.
     * THE BIN is the app's only other record of what a filename's
     * bytes actually were at a past moment, which is exactly
     * what THIS function needs to make "roll back to take T" mean what a
     * user expects — the pad sounding the way it did then, not merely
     * carrying the right slot/name/tune settings while silently pointing
     * at whatever audio happens to occupy that filename NOW (see
     * [restoreLiveAudioAsOf] below, which reads the bin as its audio
     * source of truth for exactly this reason). Severing that coupling —
     * making TAKES restore `kit.json` alone — would silently break audio
     * rollback while leaving `kit.json` rollback looking like it still
     * worked; the shared screen and this cross-read are load-bearing, not
     * an artifact of the two once having had one name.
     *
     * Roll back to an archived take. This restores `kit.json` *and* the
     * actual audio that was live when the take was archived — not
     * whatever happens to be sitting on the filename now. A take file's
     * `lastModified()` is used as its archive time T: [AtomicFile] writes
     * every take through a temp file (fsynced) and then an atomic rename
     * over the destination, and on every filesystem this project targets
     * that rename carries the temp file's own fresh-write mtime forward
     * rather than resetting it — so `take.lastModified()` is a faithful T,
     * not an artifact of the rename. (Verified: APFS gives sub-millisecond
     * resolution in dev; every POSIX target this ships to does likewise or
     * better.) [AtomicFile]'s non-atomic fallback (some filesystems throw
     * `AtomicMoveNotSupportedException`, and it falls back to a plain
     * move) isn't guaranteed to carry the same mtime, so T could in theory
     * drift by a millisecond or two there — [archiveTake]'s own
     * monotonicity guard (below) neutralizes most of that risk anyway,
     * since it re-pins T relative to the bin regardless of which move
     * path wrote the file.
     *
     * For each file a restored pad references: the bin already timestamps
     * every rewrite it archives ([BinEntry.binnedAtMillis], via
     * [moveToBin]), so the bin doubles as that file's own history. Any
     * entry binned at-or-after T is audio that displaced what was live at
     * T; the OLDEST such entry is the one that WAS live at T (anything
     * newer is a later displacement the take doesn't want either). The
     * selection is deliberately inclusive (`>=`, not `>`): with
     * [archiveTake]'s monotonicity guard in place, a bin entry timestamped
     * exactly T can only be a *post*-archive displacement (nothing already
     * in the bin at archive time can share T), so including it is always
     * correct — a treat's own moveToBin landing in the same millisecond as
     * the very next save can no longer masquerade as that entry's earlier
     * self. That entry's bytes are COPIED onto the live file — not
     * consumed the way [restoreFromBin] normally deletes on restore —
     * because jumping between takes must stay non-destructive: an
     * even-older take restored later may need that exact same entry, and a
     * take-restore reads history, it doesn't spend it. Before the copy
     * lands, whatever is currently live is itself binned first (unless
     * it's already byte-identical to the candidate — restoring the same
     * take twice must not bin a redundant copy of audio that's already
     * correct), so the audio this restore displaces isn't lost either —
     * restoring a take is itself undoable by pulling that fresh bin entry
     * back out ([restoreFromBin]). The candidate's bytes are read into
     * memory before that pre-overwrite bin happens, not copied by path
     * afterward: [moveToBin] is now collision-proof on its own (see its
     * KDoc), but reading first means this function's correctness never
     * again depends on that — nothing it does afterward can change what
     * gets written back.
     *
     * If nothing was binned since T, the live file was never rewritten and
     * is already the right audio — left untouched, now correct by argument
     * instead of by accident. If the file is missing entirely and no
     * post-T entry exists, the old newest-first fallback
     * ([restoreFromBin] by name) still applies.
     *
     * Honesty about the bin's own limit: it only keeps history
     * [BIN_KEEP_DAYS] days ([purgeBin]). A take older than that horizon may
     * find its history already purged for some of its files — those pads
     * restore with whatever audio is currently live, the same honest
     * degradation as the missing-file fallback, not a crash or a lie.
     */
    fun restoreTake(take: File): Kit {
        val restored = KitStore.read(take)
        val archivedAtMillis = take.lastModified()
        for (pad in restored.pads) {
            val files = listOf(pad.sampleFile) + pad.velocityLayers.map { it.sampleFile }
            for (f in files) {
                restoreLiveAudioAsOf(f, archivedAtMillis)
            }
        }
        kit = restored
        dirty = true
        return restored
    }

    /**
     * The audio-fidelity core of [restoreTake] for one file: find the bin
     * entry that was live at [archivedAtMillis] and copy it onto the live
     * file (binning whatever's live first, so that's undoable too). See
     * [restoreTake]'s KDoc for the full reasoning — copy-don't-consume in
     * particular.
     */
    private fun restoreLiveAudioAsOf(fileName: String, archivedAtMillis: Long) {
        val candidate = binContents()
            .filter { it.originalName == fileName && it.binnedAtMillis >= archivedAtMillis }
            .minByOrNull { it.binnedAtMillis }
        if (candidate != null) {
            // Read the candidate's bytes into memory BEFORE touching
            // anything else on disk. [moveToBin] below is itself now
            // collision-proof (never overwrites an existing bin file), but
            // reading first removes any dependency on that guarantee here
            // too: nothing this function does afterward can change what
            // gets written back, even if some other path someday binned
            // under this exact name at this exact millisecond.
            val candidateBytes = candidate.file.readBytes()
            val live = File(kitDir, fileName)
            // Already true: a second restore of the same take (or of two
            // takes that share this file's history) must not keep binning
            // identical bytes forever - compare before touching anything.
            if (live.isFile && live.readBytes().contentEquals(candidateBytes)) return
            moveToBin(fileName) // what's live now becomes history too - a no-op if the file is missing
            live.writeBytes(candidateBytes) // copy, not consume - see restoreTake KDoc
            return
        }
        if (!File(kitDir, fileName).isFile) restoreFromBin(fileName)
    }

    private fun archiveTake() {
        val current = File(kitDir, KitStore.FILE_NAME)
        // A clean save changes nothing; archiving it would duplicate takes.
        if (!current.isFile || !dirty) return
        val takesDir = File(kitDir, TAKES_DIR).apply { mkdirs() }
        val next = (takes().lastOrNull()?.let { TAKE_NAME.find(it.name)!!.groupValues[1].toInt() } ?: 0) + 1
        // Locale.ROOT: this name is parsed back by TAKE_NAME, whose \d expects ASCII digits.
        val takeFile = File(takesDir, String.format(Locale.ROOT, "take_%03d.json", next))
        com.snipsnap.kit.AtomicFile.writeBytes(takeFile, current.readBytes())
        // A take's T must be strictly later than every bin event that
        // produced the state it snapshots; same-millisecond flash writes
        // otherwise make the tie ambiguous in both directions (see
        // restoreTake's KDoc on the >= selection).
        val newestBinned = binContents().maxOfOrNull { it.binnedAtMillis }
        if (newestBinned != null && takeFile.lastModified() <= newestBinned) {
            takeFile.setLastModified(newestBinned + 1)
        }
        // Rotate: the cap outlasts any honest session; oldest go first.
        takes().dropLast(MAX_TAKES).forEach { it.delete() }
    }

    // ---------- the bin ----------

    /**
     * What's recoverable: file name it had, when it was binned, its bin
     * file. [padSnapshot], when present, is the whole pad this file was
     * ejected from (via [clear]) — read back from the bin's paired JSON
     * tombstone (see [moveToBin]) — and is what lets [restoreFromBin]
     * reinstate the pad itself, not just its audio. Every eject writes a
     * tombstone, but only a pad that can come back from this one file (a
     * single-sample pad that played it, with no chain) reads back as a
     * [padSnapshot]; a layered or chained pad's reads back as null.
     */
    data class BinEntry(val originalName: String, val binnedAtMillis: Long, val file: File, val padSnapshot: KitPad? = null)

    /**
     * Recoverable deletes, newest first. The listing predicate excludes
     * tombstone sidecars (`..._something.wav.json`) themselves — [BIN_NAME]
     * is loose enough to match one, and without the exclusion each would
     * double-count as a bogus entry of its own. For each real entry, a
     * paired tombstone is read back if present; a corrupt or unparseable
     * one degrades to `padSnapshot = null` rather than breaking the whole
     * listing — the same "degrade honestly, never crash" posture the rest
     * of this file keeps around torn/partial state (see [takes]). A
     * tombstone holding a pad this file alone cannot bring back (layers, a
     * chain, or a different main file) also reads back as null: it still
     * marks the entry as an eject for [binHolds], which asks whether the
     * file is there, but BACK restores only the audio.
     */
    fun binContents(): List<BinEntry> =
        File(kitDir, BIN_DIR).listFiles { f: File -> BIN_NAME.matches(f.name) && !f.name.endsWith(".json", ignoreCase = true) }
            ?.map {
                val m = BIN_NAME.find(it.name)!!
                val tombstone = File(it.parentFile, "${it.name}.json")
                val padSnapshot = if (tombstone.isFile) {
                    runCatching { KitStore.padFromJson(Json.parse(tombstone.readText(Charsets.UTF_8))) }.getOrNull()
                        ?.takeIf { pad -> pad.sampleFile == m.groupValues[2] && pad.velocityLayers.isEmpty() && pad.chain == null }
                } else {
                    null
                }
                BinEntry(m.groupValues[2], m.groupValues[1].toLong(), it, padSnapshot)
            }
            ?.sortedByDescending { it.binnedAtMillis } ?: emptyList()

    /**
     * A specific bin entry back into the kit, or null if it's already gone
     * (restored or purged by something else since the caller listed it).
     * The entry-keyed overload exists because [originalName] alone is
     * ambiguous: `treatPad`/`eraPad` can bin several copies of the same
     * filename (each treat-then-rewrite cycle bins the previous version
     * under that same name), and a caller holding a specific [BinEntry] —
     * e.g. a screen listing bin rows, each with its own countdown — means
     * *that* one, not "whichever is newest."
     */
    fun restoreFromBin(entry: BinEntry): File? {
        if (!entry.file.isFile) return null
        val dest = File(kitDir, entry.originalName)
        entry.file.copyTo(dest, overwrite = true)
        entry.file.delete()
        // Reinstate the pad itself only when we know what it was AND its
        // slot is still empty. The slot-occupancy check is what makes this
        // correct for both callers of moveToBin's tombstone: clear()'s
        // case (the slot genuinely has nothing in it - safe to bring the
        // pad back) and assign()'s previous-pad-replaced case (a NEW pad
        // already occupies that slot by the time anyone could restore the
        // old one - clobbering it would silently steal the slot back from
        // whatever the user put there since). When the guard fails this
        // stays exactly the old file-only behaviour.
        if (entry.padSnapshot != null && kit.pad(entry.padSnapshot.slot) == null) {
            kit = kit.copy(pads = kit.pads + entry.padSnapshot.copy(sampleFile = entry.originalName))
            dirty = true
            save(accrueWear = false)
        }
        // The tombstone's lifecycle mirrors the WAV's: consumed here either
        // way, tolerant of it not existing (an entry from before tombstones
        // existed, or one moveToBin never wrote one for).
        File(entry.file.parentFile, "${entry.file.name}.json").delete()
        return dest
    }

    /** The newest binned copy of [originalName] back into the kit, or null. */
    fun restoreFromBin(originalName: String): File? =
        binContents().firstOrNull { it.originalName == originalName }?.let(::restoreFromBin)

    /**
     * One [BinEntry] and when the bin lets it go ([binHolds]). [keptFor] is the slot of a pad that
     * still needs the entry to undo (the lowest, when several do), and [purgeAtMillis] is then null:
     * the bin keeps it until that need ends. Otherwise [keptFor] is null and [purgeAtMillis] is when
     * the sweep may take it, [BIN_KEEP_DAYS] after it went in or with its ejected pad's tombstone.
     */
    data class BinHold(val entry: BinEntry, val keptFor: Int?, val purgeAtMillis: Long?) {
        init {
            require((keptFor == null) != (purgeAtMillis == null)) { "a bin entry is either kept for a pad or on the clock" }
        }

        /**
         * Whole days until the sweep may take this entry, rounded up: a fresh entry reads
         * [BIN_KEEP_DAYS], and 0 means [purgeBin] at [nowMillis] takes it (the last millisecond
         * before that reads 1, because the sweep's test is strictly "before"). Null while a pad
         * keeps it. The one countdown both screens draw, so they cannot round it two ways. Clamped
         * before it narrows to an Int, so a forged stamp the sweep never reaches reads as far off,
         * not as a wrapped count.
         */
        fun daysLeft(nowMillis: Long): Int? {
            val left = (purgeAtMillis ?: return null) - nowMillis
            if (left < 0) return 0
            return (left / DAY_MS + if (left % DAY_MS == 0L) 0 else 1).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        }
    }

    /**
     * Every bin entry, newest first, with when the bin lets it go: the owner's rule of 2026-10-31
     * (`docs/superpowers/specs/2026-10-31-bin-keeps-what-a-pad-needs-design.md`), and the one answer
     * [purgeBin] and both screens' countdowns read, so the sweep and the words cannot disagree.
     *
     * Every undo door restores the NEWEST entry under a file name ([restoreFromBin] by name), so
     * what a pad needs is that one entry per file it plays, and only while it [dependsOnBin]. The
     * pads that can claim one: (a) every live pad that is not printed; (b) for every printed pad,
     * the pad underneath it ([unprinted]), which REMOVE brings back; (c) for every ejected pad's
     * tombstone, the pad it holds, or the pad underneath it when that pad is printed, which BACK
     * (then REMOVE) brings back. (a) and (b) keep the newest entry under each of the claimant's
     * files ([BinHold.keptFor]). (c) keeps, under each of its pad's files, the newest entry that went
     * in before the tombstone, for exactly as long as the tombstone stays: it takes the tombstone's
     * [BinHold.purgeAtMillis] and leaves in the same sweep.
     *
     * No entry with a tombstone of its own is ever kept, and the guard comes after "newest", never
     * before it: when the entry a claimant would read is an eject record, it keeps nothing rather
     * than falling back to the entry underneath. Every eject writes a tombstone ([moveToBin]) and
     * every treatment bins without, so such an entry is a record of an ejected pad, and a new pad on
     * a reused file name (an EVIL TWINS reroll) must not pin a stranger's sound, nor the sound under
     * it. The guard asks whether the tombstone FILE is there, not whether it parsed. One known limit:
     * VERSIONS ([restoreTake]) copies an eject record back without consuming it, so a pad it makes
     * live again can be reading its own eject record, and its original then leaves with that
     * tombstone. Every other entry keeps the clock: [keepDays] after it went in.
     *
     * Builds the whole answer before returning it, and throws when it cannot: a printed pad, live
     * or ejected, whose saved source will not read is the case that matters, and [purgeBin] then
     * deletes nothing. An ejected one only stops it while its tombstone stays past [nowMillis]: a
     * tombstone the sweep at [nowMillis] takes holds nothing past that moment, so a print in it that
     * will not read is skipped, and cannot stop the sweep that clears it.
     */
    fun binHolds(keepDays: Double = BIN_KEEP_DAYS, nowMillis: Long = System.currentTimeMillis()): List<BinHold> {
        val entries = binContents()
        val keepMs = (keepDays * DAY_MS).toLong()
        // Saturating: a forged stamp near Long.MAX_VALUE stays out of reach, as it always was, rather than wrap.
        val clock = { e: BinEntry ->
            if (keepMs > 0 && e.binnedAtMillis > Long.MAX_VALUE - keepMs) Long.MAX_VALUE else e.binnedAtMillis + keepMs
        }
        val ejected = entries.filter { tombstoneOf(it.file).isFile }.map { it.file }.toSet()
        val newest = entries.groupBy { it.originalName }.mapValues { (_, under) -> under.first() }
        val filesOf = { pad: KitPad -> (listOf(pad.sampleFile) + pad.velocityLayers.map { it.sampleFile }).distinct() }

        val keptFor = HashMap<File, Int>()
        for (pad in kit.pads.map { unprinted(it) }.filter { dependsOnBin(it) }) {
            for (name in filesOf(pad)) {
                val entry = newest[name]?.takeIf { it.file !in ejected } ?: continue
                keptFor.merge(entry.file, pad.slot, ::minOf)
            }
        }
        val purgeAt = entries.associateTo(HashMap()) { it.file to clock(it) }
        for (tombstoned in entries) {
            val snapshot = tombstoned.padSnapshot ?: continue
            // Leaving at nowMillis anyway, and so would anything it held, so a print in it that will
            // not read cannot change what this sweep takes: skip it rather than fail closed.
            val under = if (clock(tombstoned) < nowMillis) runCatching { unprinted(snapshot) }.getOrNull() ?: continue else unprinted(snapshot)
            val pad = under.takeIf { dependsOnBin(it) } ?: continue
            for (name in filesOf(pad)) {
                val original = entries.firstOrNull { it.originalName == name && it.binnedAtMillis < tombstoned.binnedAtMillis }
                    ?.takeIf { it.file !in ejected } ?: continue
                purgeAt.merge(original.file, clock(tombstoned), ::maxOf)
            }
        }
        return entries.map { e ->
            keptFor[e.file]?.let { BinHold(e, keptFor = it, purgeAtMillis = null) }
                ?: BinHold(e, keptFor = null, purgeAtMillis = purgeAt.getValue(e.file))
        }
    }

    /**
     * [pad] as the bin sees it: a printed pad is the pad REMOVE would bring back
     * ([PrintedPads.sourcePad]), followed down through a print over a print (an aquarium over a
     * board) to the pad underneath, whose NONE is the one that reads the bin. Throws when a saved
     * source will not read, or when the prints loop back on themselves, which only a hand-edited kit
     * could hold: either way [binHolds] cannot know what that pad needs, and fails closed. Public for
     * THE BIN's screen, which asks of this pad, not the print over it, whether it can undo itself
     * ([PadSheet.hasOwnWayBack]): a printed pad's NONE removes the print first.
     */
    fun unprinted(pad: KitPad): KitPad {
        var under = pad
        val seen = HashSet<com.snipsnap.json.JsonValue.Obj>()
        while (PrintedPads.has(under.recipe)) {
            require(seen.add(under.recipe!!)) { "pad ${pad.slot}'s prints loop back on themselves" }
            under = PrintedPads.sourcePad(kitDir, under)
        }
        return under
    }

    /**
     * Every file name a print's saved source names, at every level under every print THE BIN could
     * still bring back: each live printed pad, and each ejected one whose tombstone BACK can
     * reinstate. REMOVE (and the print's own UNDO) restores those files and first checks each one
     * by hash (`PrintedPadStore.requireRestoreSafe`), so BACK on a bin row under one of these names
     * would overwrite a file the print then refuses to restore, for good. THE BIN shows no BACK on
     * such a row (the owner's call, 2026-11-01); once the print is removed, the name leaves this set
     * and BACK comes back. Throws when a saved source will not read, as [unprinted] does.
     */
    fun filesUnderPrints(): Set<String> {
        val names = HashSet<String>()
        for (top in kit.pads + binContents().mapNotNull { it.padSnapshot }) {
            var under = top
            val seen = HashSet<com.snipsnap.json.JsonValue.Obj>()
            while (PrintedPads.has(under.recipe)) {
                require(seen.add(under.recipe!!)) { "pad ${top.slot}'s prints loop back on themselves" }
                under = PrintedPads.sourcePad(kitDir, under)
                names += under.sampleFile
                under.velocityLayers.mapTo(names) { it.sampleFile }
            }
        }
        return names
    }

    /**
     * The launch sweep (`App.kt`, once per kit): every entry whose [BinHold.purgeAtMillis] is before
     * [nowMillis] leaves, its tombstone with it, and an original a pad still needs ([binHolds]) stays
     * however old it is. Fail closed: the whole [binHolds] answer exists before the first delete, so
     * a kit whose kept set cannot be built throws having deleted nothing. Deleting an original a pad
     * needs cannot be undone; keeping a file one more launch can. Never saves and never writes
     * `kit.json`: it decides from the kit [open] just read.
     */
    fun purgeBin(olderThanDays: Double = BIN_KEEP_DAYS, nowMillis: Long = System.currentTimeMillis()): Int {
        val leaving = binHolds(olderThanDays, nowMillis).filter { it.purgeAtMillis != null && it.purgeAtMillis < nowMillis }
        leaving.forEach {
            it.entry.file.delete()
            // The tombstone must not outlive its WAV as orphaned litter
            // past the purge horizon.
            tombstoneOf(it.entry.file).delete()
        }
        return leaving.size
    }

    /** The JSON tombstone [moveToBin] writes beside an ejected pad's bin file. */
    private fun tombstoneOf(binFile: File): File = File(binFile.parentFile, "${binFile.name}.json")

    /**
     * EMPTY THE BIN NOW: everything under [BIN_DIR] gone, not just what
     * [binContents] can parse.
     *
     * [binContents] keeps only names matching [BIN_NAME]; anything else in
     * the folder — a torn write, an aborted move, a stray file — is
     * invisible to it, and [purgeBin] filters the same way, so emptying by
     * that listing would strand exactly those entries permanently while the
     * button claimed NO TAKEBACKS. Emptying a bin has to mean the folder is
     * empty afterwards. [Rooms.emptyBin], [SnipStore.emptyBin] and
     * [KitShelf.emptyKitBin] all walk their directory for the same reason.
     */
    fun emptyBin(): Int {
        val children = File(kitDir, BIN_DIR).listFiles() ?: return 0
        return children.count { it.delete() }
    }

    /**
     * The pad on [slot], refused in words when its audio cannot be
     * rewritten: a velocity-layered pad has more than one sound to
     * replace, and a round-robin chain is several files pretending to be
     * one pad.
     *
     * Public because [replaceAudio] is not the only caller that needs the
     * answer. A door that renders a preview of a rewrite has to refuse
     * exactly what the rewrite will refuse — `MutateSheet.preview` plays a
     * mutate before it exists, and a preview of a move the keep would then
     * decline is worse than no preview. One gate, asked twice, rather than
     * two copies that can drift apart.
     */
    fun requireRewritable(slot: Int): KitPad {
        val pad = kit.pad(slot) ?: throw IllegalArgumentException("no pad on slot $slot")
        require(pad.velocityLayers.isEmpty()) {
            "pad $slot is velocity-layered - clear the layers before rewriting its audio"
        }
        requireNotChained(pad, "rewriting")
        return pad
    }

    /**
     * A chain pad's slice boundaries index into its WAV frame-for-frame;
     * any rewrite (or restore) that isn't the robin's own would orphan
     * them. One gate for every audio door.
     */
    private fun requireNotChained(pad: KitPad, doing: String) {
        require(pad.chain == null) {
            "pad ${pad.slot} is a round-robin chain - `robin --undo` before $doing it"
        }
    }

    private fun classCount(dc: DrumClass): Int = kit.pads.count { it.drumClass == dc }

    /**
     * A file stem for a new pad on [slot] that nothing else answers to: no live pad plays it, no
     * file sits there, and THE BIN holds nothing under it. The bin counts because its undo doors
     * read by name ([restoreFromBin] by name): a new pad on a binned name would find a stranger's
     * audio as its own "original", and an EVIL TWINS reroll, which clears bank B and names the new
     * twins at once, did exactly that until 2026-11-01. Once the bin lets the entry go, the name is
     * free again. [binHolds]' tombstone guard still covers bins and kits from before that.
     */
    private fun nextStem(slot: Int, dc: DrumClass): String {
        val base = "%s_%s".format(PadNoteMap.labelForPad(slot), AutoPlace.nameFor(dc))
        val binned = binnedNames()
        // Bounded, not `while (true)`: the loop only terminates because the
        // formatted counter varies from n to n, an invariant Locale.ROOT
        // restores today but does not itself guarantee. A kit holds 128
        // pads, so 999 candidates is ample headroom; if every one of them
        // still collides — the invariant broken again, or genuinely 999
        // takers of one stem — this fails loudly instead of hanging the
        // caller (an onClick, in production) forever.
        for (n in 1..MAX_STEM_ATTEMPTS) {
            val stem = Names.sanitizeStem(String.format(Locale.ROOT, "%s_%02d", base, n))
            val taken = kit.pads.any { it.sampleFile.equals("$stem.wav", ignoreCase = true) } ||
                File(kitDir, "$stem.wav").exists() ||
                "$stem.wav".lowercase(Locale.ROOT) in binned
            if (!taken) return stem
        }
        throw IllegalStateException(
            "couldn't find a free stem for '$base' after $MAX_STEM_ATTEMPTS attempts " +
                "(kept producing '${Names.sanitizeStem(String.format(Locale.ROOT, "%s_%02d", base, MAX_STEM_ATTEMPTS))}')",
        )
    }

    /**
     * Every `originalName` THE BIN holds, lower-cased: [binContents]' names without reading a
     * tombstone, for [nextStem], which only needs to know a name is spoken for.
     */
    private fun binnedNames(): Set<String> =
        File(kitDir, BIN_DIR).listFiles { f: File -> BIN_NAME.matches(f.name) && !f.name.endsWith(".json", ignoreCase = true) }
            ?.map { BIN_NAME.find(it.name)!!.groupValues[2].lowercase(Locale.ROOT) }
            ?.toSet() ?: emptySet()

    private fun deleteIfUnreferenced(pad: KitPad) {
        val files = (listOf(pad.sampleFile) + pad.velocityLayers.map { it.sampleFile }).toSet()
        val stillUsed = kit.pads.flatMap { listOf(it.sampleFile) + it.velocityLayers.map { l -> l.sampleFile } }
        for (f in files) {
            if (f !in stillUsed) moveToBin(f, padSnapshot = pad)
        }
    }

    /**
     * DELETED. THE BIN KEEPS IT 30 DAYS — deletes are recoverable, not gone.
     *
     * Bin filenames are only `"<millis>_<name>"` ([BIN_NAME]) — two calls
     * that bin the SAME [fileName] inside the same real millisecond (a
     * treat-then-rewrite followed immediately by another one, or a
     * take-restore binning the file it's about to overwrite right after
     * that same file was itself just binned by something else) would
     * otherwise collide on that exact filename, and `copyTo(overwrite =
     * true)` would silently clobber whichever entry got there first with
     * the second call's bytes — a real, observed failure mode (root-caused
     * via instrumented repro, not theorized), not a rounding artifact.
     * Walking the millisecond forward until the name is free keeps
     * `binnedAtMillis` meaningful (still real-clock-based, only nudged
     * past a genuine same-instant tie) instead of losing one entry's
     * content outright.
     *
     * [padSnapshot], when given, is the whole [KitPad] this file was
     * ejected from (not merely rewritten) — [clear]'s own call, via
     * [deleteIfUnreferenced] — and a JSON tombstone holding it rides
     * alongside the WAV. Every eject writes one, because [binHolds] reads
     * it as "a record of an ejected pad, never a live pad's original": a
     * new pad that reuses the name must not pin it. Only when the pad
     * names THIS file as its own [KitPad.sampleFile] and is otherwise a
     * plain single-sample pad (no velocity layers, no chain) does
     * [binContents] read it back as a [BinEntry.padSnapshot], so
     * [restoreFromBin] can reinstate the pad itself, not just its bytes —
     * see that function's KDoc. Deliberately narrow: a velocity-layered or
     * chained pad is several files pretending to be one, and
     * reconstructing that correctly from a single restored file is a
     * materially harder problem than this door needs to solve today.
     * [assign]'s previous-pad-replaced call also routes through
     * [deleteIfUnreferenced] and can pass a snapshot here too — harmless by
     * construction, see [restoreFromBin]'s slot-occupancy guard.
     */
    private fun moveToBin(fileName: String, padSnapshot: KitPad? = null) {
        val src = File(kitDir, fileName)
        if (!src.isFile) return
        val binDir = File(kitDir, BIN_DIR).apply { mkdirs() }
        var stamp = System.currentTimeMillis()
        var dest = File(binDir, "${stamp}_$fileName")
        while (dest.exists()) {
            stamp++
            dest = File(binDir, "${stamp}_$fileName")
        }
        src.copyTo(dest, overwrite = true)
        src.delete()
        if (padSnapshot != null) {
            val tombstone = File(binDir, "${stamp}_$fileName.json")
            AtomicFile.writeText(tombstone, Json.write(KitStore.padToJson(padSnapshot)) + "\n")
        }
    }

    companion object {

        /**
         * How far apart a re-render and the file on disk may be and still be the same sound: 1e-3, well over a
         * 16-bit step (3.1e-5) and a 24-bit one, and far under any change a different sound would make.
         */
        const val RENDER_MATCH = 1e-3f

        /** Provenance stamps that describe the old file's processing, dropped by [backOnto]. */
        val LEFT_WITH_OLD_FILE: Set<String> = setOf("mutatedWith", "outside", "desampled")

        /** Where saves archive their history, inside the kit folder. */
        const val TAKES_DIR = ".takes"

        /** Where deletes wait out [BIN_KEEP_DAYS], and the originals a pad still needs wait for as long as it needs them ([binHolds]). */
        const val BIN_DIR = ".bin"

        /** The retune's target when the kit has no key. */
        const val NO_KEY_LABEL = Keyed.NO_KEY_LABEL

        const val MAX_TAKES = 32
        const val BIN_KEEP_DAYS = 30.0
        private const val DAY_MS = 24L * 60 * 60 * 1000

        /**
         * Whether undoing [pad] reads the bin, so the bin keeps its original ([binHolds]): a
         * round-robin chain ([Robin.undo] reads `chain` alone), or any recipe but two. A printed
         * wrapper ([PrintedPads]) keeps its source in its own store. A plain synth render, a
         * [com.snipsnap.synth.PadRecipe] with a patch and no pedals and no treatment, re-renders
         * from the patch, and no undo door reads the bin for it — unless DE-SAMPLE made it: the
         * recipe is the same, but [desamplePad] stamps `desampled` and `snipsnap desample --undo`
         * brings the capture back out of the bin, so a de-sampled pad counts. A synth send over it
         * keeps the stamp, so the bin then keeps the render that send replaced, which is what
         * `--undo` would restore. "Any recipe but these", not a list of recipe readers, so a
         * treatment added later is kept without anyone adding it here.
         */
        internal fun dependsOnBin(pad: KitPad): Boolean {
            if (pad.chain != null) return true
            val recipe = pad.recipe ?: return false
            if (PrintedPads.has(recipe)) return false
            val parsed = Breed.recipeOf(recipe) ?: return true
            return parsed.patch == null || parsed.pedals != null || parsed.treatment != null || "desampled" in pad.source
        }

        internal val TAKE_NAME = Regex("take_(\\d{3})\\.json")
        internal val BIN_NAME = Regex("(\\d+)_(.+)")

        /** Open an existing kit folder. */
        fun open(kitDir: File): KitBuilderModel =
            KitBuilderModel(kitDir, KitStore.load(kitDir))

        /** The source key a twin carries: the bank-A tag it was dealt from. */
        const val TWIN_OF = "twinOf"

        /** [isTwin] on a loaded [kit], for a screen that holds the kit but not the model. */
        fun isTwin(kit: Kit, pad: KitPad): Boolean {
            if (pad.source[TWIN_OF] != null) return true
            if (pad.slot !in 17..32 || pad.recipe == null) return false
            val a = kit.pad(pad.slot - 16) ?: return false
            return pad.displayName == "${a.displayName} B"
        }

        /** [ownPadsOnBankB] on a loaded [kit]: the KIT screen's pre-check before REMIX BANK B runs. */
        fun ownPadsOnBankB(kit: Kit): List<Int> =
            kit.pads.filter { it.slot in 17..32 && !isTwin(kit, it) }.map { it.slot }.sorted()

        /** FRESH TAPE: a new, empty kit. */
        fun create(name: String, kitDir: File): KitBuilderModel {
            require(Names.isMpcSafe(name)) { "kit name isn't MPC-safe: '$name'" }
            kitDir.mkdirs()
            val model = KitBuilderModel(kitDir, Kit(name, emptyList()))
            model.save()
            return model
        }

        /**
         * SEND TO PADS lands here: the chop screen's arrangement becomes a
         * whole kit folder in one step, via the same [KitAssembler] the CLI
         * and the generators use.
         */
        fun fromChop(name: String, arranged: List<ArrangedPad?>, kitDir: File): KitBuilderModel {
            require(Names.isMpcSafe(name)) { "kit name isn't MPC-safe: '$name'" }
            val kit = KitAssembler.assembleArranged(name, arranged, kitDir)
            return KitBuilderModel(kitDir, kit)
        }
    }
}
