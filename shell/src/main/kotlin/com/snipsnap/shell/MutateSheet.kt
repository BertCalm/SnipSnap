package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.AtomicFile
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Names
import java.io.File
import kotlin.math.roundToInt

/**
 * The PAD SHEET's MUTATE card, as data — the phone's door onto [Mutate].
 *
 * The CLI verb takes parents as pad refs, other kits' pads, WAV paths and
 * a roulette; the card keeps every one of them a thumb can reach: **a
 * pad on this kit** (tap it on the mini grid), **the crate's deal**
 * (ROULETTE spins the shelf, seeded by the tap count so every spin is a
 * different deal and each is reproducible), **a room on the shelf** (one
 * OUTSIDE measured and kept, [Rooms]; ROOM plays the pad inside it), **a
 * pad picked on another kit** ([otherKits], then [padsOf] - the crate
 * with intent, where ROULETTE is the crate by chance), or **a file off
 * the phone** ([hold]: the picker's file, held as a WAV, the way the CLI
 * takes `--with hit.wav`). One parent, one move, one knob: STACK has
 * none, SPLICE has AT (where the pad's transient hands over), SPLIT has
 * HZ (the crossover), MORPH has MIX, ROOM has WET, TRANSPLANT has BANDS.
 * The knob's range is the verb's own, mapped exponentially where the ear
 * hears ratios. DRIFT is the card's one-tap move: the crate deals and
 * MORPH blends, MIX how far ([drift]).
 */
object MutateSheet {

    /** Move order, left to right, as the card draws it. */
    val MODES: List<String> = Mutate.Mode.values().map { it.name }

    fun modeFor(name: String): Mutate.Mode =
        Mutate.Mode.values().firstOrNull { it.name == name }
            ?: throw IllegalArgumentException("unknown mutate move '$name' - the card draws: ${MODES.joinToString(", ")}")

    private val KNOBS: Map<Mutate.Mode, Knob> = mapOf(
        Mutate.Mode.SPLICE to Knob("AT", 5f, 2000f, Mutate.DEFAULT_SPLICE_MS.toFloat(), exponential = true),
        Mutate.Mode.SPLIT to Knob("HZ", 40f, 8000f, Mutate.DEFAULT_CROSSOVER_HZ, exponential = true),
        Mutate.Mode.MORPH to Knob("MIX", 0f, 1f, 0.5f, exponential = false),
        Mutate.Mode.ROOM to Knob("WET", 0f, 1f, 0.5f, exponential = false),
        Mutate.Mode.TRANSPLANT to Knob(
            "BANDS",
            com.snipsnap.audio.Transplant.MIN_BANDS.toFloat(),
            com.snipsnap.audio.Transplant.MAX_BANDS.toFloat(),
            com.snipsnap.audio.Transplant.DEFAULT_BANDS.toFloat(),
            exponential = true,
        ),
    )

    /** STACK has no knob: layering is transient-aligned, nothing to dial. */
    fun knobFor(mode: Mutate.Mode): Knob? = KNOBS[mode]

    /** Stepper fraction 0..1 → the knob's value. */
    fun value(knob: Knob, fraction: Float): Float = knob.value(fraction)

    /** The knob's value → stepper fraction; the inverse of [value]. */
    fun fraction(knob: Knob, value: Float): Float = knob.fraction(value)

    /** What the value column reads: "40 ms", "200 Hz" / "1.2k", "50%", "16 bands". */
    fun label(knob: Knob, value: Float): String = when (knob.label) {
        "AT" -> "${value.roundToInt()} ms"
        "HZ" -> if (value >= 1000f) "%.1fk".format(java.util.Locale.ROOT, value / 1000f) else "${value.roundToInt()} Hz"
        "BANDS" -> "${value.roundToInt()} bands"
        else -> "${(value * 100).roundToInt()}%"
    }

    /**
     * "A03", "B01" — the pad's tag across banks, the way the CLI and the
     * lineage name it. Kept as the name a dozen callers already use; the
     * rule itself lives in [PadBanks] now, where the screens share it.
     */
    fun padTag(slot: Int): String = PadBanks.tag(slot)

    /** Every other assigned pad on [kit], slot order — the mini grid's candidates. Never the pad itself. */
    fun partners(kit: Kit, slot: Int): List<KitPad> = kit.pads.filter { it.slot != slot }.sortedBy { it.slot }

    /** Who the other parent is. */
    sealed interface Partner {
        /** A pad on this kit. */
        data class Pad(val slot: Int) : Partner

        /** What ROULETTE dealt off the shelf: the crate's own name for it, its file, and the seed that dealt it. */
        data class Deal(val label: String, val file: File, val seed: Int) : Partner

        /** A room kept on the shelf ([Rooms]): its name and its impulse. */
        data class Room(val name: String, val file: File) : Partner

        /** A pad picked on another kit of the shelf: the kit's name and folder, and the pad's slot there. */
        data class Other(val kitName: String, val kitDir: File, val slot: Int) : Partner

        /** A file picked off the phone: the name it came with (the CLI's label for a `.wav` parent) and the held WAV. */
        data class Wav(val label: String, val file: File) : Partner
    }

    /** The card's own name for a partner: the pad tag, the crate's label, the room's name, "SOUL A03", or the file's name. */
    fun name(partner: Partner): String = when (partner) {
        is Partner.Pad -> padTag(partner.slot)
        is Partner.Deal -> partner.label
        is Partner.Room -> partner.name
        is Partner.Other -> "${partner.kitName} ${padTag(partner.slot)}"
        is Partner.Wav -> partner.label
    }

    /** Where the phone holds a picked parent between the pick and the move, under its cache. */
    const val PARENTS_DIR = "parents"

    /**
     * A picked file held as a parent: its audio written under [holdDir] as
     * a WAV named after [displayName] (the stem made safe, the extension
     * swapped for `.wav`), landed by write-then-rename like a kept room.
     * The partner's label is the name the file came with, extension and
     * all - what `snipsnap mutate --with hit.wav` puts in the lineage. One
     * is held at a time: the last pick's file goes. A silent file refuses
     * in words, as ROULETTE's empty crate does.
     */
    fun hold(holdDir: File, displayName: String, snip: Snip): Partner.Wav {
        require(snip.frameCount > 0 && snip.peak() > 0f) { "that file is silent - nothing to mutate with" }
        val bare = displayName.substringAfterLast('/').substringAfterLast('\\').trim().ifBlank { "parent.wav" }
        val stem = Names.sanitizeStem(if (bare.contains('.')) bare.substringBeforeLast('.') else bare)
        holdDir.mkdirs()
        val wav = File(holdDir, "$stem.wav")
        holdDir.listFiles()?.forEach { if (it.name != wav.name) it.delete() }
        val bytes = java.io.ByteArrayOutputStream().also { WavWriter.write(it, snip) }.toByteArray()
        AtomicFile.writeBytes(wav, bytes)
        return Partner.Wav(bare, wav)
    }

    /** Another kit on the shelf, for the picker: its name and folder. */
    data class OtherKit(val name: String, val dir: File)

    /**
     * The other kits on the shelf under [shelfRoot], by name, never
     * [thisKitDir] itself - the picker's candidates. A folder whose
     * `kit.json` is broken is skipped, not fatal. The two folders are told
     * apart by their normalised absolute paths, which never touch the disk
     * (a canonical path would, and could throw).
     */
    fun otherKits(shelfRoot: File, thisKitDir: File): List<OtherKit> {
        val here = thisKitDir.absoluteFile.normalize()
        return KitStore.list(shelfRoot)
            .filter { it.absoluteFile.normalize() != here }
            .mapNotNull { dir -> runCatching { OtherKit(KitStore.load(dir).name, dir) }.getOrNull() }
            .sortedBy { it.name.lowercase(java.util.Locale.ROOT) }
    }

    /** The assigned pads of another kit, slot order - the picker's second row. */
    fun padsOf(kit: OtherKit): List<KitPad> = KitStore.load(kit.dir).pads.sortedBy { it.slot }

    /** What a mutated pad carries: the move and the parents' labels, read from the `mutate` recipe; DRIFT reads as its own word. */
    data class Applied(val mode: String, val parents: List<String>, val drifted: Boolean = false) {
        /** "DRIFT" for a drift, else the move. */
        val word: String get() = if (drifted) "DRIFT" else mode
    }

    /**
     * Read defensively: `Mutate.apply` leaves `{"mutate": {"mode", "with", …}}`;
     * any other recipe (an era, a character, a synth patch) reads as unmutated.
     */
    fun read(recipe: JsonValue.Obj?): Applied? {
        val m = recipe?.entries?.get("mutate") as? JsonValue.Obj ?: return null
        val mode = (m.entries["mode"] as? JsonValue.Str)?.value ?: return null
        val with = (m.entries["with"] as? JsonValue.Arr)?.items?.mapNotNull { (it as? JsonValue.Str)?.value } ?: emptyList()
        val drifted = (m.entries["drift"] as? JsonValue.Bool)?.value == true
        return Applied(mode.uppercase(), with, drifted)
    }

    /**
     * DRIFT: one tap — the crate under [root] deals the neighbour and MORPH
     * blends [fraction] of the MIX knob toward it, through [Mutate.drift] so
     * the recipe records the spin and the drift. A new [seed] is a new
     * neighbour. Throws [IllegalArgumentException] when the crate is empty.
     */
    fun drift(model: KitBuilderModel, slot: Int, root: File, seed: Int, fraction: Float): Mutate.Drifted {
        val mix = knobFor(Mutate.Mode.MORPH)!!
        return Mutate.drift(model, slot, root, seed, value(mix, fraction))
    }

    /**
     * ROULETTE: the crate under [root] (the shelf, on the phone) deals a
     * partner for [slot] — guided, never wild, never the pad itself; a new
     * [seed] is a new deal, the same seed the same one. Throws
     * [IllegalArgumentException] when the crate has nothing to deal.
     */
    fun deal(model: KitBuilderModel, slot: Int, root: File, seed: Int): Partner.Deal {
        val pick = Mutate.roulette(model, slot, root, seed = seed, wild = false)
        return Partner.Deal(pick.label, pick.file, seed)
    }

    /** The parent as [Mutate] wants it; a pad's label is the CLI's own `Kit:A03` form, so the lineage reads the same either way. */
    fun source(model: KitBuilderModel, partner: Partner): Mutate.Source = when (partner) {
        is Partner.Pad -> {
            val pad = model.pad(partner.slot) ?: throw IllegalArgumentException("no pad on ${padTag(partner.slot)}")
            Mutate.Source("${model.kit.name}:${padTag(partner.slot)}", WavReader.read(File(model.kitDir, pad.sampleFile)))
        }
        is Partner.Deal -> Mutate.Source(partner.label, WavReader.read(partner.file))
        is Partner.Room -> Mutate.Source(Rooms.LABEL_PREFIX + partner.name, WavReader.read(partner.file))
        is Partner.Other -> {
            val kit = KitStore.load(partner.kitDir)
            val pad = kit.pads.firstOrNull { it.slot == partner.slot }
                ?: throw IllegalArgumentException("no pad on ${partner.kitName} ${padTag(partner.slot)}")
            // The CLI's own Kit:Pad label, so the lineage reads the same as a roulette deal's.
            Mutate.Source("${kit.name}:${padTag(partner.slot)}", WavReader.read(File(partner.kitDir, pad.sampleFile)))
        }
        // The file's own name, as `--with hit.wav` labels it.
        is Partner.Wav -> Mutate.Source(partner.label, WavReader.read(partner.file))
    }

    /**
     * The card's one stepper as the five knob slots [Mutate.render] and
     * [Mutate.apply] take.
     *
     * One place, because HEAR and KEEP have to agree exactly: a preview
     * that computed its own splice point would play a sound the write
     * then did not make, which is the whole promise of auditioning first.
     * The move's knob fills its own slot; every other slot keeps the
     * verb's default, since a move never reads a knob that is not its.
     */
    private data class Knobs(
        val spliceAtMs: Int,
        val crossoverHz: Float,
        val morphAmount: Float,
        val roomMix: Float,
        val bands: Int,
    )

    private fun knobs(mode: Mutate.Mode, fraction: Float): Knobs {
        val v = knobFor(mode)?.let { value(it, fraction) }
        return Knobs(
            spliceAtMs = if (mode == Mutate.Mode.SPLICE) v!!.roundToInt() else Mutate.DEFAULT_SPLICE_MS,
            crossoverHz = if (mode == Mutate.Mode.SPLIT) v!! else Mutate.DEFAULT_CROSSOVER_HZ,
            morphAmount = if (mode == Mutate.Mode.MORPH) v!! else 0.5f,
            roomMix = if (mode == Mutate.Mode.ROOM) v!! else 0.5f,
            bands = if (mode == Mutate.Mode.TRANSPLANT) v!!.roundToInt() else com.snipsnap.audio.Transplant.DEFAULT_BANDS,
        )
    }

    /**
     * What [apply] would write, without writing it — the card's HEAR.
     *
     * Reads the pad and the partner exactly as [apply] does and renders
     * through [Mutate.render], so what this returns is what that would
     * put in the file, sample for sample. Nothing moves: no bin entry, no
     * recipe, no provenance, and the pad's WAV is untouched.
     *
     * It refuses what [apply] refuses, and for the same reasons, so a
     * player never hears a move the keep would then decline: a pad cannot
     * parent itself, a move that takes one parent gets one, and a knob out
     * of range is out of range here too.
     */
    fun preview(model: KitBuilderModel, slot: Int, partner: Partner, mode: Mutate.Mode, fraction: Float): Snip {
        require(partner !is Partner.Pad || partner.slot != slot) { "a pad can't be its own parent" }
        val pad = model.pad(slot) ?: throw IllegalArgumentException("no pad on ${padTag(slot)}")
        val base = WavReader.read(File(model.kitDir, pad.sampleFile))
        val k = knobs(mode, fraction)
        return Mutate.render(
            base, listOf(source(model, partner)), mode,
            spliceAtMs = k.spliceAtMs,
            crossoverHz = k.crossoverHz,
            morphAmount = k.morphAmount,
            roomMix = k.roomMix,
            bands = k.bands,
        ).snip
    }

    /**
     * MUTATE: [slot] and [partner] become one hit by [mode]; [fraction] is
     * the stepper's position on the move's knob (ignored by STACK). Through
     * [Mutate.apply], so the bin, the recipe and the provenance are exactly
     * the CLI's; a roulette deal records its seed like `--roulette` does.
     */
    fun apply(model: KitBuilderModel, slot: Int, partner: Partner, mode: Mutate.Mode, fraction: Float): Mutate.Outcome {
        require(partner !is Partner.Pad || partner.slot != slot) { "a pad can't be its own parent" }
        val extra = when (partner) {
            is Partner.Deal -> mapOf(
                "roulette" to JsonValue.Obj(
                    linkedMapOf<String, JsonValue>(
                        "seed" to JsonValue.Num(partner.seed.toDouble()),
                        "wild" to JsonValue.Bool(false),
                    ),
                ),
            )
            is Partner.Room -> mapOf("room" to JsonValue.Str(partner.name))
            is Partner.Other -> mapOf("otherKit" to JsonValue.Str(partner.kitName))
            // Nothing beyond the label, exactly as the CLI records a .wav parent.
            is Partner.Wav, is Partner.Pad -> emptyMap()
        }
        val k = knobs(mode, fraction)
        return Mutate.apply(
            model, slot, listOf(source(model, partner)), mode,
            spliceAtMs = k.spliceAtMs,
            crossoverHz = k.crossoverHz,
            morphAmount = k.morphAmount,
            roomMix = k.roomMix,
            bands = k.bands,
            extraRecipe = extra,
        )
    }

    /** The parents back apart: the pre-mutation audio out of the bin, recipe and parent stamp cleared. */
    fun undo(model: KitBuilderModel, slot: Int): KitPad = Mutate.undo(model, slot)
}
