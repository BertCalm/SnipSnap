package com.snipsnap.shell

import com.snipsnap.audio.AutoPlace
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Names
import com.snipsnap.xpm.PadNoteMap
import java.io.File

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
     */
    fun assign(
        slot: Int,
        snip: Snip,
        drumClass: DrumClass = DrumClass.UNKNOWN,
        displayName: String? = null,
    ): KitPad {
        require(snip.frameCount > 0) { "won't assign an empty snip" }
        val stem = nextStem(slot, drumClass)
        WavWriter.write(File(kitDir, "$stem.wav"), snip)

        val previous = kit.pad(slot)
        val pad = KitPad(
            slot = slot,
            sampleFile = "$stem.wav",
            displayName = displayName
                ?: "%s %02d".format(AutoPlace.nameFor(drumClass), classCount(drumClass) + 1),
            drumClass = drumClass,
            colorHex = AutoPlace.colorFor(drumClass),
            muteGroup = AutoPlace.muteGroupFor(drumClass),
        )
        kit = kit.copy(pads = kit.pads.filter { it.slot != slot } + pad)
        previous?.let { deleteIfUnreferenced(it) }
        dirty = true
        return pad
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

    /**
     * Set or clear the kit's key. Choosing a key never retunes anything by
     * itself — that's [retuneTonalPads], an explicit action.
     */
    fun setKey(key: com.snipsnap.audio.KeySpec?) {
        if (kit.key == key) return
        kit = kit.copy(key = key)
        dirty = true
    }

    /**
     * IN KEY: retune every TONAL pad onto the nearest note of the kit's
     * key, via the tune fields the MPC pad already has — audio untouched,
     * unpitched pads and non-tonal classes never "corrected". Returns the
     * slots that moved. No key set, nothing happens.
     */
    fun retuneTonalPads(): List<Int> {
        val key = kit.key ?: return emptyList()
        val moved = mutableListOf<Int>()
        for (pad in kit.pads) {
            if (pad.drumClass != DrumClass.TONAL) continue
            val snip = com.snipsnap.audio.WavReader.read(File(kitDir, pad.sampleFile))
            val tune = com.snipsnap.audio.Tuner.inKey(snip, key.rootSemitone, key.scale) ?: continue
            if (tune.tuneCoarse == pad.tuneCoarse && tune.tuneFine == pad.tuneFine) continue
            kit = kit.copy(
                pads = kit.pads.map {
                    if (it.slot == pad.slot) it.copy(tuneCoarse = tune.tuneCoarse, tuneFine = tune.tuneFine) else it
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
     * (so the hats still choke in bank B) and carry fx-only recipes.
     * Rerolling with a new seed replaces the bank. Returns bank-B slots.
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
                ),
            )
            added += slot
        }
        dirty = true
        return added
    }

    /**
     * Ghost notes: soft velocity zones rendered darker (not just quieter)
     * under the pad's main sample, via the same softening the velocity
     * kit ships with. [softZones] 1 or 2. Reversible with
     * [clearGhostLayers].
     */
    fun addGhostLayers(slot: Int, softZones: Int = 1): KitPad {
        require(softZones in 1..2) { "1 or 2 soft zones, got $softZones" }
        val pad = kit.pad(slot) ?: throw IllegalArgumentException("no pad on slot $slot")
        require(pad.velocityLayers.isEmpty()) { "pad $slot is already velocity-layered" }
        val main = com.snipsnap.audio.WavReader.read(File(kitDir, pad.sampleFile))

        val amounts = if (softZones == 1) listOf(0.55f) else listOf(0.7f, 0.4f) // softest first
        val zoneCount = softZones + 1
        val layers = buildList {
            amounts.forEachIndexed { v, amount ->
                val file = "${pad.sampleStem}_v${v + 1}.wav"
                WavWriter.write(File(kitDir, file), com.snipsnap.synth.Velocity.soften(main, amount))
                add(
                    com.snipsnap.kit.KitLayer(
                        file,
                        velStart = if (v == 0) 1 else 128 * v / zoneCount,
                        velEnd = 128 * (v + 1) / zoneCount - 1,
                    ),
                )
            }
            add(com.snipsnap.kit.KitLayer(pad.sampleFile, 128 * softZones / zoneCount, 127))
        }
        return update(slot) { it.copy(velocityLayers = layers) }
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
        val pad = kit.pad(slot) ?: throw IllegalArgumentException("no pad on slot $slot")
        require(pad.velocityLayers.isEmpty()) {
            "pad $slot is velocity-layered - clear the layers before rewriting its audio"
        }
        requireNotChained(pad, "rewriting")
        val original = com.snipsnap.audio.WavReader.read(File(kitDir, pad.sampleFile))
        val processed = transform(original)
        require(processed.frameCount > 0) { "a rewrite must leave audio behind" }
        moveToBin(pad.sampleFile)
        WavWriter.write(File(kitDir, pad.sampleFile), processed)
        return update(slot) { it.copy(recipe = recipe ?: it.recipe) }
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
        requireNotChained(pad, "aging")
        val files = (listOf(pad.sampleFile) + pad.velocityLayers.map { it.sampleFile }).distinct()
        var recipe: com.snipsnap.json.JsonValue.Obj? = null
        for (f in files) {
            val original = com.snipsnap.audio.WavReader.read(File(kitDir, f))
            val aged = com.snipsnap.synth.Eras.apply(era, original, amount)
            moveToBin(f)
            WavWriter.write(File(kitDir, f), aged.snip)
            recipe = aged.recipe
        }
        return update(slot) { it.copy(recipe = recipe) }
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

    /** Undo an era on one pad: every file it references comes back out of the bin. */
    fun unEraPad(slot: Int): KitPad {
        val pad = kit.pad(slot) ?: throw IllegalArgumentException("no pad on slot $slot")
        requireNotChained(pad, "un-aging")
        val files = (listOf(pad.sampleFile) + pad.velocityLayers.map { it.sampleFile }).distinct()
        var restoredAny = false
        for (f in files) {
            if (restoreFromBin(f) != null) restoredAny = true
        }
        require(restoredAny) { "nothing to restore for pad $slot - the bin holds no earlier take of it" }
        return update(slot) { it.copy(recipe = null) }
    }

    /** Undo the last treatment: the previous audio comes back out of the bin. */
    fun untreatPad(slot: Int): KitPad {
        val pad = kit.pad(slot) ?: throw IllegalArgumentException("no pad on slot $slot")
        requireNotChained(pad, "un-treating")
        restoreFromBin(pad.sampleFile)
            ?: throw IllegalArgumentException("nothing to restore for pad $slot - the bin holds no earlier take of it")
        return update(slot) { it.copy(recipe = null) }
    }

    /** Back to a single-sample pad; the soft renders are deleted. */
    fun clearGhostLayers(slot: Int): KitPad {
        val pad = kit.pad(slot) ?: throw IllegalArgumentException("no pad on slot $slot")
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
     */
    fun save(accrueWear: Boolean = true): File {
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
        val current = File(kitDir, "kit.json")
        // A clean save changes nothing; archiving it would duplicate takes.
        if (!current.isFile || !dirty) return
        val takesDir = File(kitDir, TAKES_DIR).apply { mkdirs() }
        val next = (takes().lastOrNull()?.let { TAKE_NAME.find(it.name)!!.groupValues[1].toInt() } ?: 0) + 1
        val takeFile = File(takesDir, "take_%03d.json".format(next))
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

    /** What's recoverable: file name it had, when it was binned, its bin file. */
    data class BinEntry(val originalName: String, val binnedAtMillis: Long, val file: File)

    /** Recoverable deletes, newest first. */
    fun binContents(): List<BinEntry> =
        File(kitDir, BIN_DIR).listFiles { f: File -> BIN_NAME.matches(f.name) }
            ?.map {
                val m = BIN_NAME.find(it.name)!!
                BinEntry(m.groupValues[2], m.groupValues[1].toLong(), it)
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
        return dest
    }

    /** The newest binned copy of [originalName] back into the kit, or null. */
    fun restoreFromBin(originalName: String): File? =
        binContents().firstOrNull { it.originalName == originalName }?.let(::restoreFromBin)

    /** THE BIN KEEPS IT 30 DAYS — this is the keeping-side of that promise. */
    fun purgeBin(olderThanDays: Double = BIN_KEEP_DAYS, nowMillis: Long = System.currentTimeMillis()): Int {
        val cutoff = nowMillis - (olderThanDays * 24 * 60 * 60 * 1000).toLong()
        val old = binContents().filter { it.binnedAtMillis < cutoff }
        old.forEach { it.file.delete() }
        return old.size
    }

    fun emptyBin(): Int {
        val all = binContents()
        all.forEach { it.file.delete() }
        return all.size
    }

    /** The kit-name easter egg, for the rename dialog to surface. */
    fun nameResponse(proposed: String): String? = Copy.kitNameResponse(proposed)

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

    private fun nextStem(slot: Int, dc: DrumClass): String {
        val base = "%s_%s".format(PadNoteMap.labelForPad(slot), AutoPlace.nameFor(dc))
        var n = 1
        while (true) {
            val stem = Names.sanitizeStem("%s_%02d".format(base, n))
            val taken = kit.pads.any { it.sampleFile.equals("$stem.wav", ignoreCase = true) } ||
                File(kitDir, "$stem.wav").exists()
            if (!taken) return stem
            n++
        }
    }

    private fun deleteIfUnreferenced(pad: KitPad) {
        val files = (listOf(pad.sampleFile) + pad.velocityLayers.map { it.sampleFile }).toSet()
        val stillUsed = kit.pads.flatMap { listOf(it.sampleFile) + it.velocityLayers.map { l -> l.sampleFile } }
        for (f in files) {
            if (f !in stillUsed) moveToBin(f)
        }
    }

    /**
     * EJECTED. THE BIN KEEPS IT 30 DAYS — deletes are recoverable, not gone.
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
     */
    private fun moveToBin(fileName: String) {
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
    }

    companion object {

        /** Where saves archive their history, inside the kit folder. */
        const val TAKES_DIR = ".takes"

        /** Where deletes wait out their 30 days. */
        const val BIN_DIR = ".bin"

        const val MAX_TAKES = 32
        const val BIN_KEEP_DAYS = 30.0

        internal val TAKE_NAME = Regex("take_(\\d{3})\\.json")
        internal val BIN_NAME = Regex("(\\d+)_(.+)")

        /** Open an existing kit folder. */
        fun open(kitDir: File): KitBuilderModel =
            KitBuilderModel(kitDir, KitStore.load(kitDir))

        /** FRESH TAPE: a new, empty kit. */
        fun create(name: String, kitDir: File): KitBuilderModel {
            require(Names.isMpcSafe(name)) { "kit name isn't MPC-safe: '$name'" }
            kitDir.mkdirs()
            val model = KitBuilderModel(kitDir, Kit(name, emptyList()))
            model.save()
            return model
        }

        /**
         * SEND TO GRID lands here: the chop screen's arrangement becomes a
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
