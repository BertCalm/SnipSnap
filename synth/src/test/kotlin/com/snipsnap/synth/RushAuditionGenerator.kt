package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * RUSH's first listening proof, rendered by the actual production board.
 *
 * Main and cumulative-stage clips are individually audition-levelled. The
 * separate level-response group uses one shared gain, so its quiet source
 * remains 18 dB below the loud source at the processor input. Every mono
 * file is the unchanged average of the corresponding levelled stereo pair.
 * No effect tail is cut or overlapped by the next hit.
 */
object RushAuditionGenerator {
    private const val EXPERIMENT = "RUSH_AUDITION"
    private const val VERSION = 1
    private const val MIN_HIT_SPACING_SECONDS = 2.25
    private const val HIT_GAP_SECONDS = .25
    private const val END_SILENCE_SECONDS = .2
    private const val QUIET_INPUT_GAIN = .12589254f // -18 dB, before RUSH.
    private val amounts = listOf(0f, .35f, .7f, 1f)
    private const val MATCHED_LEVEL_POLICY =
        "AuditionLevel on the completed stereo phrase; mono derived afterwards without re-levelling"
    private const val SHARED_LEVEL_POLICY =
        "One gain shared by all four level-response stereo phrases; quiet input is -18 dB before RUSH; no per-clip matching; mono derived afterwards without re-levelling"

    private data class Source(
        val id: String,
        val name: String,
        val description: String,
        val engine: String,
        val voice: String,
        val macros: Map<String, Float>,
        val details: Map<String, Any?> = emptyMap(),
        val render: (Map<String, Float>) -> Snip,
    ) {
        fun recipe(): Map<String, Any?> = linkedMapOf<String, Any?>(
            "engine" to engine, "voice" to voice, "macros" to macros,
        ) + details
    }

    private data class Stage(
        val name: String,
        val macros: Map<String, Float>,
        val run: (Snip, Map<String, Float>) -> Snip,
    ) {
        fun recipe(): Map<String, Any?> = linkedMapOf("processor" to name, "macros" to macros)
    }

    private data class Candidate(
        val id: String,
        val label: String,
        val amount: Float,
        val hit: Snip,
        val chain: List<Stage>,
        val inputGain: Float = 1f,
    )

    private val sources = listOf(
        Source(
            "pluck", "PLUCK · NYLON",
            "A mono nylon string: listen for the softened pick, metallic movement and pitched repeats.",
            "PLUCK", "NYLON", Pluck.defaults(PluckVoice.NYLON),
        ) { Pluck.render(PluckVoice.NYLON, it) },
        Source(
            "lead", "RESIN · HELD LEAD",
            "A 1.2-second held mono lead: listen for a rising entrance, input-following tone and separated echoes.",
            "RESIN", "LEAD", Resin.defaults(ResinVoice.LEAD),
            linkedMapOf(
                "articulation" to "native Resin.renderHeld, then ordinary melodic source levelling and a 40 ms endpoint fade",
                "heldSeconds" to 1.2, "attackSeconds" to .01, "endpointFadeSeconds" to .04,
                "sourceLoudnessTarget" to Dsp.MELODIC_LOUDNESS_TARGET,
            ),
        ) {
            val snip = Resin.renderHeld(ResinVoice.LEAD, it, Resin.Held(.01f, 1.2f))
            Dsp.levelTo(snip.samples, snip.sampleRate, target = Dsp.MELODIC_LOUDNESS_TARGET)
            Dsp.fadeTail(snip.samples, 40f, snip.sampleRate, snip.channels)
            snip
        },
        Source(
            "choir", "VOX · CHOIR",
            "The shipped choir starts stereo: listen to the swell and whether the voice survives the metallic treatment.",
            "VOX", "CHOIR", Vox.defaults(VoxVoice.CHOIR),
        ) { Vox.render(VoxVoice.CHOIR, it) },
        Source(
            "snare", "THUMP · SNARE",
            "A mono snare: judge whether the changed attack is useful and the repeats stay distinct.",
            "THUMP", "SNARE", Thump.defaults(ThumpVoice.SNARE),
        ) { Thump.render(ThumpVoice.SNARE, it) },
    )

    private fun stages(amount: Float): List<Stage> =
        Rush.stages(amount).map { Stage(it.name, it.macros, it.run) }

    private fun cumulative(source: Snip, chain: List<Stage>): Snip {
        var result = source
        for (stage in chain) {
            result = stage.run(result, stage.macros)
            checkAudio(result, stage.name)
        }
        checkTail(source, result)
        return result
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "Usage: RushAuditionGenerator [output-directory]" }
        val repo = repositoryRoot()
        val root = args.firstOrNull()?.let(::File) ?: File(repo, "testkit/rush-audition")
        check(root.mkdirs() || root.isDirectory) { "Cannot create ${root.absolutePath}" }
        val sourceHashes = sourceHashes(repo)
        val sourceCommit = git(repo, "rev-parse", "HEAD")
        val sourceTree = git(repo, "rev-parse", "HEAD^{tree}")
        val dirty = git(repo, "status", "--porcelain", "--untracked-files=normal").isNotEmpty()
        val sourceRecords = mutableListOf<Map<String, Any?>>()
        var isolation: Map<String, Any?>? = null
        var levelResponse: Map<String, Any?>? = null
        var wavs = 0

        for (source in sources) {
            val dry = source.render(source.macros)
            checkAudio(dry, source.id)
            check(dry.sampleRate == WavWriter.MPC_SAMPLE_RATE) { "Expected native 44.1 kHz source" }
            val fullChain = stages(1f)
            check(fullChain.size == 5) { "RUSH stage-isolation proof expects five production stages" }
            val candidates = amounts.map { amount ->
                val hit = Rush.process(dry, amount)
                checkAudio(hit, "${source.id} $amount")
                checkTail(dry, hit)
                check(hit.samples.contentEquals(Rush.process(dry, amount).samples)) {
                    "${source.id}: fresh per-hit renders differ at AMT $amount"
                }
                val tag = if (amount == 0f) "original" else "amt" + (amount * 100).roundToInt().toString().padStart(3, '0')
                val label = if (amount == 0f) "Original" else "RUSH · AMT ${(amount * 100).roundToInt()}%"
                Candidate("${source.id}-$tag", label, amount, hit, if (amount == 0f) emptyList() else stages(amount))
            }
            val stageCandidates = if (source.id == "pluck") (1..fullChain.size).map { count ->
                val chain = fullChain.take(count)
                val hit = cumulative(dry, chain)
                if (count == fullChain.size) check(hit.samples.contentEquals(candidates.last().hit.samples)) {
                    "Cumulative fifth stage differs from production RUSH"
                }
                Candidate("pluck-stage-$count", chain.joinToString(" → ") { it.name }, 1f, hit, chain)
            } else emptyList()
            val levelCandidates = if (source.id == "pluck") listOf(
                "quiet" to QUIET_INPUT_GAIN, "loud" to 1f,
            ).flatMap { (name, gain) ->
                val input = scale(dry, gain)
                listOf(0f, .7f).map { amount ->
                    val hit = Rush.process(input, amount)
                    checkAudio(hit, "$name input $amount")
                    checkTail(input, hit)
                    check(hit.samples.contentEquals(Rush.process(input, amount).samples)) {
                        "$name input: fresh per-hit renders differ at AMT $amount"
                    }
                    Candidate(
                        "pluck-$name-" + if (amount == 0f) "original" else "amt070",
                        "${name.replaceFirstChar { it.uppercase() }} input · " + if (amount == 0f) "Original" else "RUSH 70%",
                        amount, hit, if (amount == 0f) emptyList() else stages(amount), gain,
                    )
                }
            } else emptyList()
            val longest = (candidates + stageCandidates + levelCandidates).maxOf { it.hit.frameCount }
            val secondHitSeconds = maxOf(
                MIN_HIT_SPACING_SECONDS,
                ceil((longest.toDouble() / dry.sampleRate + HIT_GAP_SECONDS) * 4) / 4,
            )
            val secondHitFrame = (secondHitSeconds * dry.sampleRate).roundToInt()
            check(secondHitFrame >= longest + (HIT_GAP_SECONDS * dry.sampleRate).toInt()) {
                "The next hit would overlap the preceding printed tail"
            }
            val phraseFrames = secondHitFrame + longest + (END_SILENCE_SECONDS * dry.sampleRate).roundToInt()

            fun export(candidate: Candidate, sharedGain: Float? = null): Map<String, Any?> {
                val phrase = twoHits(toStereo(candidate.hit), phraseFrames, secondHitFrame)
                val stereo = sharedGain?.let { scale(phrase, it) } ?: AuditionLevel.level(phrase)
                checkAudio(stereo, candidate.id)
                check(stereo.peak() <= .99001f) { "${candidate.id}: audition peak guard failed" }
                // Use the stated two-channel arithmetic directly. Cleanup's
                // zero-initialised accumulator turns (-0 + -0) into +0;
                // retaining the direct fold also retains its signed zero.
                val mono = Snip(
                    FloatArray(stereo.frameCount) { frame ->
                        (stereo.samples[2 * frame] + stereo.samples[2 * frame + 1]) / 2f
                    },
                    channels = 1, sampleRate = stereo.sampleRate,
                )
                check(mono.samples.indices.all { frame ->
                    mono.samples[frame].toRawBits() ==
                        ((stereo.samples[2 * frame] + stereo.samples[2 * frame + 1]) / 2f).toRawBits()
                }) { "${candidate.id}: mono fold differs from the levelled stereo average" }
                val stereoPath = "audio/${candidate.id}-stereo.wav"
                val monoPath = "audio/${candidate.id}-mono.wav"
                val stereoFile = WavWriter.write(File(root, stereoPath), stereo, WavWriter.BitDepth.PCM_24)
                val monoFile = WavWriter.write(File(root, monoPath), mono, WavWriter.BitDepth.PCM_24)
                wavs += 2
                val meter = FoldMeter.report(stereo)
                val recipe = linkedMapOf<String, Any?>(
                    "experiment" to EXPERIMENT, "version" to VERSION,
                    "status" to "production board renderer; provisional sound-design mapping awaiting owner listening",
                    "source" to source.recipe(), "amount" to candidate.amount,
                    "amountMeaning" to "macro intensity; zero hard-bypasses the processors",
                    "stages" to candidate.chain.map { it.recipe() },
                    "inputGain" to candidate.inputGain, "inputGainDb" to db(candidate.inputGain.toDouble()),
                    "sourceFrames" to dry.frameCount, "printedHitFrames" to candidate.hit.frameCount,
                    "phraseFrames" to phraseFrames, "sampleRate" to stereo.sampleRate,
                    "hitStartsSeconds" to listOf(0.0, secondHitSeconds),
                    "statePolicy" to "independent deterministic fresh-state renders verified; one printed hit copied twice, with a complete tail and at least 250 ms silence before the next hit",
                    "levelPolicy" to if (sharedGain == null) MATCHED_LEVEL_POLICY else SHARED_LEVEL_POLICY,
                    "sharedAuditionGain" to sharedGain,
                    "originalStereoPolicy" to "mono source duplicated left/right; native stereo preserved",
                    "sourceAudioSha256" to sampleHash(dry),
                    "inputAudioSha256" to sampleHash(scale(dry, candidate.inputGain)),
                    "printedHitAudioSha256" to sampleHash(candidate.hit),
                )
                return linkedMapOf(
                    "id" to candidate.id, "label" to candidate.label, "amount" to candidate.amount,
                    "stereo" to stereoPath, "mono" to monoPath,
                    "durationSeconds" to stereo.durationSeconds, "sampleRate" to stereo.sampleRate,
                    "channels" to stereo.channels, "sourceChannels" to dry.channels,
                    "printedHitFrames" to candidate.hit.frameCount,
                    "peakDbfs" to db(stereo.peak().toDouble()),
                    "loudnessDb" to db(Loudness.of(stereo).toDouble()),
                    "foldLoudnessDb" to db(Loudness.of(mono).toDouble()),
                    "foldLossDb" to finite(meter.lossDb), "correlation" to finite(meter.correlation),
                    "metrics" to linkedMapOf(
                        "meter" to "SnipSnap Loudness.of: 120 Hz high-pass, loudest 200 ms RMS; not LUFS",
                        "foldDefinition" to "(left + right) / 2",
                        "foldLossReference" to "mean of separate left/right Loudness.of readings",
                        "correlationFromSeconds" to FoldMeter.FROM_SECONDS,
                        "foldPumpDb" to finite(meter.pumpDb), "foldRippleDb" to finite(meter.rippleDb),
                        "foldEqualWindowLossDb" to finite(meter.equalWindowLossDb),
                        "foldEqualWindowLawDb" to finite(meter.equalWindowLawDb),
                        "stereoRmsDbfs" to db(rms(stereo)), "monoRmsDbfs" to db(rms(mono)),
                        "preAuditionPeakDbfs" to db(candidate.hit.peak().toDouble()),
                        "preAuditionLoudnessDb" to db(Loudness.of(candidate.hit).toDouble()),
                    ),
                    "stereoSha256" to fileHash(stereoFile), "monoSha256" to fileHash(monoFile),
                    "recipe" to recipe,
                )
            }

            val clips = candidates.map { export(it) }
            sourceRecords += linkedMapOf(
                "id" to source.id, "name" to source.name, "description" to source.description,
                "sourceRecipe" to source.recipe(), "sourceChannels" to dry.channels,
                "sourceDurationSeconds" to dry.durationSeconds,
                "hitStartsSeconds" to listOf(0.0, secondHitSeconds), "clips" to clips,
            )
            if (stageCandidates.isNotEmpty()) {
                isolation = linkedMapOf(
                    "sourceId" to source.id,
                    "description" to "Five cumulative stages at AMT 100%; the fifth is the complete production board. Each stereo phrase is independently audition-levelled; mono folds are not re-levelled.",
                    "includesFull" to true, "clips" to stageCandidates.map { export(it) },
                )
                val phrases = levelCandidates.map { twoHits(toStereo(it.hit), phraseFrames, secondHitFrame) }
                var gain = .03f / phrases.maxOf { Loudness.of(it) }.coerceAtLeast(1e-9f)
                val peak = phrases.maxOf { it.peak() }
                if (peak * gain > .99f) gain = .99f / peak
                levelResponse = linkedMapOf(
                    "sourceId" to source.id, "amount" to .7,
                    "description" to "Quiet input is 18 dB below loud input before RUSH. One shared audition gain preserves the input difference. Both followers are relative to the linked input peak, so changing level alone should preserve the swell/filter response; these are not loudness-matched listening clips.",
                    "quietInputGain" to QUIET_INPUT_GAIN, "loudInputGain" to 1.0,
                    "inputDifferenceDb" to 18.0, "sharedAuditionGain" to gain,
                    "levelPolicy" to SHARED_LEVEL_POLICY,
                    "clips" to levelCandidates.map { export(it, gain) },
                )
            }
            println("RUSH: ${source.id}, ${clips.size} original/intensity pairs, second hit at ${secondHitSeconds}s")
        }

        check(sourceHashes == sourceHashes(repo)) { "A renderer or source file changed during audition generation" }
        check(sourceCommit == git(repo, "rev-parse", "HEAD")) { "Git HEAD changed during audition generation" }
        val manifest = linkedMapOf(
            "schema" to VERSION, "title" to "RUSH · First listening proof",
            "description" to "Auto-Swell → Ring → Distortion → Envelope Filter → Pitched Echo on four real SnipSnap sources. Original and three intensity candidates, unchanged mono folds, cumulative stages and shared-gain input-level comparisons.",
            "experiment" to EXPERIMENT,
            "chain" to stages(1f).map { it.name },
            "repository" to "https://github.com/BertCalm/SnipSnap",
            "sourceCommit" to sourceCommit, "sourceTree" to sourceTree,
            "workingTreeDirty" to dirty,
            "commitMeaning" to "Git HEAD at render time; selected file hashes below identify actual render inputs, including any uncommitted changes",
            "conceptReference" to "https://github.com/BertCalm/XO_OX-XOmnibus/blob/main/Docs%2Fspecs%2F2026-04-05-epic-chains-fx-design.md",
            "status" to "provisional sound-design audition; owner listening pending",
            "amounts" to amounts,
            "amountMeaning" to "macro intensity, not a parallel dry/wet crossfade; zero is an exact bypass",
            "maxTailSeconds" to Rush.MAX_TAIL_SECONDS,
            "implementation" to linkedMapOf(
                "autoSwell" to "input-triggered linked attack/release swell with Schmitt gate; thresholds relative to the source linked peak",
                "ring" to "existing sine Ring, shared ${Rush.RING_HZ} Hz carrier",
                "distortion" to "existing four-times-oversampled asymmetric VALVE amp with CAB=0; approximation of hard-clip distortion",
                "envelopeFilter" to "post-distortion linked magnitude follower relative to that stage input peak; smoothed log-frequency TptSvf low-pass",
                "pitchedEcho" to "six decaying progressively low-passed feedforward taps; no feedback accumulation or BBD model",
                "timingPolicy" to "fixed offline per-hit timing; no host or live tempo sync",
                "delaySeconds" to Rush.DELAY_SECONDS, "echoTaps" to Rush.ECHO_TAPS,
                "leftPitchStepsSemitones" to listOf(Rush.FIRST_STEP_SEMITONES, Rush.SECOND_STEP_SEMITONES),
                "rightPitchStepsSemitones" to listOf(Rush.SECOND_STEP_SEMITONES, Rush.FIRST_STEP_SEMITONES),
                "pitchWindowSeconds" to Rush.PITCH_WINDOW_SECONDS,
                "pitchLatencyPolicy" to "dual-Hann readheads add 0..40 ms latency/spread to shifted taps",
                "gainPolicy" to "new stages never normalise upward; linked attenuation-only guards; inherited Ring/VALVE retain shared peak matching",
            ),
            "listeningNotes" to listOf(
                "Each clip contains two identical printed hits. The next starts only after the complete printed tail plus at least 250 ms silence; source-specific hit times are recorded in each recipe.",
                "The main and stage clips use SnipSnap's mono-fold-based loudest-200 ms audition meter, not LUFS or perceived stereo loudness. Stereo gains can differ with width. Mono is derived afterwards without re-levelling.",
                "The separate quiet/loud input group uses one shared gain and preserves an 18 dB input difference. The followers use relative linked input peak, so this tests level invariance rather than claiming an absolute threshold effect.",
                "The choir starts stereo. Mono originals are duplicated to stereo. The lead is a finite 1.2-second native RESIN held render with a 10 ms attack and explicit 40 ms endpoint fade.",
                "This is an offline per-hit board with fresh state, fixed timing and a bounded printed tail. It does not synchronize to a live tempo or share feedback across pad hits.",
            ),
            "audio" to linkedMapOf("encoding" to "PCM", "bitDepth" to 24, "sampleRate" to WavWriter.MPC_SAMPLE_RATE),
            "sourceHashes" to sourceHashes,
            "sourceHashScope" to "generator, audition meter, production board and directly reused processors/source engines; not an exhaustive repository fingerprint",
            "sources" to sourceRecords, "isolation" to isolation, "levelResponse" to levelResponse,
        )
        File(root, "manifest.json").writeText(Json.write(json(manifest)) + "\n")
        File(root, "README.txt").writeText(
            "RUSH audition\n\nRun :synth:generateRushAudition to reproduce these PCM24 WAVs.\n" +
                "manifest.json records source commit, dirty-state flag, actual source hashes, complete recipes, WAV SHA-256 values, hit timing and level policy.\n" +
                "Main and stage clips are individually matched. Quiet/loud level-response clips share one gain; quiet input is -18 dB before RUSH.\n" +
                "Every mono file is the unchanged stereo average, subject only to independent PCM24 rounding. No listening verdict is inferred from measurements.\n",
        )
        println("Wrote $wavs PCM24 WAVs and manifest.json under ${root.absolutePath}")
    }

    private fun checkTail(source: Snip, result: Snip) {
        check(result.frameCount <= source.frameCount + (Rush.MAX_TAIL_SECONDS * source.sampleRate).roundToInt()) {
            "RUSH exceeded its production tail budget"
        }
    }

    private fun toStereo(snip: Snip): Snip = if (snip.channels == 2) snip else {
        check(snip.channels == 1)
        Snip(FloatArray(snip.frameCount * 2) { snip.samples[it / 2] }, 2, snip.sampleRate)
    }

    private fun scale(snip: Snip, gain: Float): Snip =
        Snip(FloatArray(snip.samples.size) { snip.samples[it] * gain }, snip.channels, snip.sampleRate)

    private fun twoHits(hit: Snip, frames: Int, secondHitFrame: Int): Snip {
        check(hit.channels == 2)
        val out = FloatArray(frames * 2)
        for (at in listOf(0, secondHitFrame)) {
            check(at + hit.frameCount <= frames) { "The phrase would truncate a printed hit" }
            for (i in hit.samples.indices) out[at * 2 + i] = hit.samples[i]
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

    private fun db(amplitude: Double): Double = 20 * log10(abs(amplitude).coerceAtLeast(1e-12))
    private fun finite(value: Double): Double? = value.takeIf { it.isFinite() }

    private fun repositoryRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile && File(dir, "synth").isDirectory) return dir
            dir = dir.parentFile
        }
        error("Run from the SnipSnap repository or one of its subdirectories")
    }

    private fun git(repo: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).directory(repo).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0) { "Cannot read Git provenance: $output" }
        return output
    }

    private fun sourceHashes(repo: File): Map<String, String> = listOf(
        "synth/src/test/kotlin/com/snipsnap/synth/RushAuditionGenerator.kt",
        "synth/src/test/kotlin/com/snipsnap/synth/AuditionLevel.kt",
        "synth/src/test/kotlin/com/snipsnap/synth/FoldMeter.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Rush.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/BoardRecipe.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Ring.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Valve.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Tide.kt",
        "audio/src/main/kotlin/com/snipsnap/audio/Resampler.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Pluck.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Strings.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Modes.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Resin.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Vox.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Thump.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Punch.kt",
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
