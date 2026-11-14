package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.AtomicFile
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import com.snipsnap.synth.Osteria
import com.snipsnap.synth.OsteriaPatch
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import com.snipsnap.xpm.VoiceMode
import java.io.File
import java.security.MessageDigest

/** Native single-note keygroups, independent of the polyphonic strum study. No synthetic sustain loop. */
object OsteriaKeygroupBank {
    val roots: List<Int> = (Osteria.MIN_MIDI..Osteria.MAX_MIDI step 4).toList()
    val velocities: List<Float> = listOf(.25f, .6f, 1f)
    private val velocityWindows = listOf(0..41,42..84,85..127)
    data class Cell(val midi: Int, val touch: Int)
    data class Note(val patch: OsteriaPatch, val snip: Snip)
    data class Assembly(val program: KeygroupProgram, val samples: Map<String, Snip>, val recipes: Map<String, OsteriaPatch>)

    fun recipes(patch: OsteriaPatch): Map<Cell, OsteriaPatch> {
        require(patch.config.model == 2 && patch.strum == null) { "A single-note fixed-root model two is required; chord STRUM is not a pitched keygroup" }
        return roots.flatMap { midi -> velocities.indices.map { touch ->
            Cell(midi, touch) to patch.copy(midi = midi, velocity = velocities[touch])
        } }.toMap()
    }

    /** The caller may render incrementally/cancellably. Assembly refuses incomplete or relabelled cells. */
    fun assemble(name: String, patch: OsteriaPatch, notes: Map<Cell, Note>, voiceMode: VoiceMode = VoiceMode.POLY): Assembly {
        require(Names.isMpcSafe(name) && name.length <= com.snipsnap.kit.OneNote.MAX_NAME_LENGTH)
        val expected = recipes(patch)
        require(notes.keys == expected.keys) { "All 13 roots and three native velocity layers are required" }
        val samples = linkedMapOf<String, Snip>()
        val saved = linkedMapOf<String, OsteriaPatch>()
        val groups = roots.mapIndexed { index, midi ->
            val layers = velocities.indices.map { touch ->
                val cell = Cell(midi, touch); val note = notes.getValue(cell)
                require(note.patch.toJsonText() == expected.getValue(cell).toJsonText()) { "Source recipe differs at $cell" }
                require(note.snip.channels == 2 && note.snip.sampleRate == WavWriter.MPC_SAMPLE_RATE && note.snip.frameCount > 0)
                require(note.snip.samples.all { it.isFinite() })
                val stem = "Osteria_${midi}_T${touch+1}"
                samples[stem] = note.snip; saved[stem] = note.patch
                val window = velocityWindows[touch]
                VelocityLayer(stem, note.snip.frameCount.toLong(), velStart=window.first, velEnd=window.last)
            }
            Keygroup(
                lowNote = if (index == 0) midi else (roots[index-1] + midi)/2 + 1,
                highNote = if (index == roots.lastIndex) midi else (midi + roots[index+1])/2,
                rootNote = midi, layers = layers,
            )
        }
        return Assembly(KeygroupProgram(name, groups, volumeRelease=patch.config.releaseSeconds.toFloat(), explicitNoteOn=true, voiceMode=voiceMode), samples, saved)
    }

    fun export(name: String, patch: OsteriaPatch, notes: Map<Cell, Note>, destination: File,
               overwrite: Boolean = false, voiceMode: VoiceMode = VoiceMode.POLY): Assembly =
        OsteriaKeygroupTransaction.export(name, patch, notes, destination, overwrite, voiceMode=voiceMode)

    internal fun writeStaged(name: String, patch: OsteriaPatch, notes: Map<Cell, Note>, destination: File, voiceMode: VoiceMode): Assembly {
        val built = assemble(name, patch, notes, voiceMode)
        OneNote.writePackage(name, built.program, built.samples, destination, overwrite=false, spanKeyboard=false)
        val data = File(destination, "${name}_[TrackData]")
        val bindings = built.recipes.map { (stem, source) ->
            val wav = File(data, "$stem.wav")
            val hash = MessageDigest.getInstance("SHA-256").digest(wav.readBytes()).joinToString("") { "%02x".format(it) }
            JsonValue.Obj(linkedMapOf("sample" to JsonValue.Str(wav.name), "sha256" to JsonValue.Str(hash), "recipe" to source.toJsonValue()))
        }
        // Retain the published v1 metadata schema. OsteriaPadMaker.read/reexport validates it;
        // generic keygroup readers retain sampler mappings but do not restore synth editing.
        AtomicFile.writeText(File(data,"osteria-source-recipes.json"), Json.write(JsonValue.Obj(linkedMapOf(
            "studyVersion" to JsonValue.Num(1.0), "sourcePatch" to patch.toJsonValue(), "samples" to JsonValue.Arr(bindings),
        ))))
        return built
    }
}
