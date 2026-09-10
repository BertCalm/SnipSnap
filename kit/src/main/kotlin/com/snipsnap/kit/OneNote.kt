package com.snipsnap.kit

import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.KeygroupWriter
import com.snipsnap.xpm.VelocityLayer
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

/**
 * One captured note → a playable instrument. MPC keygroups pitch the
 * sample themselves, so a single pitched snip plus its detected root is a
 * full-range chromatic instrument — sample one note of anything, play it
 * up and down the keys.
 *
 * Unpitched material is refused with a reason, never guessed at: an
 * instrument rooted on noise would be wrong on every key at once.
 */
object OneNote {

    /** Below this the detector is guessing; refuse rather than mistune. */
    const val MIN_CONFIDENCE = 0.5f

    data class Result(
        val program: KeygroupProgram,
        val rootMidi: Int,
        /** "A2", "C#4" — display name of the root. */
        val rootName: String,
        val detectedHz: Float,
        val confidence: Float,
        /** The sample stem the program references. */
        val sampleStem: String,
        /** What actually gets written — trimmed/crossfaded when looped. */
        val sample: Snip,
        /** 0 = plays unlooped; otherwise the frame playback wraps back to. */
        val loopStartFrame: Long = 0,
    )

    /**
     * Build the program from a pitched snip. Throws
     * [IllegalArgumentException] with the reason when no confident pitch
     * is found.
     */
    fun program(name: String, snip: Snip, sustainLoop: Boolean = false): Result {
        require(Names.isMpcSafe(name)) { "instrument name isn't MPC-safe: '$name'" }
        require(snip.frameCount > 0) { "empty snip" }
        val est = Pitch.detect(snip)
            ?: throw IllegalArgumentException(
                "no pitch found - a one-note instrument needs a pitched note, not a hit",
            )
        require(est.confidence >= MIN_CONFIDENCE) {
            "pitch too uncertain (%.0f Hz at confidence %.2f, want >= %.2f) - ".format(java.util.Locale.ROOT, 
                est.hz, est.confidence, MIN_CONFIDENCE,
            ) + "try a cleaner sustained note"
        }
        // A note that honestly has no sustain simply plays unlooped —
        // pretending a pluck loops sounds worse than decay.
        val looped = if (sustainLoop) com.snipsnap.audio.LoopCut.sustainLoop(snip) else null
        val sample = looped?.snip ?: snip
        val rootMidi = Scales.hzToMidi(est.hz).roundToInt().coerceIn(0, 127)
        val stem = Names.sanitizeStem("${name.filter { !it.isWhitespace() }}_${Scales.nameOf(rootMidi)}")
        val program = KeygroupProgram(
            name,
            listOf(
                Keygroup(
                    lowNote = 0,
                    highNote = 127,
                    rootNote = rootMidi,
                    layers = listOf(
                        VelocityLayer(
                            stem, sample.frameCount.toLong(), velStart = 0, velEnd = 127,
                            loopStartFrame = looped?.loopStartFrame ?: 0,
                        ),
                    ),
                ),
            ),
            volumeRelease = 0.35f,
        )
        return Result(
            program, rootMidi, Scales.nameOf(rootMidi), est.hz, est.confidence, stem,
            sample = sample, loopStartFrame = looped?.loopStartFrame ?: 0,
        )
    }

    /**
     * Build and write the instrument in the dual-generation layout the
     * suite ships: `<Name>.xty` beside `<Name>_[TrackData]/` holding the
     * WAV and the MPC 2 `.xpm` twin.
     */
    fun export(
        name: String,
        snip: Snip,
        destRoot: File,
        overwrite: Boolean = false,
        sustainLoop: Boolean = false,
    ): Result {
        val result = program(name, snip, sustainLoop)
        writePackage(name, result.program, mapOf(result.sampleStem to result.sample), destRoot, overwrite)
        return result
    }

    // ---------- multisample ----------

    /** One zone of a multisampled result. */
    data class Zone(
        val rootMidi: Int,
        val rootName: String,
        val detectedHz: Float,
        val confidence: Float,
        val sampleStem: String,
        /** The label the caller gave the snip — usually its file name. */
        val label: String,
        /** 0 = plays unlooped; otherwise the frame playback wraps back to. */
        val loopStartFrame: Long = 0,
    )

    data class MultiResult(
        val program: KeygroupProgram,
        val zones: List<Zone>,
        /** What actually gets written per stem — trimmed/crossfaded when looped. */
        val samples: Map<String, Snip>,
    )

    /**
     * Several pitched captures → a real multisampled instrument: each
     * snip a zone at its detected root, zones tiling at the midpoints.
     * Any unpitched snip refuses **by its label**; two snips detecting
     * the same root refuse by both labels — the caller picks, we don't.
     */
    fun multiProgram(name: String, snips: List<Pair<String, Snip>>, sustainLoop: Boolean = false): MultiResult {
        require(Names.isMpcSafe(name)) { "instrument name isn't MPC-safe: '$name'" }
        require(snips.isNotEmpty()) { "no snips, no instrument" }
        require(snips.map { it.first }.toSet().size == snips.size) { "labels must be unique" }
        if (snips.size == 1) {
            val r = program(name, snips.single().second, sustainLoop)
            return MultiResult(
                r.program,
                listOf(
                    Zone(
                        r.rootMidi, r.rootName, r.detectedHz, r.confidence, r.sampleStem,
                        snips.single().first, r.loopStartFrame,
                    ),
                ),
                mapOf(r.sampleStem to r.sample),
            )
        }

        val prefix = name.filter { !it.isWhitespace() }
        val detected = snips.map { (label, snip) ->
            val est = Pitch.detect(snip)
                ?: throw IllegalArgumentException("$label: no pitch found - every zone needs a pitched note")
            require(est.confidence >= MIN_CONFIDENCE) {
                "%s: pitch too uncertain (%.0f Hz at confidence %.2f)".format(java.util.Locale.ROOT, label, est.hz, est.confidence)
            }
            // Per-zone sustain loops: a zone that honestly has none plays unlooped.
            val looped = if (sustainLoop) com.snipsnap.audio.LoopCut.sustainLoop(snip) else null
            val sample = looped?.snip ?: snip
            val root = Scales.hzToMidi(est.hz).roundToInt().coerceIn(0, 127)
            Zone(
                root, Scales.nameOf(root), est.hz, est.confidence,
                Names.sanitizeStem("${prefix}_${Scales.nameOf(root)}"), label,
                looped?.loopStartFrame ?: 0,
            ) to sample
        }.sortedBy { it.first.rootMidi }

        detected.zipWithNext().forEach { (a, b) ->
            require(a.first.rootMidi != b.first.rootMidi) {
                "'${a.first.label}' and '${b.first.label}' both detect as ${a.first.rootName} - drop one"
            }
        }

        val roots = detected.map { it.first.rootMidi }
        val keygroups = detected.mapIndexed { i, (zone, sample) ->
            Keygroup(
                lowNote = if (i == 0) 0 else (roots[i - 1] + roots[i]) / 2 + 1,
                highNote = if (i == detected.lastIndex) 127 else (roots[i] + roots[i + 1]) / 2,
                rootNote = zone.rootMidi,
                layers = listOf(
                    VelocityLayer(
                        zone.sampleStem, sample.frameCount.toLong(), velStart = 0, velEnd = 127,
                        loopStartFrame = zone.loopStartFrame,
                    ),
                ),
            )
        }
        return MultiResult(
            KeygroupProgram(name, keygroups, volumeRelease = 0.35f),
            detected.map { it.first },
            detected.associate { (zone, sample) -> zone.sampleStem to sample },
        )
    }

    /** Multisample edition of [export]. */
    fun multiExport(
        name: String,
        snips: List<Pair<String, Snip>>,
        destRoot: File,
        overwrite: Boolean = false,
        sustainLoop: Boolean = false,
    ): MultiResult {
        val result = multiProgram(name, snips, sustainLoop)
        writePackage(name, result.program, result.samples, destRoot, overwrite)
        return result
    }

    internal fun writePackage(
        name: String,
        program: KeygroupProgram,
        samples: Map<String, Snip>,
        destRoot: File,
        overwrite: Boolean,
    ) {
        destRoot.mkdirs()
        val xty = File(destRoot, "$name.xty")
        val dataDir = File(destRoot, Mpc3TrackWriter.trackDataDirName(name))
        if ((xty.exists() || dataDir.exists()) && !overwrite) {
            throw IOException("destination already exists: $xty (pass overwrite=true to replace same-named files)")
        }
        dataDir.deleteRecursively()
        dataDir.mkdirs()
        // Every zone's WAV carries its own sampler sheet - the root the
        // program plays it at and its sustain loop, if it has one - so the
        // sample loaded on its own at the MPC, outside this program, arrives
        // tuned and looping. Read back from the program, the one source of
        // truth for both.
        val sheet = program.keygroups.flatMap { kg ->
            kg.layers.map { layer -> layer.sampleName to (kg.rootNote to layer.loopStartFrame) }
        }.toMap()
        for ((stem, snip) in samples) {
            val smpl = sheet[stem]?.let { (root, loopStart) ->
                com.snipsnap.audio.SmplChunk(
                    root,
                    if (loopStart > 0L) com.snipsnap.audio.SmplChunk.Loop(loopStart, snip.frameCount.toLong()) else null,
                )
            }
            WavWriter.write(File(dataDir, "$stem.wav"), snip, smpl = smpl)
        }
        Mpc3TrackWriter().writeKeygroupTo(destRoot, program)
        KeygroupWriter().writeTo(dataDir, program)
        // The phone's own reading of the package, beside the hardware's.
        InstrumentStore.write(destRoot, name, program, dataDir.name)
    }
}
