package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Scale
import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.audio.Tuner
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import kotlin.math.roundToInt

/**
 * SPREAD: one sound laid across a bank as a playable scale — the MPC's own
 * "16 levels of tune" idea, but in key. The keys-on-pads layout is
 * [Scales.layout]'s (root on the bank's first pad, ascending left to right,
 * bottom to top), so a spread bank plays exactly like KEYS does.
 *
 * Every pad gets its own copy of the sample and reaches its note through
 * the pad's tune fields, the same non-destructive move IN KEY makes: the
 * audio is untouched, the pitch lives in metadata the MPC already has.
 * The shift is measured from the sample's detected pitch, so a note that
 * was a few cents flat lands every pad in tune, not a few cents flat.
 */
object Spread {

    const val MIN_PADS = 4
    const val MAX_PADS = PadBanks.SIZE

    /** Where a sample with no confident pitch is taken to sound: C4, so A01 plays it unshifted at the default root. */
    const val ASSUMED_MIDI = 60

    /** The root [Options.rootMidi] may move across: C1..C7. */
    const val ROOT_MIN = 24
    const val ROOT_MAX = 96

    /** Banks SPREAD offers: A and B, the two the pad grid shows. */
    const val BANKS = 2

    /** Provenance keys a spread pad carries: its note, and where the sound came from. */
    const val NOTE_KEY = "spreadNote"
    const val FROM_KEY = "spreadFrom"

    /** The scales, in the order the chips draw them. */
    val SCALES: List<Scale> = listOf(
        Scale.MAJOR, Scale.MINOR, Scale.MAJOR_PENTATONIC, Scale.MINOR_PENTATONIC, Scale.CHROMATIC,
    )

    fun scaleLabel(scale: Scale): String = when (scale) {
        Scale.MAJOR -> "MAJOR"
        Scale.MINOR -> "MINOR"
        Scale.MAJOR_PENTATONIC -> "MAJ PENT"
        Scale.MINOR_PENTATONIC -> "MIN PENT"
        Scale.CHROMATIC -> "CHROMATIC"
    }

    data class Options(
        /** The note the bank's first pad plays. */
        val rootMidi: Int,
        val scale: Scale,
        val padCount: Int = MAX_PADS,
        /** 0 = bank A, 1 = bank B. */
        val bank: Int = 0,
        /** Every spread pad shares one free choke group: a new note cuts the last. */
        val mono: Boolean = false,
        /** Occupied pads in the way are replaced (their audio binned) rather than kept. */
        val replaceFull: Boolean = false,
    ) {
        init {
            require(rootMidi in ROOT_MIN..ROOT_MAX) { "root is MIDI $ROOT_MIN..$ROOT_MAX, got $rootMidi" }
            require(padCount in MIN_PADS..MAX_PADS) { "pad count is $MIN_PADS..$MAX_PADS, got $padCount" }
            require(bank in 0 until BANKS) { "bank is 0..${BANKS - 1}, got $bank" }
        }
    }

    /** The sample's own pitch as fractional MIDI, or null when the detector isn't confident. */
    fun detect(snip: Snip): Float? {
        val est = Pitch.detect(snip) ?: return null
        if (est.confidence < Tuner.MIN_CONFIDENCE) return null
        return Scales.hzToMidi(est.hz)
    }

    /**
     * The root a spread opens on: the note the sound plays at now — its
     * detected pitch plus any tune its pad already carries — so the
     * bank's first pad sounds exactly like the original.
     */
    fun defaultRoot(sourceMidi: Float?, tuneCoarse: Int = 0, tuneFine: Int = 0): Int =
        ((sourceMidi ?: ASSUMED_MIDI.toFloat()) + tuneCoarse + tuneFine / 100f)
            .roundToInt().coerceIn(ROOT_MIN, ROOT_MAX)

    enum class Skip { FULL, OUT_OF_REACH }

    data class Note(val slot: Int, val midi: Int, val tuneCoarse: Int, val tuneFine: Int) {
        val name: String get() = Scales.nameOf(midi)
    }

    data class Plan(
        val options: Options,
        val notes: List<Note>,
        /** Pads in the spread's range that won't take a note, and why. */
        val skipped: Map<Int, Skip>,
        /** Pads [notes] will overwrite. */
        val replacing: List<Int>,
        /** The shared choke group when [Options.mono]; 0 otherwise. */
        val muteGroup: Int,
        /** MONO was asked for and all 32 groups are held by pads the spread leaves alone. */
        val monoUnavailable: Boolean,
    ) {
        fun noteAt(slot: Int): Note? = notes.firstOrNull { it.slot == slot }

        fun isRoot(note: Note): Boolean = (note.midi - options.rootMidi) % 12 == 0
    }

    /** The slots [options] spans, first pad first. */
    fun slots(options: Options): List<Int> =
        PadBanks.slots(options.bank).first.let { first -> (first until first + options.padCount).toList() }

    /**
     * Which pad plays which note, and what each one's tune must be.
     * [sourceMidi] is the raw sample's pitch ([detect]), null when unknown
     * ([ASSUMED_MIDI] stands in). Pure: reads [kit], changes nothing.
     */
    fun plan(kit: Kit, sourceMidi: Float?, options: Options): Plan {
        val from = sourceMidi ?: ASSUMED_MIDI.toFloat()
        val offsets = Scales.layout(options.scale, options.padCount)
        val notes = mutableListOf<Note>()
        val skipped = linkedMapOf<Int, Skip>()
        val replacing = mutableListOf<Int>()
        for ((i, slot) in slots(options).withIndex()) {
            val midi = options.rootMidi + offsets[i]
            val tune = if (midi in 0..127) Tuner.coarseFine(midi - from) else null
            val occupied = kit.pad(slot) != null
            when {
                occupied && !options.replaceFull -> skipped[slot] = Skip.FULL
                tune == null -> skipped[slot] = Skip.OUT_OF_REACH
                else -> {
                    notes += Note(slot, midi, tune.first, tune.second)
                    if (occupied) replacing += slot
                }
            }
        }
        var group = 0
        var unavailable = false
        if (options.mono && notes.isNotEmpty()) {
            val landing = notes.map { it.slot }.toSet()
            val held = kit.pads.filter { it.slot !in landing }.map { it.muteGroup }.toSet()
            group = (1..32).firstOrNull { it !in held } ?: 0
            unavailable = group == 0
        }
        return Plan(options, notes, skipped, replacing, group, unavailable)
    }

    /** What is being spread, beyond its audio. */
    data class Sound(
        /** The pads' name after their note: "Bass Velvet" lands as "C3 Bass Velvet". */
        val name: String,
        val drumClass: DrumClass,
        val colorHex: String,
        /** Carried to every pad so each stays re-renderable. */
        val recipe: JsonValue.Obj? = null,
        /** A pad being spread: its level, pan, shape and one-shot ride along. */
        val like: KitPad? = null,
        val source: Map<String, String> = emptyMap(),
    )

    /**
     * Land [plan] on [model]: every note's pad gets [snip], its tune, its
     * note in its name, and the root's pads a lighter shade of [Sound.colorHex]
     * so the octaves read at a glance. The caller saves.
     */
    fun apply(model: KitBuilderModel, snip: Snip, plan: Plan, sound: Sound): List<Note> {
        val color = sound.colorHex
        val rootColor = rootTint(color)
        for (note in plan.notes) {
            model.assign(
                note.slot,
                snip,
                sound.drumClass,
                "${note.name} ${sound.name}",
                sound.source + (NOTE_KEY to note.name),
            )
            model.update(note.slot) { p ->
                val tuned = p.copy(
                    tuneCoarse = note.tuneCoarse,
                    tuneFine = note.tuneFine,
                    colorHex = if (plan.isRoot(note)) rootColor else color,
                    recipe = sound.recipe,
                    muteGroup = if (plan.muteGroup > 0) plan.muteGroup else p.muteGroup,
                )
                val like = sound.like ?: return@update tuned
                tuned.copy(
                    level = like.level,
                    pan = like.pan,
                    oneShot = like.oneShot,
                    attack = like.attack,
                    decay = like.decay,
                    cutoff = like.cutoff,
                    resonance = like.resonance,
                    humanize = like.humanize,
                )
            }
        }
        return plan.notes
    }

    /** The toast for a spread that ran: what landed, or that nothing could. */
    fun toast(plan: Plan): String {
        if (plan.notes.isEmpty()) return Copy.SPREAD_NOTHING
        val o = plan.options
        return Copy.spreadLanded(
            root = Scales.nameOf(o.rootMidi),
            scale = scaleLabel(o.scale),
            pads = plan.notes.size,
            firstPad = PadBanks.tag(plan.notes.first().slot),
            keptFull = plan.skipped.values.count { it == Skip.FULL },
            outOfReach = plan.skipped.values.count { it == Skip.OUT_OF_REACH },
            replaced = plan.replacing.size,
            chokeGroup = plan.muteGroup,
            noFreeChoke = plan.monoUnavailable,
        )
    }

    /** A pad's name without the note a previous spread put in front of it. */
    fun baseName(pad: KitPad): String {
        val note = pad.source[NOTE_KEY] ?: return pad.displayName
        return pad.displayName.removePrefix("$note ").ifBlank { pad.displayName }
    }

    /** Halfway to white: the root's shade of a pad colour. */
    fun rootTint(hex: String): String {
        require(Regex("^#[0-9a-fA-F]{6}$").matches(hex)) { "colour must be #rrggbb: $hex" }
        val rgb = hex.substring(1).toInt(16)
        fun lift(shift: Int): Int {
            val c = (rgb shr shift) and 0xff
            return c + (255 - c) / 2
        }
        return "#%02x%02x%02x".format(java.util.Locale.ROOT, lift(16), lift(8), lift(0))
    }
}
