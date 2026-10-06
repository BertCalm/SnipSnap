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
 * MORPH alone has a second knob, BECOME: how many milliseconds the hit
 * takes to turn from the pad into the MIX blend, OFF at 0 ([becomeFor]).
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

    /**
     * MORPH's second knob: the milliseconds the hit takes to turn from the
     * pad into the MIX blend ([Mutate.becomeAmount]). Linear, because its
     * `lo` is 0 and [Knob.value]'s exponential form is `lo × (hi/lo)^f`,
     * NaN for every fraction above 0 there. The phone's 1/40 snap makes it
     * 50 ms steps, and the first, 50 ms, already clears one analysis window
     * (23 ms at 44.1 kHz), under which a ramp reads as a step.
     */
    val BECOME = Knob("BECOME", 0f, Mutate.MAX_BECOME_MS.toFloat(), 0f, exponential = false)

    /** [BECOME] for MORPH, null for every other move: no other move reads it. */
    fun becomeFor(mode: Mutate.Mode): Knob? = if (mode == Mutate.Mode.MORPH) BECOME else null

    /**
     * The blend DRIFT uses when the card is not already on MORPH — MORPH's
     * own knob default, matching [knobs]'s `morphAmount` fallback for the
     * same situation.
     *
     * This exists so the decision lives here rather than in the card, which
     * had been reading whatever fraction the *previous* move's stepper
     * happened to hold: [MODES] opens on STACK, STACK has no knob at all,
     * so that fraction was `0f` and the common DRIFT tap blended none of
     * the neighbour in while the card then redrew MIX at 50%.
     */
    val DRIFT_FRACTION: Float = KNOBS.getValue(Mutate.Mode.MORPH).defaultFraction

    /** Stepper fraction 0..1 → the knob's value. */
    fun value(knob: Knob, fraction: Float): Float = knob.value(fraction)

    /** The knob's value → stepper fraction; the inverse of [value]. */
    fun fraction(knob: Knob, value: Float): Float = knob.fraction(value)

    /** What the value column reads: "40 ms", "200 Hz" / "1.2k", "50%", "16 bands", BECOME's "OFF" / "400 ms". */
    fun label(knob: Knob, value: Float): String = when (knob.label) {
        "AT" -> "${value.roundToInt()} ms"
        "HZ" -> if (value >= 1000f) "%.1fk".format(java.util.Locale.ROOT, value / 1000f) else "${value.roundToInt()} Hz"
        "BANDS" -> "${value.roundToInt()} bands"
        "BECOME" -> if (value <= 0f) "OFF" else "${value.roundToInt()} ms"
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

    /** What a mutated pad carries: the move, the parents' labels and any BECOME ramp, read from the `mutate` recipe; DRIFT and BECOME read as their own words. */
    data class Applied(val mode: String, val parents: List<String>, val drifted: Boolean = false, val becomeMs: Int = 0) {
        /** "DRIFT" for a drift, "BECOME" for a MORPH with a ramp, else the move. */
        val word: String get() = when {
            drifted -> "DRIFT"
            becomeMs > 0 -> "BECOME"
            else -> mode
        }
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
        // BECOME is MORPH's alone and 1..MAX_BECOME_MS; anything else a hand
        // or another build left (a string, NaN, a negative, a huge number, a
        // ramp on another move) reads as no ramp, never a throw.
        val becomeMs = (m.entries["become"] as? JsonValue.Num)?.value
            ?.takeIf { mode.uppercase() == Mutate.Mode.MORPH.name && it.isFinite() && it >= 1.0 && it <= Mutate.MAX_BECOME_MS }
            ?.roundToInt() ?: 0
        return Applied(mode.uppercase(), with, drifted, becomeMs)
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
     * The card's two steppers as the six knob slots [Mutate.render] and
     * [Mutate.apply] take.
     *
     * One place, because HEAR and KEEP have to agree exactly: a preview
     * that computed its own splice point would play a sound the write
     * then did not make, which is the whole promise of auditioning first.
     * The move's knob fills its own slot; every other slot keeps the
     * verb's default, since a move never reads a knob that is not its.
     * BECOME is MORPH's second slot and 0 for every other move.
     */
    private data class Knobs(
        val spliceAtMs: Int,
        val crossoverHz: Float,
        val morphAmount: Float,
        val roomMix: Float,
        val bands: Int,
        val becomeMs: Int,
    )

    private fun knobs(mode: Mutate.Mode, fraction: Float, becomeFraction: Float): Knobs {
        val v = knobFor(mode)?.let { value(it, fraction) }
        return Knobs(
            spliceAtMs = if (mode == Mutate.Mode.SPLICE) v!!.roundToInt() else Mutate.DEFAULT_SPLICE_MS,
            crossoverHz = if (mode == Mutate.Mode.SPLIT) v!! else Mutate.DEFAULT_CROSSOVER_HZ,
            morphAmount = if (mode == Mutate.Mode.MORPH) v!! else 0.5f,
            roomMix = if (mode == Mutate.Mode.ROOM) v!! else 0.5f,
            bands = if (mode == Mutate.Mode.TRANSPLANT) v!!.roundToInt() else com.snipsnap.audio.Transplant.DEFAULT_BANDS,
            becomeMs = becomeMs(mode, becomeFraction),
        )
    }

    /**
     * BECOME's milliseconds for [mode] at [becomeFraction]: MORPH's alone,
     * 0 on every other move. One place, read by [knobs] for the render and
     * by [outcomeLine] for the words, so the line under the moves says the
     * hit turns exactly when the render turns it.
     */
    private fun becomeMs(mode: Mutate.Mode, becomeFraction: Float): Int =
        becomeFor(mode)?.let { value(it, becomeFraction).roundToInt() } ?: 0

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
     * of range is out of range here too. [becomeFraction] is the BECOME
     * stepper's position, read by MORPH alone.
     */
    fun preview(model: KitBuilderModel, slot: Int, partner: Partner, mode: Mutate.Mode, fraction: Float, becomeFraction: Float = 0f): Snip {
        require(partner !is Partner.Pad || partner.slot != slot) { "a pad can't be its own parent" }
        // The rewrite's own gate, not a copy of it: a velocity-layered pad
        // or a round-robin chain refuses here exactly as it refuses inside
        // `replaceAudio`, so HEAR never plays a move KEEP would decline.
        val pad = model.requireRewritable(slot)
        val base = WavReader.read(File(model.kitDir, pad.sampleFile))
        val k = knobs(mode, fraction, becomeFraction)
        return Mutate.render(
            base, listOf(source(model, partner)), mode,
            spliceAtMs = k.spliceAtMs,
            crossoverHz = k.crossoverHz,
            morphAmount = k.morphAmount,
            roomMix = k.roomMix,
            bands = k.bands,
            becomeMs = k.becomeMs,
        ).snip
    }

    /**
     * MUTATE: [slot] and [partner] become one hit by [mode]; [fraction] is
     * the stepper's position on the move's knob (ignored by STACK), and
     * [becomeFraction] the BECOME stepper's (read by MORPH alone). Through
     * [Mutate.apply], so the bin, the recipe and the provenance are exactly
     * the CLI's; a roulette deal records its seed like `--roulette` does.
     */
    fun apply(model: KitBuilderModel, slot: Int, partner: Partner, mode: Mutate.Mode, fraction: Float, becomeFraction: Float = 0f): Mutate.Outcome {
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
        val k = knobs(mode, fraction, becomeFraction)
        return Mutate.apply(
            model, slot, listOf(source(model, partner)), mode,
            spliceAtMs = k.spliceAtMs,
            crossoverHz = k.crossoverHz,
            morphAmount = k.morphAmount,
            roomMix = k.roomMix,
            bands = k.bands,
            becomeMs = k.becomeMs,
            extraRecipe = extra,
        )
    }

    /** The parents back apart: the pre-mutation audio out of the bin, recipe and parent stamp cleared. */
    fun undo(model: KitBuilderModel, slot: Int): KitPad = Mutate.undo(model, slot)

    // ---------- the card's words (the redesign's round M1) ----------
    //
    // Card labels, not `Copy` constants: PersonalityTest's reflective shout
    // law cannot see them, so MutateWordsTest holds them to the house style
    // (capitals, a 44-character row, no retired word, one meaning per word).

    /** A full row of the card, in characters (`docs/UI_DESIGN.md`'s 44-character budget). */
    const val ROW_CHARS = 44

    /** The longest a partner's name runs on the card: the TERRA spec's `MAX_FROM_CHARS`, for the shared chooser. */
    const val NAME_CHARS = 24

    /** HEAR plays the move on the pad and never writes; its label says so. */
    const val HEAR_LABEL = "▶ HEAR THE RESULT"

    /** The line under HEAR. */
    const val NOTE_LINE = "NOTHING CHANGES YOUR PAD UNTIL YOU KEEP IT"

    /** The commit button. KEEP writes in place, so it carries no `▸`, which the house keeps for "goes somewhere". */
    const val KEEP_LABEL = "KEEP"

    /** DRIFT beside KEEP: it picks a partner, blends toward it and saves, in one tap, and its label says it saves. */
    const val DRIFT_LABEL = "DRIFT · BLEND & SAVE"

    /** ROULETTE at rest: it only picks a partner, and writes nothing. */
    const val ROULETTE_LABEL = "ROULETTE · PICK A PARTNER OFF THE SHELF"

    /** The partner panel's room row with no room kept (drawn from round M2). It avoids KEEP ROOM: KEEP is the card's commit verb. */
    const val NO_ROOM_LINE = "NO ROOM YET · MAKE ONE IN OUTSIDE ▸ ROOM"

    /** The partner panel's other-kit row on a one-kit shelf (drawn from round M2). */
    const val NO_OTHER_KIT_LINE = "NO OTHER KIT ON THE SHELF"

    /** STACK's knob row, where the bar would be: `design/mutate-v2/Moves.dc.html:45`, verbatim. */
    const val deadKnobLine = "NO KNOB — THEY LINE UP ON THE HIT"

    /** One line per move, under the move chips: `design/mutate-v2/Moves.dc.html:45-50`, verbatim. */
    private val MOVE_LINES: Map<Mutate.Mode, String> = mapOf(
        Mutate.Mode.STACK to "BOTH AT ONCE. THICKER.",
        Mutate.Mode.SPLICE to "THIS ATTACK, THAT TAIL.",
        Mutate.Mode.SPLIT to "MY LOWS, THEIR HIGHS.",
        Mutate.Mode.MORPH to "A HIT BETWEEN THE TWO.",
        Mutate.Mode.ROOM to "MY HIT, PLAYED IN THEIR ROOM.",
        Mutate.Mode.TRANSPLANT to "MY TIMING, THEIR TONE.",
    )

    /** MORPH's line while BECOME is above OFF (the brief's). */
    private const val BECOME_LINE = "STARTS AS MINE, TURNS INTO THE MIX."

    /** What each knob means, after its value: `design/mutate-v2/Moves.dc.html:46-50`, verbatim. */
    private val KNOB_MEANINGS: Map<Mutate.Mode, String> = mapOf(
        Mutate.Mode.SPLICE to "WHERE THEY HAND OVER",
        Mutate.Mode.SPLIT to "WHERE LOWS BECOME HIGHS",
        Mutate.Mode.MORPH to "HOW FAR TOWARD THEM",
        Mutate.Mode.ROOM to "HOW MUCH ROOM",
        Mutate.Mode.TRANSPLANT to "HOW FINELY TONE IS READ",
    )

    /**
     * The line under the move chips: what HEAR would play. MORPH's turns to
     * BECOME's line exactly when [becomeFraction] maps above 0 ms, through
     * the same mapping the render reads ([becomeMs]).
     */
    fun outcomeLine(mode: Mutate.Mode, becomeFraction: Float): String =
        if (becomeMs(mode, becomeFraction) > 0) BECOME_LINE else MOVE_LINES.getValue(mode)

    /** The knob's meaning, drawn after its value; null for STACK, which has no knob ([deadKnobLine] instead). */
    fun knobMeaning(mode: Mutate.Mode): String? = KNOB_MEANINGS[mode]

    /** BECOME's row: its meaning after the value on MORPH; on every other move, the words its dead row draws. */
    fun becomeMeaning(mode: Mutate.Mode): String =
        if (mode == Mutate.Mode.MORPH) "HOW LONG THE TURN TAKES" else "ONLY MORPH TURNS OVER TIME"

    /**
     * The partner as the pairing line names it, in capitals, at most
     * [NAME_CHARS]: a pad on this kit by its tag and name (`B07 KICK`,
     * [padName] giving the name), another kit's pad as `SOUL B02`, a
     * ROULETTE pick as the strip names it (`SOUL A03`), a room or a file
     * by its name. A cut never goes through the tag.
     */
    fun partnerName(partner: Partner, padName: (Int) -> String? = { null }): String = cutName(
        when (partner) {
            is Partner.Pad -> listOfNotNull(padTag(partner.slot), padName(partner.slot)?.takeIf { it.isNotBlank() }).joinToString(" ")
            is Partner.Other -> "${partner.kitName} ${padTag(partner.slot)}"
            is Partner.Deal -> PadSheetBoxes.parentName(partner.label)
            is Partner.Room -> partner.name
            is Partner.Wav -> partner.label
        }.uppercase(java.util.Locale.ROOT),
        NAME_CHARS,
    )

    /** The partner's short name, for the keep toast (and KEEP's label from round M2): a pad on this kit by its tag alone, every other kind as [partnerName]. */
    fun partnerShort(partner: Partner): String =
        if (partner is Partner.Pad) padTag(partner.slot) else partnerName(partner)

    /** Between the pad and its partner on the pairing line. */
    private const val PAIR_SEP = " × "

    /** The pairing line's empty state, after the pad: never cut. */
    private const val NO_PARTNER_TAIL = " × ?  — PICK A PARTNER"

    /**
     * The card's top line: the pad × its partner (`A02 SNARE × B07 KICK`),
     * or `A02 SNARE × ?  — PICK A PARTNER` with none, within one row
     * ([ROW_CHARS]). When both sides do not fit, each is cut to an even
     * share, its tag kept whole; a side shorter than its share gives the
     * rest to the other. The empty state's tail is never cut: the pad's
     * name gives way and its tag stays. [partnerName] is [partnerName]'s.
     */
    fun pairLine(padTag: String, padName: String, partnerName: String?): String {
        val mine = pairSide(padTag, padName)
        if (partnerName == null) return cutName(mine, ROW_CHARS - NO_PARTNER_TAIL.length) + NO_PARTNER_TAIL
        val room = ROW_CHARS - PAIR_SEP.length
        val (mineMax, theirsMax) = when {
            mine.length + partnerName.length <= room -> mine.length to partnerName.length
            mine.length <= room / 2 -> mine.length to room - mine.length
            partnerName.length <= room - room / 2 -> room - partnerName.length to partnerName.length
            else -> room / 2 to room - room / 2
        }
        return cutName(mine, mineMax) + PAIR_SEP + cutName(partnerName, theirsMax)
    }

    /** [pairLine] uncut, for TalkBack. */
    fun pairSpoken(padTag: String, padName: String, partnerName: String?): String =
        pairSide(padTag, padName) + (if (partnerName == null) NO_PARTNER_TAIL else PAIR_SEP + partnerName)

    private fun pairSide(padTag: String, padName: String): String =
        "$padTag ${padName.uppercase(java.util.Locale.ROOT)}".trimEnd()

    /** A pad tag at the start of a name (`A02 SNARE`) or at its end (`SOUL B02`): [PadBanks.tag]'s shape. */
    private val LEADING_TAG = Regex("""^[A-Z]\d{2}(?= |$)""")
    private val TRAILING_TAG = Regex("""(?<=^| )[A-Z]\d{2}$""")

    /**
     * [name] cut to [max] characters, ending in `…` where it is cut, never
     * through a pad tag: a leading tag stays whole (`A02 SNA…`), so does a
     * trailing one (`SO… B02`), and where not one character of the rest
     * fits, the tag stands alone. A name with no tag (a room, a file) is
     * cut at the end.
     */
    private fun cutName(name: String, max: Int): String {
        if (name.length <= max) return name
        LEADING_TAG.find(name)?.let { m ->
            val rest = name.substring(m.value.length).trimStart()
            val keep = max - m.value.length - 2 // a space and the ellipsis
            return if (keep < 1 || rest.isEmpty()) m.value else "${m.value} ${rest.take(keep).trimEnd()}…"
        }
        TRAILING_TAG.find(name)?.let { m ->
            val head = name.substring(0, m.range.first).trimEnd()
            val keep = max - m.value.length - 2
            return if (keep < 1 || head.isEmpty()) m.value else "${head.take(keep).trimEnd()}… ${m.value}"
        }
        return name.take(max - 1).trimEnd() + "…"
    }
}
