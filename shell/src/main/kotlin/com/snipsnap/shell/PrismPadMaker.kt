package com.snipsnap.shell

import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.AtomicFile
import com.snipsnap.kit.DestinationExists
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Keys
import com.snipsnap.synth.Prism
import com.snipsnap.synth.PrismPatch
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import java.io.File

/**
 * An authored PRISM opening followed by its moving sustain, delivered as a keygroup instrument.
 * The source patch remains the complete saved repeat recipe; this independently versioned delivery
 * keeps the opening before a positive loop marker rather than repeating the entire sample.
 * Note-off release belongs to the instrument player, outside the rendered sustain cycle.
 */
object PrismPadMaker {
    const val RELEASE_MIN_SECONDS = .1f
    const val RELEASE_MAX_SECONDS = 1.5f
    const val RECIPE_SUFFIX = ".prism-opening-keys.json"
    private const val RECIPE_VERSION = 1
    val RELEASE = Knob("RELEASE", RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS, .6f, exponential = true)

    data class Spec(val patch: PrismPatch, val releaseSeconds: Float = RELEASE.default) {
        init {
            require(supports(patch)) { "PRISM opening keys require REPEAT motion and a loop HOLD" }
            require(releaseSeconds.isFinite() && releaseSeconds in RELEASE_MIN_SECONDS..RELEASE_MAX_SECONDS) {
                "release wants $RELEASE_MIN_SECONDS..$RELEASE_MAX_SECONDS s, got $releaseSeconds"
            }
        }

        /** The saved patch still uses its ordinary renderer; heldModel selects this delivery explicitly. */
        fun toJsonText(): String = Json.write(JsonValue.Obj(linkedMapOf(
            "version" to JsonValue.Num(RECIPE_VERSION.toDouble()),
            "heldModel" to JsonValue.Num(Prism.OPENING_MODEL_VERSION.toDouble()),
            "releaseSeconds" to JsonValue.Num(releaseSeconds.toDouble()),
            "patch" to patch.toJsonValue(),
        )))

        companion object {
            fun fromJsonText(text: String): Spec {
                val obj = Json.parse(text).obj()
                val version = obj["version"]?.int() ?: throw JsonException("PRISM opening keys recipe has no version")
                if (version != RECIPE_VERSION) throw JsonException("unsupported PRISM opening keys recipe version $version")
                val heldModel = obj["heldModel"]?.int() ?: throw JsonException("PRISM opening keys recipe has no held model")
                if (heldModel != Prism.OPENING_MODEL_VERSION) throw JsonException("unsupported PRISM opening keys held model $heldModel")
                val patch = PrismPatch.fromJsonValue(obj["patch"]
                    ?: throw JsonException("PRISM opening keys recipe has no patch")) as PrismPatch
                val release = obj["releaseSeconds"]?.num()?.toFloat()
                    ?: throw JsonException("PRISM opening keys recipe has no release")
                return Spec(patch, release)
            }
        }
    }

    fun supports(patch: PrismPatch): Boolean = patch.motion?.repeat == true &&
        Prism.isLoop(patch.macros["HOLD"] ?: Prism.defaults(patch.voice).getValue("HOLD"))

    fun spec(patch: PrismPatch, releaseFraction: Float): Spec =
        Spec(patch, RELEASE.value(releaseFraction).coerceIn(RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS))

    fun secondsLabel(seconds: Float): String = "%.2f s".format(java.util.Locale.ROOT, seconds)

    /** Nine minor-third roots tile the voice's supported two-octave register. */
    fun zoneMidis(spec: Spec): List<Int> = Keys.prismPadMidis(spec.patch.voice)

    /** Exact zone pitch replaces only TUNE; preview may also request any supported saved note. */
    fun renderZone(spec: Spec, midi: Int, cancelled: () -> Boolean = { false }): KeyNote {
        val patch = spec.patch
        val low = Prism.rootMidi(patch.voice)
        require(midi - low in 0..Prism.TUNE_SEMITONES) {
            "PRISM ${patch.voice} opening keys are MIDI $low..${low + Prism.TUNE_SEMITONES}, got $midi"
        }
        val macros = patch.macros + ("TUNE" to (midi - low) / Prism.TUNE_SEMITONES.toFloat())
        return Prism.openingNote(patch.voice, macros, patch.velocity, patch.template, requireNotNull(patch.motion), cancelled)
    }

    /** Assemble already rendered notes; one common package writer owns all sampler loop projections. */
    fun assemble(name: String, spec: Spec, notes: List<KeyNote>): Pair<KeygroupProgram, Map<String, Snip>> {
        require(Names.isMpcSafe(name) && name.length <= OneNote.MAX_NAME_LENGTH) {
            "instrument name isn't MPC-safe or is longer than ${OneNote.MAX_NAME_LENGTH}: '$name'"
        }
        val roots = zoneMidis(spec)
        require(notes.size == roots.size) { "want ${roots.size} pitch zones, got ${notes.size}" }
        val samples = linkedMapOf<String, Snip>()
        val groups = roots.mapIndexed { index, midi ->
            val note = notes[index]
            require(note.snip.channels == 1 && note.snip.sampleRate == WavWriter.MPC_SAMPLE_RATE) {
                "PRISM samples must be mono ${WavWriter.MPC_SAMPLE_RATE} Hz"
            }
            require(note.loopStartFrame in 1 until note.snip.frameCount.toLong() - 1) {
                "PRISM zone $midi must retain its opening and at least two sustain frames"
            }
            require(note.snip.samples.all { it.isFinite() && it in -1f..1f }) {
                "PRISM zone $midi has non-finite or out-of-range audio"
            }
            val stem = Names.sanitizeStem("${name.filterNot(Char::isWhitespace)}_${Scales.nameOf(midi)}")
            samples[stem] = note.snip
            Keygroup(
                lowNote = if (index == 0) roots.first() else (roots[index - 1] + midi) / 2 + 1,
                highNote = if (index == roots.lastIndex) roots.last() else (midi + roots[index + 1]) / 2,
                rootNote = midi,
                layers = listOf(VelocityLayer(stem, note.snip.frameCount.toLong(),
                    velStart = 0, velEnd = 127, loopStartFrame = note.loopStartFrame)),
            )
        }
        return KeygroupProgram(name, groups, volumeRelease = spec.releaseSeconds, explicitNoteOn = true) to samples
    }

    fun recipeFile(destRoot: File, name: String): File = File(destRoot, "$name$RECIPE_SUFFIX")

    fun export(name: String, spec: Spec, notes: List<KeyNote>, destRoot: File, overwrite: Boolean = false): KeygroupProgram {
        val (program, samples) = assemble(name, spec, notes)
        val recipe = recipeFile(destRoot, name)
        if (recipe.exists() && !overwrite) throw DestinationExists(recipe)
        OneNote.writePackage(name, program, samples, destRoot, overwrite)
        AtomicFile.writeText(recipe, spec.toJsonText())
        return program
    }

    /** Compatibility audio view; live preview uses renderZone's actual KeyNote loop marker. */
    fun preview(spec: Spec, cancelled: () -> Boolean = { false }): Snip {
        val patch = spec.patch
        val tune = patch.macros["TUNE"] ?: Prism.defaults(patch.voice).getValue("TUNE")
        return renderZone(spec, Prism.midiFor(patch.voice, tune), cancelled).snip
    }
}
