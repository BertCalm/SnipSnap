package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** R1 input/reference evidence only. No new production processor or app integration. */
object SourcesAndBaseline {
    private const val BASE = "fd76ffa382c1bf94d6a82e5a9484e6cd4140e91c"
    private const val RATE = 44_100
    private const val TAIL_FRAMES = 33_075
    private const val BPM = 120f
    private const val DIVISION = "1/16"
    private val amounts = listOf(.35f, .7f, 1f)

    private data class Source(val id: String, val name: String, val recipe: Map<String, Any?>, val render: () -> Snip)
    private data class Event(val voice: ThumpVoice, val seconds: Double, val gain: Float)
    private data class Note(val seconds: Double, val semitone: Int, val length: Double, val pan: Float, val gain: Float)

    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 2) { "SourcesAndBaseline <repo> <output>" }
        val repo = File(args[0]).canonicalFile
        val root = File(args[1]).canonicalFile.apply { mkdirs() }
        val head = git(repo, "rev-parse", "HEAD")
        git(repo, "merge-base", "--is-ancestor", BASE, head)
        check(git(repo, "diff", "--no-ext-diff", BASE, "--", "audio", "synth", "json", "kit", "xpm", "mpc3").isEmpty()) {
            "The stock baseline modules must remain identical to $BASE"
        }
        val dirty = git(repo, "status", "--porcelain", "--untracked-files=normal").isNotEmpty()
        val inputHash = sourceHashes(repo)
        val snareMacros = Thump.defaults(ThumpVoice.SNARE) + ("WIDTH" to 0f)
        val bassMacros = Velvet.defaults(VelvetVoice.BASS) + mapOf(
            "TUNE" to .125f, "DECAY" to .9f, "CUTOFF" to .3f, "SQUEEZE" to .28f,
        )
        val percussion = listOf(
            Event(ThumpVoice.KICK, 0.0, .42f), Event(ThumpVoice.KICK, .75, .36f),
            Event(ThumpVoice.KICK, 1.0, .42f), Event(ThumpVoice.KICK, 1.5, .3f),
            Event(ThumpVoice.SNARE, .5, .35f), Event(ThumpVoice.SNARE, 1.5, .35f),
        ) + (0..7).map { Event(ThumpVoice.HAT_CLOSED, it * .25, if (it % 2 == 0) .12f else .09f) } + listOf(
            Event(ThumpVoice.RIM, 1.625, .18f), Event(ThumpVoice.RIM, 1.875, .16f),
        )
        val notes = listOf(
            Note(0.0, 0, .16, -.65f, .55f), Note(.25, 7, .10, .55f, .5f),
            Note(.375, 12, .15, -.25f, .45f), Note(.75, 3, .20, .75f, .6f),
            Note(1.0, 10, .18, .1f, .5f), Note(1.25, 7, .12, -.7f, .45f),
            Note(1.5, 15, .18, .45f, .55f), Note(1.75, 12, .20, -.35f, .5f),
        )
        val sources = listOf(
            Source("snare", "Snare hit", linkedMapOf(
                "api" to "Thump.render", "engine" to "THUMP", "voice" to "SNARE", "macros" to snareMacros,
                "levelPolicy" to "Native engine output; no additional gain or audition matching",
            )) { Thump.render(ThumpVoice.SNARE, snareMacros) },
            Source("percussion", "One-bar percussion phrase", linkedMapOf(
                "api" to "Thump.render, fixed-gain event arrangement", "bpm" to 120, "beats" to 4,
                "seconds" to 2, "channels" to 1, "endpointFadeMs" to 5,
                "events" to percussion.map { e -> linkedMapOf(
                    "engine" to "THUMP", "voice" to e.voice.name, "startSeconds" to e.seconds,
                    "gain" to e.gain, "macros" to Thump.defaults(e.voice),
                ) },
                "levelPolicy" to "Fixed event gains provide summing headroom; no normalization or audition gain",
                "finitePhrasePolicy" to "Exactly one 2-second bar; event tails crossing the boundary end at the 5 ms phrase fade",
            )) { percussion(percussion) },
            Source("pluck", "Stereo chopped nylon phrase", linkedMapOf(
                "api" to "Pluck.render, finite note chops and equal-power event panning", "bpm" to 120,
                "beats" to 4, "seconds" to 2, "channels" to 2, "voice" to "NYLON",
                "noteFadeMs" to 5, "endpointFadeMs" to 5,
                "events" to notes.map { n -> linkedMapOf(
                    "engine" to "PLUCK", "voice" to "NYLON", "startSeconds" to n.seconds,
                    "lengthSeconds" to n.length, "panMinusOneToOne" to n.pan, "gain" to n.gain,
                    "macros" to (Pluck.defaults(PluckVoice.NYLON) + ("TUNE" to n.semitone / 24f)),
                    "targetHz" to Pluck.frequencyFor(PluckVoice.NYLON, n.semitone / 24f),
                ) },
                "stereoPolicy" to "Different pitched note events have different equal-power pans; L and R are not duplicates",
                "levelPolicy" to "Native engine note levels and fixed arrangement gains; no audition matching",
            )) { pluck(notes) },
            Source("bass", "Decaying bass note", linkedMapOf(
                "api" to "Velvet.render", "engine" to "VELVET", "voice" to "BASS", "macros" to bassMacros,
                "targetHz" to Velvet.frequencyFor(VelvetVoice.BASS, bassMacros.getValue("TUNE")),
                "levelPolicy" to "Native melodic engine output; no additional gain or audition matching",
            )) { Velvet.render(VelvetVoice.BASS, bassMacros) },
        )
        val records = mutableListOf<Map<String, Any?>>()
        for (source in sources) {
            val first = source.render()
            val again = source.render()
            check(first.samples.contentEquals(again.samples)) { "Source ${source.id} is not deterministic" }
            validate(first)
            val file = File(root, "sources/${source.id}.wav")
            write(file, first)
            val input = WavReader.read(file)
            if (source.id == "percussion" || source.id == "pluck") check(input.frameCount == RATE * 2)
            if (source.id == "pluck") check(input.channels == 2 && input.samples.indices.any {
                it % 2 == 0 && input.samples[it] != input.samples[it + 1]
            }) { "Stereo source must have different L/R material" }
            val budgetFrames = input.frameCount + TAIL_FRAMES
            val seconds = secondsForFrames(budgetFrames)
            val seed = Dsp.seedFor("OBLITERATE", "baseline", source.id)
            val baselines = amounts.map { amount ->
                val macros = linkedMapOf(
                    "SIZE" to .65f - .45f * amount,
                    "SMEAR" to .45f + .4f * amount,
                    "DRIFT" to .15f + .55f * amount,
                    "PITCH" to .5f,
                    "SHINE" to .1f + .2f * amount,
                )
                val grains = Grains.render(input, macros, seconds, seed)
                check(grains.frameCount == budgetFrames) { "Grains must actually render the full core budget" }
                check(Gate.refusal(grains, BPM, DIVISION) == null) { "Invalid gate for ${source.id}" }
                val baseline = Gate.chop(grains, BPM, DIVISION, amount)
                validate(baseline)
                check(baseline.frameCount == budgetFrames && baseline.channels == 1)
                check(baseline.samples.contentEquals(Gate.chop(Grains.render(input, macros, seconds, seed), BPM, DIVISION, amount).samples))
                val tag = (amount * 100).roundToInt().toString().padStart(3, '0')
                val path = File(root, "baselines/${source.id}-$tag.wav")
                write(path, baseline)
                val stored = WavReader.read(path)
                val activity = activity(stored)
                check((activity.getValue("lastActiveFrame") as Int) > input.frameCount) {
                    "${source.id} $tag did not resynthesize beyond the original; inspect actual tail activity"
                }
                check((activity.getValue("lastQuarterSecondRms") as Double) > 1e-8) {
                    "${source.id} $tag ended in an empty nominal duration"
                }
                linkedMapOf<String, Any?>(
                    "amount" to (amount * 100).roundToInt() / 100.0,
                    "amountFloat32" to amount.toDouble(),
                    "file" to path.relativeTo(root).invariantSeparatorsPath,
                    "sha256" to sha(path), "frames" to stored.frameCount, "channels" to stored.channels,
                    "sampleRate" to stored.sampleRate, "peak" to stored.peak(), "rms" to rms(stored.samples),
                    "recipe" to linkedMapOf(
                        "chain" to listOf("Grains.render", "Gate.chop"),
                        "grains" to linkedMapOf("macros" to macros, "seconds" to seconds, "seed" to seed),
                        "gate" to linkedMapOf("bpm" to BPM, "division" to DIVISION, "amount" to amount,
                            "duty" to Gate.DUTY, "edgeSeconds" to Gate.EDGE_SEC),
                        "levelPolicy" to "Actual stock Grains mono fold/peak normalization and 12 ms fade, then Gate input-peak matching; no extra gain",
                    ),
                    "validation" to (activity + linkedMapOf(
                        "gateRefusal" to null, "deterministicRerender" to true,
                        "durationMatchesCoreBudget" to true, "silencePaddingFrames" to 0,
                        "postRenderTrimFrames" to 0,
                    )),
                )
            }
            records += linkedMapOf(
                "id" to source.id, "name" to source.name, "sourceRecipe" to source.recipe,
                "raw" to linkedMapOf("file" to file.relativeTo(root).invariantSeparatorsPath,
                    "sha256" to sha(file), "frames" to input.frameCount, "channels" to input.channels,
                    "sampleRate" to input.sampleRate, "peak" to input.peak(), "rms" to rms(input.samples)),
                "coreBudget" to linkedMapOf("tailSeconds" to .75, "tailFrames" to TAIL_FRAMES,
                    "frames" to budgetFrames, "seconds" to budgetFrames.toDouble() / RATE),
                "baselines" to baselines,
            )
            println("OBLITERATE inputs: ${source.id}, ${input.frameCount} frames/${input.channels}ch; 3 real Grains+Gate renders of $budgetFrames frames")
        }
        check(inputHash == sourceHashes(repo)) { "A source file changed while rendering" }
        val manifest = linkedMapOf<String, Any?>(
            "schemaVersion" to 1, "baseGitCommit" to BASE, "sourceTree" to git(repo, "rev-parse", "$BASE^{tree}"),
            "renderGitCommit" to head, "renderGitTree" to git(repo, "rev-parse", "HEAD^{tree}"),
            "workingTreeDirty" to dirty,
            "sampleRate" to RATE, "pcmBitDepth" to 24,
            "sourceLevelPolicy" to "Ordinary native synth output stages; fixed arrangement gains/headroom only; no audition gain",
            "baselinePurpose" to "Actual existing stock Grains.render followed by Gate.chop, not a new core implementation",
            "baselineMacroLaw" to linkedMapOf(
                "SIZE" to ".65 - .45*a", "SMEAR" to ".45 + .4*a", "DRIFT" to ".15 + .55*a",
                "PITCH" to ".5 (native pitch)", "SHINE" to ".1 + .2*a", "Gate.amount" to "a",
                "Gate.bpm" to BPM, "Gate.division" to DIVISION,
                "seed" to "Dsp.seedFor(OBLITERATE, baseline, sourceId), held fixed across amounts",
            ),
            "baselineLimitations" to listOf(
                "Stock Grains folds a stereo source to mono and peak-normalizes internally; this is retained and disclosed.",
                "Gate is fixed 50% duty at 1/16, 120 BPM with its stock 3 ms edges, and peak-matches its input.",
                "The full source+.75 second duration is resynthesized by Grains; no rendered audio is replaced with padding or trimmed.",
                "Late grains may naturally decay or read near the source endpoint; measured activity is reported for every baseline.",
            ),
            "sourceFileHashScope" to "All Kotlin files under audio/synth/json src/main/kotlin plus this retained experiment helper/init script; base Git commit identifies the rest",
            "sourceFileHashes" to inputHash, "sources" to records,
            "verification" to linkedMapOf("sources" to 4, "baselines" to 12, "sourceAndBaselineDeterminism" to true,
                "allFullBudgetFrames" to true, "allGatesValid" to true, "physicalListening" to "not performed"),
        )
        File(root, "input-baseline-manifest.json").writeText(Json.write(json(manifest)) + "\n")
        println("Wrote 4 PCM24 sources, 12 stock baselines and input-baseline-manifest.json under $root")
    }

    private fun percussion(events: List<Event>): Snip {
        val out = FloatArray(RATE * 2)
        val voices = events.map { it.voice }.distinct().associateWith { Thump.render(it, Thump.defaults(it)) }
        for (event in events) {
            val hit = voices.getValue(event.voice)
            check(hit.channels == 1)
            val at = (event.seconds * RATE).roundToInt()
            for (i in 0 until minOf(hit.frameCount, out.size - at)) out[at + i] += hit.samples[i] * event.gain
        }
        Dsp.fadeTail(out, 5f, RATE)
        return Snip(out, 1, RATE)
    }

    private fun pluck(notes: List<Note>): Snip {
        val out = FloatArray(RATE * 2 * 2)
        for (note in notes) {
            val hit = Pluck.render(PluckVoice.NYLON, Pluck.defaults(PluckVoice.NYLON) + ("TUNE" to note.semitone / 24f))
            check(hit.channels == 1)
            val length = minOf(hit.frameCount, (note.length * RATE).roundToInt())
            val chop = hit.samples.copyOf(length)
            Dsp.fadeTail(chop, 5f, RATE)
            val left = sqrt((1f - note.pan) * .5f) * note.gain
            val right = sqrt((1f + note.pan) * .5f) * note.gain
            val at = (note.seconds * RATE).roundToInt()
            for (i in 0 until minOf(length, RATE * 2 - at)) {
                out[(at + i) * 2] += chop[i] * left
                out[(at + i) * 2 + 1] += chop[i] * right
            }
        }
        Dsp.fadeTail(out, 5f, RATE, 2)
        return Snip(out, 2, RATE)
    }

    private fun secondsForFrames(frames: Int): Float {
        var seconds = frames.toFloat() / RATE
        repeat(32) {
            val rendered = (seconds * RATE).toInt()
            if (rendered == frames) return seconds
            seconds = if (rendered < frames) Math.nextUp(seconds) else Math.nextDown(seconds)
        }
        error("Cannot express requested Grains frame count $frames")
    }

    private fun validate(snip: Snip) {
        check(snip.frameCount > 256 && snip.sampleRate == RATE && snip.channels in 1..2)
        check(snip.samples.all { it.isFinite() } && snip.peak() in 1e-6f..1f) { "Invalid or clipped input/reference" }
    }
    private fun activity(snip: Snip): Map<String, Any?> {
        val threshold = snip.peak() * 1e-5f
        val active = (0 until snip.frameCount).filter { frame ->
            (0 until snip.channels).any { abs(snip.samples[frame * snip.channels + it]) > threshold }
        }
        val start = (snip.frameCount - RATE / 4).coerceAtLeast(0) * snip.channels
        return linkedMapOf("activeThreshold" to threshold, "firstActiveFrame" to active.firstOrNull(),
            "lastActiveFrame" to active.lastOrNull(), "lastQuarterSecondRms" to rms(snip.samples.copyOfRange(start, snip.samples.size)))
    }
    private fun rms(samples: FloatArray): Double = sqrt(samples.sumOf { it.toDouble() * it } / samples.size.coerceAtLeast(1))
    private fun write(file: File, snip: Snip) { file.parentFile.mkdirs(); WavWriter.write(file, snip, WavWriter.BitDepth.PCM_24) }
    private fun sourceHashes(repo: File): Map<String, String> {
        val files = listOf("audio", "synth", "json").flatMap { module ->
            File(repo, "$module/src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        } + listOf("SourcesAndBaseline.kt", "sources.init.gradle").map { File(repo, "experiments/obliterate-core/$it") }
        return files.sortedBy { it.relativeTo(repo).invariantSeparatorsPath }.associate { it.relativeTo(repo).invariantSeparatorsPath to sha(it) }
    }
    private fun sha(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun git(repo: File, vararg args: String): String {
        val p = ProcessBuilder(listOf("git") + args).directory(repo).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText().trim(); check(p.waitFor() == 0) { text }; return text
    }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { check(it.isFinite()) })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Cannot write manifest value ${value.javaClass}")
    }
}
