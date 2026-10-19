package com.snipsnap.synth

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * First WIDE listening proof, using the shared production board renderer.
 *
 * Reuses the shipped processors in Tape -> Eq -> Ensemble -> Spring order.
 * AMT scales the processors' macros; it is not a parallel dry/wet crossfade.
 * At zero the source is bypassed. The nonzero values are discrete listening
 * candidates: Tape and Ensemble retain their short delay/tone at zero depth,
 * so this does not claim a continuous transparent control near zero.
 *
 * Every comparison contains two identical printed hits, with all effect state
 * restarting per hit. This demonstrates a treated pad rather than a live bus.
 * The mono WAV is the average of the levelled stereo WAV, never re-levelled.
 * Run generateWideAudition, or main with an optional output directory.
 */
object WideAuditionGenerator {
    private const val EXPERIMENT = "WIDE_AUDITION"
    private const val VERSION = 1
    private const val SECOND_HIT_SECONDS = 2.25
    private const val END_SILENCE_SECONDS = 0.2
    private const val MIN_SECONDS = 4.0
    private const val MAX_SECONDS = 8.0
    private val amounts = listOf(0f, .35f, .7f, 1f)

    private data class Source(
        val id: String,
        val name: String,
        val description: String,
        val engine: String,
        val voice: String,
        val macros: Map<String, Float>,
        val render: (Map<String, Float>) -> Snip,
    ) {
        fun recipe(): Map<String, Any?> = linkedMapOf(
            "engine" to engine, "voice" to voice, "macros" to macros,
        )
    }

    private data class Stage(
        val name: String,
        val macros: Map<String, Float>,
        val run: (Snip, Map<String, Float>) -> Snip,
    ) {
        fun recipe(): Map<String, Any?> = linkedMapOf("processor" to name, "macros" to macros)
    }

    private val sources = listOf(
        Source(
            "pluck", "PLUCK · NYLON",
            "A mono nylon string: hear whether the pick stays clear as the body spreads.",
            "PLUCK", "NYLON", Pluck.defaults(PluckVoice.NYLON),
        ) { Pluck.render(PluckVoice.NYLON, it) },
        Source(
            "choir", "VOX · CHOIR",
            "The shipped choir already has a stereo image; hear whether WIDE adds space without washing it away.",
            "VOX", "CHOIR", Vox.defaults(VoxVoice.CHOIR),
        ) { Vox.render(VoxVoice.CHOIR, it) },
        Source(
            "snare", "THUMP · SNARE",
            "A mono snare: listen to the strike, wire texture and short room tail.",
            "THUMP", "SNARE", Thump.defaults(ThumpVoice.SNARE),
        ) { Thump.render(ThumpVoice.SNARE, it) },
        Source(
            "bass", "VELVET · BASS",
            "A mono bass: hear whether the low note stays solid when the upper harmonics widen.",
            "VELVET", "BASS", Velvet.defaults(VelvetVoice.BASS),
        ) { Velvet.render(VelvetVoice.BASS, it) },
    )

    private fun stages(amount: Float): List<Stage> =
        Wide.stages(amount).map { Stage(it.name, it.macros, it.run) }

    private fun processed(source: Snip, stages: List<Stage>): Snip {
        var out = source
        for (stage in stages) {
            out = stage.run(out, stage.macros)
            checkAudio(out, stage.name)
        }
        // Reusing Spring's own bounded tail here, rather than FxChain's
        // one-second total cap, makes this explicitly a separate proof.
        check(out.frameCount <= source.frameCount + (Spring.MAX_TAIL_SECONDS * source.sampleRate).toInt()) {
            "WIDE exceeded the existing Spring tail budget"
        }
        return out
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "Usage: WideAuditionGenerator [output-directory]" }
        val repo = repositoryRoot()
        val root = args.firstOrNull()?.let(::File) ?: File(repo, "testkit/wide-audition")
        check(root.mkdirs() || root.isDirectory) { "Cannot create ${root.absolutePath}" }
        val audioDir = File(root, "audio")
        check(audioDir.mkdirs() || audioDir.isDirectory) { "Cannot create ${audioDir.absolutePath}" }
        val sourceHashes = sourceHashes(repo)
        val sourceRecords = mutableListOf<Map<String, Any?>>()
        var isolation: Map<String, Any?>? = null
        var wavs = 0

        for (source in sources) {
            val dry = source.render(source.macros)
            checkAudio(dry, source.id)
            check(dry.sampleRate == WavWriter.MPC_SAMPLE_RATE) { "Expected native 44.1 kHz source" }
            val fullStages = stages(1f)
            val full = Wide.process(dry, 1f)
            val phraseFrames = maxOf(
                (MIN_SECONDS * dry.sampleRate).roundToInt(),
                (SECOND_HIT_SECONDS * dry.sampleRate).roundToInt() + full.frameCount +
                    (END_SILENCE_SECONDS * dry.sampleRate).roundToInt(),
            )
            check(phraseFrames <= (MAX_SECONDS * dry.sampleRate).roundToInt()) { "Audition would exceed eight seconds" }

            fun export(id: String, label: String, amount: Float, hit: Snip, chain: List<Stage>): Map<String, Any?> {
                val phrase = twoHits(toStereo(hit), phraseFrames)
                val stereo = AuditionLevel.level(phrase)
                checkAudio(stereo, id)
                check(stereo.peak() <= .99001f) { "$id: audition peak guard failed" }
                val mono = Cleanup.toMono(stereo)
                // A fold is made only after stereo levelling; no independent
                // gain is applied here or by the file writer.
                check(mono.samples.indices.all { f ->
                    mono.samples[f].toRawBits() == ((stereo.samples[2 * f] + stereo.samples[2 * f + 1]) / 2f).toRawBits()
                }) { "$id: fold differs from the average of the levelled pair" }
                val stereoPath = "audio/$id-stereo.wav"
                val monoPath = "audio/$id-mono.wav"
                val stereoFile = WavWriter.write(File(root, stereoPath), stereo, WavWriter.BitDepth.PCM_24)
                val monoFile = WavWriter.write(File(root, monoPath), mono, WavWriter.BitDepth.PCM_24)
                wavs += 2
                val meter = FoldMeter.report(stereo)
                val loudness = Loudness.of(stereo).toDouble()
                val foldLoudness = Loudness.of(mono).toDouble()
                val recipe = linkedMapOf<String, Any?>(
                    "experiment" to EXPERIMENT, "version" to VERSION,
                    "status" to "listening candidate; not a production FxChain recipe",
                    "source" to source.recipe(), "amount" to amount,
                    "amountMeaning" to "macro intensity; zero hard-bypasses the processors",
                    "stages" to chain.map { it.recipe() },
                    "sourceFrames" to dry.frameCount, "printedHitFrames" to hit.frameCount,
                    "phraseFrames" to phraseFrames, "sampleRate" to stereo.sampleRate,
                    "hitStartsSeconds" to listOf(0.0, SECOND_HIT_SECONDS),
                    "statePolicy" to "render one hit then copy it twice; effect state resets per hit; tails may overlap",
                    "levelPolicy" to "AuditionLevel on the completed stereo phrase; fold derived afterwards without re-levelling",
                    "originalStereoPolicy" to "mono source duplicated left/right; native stereo preserved",
                    "sourceAudioSha256" to sampleHash(dry),
                )
                return linkedMapOf(
                    "id" to id, "label" to label, "amount" to amount,
                    "stereo" to stereoPath, "mono" to monoPath,
                    "durationSeconds" to stereo.durationSeconds,
                    "sampleRate" to stereo.sampleRate, "channels" to stereo.channels,
                    "sourceChannels" to dry.channels, "printedHitFrames" to hit.frameCount,
                    "peakDbfs" to db(stereo.peak().toDouble()),
                    "loudnessDb" to db(loudness), "foldLoudnessDb" to db(foldLoudness),
                    "foldLossDb" to finite(meter.lossDb), "correlation" to finite(meter.correlation),
                    "metrics" to linkedMapOf(
                        "meter" to "SnipSnap Loudness.of: 120 Hz high-pass, loudest 200 ms RMS; not LUFS",
                        "foldDefinition" to "(left + right) / 2",
                        "foldLossReference" to "mean of separate left/right Loudness.of readings",
                        "correlationFromSeconds" to FoldMeter.FROM_SECONDS,
                        "foldPumpDb" to finite(meter.pumpDb),
                        "foldRippleDb" to finite(meter.rippleDb),
                        "foldEqualWindowLossDb" to finite(meter.equalWindowLossDb),
                        "foldEqualWindowLawDb" to finite(meter.equalWindowLawDb),
                        "stereoRmsDbfs" to db(rms(stereo)), "monoRmsDbfs" to db(rms(mono)),
                    ),
                    "stereoSha256" to fileHash(stereoFile), "monoSha256" to fileHash(monoFile),
                    "recipe" to recipe,
                )
            }

            val clips = amounts.map { amount ->
                val chain = stages(amount)
                val hit = if (amount == 1f) full else Wide.process(dry, amount)
                val tag = if (amount == 0f) "original" else "amt" + (amount * 100).roundToInt().toString().padStart(3, '0')
                val label = if (amount == 0f) "Original" else "WIDE · AMT " + (amount * 100).roundToInt() + "%"
                export("${source.id}-$tag", label, amount, hit, chain)
            }
            sourceRecords += linkedMapOf(
                "id" to source.id, "name" to source.name, "description" to source.description,
                "sourceRecipe" to source.recipe(), "sourceChannels" to dry.channels,
                "sourceDurationSeconds" to dry.durationSeconds,
                "hitStartsSeconds" to listOf(0.0, SECOND_HIT_SECONDS), "clips" to clips,
            )
            if (source.id == "pluck") {
                val labels = listOf("Tape only", "Tape → EQ", "Tape → EQ → Ensemble")
                val isolated = (1..3).map { count ->
                    val chain = fullStages.take(count)
                    export("pluck-stage-$count", labels[count - 1], 1f, processed(dry, chain), chain)
                }
                isolation = linkedMapOf(
                    "sourceId" to "pluck",
                    "description" to "Cumulative stages at AMT 100%; compare the final step with the full WIDE clip above. Each stereo phrase is independently loudness matched.",
                    "clips" to isolated,
                )
            }
            println("WIDE: ${source.id}, ${clips.size} original/intensity pairs")
        }

        check(sourceHashes == sourceHashes(repo)) { "A source file changed during audition generation" }
        val manifest = linkedMapOf(
            "schema" to VERSION, "title" to "WIDE · First listening proof",
            "description" to "A fixed Tape → EQ → Ensemble → Spring treatment on four real SnipSnap sources. Original plus three intensity candidates, with level-matched stereo and its unchanged mono fold.",
            "experiment" to EXPERIMENT, "chain" to listOf("Tape", "EQ", "Ensemble", "Spring"),
            "repository" to "https://github.com/BertCalm/SnipSnap",
            "inspectedCommit" to "3c0d1c781b803d53745bf38032198f78c1f918fa",
            "conceptReference" to "https://github.com/BertCalm/XO_OX-XOmnibus/blob/main/Docs%2Fspecs%2F2026-04-05-epic-chains-fx-design.md",
            "status" to "provisional sound-design audition; owner listening pending",
            "amounts" to amounts, "amountMeaning" to "macro intensity, not a dry/wet crossfade; zero is an exact bypass",
            "listeningNotes" to listOf(
                "Each clip contains two identical printed hits at 0 and 2.25 seconds. Effect state restarts per hit; tails may overlap.",
                "Stereo files are matched using SnipSnap’s mono-fold-based loudest-200 ms meter, not LUFS or perceived stereo loudness; the resulting stereo gains can differ with width. Mono is then derived without re-levelling.",
                "The choir starts stereo. Mono originals are duplicated to stereo for consistent playback controls.",
                "This proof reuses tape wobble, three-tap ensemble and Schroeder reverb; it does not yet add a separate vibrato, plate reverb or a live bus.",
                "Nonzero intensity settings retain the existing Tape/Ensemble short delay and tone; this proof does not validate a continuously transparent control near zero.",
            ),
            "audio" to linkedMapOf("encoding" to "PCM", "bitDepth" to 24, "sampleRate" to WavWriter.MPC_SAMPLE_RATE),
            "sourceHashes" to sourceHashes,
            "sourceHashScope" to "selected source hashes; not an exhaustive fingerprint",
            "sources" to sourceRecords, "isolation" to isolation,
        )
        File(root, "manifest.json").writeText(Json.write(json(manifest)) + "\n")
        println("Wrote $wavs PCM24 WAVs and manifest.json under ${root.absolutePath}")
    }

    private fun toStereo(snip: Snip): Snip = if (snip.channels == 2) snip else {
        check(snip.channels == 1)
        Snip(FloatArray(snip.frameCount * 2) { snip.samples[it / 2] }, 2, snip.sampleRate)
    }

    private fun twoHits(hit: Snip, frames: Int): Snip {
        check(hit.channels == 2)
        val out = FloatArray(frames * 2)
        for (at in listOf(0, (SECOND_HIT_SECONDS * hit.sampleRate).roundToInt())) {
            check(at + hit.frameCount <= frames) { "The phrase would cut off a printed hit" }
            for (i in hit.samples.indices) out[at * 2 + i] += hit.samples[i]
        }
        return Snip(out, 2, hit.sampleRate)
    }

    private fun checkAudio(snip: Snip, label: String) {
        check(snip.channels in 1..2 && snip.frameCount > 0) { "$label: invalid audio shape" }
        check(snip.samples.all { it.isFinite() }) { "$label: non-finite samples" }
        check(snip.peak() > 1e-9f) { "$label: silent audio" }
    }

    private fun rms(snip: Snip): Double {
        var sum = 0.0
        for (sample in snip.samples) sum += sample.toDouble() * sample
        return sqrt(sum / snip.samples.size)
    }

    private fun db(amplitude: Double): Double = 20.0 * log10(abs(amplitude).coerceAtLeast(1e-12))

    private fun finite(value: Double): Double? = value.takeIf { it.isFinite() }

    private fun repositoryRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile && File(dir, "synth").isDirectory) return dir
            dir = dir.parentFile
        }
        error("Run from the SnipSnap repository or one of its subdirectories")
    }

    private fun sourceHashes(repo: File): Map<String, String> = listOf(
        "synth/src/test/kotlin/com/snipsnap/synth/WideAuditionGenerator.kt",
        "synth/src/test/kotlin/com/snipsnap/synth/AuditionLevel.kt",
        "synth/src/test/kotlin/com/snipsnap/synth/FoldMeter.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Wide.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/BoardRecipe.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Tape.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Eq.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Ensemble.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/EnsemblePlayers.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Spring.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Pluck.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Strings.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Modes.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Vox.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Thump.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Velvet.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt",
        "audio/src/main/kotlin/com/snipsnap/audio/Loudness.kt",
        "audio/src/main/kotlin/com/snipsnap/audio/Cleanup.kt",
        "audio/src/main/kotlin/com/snipsnap/audio/WavWriter.kt",
    ).associateWith { fileHash(File(repo, it)) }

    private fun fileHash(file: File): String = hex(MessageDigest.getInstance("SHA-256").digest(file.readBytes()))

    private fun sampleHash(snip: Snip): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (sample in snip.samples) {
            val bits = sample.toRawBits()
            digest.update(bits.toByte())
            digest.update((bits ushr 8).toByte())
            digest.update((bits ushr 16).toByte())
            digest.update((bits ushr 24).toByte())
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is Boolean -> JsonValue.Bool(value)
        is Number -> {
            check(value.toDouble().isFinite()) { "Non-finite manifest value" }
            JsonValue.Num(value.toDouble())
        }
        is String -> JsonValue.Str(value)
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map { json(it) })
        else -> error("Cannot encode ${value.javaClass.name}")
    }
}
