package com.snipsnap.shell

import com.snipsnap.audio.WavReader
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import java.io.File
import kotlin.math.roundToInt

/**
 * The PAD SHEET's MUTATE card, as data — the phone's door onto [Mutate].
 *
 * The CLI verb takes parents as pad refs, other kits' pads, WAV paths and
 * a roulette; the card keeps the two a thumb can reach: **a pad on this
 * kit** (tap it on the mini grid) or **the crate's deal** (ROULETTE spins
 * the shelf, seeded by the tap count so every spin is a different deal
 * and each is reproducible). One parent, one move, one knob: STACK has
 * none, SPLICE has AT (where the pad's transient hands over), SPLIT has
 * HZ (the crossover), MORPH has MIX, ROOM has WET, TRANSPLANT has BANDS.
 * The knob's range is the verb's own, mapped exponentially where the ear
 * hears ratios.
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
        "HZ" -> if (value >= 1000f) "%.1fk".format(value / 1000f) else "${value.roundToInt()} Hz"
        "BANDS" -> "${value.roundToInt()} bands"
        else -> "${(value * 100).roundToInt()}%"
    }

    /** "A03", "B01" — the pad's tag across banks, the way the CLI and the lineage name it. */
    fun padTag(slot: Int): String {
        require(slot >= 1) { "slots are 1-based, got $slot" }
        val bank = 'A' + (slot - 1) / 16
        return "%c%02d".format(bank, (slot - 1) % 16 + 1)
    }

    /** Every other assigned pad on [kit], slot order — the mini grid's candidates. Never the pad itself. */
    fun partners(kit: Kit, slot: Int): List<KitPad> = kit.pads.filter { it.slot != slot }.sortedBy { it.slot }

    /** Who the other parent is. */
    sealed interface Partner {
        /** A pad on this kit. */
        data class Pad(val slot: Int) : Partner

        /** What ROULETTE dealt off the shelf: the crate's own name for it, its file, and the seed that dealt it. */
        data class Deal(val label: String, val file: File, val seed: Int) : Partner
    }

    /** The card's own name for a partner: the pad tag, or the crate's label. */
    fun name(partner: Partner): String = when (partner) {
        is Partner.Pad -> padTag(partner.slot)
        is Partner.Deal -> partner.label
    }

    /** What a mutated pad carries: the move and the parents' labels, read from the `mutate` recipe. */
    data class Applied(val mode: String, val parents: List<String>)

    /**
     * Read defensively: `Mutate.apply` leaves `{"mutate": {"mode", "with", …}}`;
     * any other recipe (an era, a character, a synth patch) reads as unmutated.
     */
    fun read(recipe: JsonValue.Obj?): Applied? {
        val m = recipe?.entries?.get("mutate") as? JsonValue.Obj ?: return null
        val mode = (m.entries["mode"] as? JsonValue.Str)?.value ?: return null
        val with = (m.entries["with"] as? JsonValue.Arr)?.items?.mapNotNull { (it as? JsonValue.Str)?.value } ?: emptyList()
        return Applied(mode.uppercase(), with)
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
    }

    /**
     * MUTATE: [slot] and [partner] become one hit by [mode]; [fraction] is
     * the stepper's position on the move's knob (ignored by STACK). Through
     * [Mutate.apply], so the bin, the recipe and the provenance are exactly
     * the CLI's; a roulette deal records its seed like `--roulette` does.
     */
    fun apply(model: KitBuilderModel, slot: Int, partner: Partner, mode: Mutate.Mode, fraction: Float): Mutate.Outcome {
        require(partner !is Partner.Pad || partner.slot != slot) { "a pad can't be its own parent" }
        val knob = knobFor(mode)
        val v = knob?.let { value(it, fraction) }
        val extra = when (partner) {
            is Partner.Deal -> mapOf(
                "roulette" to JsonValue.Obj(
                    linkedMapOf<String, JsonValue>(
                        "seed" to JsonValue.Num(partner.seed.toDouble()),
                        "wild" to JsonValue.Bool(false),
                    ),
                ),
            )
            is Partner.Pad -> emptyMap()
        }
        return Mutate.apply(
            model, slot, listOf(source(model, partner)), mode,
            spliceAtMs = if (mode == Mutate.Mode.SPLICE) v!!.roundToInt() else Mutate.DEFAULT_SPLICE_MS,
            crossoverHz = if (mode == Mutate.Mode.SPLIT) v!! else Mutate.DEFAULT_CROSSOVER_HZ,
            morphAmount = if (mode == Mutate.Mode.MORPH) v!! else 0.5f,
            roomMix = if (mode == Mutate.Mode.ROOM) v!! else 0.5f,
            bands = if (mode == Mutate.Mode.TRANSPLANT) v!!.roundToInt() else com.snipsnap.audio.Transplant.DEFAULT_BANDS,
            extraRecipe = extra,
        )
    }

    /** The parents back apart: the pre-mutation audio out of the bin, recipe and parent stamp cleared. */
    fun undo(model: KitBuilderModel, slot: Int): KitPad = Mutate.undo(model, slot)
}
