package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.roundToInt

/**
 * Renders the ETCH round-K2 listening clips from the Kotlin engine under testkit/etch-audition/k2/ (gitignored),
 * with a manifest the page builder reads. Run via `./gradlew :synth:generateEtchAudition`.
 *
 * The K2 study: short, low one-shots read as KICK or TOM to the classifier, which names saved sounds by what it
 * hears. Three ways a one-shot can end are rendered side by side, with the classifier's count for each over the
 * whole grid (six voices, seven notes, three velocities, six HOLD settings):
 * - A, as accepted: the amplifier lets go in 7 ms when the gate closes.
 * - B, ring-out: a 150 ms release on every note.
 * - C, low ring-out: 60 ms from C3 up, rising to 180 ms at C1, and RUBBER (the sub-octave voice) on B's release.
 * Loops are not affected by any of them.
 */
internal object EtchAuditionGenerator {

    /** The three ways a one-shot can end. */
    enum class Ending(val label: String) {
        A("A as accepted"), B("B ring-out"), C("C low ring-out");

        fun shape(voice: EtchVoice): Etch.OneShotShape = when (this) {
            A -> Etch.OneShotShape.ACCEPTED
            B -> RING_OUT
            C -> if (voice == EtchVoice.RUBBER) RING_OUT else LOW_RING_OUT
        }
    }

    val RING_OUT = Etch.OneShotShape(releaseSeconds = .15)
    val LOW_RING_OUT = Etch.OneShotShape(releaseSeconds = .06, releaseLowSeconds = .18)

    private val DRUMS = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)
    val GRID_MIDI = listOf(24, 30, 36, 43, 48, 55, 60)
    val GRID_VELOCITY = listOf(.3f, .6f, 1f)
    val GRID_HOLD = listOf(0f, .1f, .2f, .3f, .4f, .6f)

    /** Every grid one-shot under [ending] that the classifier puts in the drum set, as (voice, midi, velocity, hold, class). */
    fun drumReadings(ending: Ending): List<String> {
        val bad = mutableListOf<String>()
        for (voice in EtchVoice.entries) for (midi in GRID_MIDI) for (vel in GRID_VELOCITY) for (hold in GRID_HOLD) {
            val snip = Etch.renderOneShotShaped(voice, Etch.defaults(voice) + ("HOLD" to hold), midi, vel, ending.shape(voice))
            val c = Classifier.classify(FeatureExtractor.extract(snip)).drumClass
            if (c in DRUMS) bad += "$voice MIDI $midi vel $vel HOLD $hold: $c"
        }
        return bad
    }

    /** Places [hits] (start seconds, snip) into one buffer of [seconds], summed: a sampler with no choke. */
    private fun place(seconds: Double, hits: List<Pair<Double, Snip>>): Snip {
        val out = FloatArray((seconds * Dsp.RATE).roundToInt())
        for ((at, snip) in hits) {
            val start = (at * Dsp.RATE).roundToInt()
            for (i in snip.samples.indices) { val j = start + i; if (j < out.size) out[j] += snip.samples[i] }
        }
        return Snip(out, 1, Dsp.RATE)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/etch-audition/k2")
        root.deleteRecursively()
        root.mkdirs()
        val files = mutableListOf<String>()

        fun write(section: String, id: String, snip: Snip, meta: String = "") {
            val dir = File(root, section).also { it.mkdirs() }
            WavWriter.write(File(dir, "$id.wav"), AuditionLevel.level(snip), WavWriter.BitDepth.PCM_16)
            files += "{\"file\": \"$section/$id.wav\"$meta}"
        }
        fun tag(voice: EtchVoice) = voice.name.lowercase()

        // 1. The ending study: C1, C2 and C3, each at HOLD 0, 0.2 and 0.4, one second apart, velocity 0.6.
        for (voice in EtchVoice.entries) for (ending in Ending.entries) {
            val hits = mutableListOf<Pair<Double, Snip>>()
            var t = 0.0
            for (midi in listOf(24, 36, 48)) for (hold in listOf(0f, .2f, .4f)) {
                hits += t to Etch.renderOneShotShaped(voice, Etch.defaults(voice) + ("HOLD" to hold), midi, .6f, ending.shape(voice))
                t += 1.0
            }
            write("ending", "${tag(voice)}-${ending.name.lowercase()}", place(t + 0.5, hits))
        }
        // 2. Short notes played as pad hits: a two-bar riff of HOLD 0.1 one-shots at 120 BPM, eighth notes.
        val riff = listOf(0, 0, 12, 0, 3, 0, 7, 10, 0, 0, 12, 0, 15, 12, 7, 3)
        for (voice in listOf(EtchVoice.SAW, EtchVoice.RUBBER, EtchVoice.DUB)) for (ending in Ending.entries) {
            val step = 0.25
            val hits = riff.mapIndexed { i, n ->
                val vel = if (i % 4 == 0) 1f else .55f
                i * step to Etch.renderOneShotShaped(voice, Etch.defaults(voice) + ("HOLD" to .1f), 36 + n, vel, ending.shape(voice))
            }
            write("riff", "${tag(voice)}-${ending.name.lowercase()}", place(riff.size * step + 1.0, hits))
        }
        // 3. The current presets, rendered by the Kotlin engine.
        for (p in EtchPresets.all()) {
            val id = "${tag(p.voice)}-${p.name.lowercase().replace(' ', '-')}"
            write("presets", id, p.render(), ", \"voice\": \"${p.voice}\", \"name\": \"${p.name}\", \"class\": \"${Etch.drumClassFor(p.voice, p.macros)}\"")
        }
        // The classifier over the whole grid, per ending.
        val readings = Ending.entries.joinToString(",\n") { e ->
            val bad = drumReadings(e)
            "  \"${e.name}\": {\"label\": \"${e.label}\", \"drums\": ${bad.size}, \"total\": ${EtchVoice.entries.size * GRID_MIDI.size * GRID_VELOCITY.size * GRID_HOLD.size}, " +
                "\"examples\": [${bad.take(8).joinToString(", ") { "\"$it\"" }}]}"
        }
        File(root, "manifest.json").writeText("{\"model\": ${Etch.MODEL_VERSION}, \"endings\": {\n$readings\n},\n\"files\": [\n" +
            files.joinToString(",\n") + "\n]}\n")
        println("wrote ${files.size} clips + manifest.json under ${root.absolutePath}")
    }
}
