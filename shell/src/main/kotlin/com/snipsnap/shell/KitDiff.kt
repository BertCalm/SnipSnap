package com.snipsnap.shell

import com.snipsnap.json.JsonValue
import com.snipsnap.kit.ChainInfo
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.xpm.PadNoteMap
import java.util.Locale
import kotlin.math.roundToInt

/**
 * SINCE T3: what changed between two snapshots of one kit, said in the
 * pad's own words — the TAKES card's expander, so a roll-back is a
 * decision about named differences rather than a leap of faith on a
 * timestamp.
 *
 * Keyed by SLOT, never by position: `takes()` are whole-kit `kit.json`
 * snapshots of the same kit, so A03 in one take IS A03 in the other, and
 * a pad added on B02 must not make every later pad read as changed (the
 * way a positional diff like `MpcDiff` would, comparing two unrelated
 * programs).
 *
 * Honest about its own reach, in [Copy.TAKES_DIFF_CAVEAT]'s words too:
 * this reads `kit.json`, not the WAVs. Every rewrite door
 * (`replaceAudio` and everything built on it) writes the new audio under
 * the SAME filename and records what it did in the pad's recipe — so a
 * treatment shows up here through its recipe, and a rewrite that changed
 * no recipe (a doctor pass that left one already there, say) does not
 * show up at all. Provenance (`source`) is ignored on purpose: it is a
 * note about where a sound came from, not a change to the sound.
 */
object KitDiff {

    /** One named difference. [slot] is the pad it belongs to; null for a kit-level change (name, key, tempo, wear). */
    data class Change(val slot: Int?, val text: String)

    /**
     * Every difference from [from] to [to], kit-level lines first, then
     * pads in slot order. Empty when the two snapshots agree on everything
     * this diff reads. Read the direction as "since [from]": a pad in [to]
     * only was ADDED, a pad in [from] only was CLEARED.
     */
    fun changes(from: Kit, to: Kit): List<Change> {
        val out = mutableListOf<Change>()
        if (from.name != to.name) out += Change(null, "RENAMED \"${from.name}\" → \"${to.name}\"")
        if (from.key != to.key) {
            out += Change(null, "KEY ${from.key?.label?.uppercase() ?: "NONE"} → ${to.key?.label?.uppercase() ?: "NONE"}")
        }
        if (from.tempoBpm != to.tempoBpm) {
            // The unit rides each value, so a cleared tempo reads "92.5 BPM
            // → NONE", never "92.5 → NONE BPM".
            out += Change(null, "TEMPO ${bpm(from.tempoBpm)} → ${bpm(to.tempoBpm)}")
        }
        // Bound to locals first: these are `val`s of a class from another
        // module, and Kotlin won't smart-cast those after a null check.
        val wearA = from.wear
        val wearB = to.wear
        if (wearA != wearB) {
            out += Change(
                null,
                when {
                    wearA == null -> "TAPE WEAR ON"
                    wearB == null -> "TAPE WEAR OFF"
                    wearA.enabled != wearB.enabled -> if (wearB.enabled) "TAPE WEAR ON" else "TAPE WEAR PAUSED"
                    else -> "TAPE WEAR ${fmt1(wearA.mileage)} → ${fmt1(wearB.mileage)} MILES"
                },
            )
        }

        val before = from.pads.associateBy { it.slot }
        val after = to.pads.associateBy { it.slot }
        for (slot in (before.keys + after.keys).sorted()) {
            val a = before[slot]
            val b = after[slot]
            val label = PadNoteMap.labelForPad(slot)
            when {
                a == null && b != null -> out += Change(slot, "$label ADDED: ${b.displayName.uppercase()}")
                a != null && b == null -> out += Change(slot, "$label CLEARED: ${a.displayName.uppercase()}")
                a != null && b != null -> {
                    val parts = padChanges(a, b)
                    if (parts.isNotEmpty()) out += Change(slot, "$label ${parts.joinToString(" · ")}")
                }
            }
        }
        return out
    }

    /** "NO CHANGES", "1 CHANGE", "7 CHANGES" — the row's own collapsed summary. */
    fun headline(changes: List<Change>): String = when (changes.size) {
        0 -> "NO CHANGES"
        1 -> "1 CHANGE"
        else -> "${changes.size} CHANGES"
    }

    /**
     * The one-line name for a pad's recipe, in the words the door that
     * wrote it already uses on its own card: the PAD SHEET treatments
     * through [PadSheet.read] (era, character, keyed — with the AMT),
     * MUTATE and OUTSIDE through their own sheets' readers, and a short
     * word each for the recipes no card reads back (round robin, splice,
     * doctor, clean, sculpt, smear, a synth patch). Anything else is
     * "RECIPE" — a change is still reported, just not named.
     */
    fun recipeName(recipe: JsonValue.Obj): String {
        PadSheet.read(recipe)?.let { applied ->
            val word = applied.segment?.let { PadSheet.displayLabel(it) } ?: applied.treatment.name.uppercase()
            // Rounded, not truncated — the AMT stepper on the card itself
            // prints `roundToInt()`, and this line must agree with it.
            return "$word ${(applied.amount * 100).roundToInt()}%"
        }
        MutateSheet.read(recipe)?.let { return "MUTATED: ${it.word}" }
        OutsideSheet.read(recipe)?.let { return "OUTSIDE: ${it.move}" }
        val e = recipe.entries
        (e["robin"] as? JsonValue.Obj)?.let { r ->
            val takes = (r.entries["takes"] as? JsonValue.Num)?.value?.toInt()
            return if (takes != null) "ROUND ROBIN ×$takes" else "ROUND ROBIN"
        }
        if ("splice" in e) return "SPLICED"
        (e["doctor"] as? JsonValue.Str)?.let { return "DOCTORED: ${it.value.uppercase()}" }
        if ("clean" in e) return "CLEANED"
        (e["sculpt"] as? JsonValue.Obj)?.let { s ->
            val mode = (s.entries["mode"] as? JsonValue.Str)?.value
            return if (mode != null) "SCULPTED: ${mode.uppercase()}" else "SCULPTED"
        }
        (e["verb"] as? JsonValue.Str)?.let { return it.value.uppercase() }
        if ("patch" in e) return "SYNTH PATCH"
        return "RECIPE"
    }

    private fun padChanges(a: KitPad, b: KitPad): List<String> {
        val parts = mutableListOf<String>()
        // Locals, not `a.recipe`/`a.chain` directly: cross-module `val`s
        // don't smart-cast after a null check (see `changes` above).
        val recipeA = a.recipe
        val recipeB = b.recipe
        if (recipeA != recipeB) {
            parts += when {
                recipeB == null -> "UNTREATED"
                recipeA == null -> "TREATED: ${recipeName(recipeB)}"
                else -> "${recipeName(recipeA)} → ${recipeName(recipeB)}"
            }
        }
        if (a.sampleFile != b.sampleFile) parts += "AUDIO ${a.sampleFile.uppercase()} → ${b.sampleFile.uppercase()}"
        if (a.displayName != b.displayName) parts += "NAMED ${b.displayName.uppercase()}"
        if (a.drumClass != b.drumClass) parts += "CLASS ${a.drumClass.name} → ${b.drumClass.name}"
        if (a.colorHex != b.colorHex) parts += "COLOUR ${a.colorHex?.uppercase() ?: "AUTO"} → ${b.colorHex?.uppercase() ?: "AUTO"}"
        if (a.level != b.level) parts += "LEVEL ${fmt2(a.level)} → ${fmt2(b.level)}"
        if (a.pan != b.pan) parts += "PAN ${fmt2(a.pan)} → ${fmt2(b.pan)}"
        if (a.tuneCoarse != b.tuneCoarse) parts += "TUNE ${signed(a.tuneCoarse)} → ${signed(b.tuneCoarse)}"
        if (a.tuneFine != b.tuneFine) parts += "FINE ${signed(a.tuneFine)} → ${signed(b.tuneFine)}"
        if (a.muteGroup != b.muteGroup) parts += "MUTE GROUP ${a.muteGroup} → ${b.muteGroup}"
        if (a.oneShot != b.oneShot) parts += if (b.oneShot) "ONE-SHOT ON" else "ONE-SHOT OFF"
        shape(parts, "ATTACK", a.attack, b.attack)
        shape(parts, "DECAY", a.decay, b.decay)
        shape(parts, "CUTOFF", a.cutoff, b.cutoff)
        shape(parts, "RESONANCE", a.resonance, b.resonance)
        shape(parts, "HUMANIZE", a.humanize, b.humanize)
        if (a.velocityLayers != b.velocityLayers) {
            parts += "LAYERS ${zoneCount(a)} → ${zoneCount(b)}"
        }
        val chainA = a.chain
        val chainB = b.chain
        if (chainA != chainB) {
            parts += when {
                chainB == null -> "UNCHAINED"
                chainA == null -> "CHAINED: ${chainWord(chainB)}"
                else -> "CHAIN ${chainWord(chainA)} → ${chainWord(chainB)}"
            }
        }
        return parts
    }

    /**
     * A chain in the two numbers that matter: how many slices the WAV holds,
     * and what a hit actually steps through — `PadHit` plays
     * `zone?.cycle ?: chain.cycle`, so a single-zone chain is named by its
     * cycle and a zoned one by its zone count (each zone carries its own
     * cycle; naming just one of them would be a lie about the rest).
     */
    private fun chainWord(c: ChainInfo): String {
        val zones = c.zones
        val play = if (zones != null) "${zones.size} ZONES" else "CYCLE ${c.cycle}"
        return "${c.boundaries.size} SLICES, $play"
    }

    /** A pad's zone count as the card says it: 1 for the classic single-sample pad, else its layers. */
    private fun zoneCount(p: KitPad): Int = if (p.velocityLayers.isEmpty()) 1 else p.velocityLayers.size

    /** Shape fields are null when the format's own default applies — said as DEFAULT, never as a made-up number. */
    private fun shape(parts: MutableList<String>, what: String, a: Float?, b: Float?) {
        if (a == b) return
        parts += "$what ${a?.let { fmt2(it) } ?: "DEFAULT"} → ${b?.let { fmt2(it) } ?: "DEFAULT"}"
    }

    private fun signed(v: Int): String = if (v > 0) "+$v" else "$v"
    private fun bpm(v: Float?): String = if (v == null) "NONE" else "${fmt1(v)} BPM"
    private fun fmt1(v: Float): String = String.format(Locale.ROOT, "%.1f", v)
    /** Mileage is a Double on the ledger — formatted as one, not through a Float that could move the tenth. */
    private fun fmt1(v: Double): String = String.format(Locale.ROOT, "%.1f", v)
    private fun fmt2(v: Float): String = String.format(Locale.ROOT, "%.2f", v)
}
