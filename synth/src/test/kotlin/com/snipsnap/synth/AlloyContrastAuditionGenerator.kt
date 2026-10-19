package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import com.snipsnap.audio.DrumClass
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/** A separate listening experiment. Never rewrites the released 36-preset pack. */
object AlloyContrastAuditionGenerator {
    private val fixed = linkedMapOf("TUNE" to .5f, "MATERIAL" to .5f, "FUSE" to .6f,
        "EDGE" to .5f, "BODY" to .45f, "LENGTH" to .45f, "HOLD" to 0f)
    private const val velocity = .75f
    private data class Case(val id: String, val section: String, val variant: String,
        val patch: AlloyPatch, val comparisonId: String? = null, val presetId: String? = null)

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/alloy-contrast")
        require(root.mkdirs() || root.isDirectory)
        if ("--refresh-page" in args) { copyPage(root); return }
        val began = System.nanoTime()
        val cases = cases()
        require(cases.size == 60 && cases.map { it.id }.distinct().size == 60)
        listOf("audio", "patches", "clip-patches", "kit").forEach { File(root, it).deleteRecursively() }
        File(root, "audio").mkdirs()
        File(root, "patches").mkdirs()
        File(root, "clip-patches").mkdirs()
        val candidates = AlloyContrastPresets.all().map { it.copy(midi = 48, velocity = velocity,
            macros = Alloy.defaults(it.voice) + it.macros + mapOf("TUNE" to .5f, "HOLD" to 0f)) }
        require(candidates.size == 12)
        for (patch in candidates) File(root, "patches/${slug(patch.name)}.json").writeText(patch.toJsonText() + "\n")
        val clips = cases.mapIndexed { index, c ->
            val p = c.patch
            val start = System.nanoTime()
            val probe = Alloy.renderProbe(p.voice, p.macros, p.midi, p.velocity, normalize = false, config = p.config)
            val held = Alloy.isLoop(p.macros.getValue("HOLD"))
            if (!held) Dsp.fadeTail(probe.samples)
            require(probe.samples.isNotEmpty() && probe.samples.all { it.isFinite() }) { "${c.id}: invalid samples" }
            val native = Snip(probe.samples, 1, Dsp.RATE)
            require(native.peak() < 1f) { "${c.id}: raw PCM would clip" }
            val matched = AuditionLevel.level(native)
            val file = "audio/${c.id}.wav"
            WavWriter.write(File(root, file), matched, WavWriter.BitDepth.PCM_16)
            val exportedSeam = if (held) {
                require(probe.seamError.isFinite() && probe.seamError < 1e-3)
                val gain = matched.peak() / native.peak().coerceAtLeast(1e-30f)
                val previous = Snip(FloatArray(probe.previousCycle.size) { probe.previousCycle[it] * gain }, 1, Dsp.RATE)
                val bytes = ByteArrayOutputStream().also { WavWriter.write(it, previous, WavWriter.BitDepth.PCM_16) }.toByteArray()
                val decoded = WavReader.read(File(root, file))
                val previousPcm = WavReader.read(bytes).samples
                Keys.seamError(previousPcm + decoded.samples, previousPcm.size).also { require(it.isFinite() && it < 1e-3) }
            } else null
            val rawFile = if (c.variant == "new") "audio/${c.id}_raw.wav".also {
                WavWriter.write(File(root, it), native, WavWriter.BitDepth.PCM_24)
            } else null
            val patchFile = c.presetId?.let { "clip-patches/${c.id}.json".also { path ->
                File(root, path).writeText(p.toJsonText() + "\n")
            } }
            linkedMapOf<String, Any?>(
                "id" to c.id, "section" to c.section, "variant" to c.variant,
                "comparisonId" to c.comparisonId, "presetId" to c.presetId,
                "voice" to p.voice.name, "role" to p.name, "title" to p.name,
                "file" to file, "rawFile" to rawFile,
                "patchFile" to patchFile,
                "model" to p.model, "midi" to p.midi, "note" to noteName(p.midi), "velocity" to p.velocity,
                "macros" to p.macros, "config" to p.config.toJsonValue(), "patch" to p.toJsonValue(),
                "loop" to held, "frames" to native.frameCount, "duration" to native.durationSeconds,
                "loopStart" to if (held) 0 else null, "loopEnd" to if (held) native.durationSeconds else null,
                "rawPeak" to native.peak(), "rms" to rms(native.samples), "dc" to dc(native.samples),
                "loudness" to Loudness.of(native), "matchedPeak" to matched.peak(), "matchedLoudness" to Loudness.of(matched),
                "spectralCentroidHz" to centroid(native.samples),
                "seamError" to if (held) probe.seamError else null, "exportedSeamError" to exportedSeam,
                "stateError" to if (held) probe.stateError else null, "prerollCycles" to if (held) probe.prerollCycles else null,
                "renderMs" to (System.nanoTime() - start) / 1e6,
                "waveform" to envelope(matched.samples),
                "diagnostics" to linkedMapOf("rootHz" to probe.diagnostics.rootHz,
                    "expectedRootHz" to Alloy.frequencyFor(p.voice, .5f, p.midi),
                    "rawPeakBeforeConditioning" to probe.diagnostics.rawPeak,
                    "maxPhaseModulationRadians" to probe.diagnostics.maxPhaseModulation,
                    "maxConditionedAttack" to probe.diagnostics.maxConditionedAttack,
                    "internalRate" to probe.diagnostics.internalRate),
            ).also { println("ALLOY contrast ${index + 1}/60: ${c.id}") }
        }
        val recipes = clips.map { linkedMapOf("id" to it["id"], "patch" to it["patch"], "attackModulation" to true) }
        File(root, "render-recipes.json").writeText(Json.write(value(recipes)) + "\n")
        exportKit(root)
        zipDirectory(File(root, "patches"), File(root, "alloy-contrast-patches.zip"), "patches")
        val manifest = linkedMapOf<String, Any?>(
            "engine" to "ALLOY", "version" to 2, "experiment" to "voice-contrast-r2", "candidate" to true,
            "title" to "Alloy · voice contrast", "sampleRate" to Dsp.RATE, "channels" to 1,
            "bitDepth" to 16, "rawBitDepth" to 24, "clipCount" to 60, "wavCount" to 72, "presetCount" to 12,
            "voices" to AlloyVoice.entries.map { it.name },
            "sections" to listOf(
                mapOf("id" to "voices", "title" to "Voice A/B", "description" to "Old and new configurations share MIDI 48, velocity .75 and identical macros. Compare what the recipe changes."),
                mapOf("id" to "roles", "title" to "Two roles", "description" to "Two named finite candidates per voice, each at MIDI 36, 48 and 60."),
                mapOf("id" to "held", "title" to "Held A/B", "description" to "Settled loop-only old/new comparisons at MIDI 48. Repeat plays the real exported cycle without a gap.")),
            "fixedMacros" to fixed, "velocity" to velocity,
            "matchedMeaning" to "Each dry render receives one scalar gain: shared loudest-200 ms RMS target 0.03 with peak guard. No effects or attack shaping are added for playback.",
            "holdMeaning" to "One settled engine cycle; the opening full attack is absent. Seam checks use its actual previous cycle, including PCM16 export.",
            "status" to "New voice candidates awaiting listening. The released engine, defaults and 36 factory presets are preserved.",
            "baselineUrl" to "../", "generatedBy" to "./gradlew --no-daemon :synth:generateAlloyContrastAudition",
            "elapsedSeconds" to (System.nanoTime() - began) / 1e9,
            "downloads" to listOf(mapOf("title" to "12 candidate patch JSONs", "file" to "alloy-contrast-patches.zip"),
                mapOf("title" to "16-pad MPC kit · XPM + PCM24", "file" to "alloy-contrast-mpc-kit.zip"),
                mapOf("title" to "Render recipes", "file" to "render-recipes.json"),
                mapOf("title" to "Diagnostics CSV", "file" to "diagnostics.csv")),
            "provenance" to provenance(root), "clips" to clips,
        )
        File(root, "manifest.json").writeText(Json.write(value(manifest)) + "\n")
        val keys = listOf("id", "voice", "section", "variant", "midi", "velocity", "loop", "frames", "duration", "rawPeak", "rms", "dc", "matchedLoudness", "spectralCentroidHz", "seamError", "exportedSeamError", "stateError", "prerollCycles")
        File(root, "diagnostics.csv").bufferedWriter().use { out ->
            out.appendLine(keys.joinToString(",")); clips.forEach { c -> out.appendLine(keys.joinToString(",") { c[it]?.toString() ?: "" }) }
        }
        File(root, "README.txt").writeText("ALLOY voice contrast R2 — listening candidates\n60 clips, mono 44.1 kHz; 60 matched PCM16 WAVs + 12 raw PCM24 references.\nThe released 36-preset engine and audition remain unchanged.\nVoice and held comparisons share fixed macros, MIDI 48 and velocity .75.\nRole candidates have complete serializable model-1 configurations.\nHeld clips are settled loop-only exports without the opening full attack.\nNumerical checks do not establish listening approval.\nRegenerate: ${manifest["generatedBy"]}\n")
        copyPage(root)
        println("wrote ALLOY contrast listening pack to ${root.absolutePath}")
    }

    private fun cases(): List<Case> = buildList {
        for (voice in AlloyVoice.entries) {
            val tag = voice.name.lowercase(Locale.ROOT)
            for (held in listOf(false, true)) for (old in listOf(true, false)) {
                val variant = if (old) "old" else "new"
                val section = if (held) "held" else "voices"
                val mode = if (held) "held" else "finite"
                add(Case("${variant}_${tag}_$mode", section, variant,
                    AlloyPatch("${if (old) "Released" else "Candidate"} ${voice.name.lowercase()} · $mode", voice,
                        fixed + ("HOLD" to if (held) 1f else 0f), 48, velocity,
                        config = if (old) Alloy.configFor(voice) else AlloyContrastPresets.configFor(voice)),
                    "${if (held) "held" else "voice"}_$tag"))
            }
            for (patch in AlloyContrastPresets.forVoice(voice)) for (midi in listOf(36, 48, 60)) {
                val id = "r2_${slug(patch.name)}"
                add(Case("role_${slug(patch.name)}_m$midi", "roles", "candidate",
                    patch.copy(macros = Alloy.defaults(voice) + patch.macros + mapOf("TUNE" to .5f, "HOLD" to 0f),
                        midi = midi, velocity = velocity), presetId = id))
            }
        }
    }

    private fun exportKit(root: File) {
        val work = File(root, ".kit-work")
        work.deleteRecursively()
        val heldVoices = listOf(AlloyVoice.BASS, AlloyVoice.BRASS, AlloyVoice.STRING, AlloyVoice.SOFT)
        val patches = AlloyContrastPresets.all().map { it.copy(midi = 48, velocity = velocity, macros = Alloy.defaults(it.voice) + it.macros + mapOf("TUNE" to .5f, "HOLD" to 0f)) } +
            heldVoices.map { v -> AlloyPatch("${v.name} contrast held", v, fixed + ("HOLD" to 1f), 48, velocity, config = AlloyContrastPresets.configFor(v)) }
        val pads = patches.map { patch ->
            val recipe = PadRecipe(patch)
            ArrangedPad(recipe.render(), if (Alloy.isLoop(patch.macros.getValue("HOLD"))) DrumClass.LOOP else DrumClass.TONAL,
                recipe.toJsonValue(), displayName = patch.name)
        }
        val kit = KitAssembler.assembleArranged("SnipSnap Alloy Contrast Candidates", pads, work)
        val result = KitExporter.exportProgramFolder(kit, work, File(root, "kit"), overwrite = true)
        File(work, "kit.json").copyTo(File(result.directory, "kit.json"), overwrite = true)
        File(result.directory, "README.txt").writeText("ALLOY voice contrast — candidate MPC kit\nA01–A12: two finite roles per voice, in BASS/KEY/PLUCK/BRASS/STRING/SOFT order, MIDI 48 and velocity .75.\nA13–A16: BASS, BRASS, STRING and SOFT candidate held configurations at fixed comparison macros.\nThe drum program leaves infinite sample looping disabled. Enable whole-sample looping for A13–A16 to hear indefinite HOLD.\nHeld WAVs are settled loop-only regions; the opening strike is absent.\nDry PCM24 audio and complete kit.json recipes accompany the XPM.\nThese are listening candidates. Existing factory presets and saved patches retain their sound.\n")
        zipDirectory(result.directory, File(root, "alloy-contrast-mpc-kit.zip"), result.directory.name)
        work.deleteRecursively()
    }

    private fun provenance(root: File): Map<String, Any?> {
        val cwd = File(System.getProperty("user.dir"))
        val repo = if (File(cwd, "src/main/kotlin/com/snipsnap/synth/Alloy.kt").isFile) cwd.parentFile else cwd
        val paths = listOf("Alloy.kt", "AlloyConfig.kt", "AlloyPatch.kt", "AlloyContrastPresets.kt", "Dsp.kt", "Tide.kt").map { "synth/src/main/kotlin/com/snipsnap/synth/$it" } +
            listOf("synth/src/test/kotlin/com/snipsnap/synth/AlloyContrastAuditionGenerator.kt", "synth/src/test/kotlin/com/snipsnap/synth/AuditionLevel.kt")
        val sources = paths.map { mapOf("path" to it, "sha256" to sha(File(repo, it).readBytes())) }
        fun executable(c: Class<*>): List<Class<*>> = listOf(c) + c.declaredClasses.flatMap(::executable)
        val compiled = listOf(Alloy::class.java, AlloyConfig::class.java, AlloyPatch::class.java, AlloyContrastPresets::class.java,
            AlloyContrastAuditionGenerator::class.java, Dsp::class.java, Tide::class.java, Resampler::class.java,
            AuditionLevel::class.java, Loudness::class.java, Snip::class.java, WavWriter::class.java)
            .flatMap(::executable).distinctBy { it.name }.sortedBy { it.name }.map { c ->
            val bytes = requireNotNull(c.getResourceAsStream("/${c.name.replace('.', '/')}.class")).use { it.readBytes() }
            mapOf("class" to c.name, "sha256" to sha(bytes))
        }
        fun git(vararg args: String): String { val p = ProcessBuilder(listOf("git") + args).directory(repo).start(); val output = p.inputStream.bufferedReader().use { it.readText().trim() }; check(p.waitFor() == 0); return output }
        return linkedMapOf("gitRevision" to git("rev-parse", "HEAD"), "sourceFiles" to sources,
            "sourceSha256" to sha(Json.write(value(sources)).toByteArray()), "compiledClasses" to compiled,
            "rosterSha256" to sha(File(root, "render-recipes.json").readBytes()),
            "renderRecipesFile" to "render-recipes.json",
            "sourceWorkingTreeClean" to git("status", "--porcelain", "--", *paths.toTypedArray()).isEmpty())
    }

    private fun copyPage(root: File) { for (name in listOf("index.html", "app.js", "styles.css")) {
        val input = requireNotNull(javaClass.getResourceAsStream("/audition/alloy-contrast/$name"))
        File(root, name).outputStream().use { output -> input.use { it.copyTo(output) } }
    } }
    private fun zipDirectory(directory: File, destination: File, prefix: String) {
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            for (file in directory.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(directory).invariantSeparatorsPath }) {
                zip.putNextEntry(ZipEntry("$prefix/${file.relativeTo(directory).invariantSeparatorsPath}").apply { time = 0L })
                file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
    }
    private fun centroid(samples: FloatArray): Double { val spectrum = Fft.magnitudeSpectrum(samples, 8192); var weighted = 0.0; var total = 0.0
        for (i in 1 until spectrum.size) { val p = spectrum[i].toDouble().pow(2); total += p; weighted += p * Fft.binToHz(i, 8192, Dsp.RATE) }; return weighted / total.coerceAtLeast(1e-30) }
    private fun envelope(samples: FloatArray) = (0 until 200).map { bin ->
        val a = bin * samples.size / 200; val b = ((bin + 1) * samples.size / 200).coerceAtLeast(a + 1).coerceAtMost(samples.size)
        (a until b).maxOfOrNull { abs(samples[it]) } ?: 0f }
    private fun rms(a: FloatArray) = sqrt(a.sumOf { it.toDouble() * it } / a.size)
    private fun dc(a: FloatArray) = a.sumOf { it.toDouble() } / a.size
    private fun slug(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_')
    private fun noteName(midi: Int) = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[midi % 12] + (midi / 12 - 1)
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    private fun value(v: Any?): JsonValue = when (v) {
        null -> JsonValue.Null; is JsonValue -> v; is String -> JsonValue.Str(v); is Boolean -> JsonValue.Bool(v)
        is Number -> JsonValue.Num(v.toDouble().also { require(it.isFinite()) })
        is Map<*, *> -> JsonValue.Obj(v.entries.associate { it.key.toString() to value(it.value) })
        is Iterable<*> -> JsonValue.Arr(v.map(::value)); else -> error("unsupported manifest value")
    }
}
