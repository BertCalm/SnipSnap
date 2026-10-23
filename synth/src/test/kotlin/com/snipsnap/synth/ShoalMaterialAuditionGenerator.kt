package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Production model 3 recipes, native velocity and shared-level material comparisons. */
object ShoalMaterialAuditionGenerator {
    private const val REVISION = "shoal-playable-m3-r1"
    private val motions = listOf(ShoalVoice.GLASS, ShoalVoice.RIPPLE)
    private val notes = listOf(48, 60)
    private val velocities = listOf(.25f, .6f, 1f)
    private val materials = listOf(ShoalMaterial.HARMONIC, ShoalMaterial.BOWL, ShoalMaterial.WOOD, ShoalMaterial.WIRE)

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1 && args.none { it.startsWith("--") }) {
            "usage: ShoalMaterialAuditionGenerator [output-directory]"
        }
        val root = File(args.firstOrNull() ?: "../testkit/shoal-playable-audition/playable").absoluteFile
        require(root.isDirectory || root.mkdirs()) { "Cannot create $root" }
        for (name in listOf("playable.html", "playable.css", "playable.js")) {
            val page = requireNotNull(javaClass.getResourceAsStream("/audition/shoal-playable/$name")) {
                "Missing playable audition resource $name"
            }.use { it.readBytes() }
            File(root.parentFile, name).writeBytes(page)
        }
        val repo = repositoryRoot()
        val sources = sourceSnapshot(repo)
        val rows = mutableListOf<Map<String, Any?>>()
        val started = System.nanoTime()
        for (motion in motions) for (midi in notes) for (velocity in velocities) for (material in materials) {
            val id = "${material.name.lowercase()}_${motion.name.lowercase()}_m${midi}_v${(velocity * 100).roundToInt()}"
            val patch = ShoalPatch(
                name = "${materialLabel(material)} · ${motion.name} · MIDI $midi · velocity $velocity",
                voice = motion, macros = Shoal.defaults(motion) + ("HOLD" to 0f),
                midi = midi, velocity = velocity, model = 3, material = material,
            )
            val before = System.nanoTime()
            val probe = Shoal.probe(motion, patch.macros, midi, velocity, model = patch.model, material = material)
            val raw = Snip(probe.samples, 1, Dsp.RATE)
            require(raw.samples.isNotEmpty() && raw.samples.all { it.isFinite() } && probe.raw.all { it.isFinite() }) {
                "$id has empty or non-finite prelevel output"
            }
            // Two independent production runs: the diagnostic path, then the actual native Patch API.
            val native = patch.render()
            val expectedNative = probe.samples.copyOf()
            Dsp.levelTo(expectedNative, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET * velocity.pow(.65f))
            require(native.channels == 1 && native.sampleRate == Dsp.RATE && native.samples.contentEquals(expectedNative)) {
                "$id: production Patch.render differs from the independently leveled production probe"
            }
            val recipeText = Json.write(patch.toJsonValue()) + "\n"
            val restored = ShoalPatch.fromJsonText(recipeText)
            require(restored == patch) { "$id: native patch recipe does not round trip" }
            val repeat = restored.render()
            require(native.samples.contentEquals(repeat.samples)) { "$id: independently repeated native recipe is not deterministic" }
            require(native.samples.all { it.isFinite() } && native.peak() < 1f) { "$id: native output cannot be exported without clipping" }
            val matched = AuditionLevel.level(native)
            require(matched.samples.all { it.isFinite() } && matched.peak() < 1f)
            val renderMs = (System.nanoTime() - before) / 1_000_000.0
            val stem = "$REVISION/$id"
            val nativePath = "native/$stem.wav"
            val matchedPath = "matched/$stem.wav"
            val recipePath = "recipes/$stem.json"
            val tracePath = "traces/$stem.json"
            val sampler = SmplChunk(rootNote = midi)
            for ((path, snip) in listOf(nativePath to native, matchedPath to matched)) {
                val destination = File(root, path).apply { parentFile.mkdirs() }
                WavWriter.write(destination, snip, WavWriter.BitDepth.PCM_24, smpl = sampler)
            }
            File(root, recipePath).apply { parentFile.mkdirs(); writeText(recipeText) }
            val controller = sampled(probe.energyTrace).map { frame -> linkedMapOf(
                "seconds" to frame.seconds, "energy" to frame.energy, "centers" to frame.centers,
                "strayCents" to frame.strayCents, "totalEnergy" to frame.totalEnergy, "supply" to frame.supply,
            ) }
            val radiation = sampled(probe.radiationTrace.orEmpty()).map { frame -> linkedMapOf(
                "seconds" to frame.seconds, "energy" to frame.energy, "rootEnergy" to frame.rootEnergy,
                "supply" to frame.supply, "strayCents" to frame.strayCents,
            ) }
            File(root, tracePath).apply { parentFile.mkdirs(); writeText(Json.write(json(linkedMapOf(
                "id" to id, "model" to patch.model, "material" to material.name,
                "controllerDescription" to "Actual production controller energy by partial slot, sampled in time; not an audio spectrum.",
                "radiationDescription" to "Actual production material radiation state; independent of the controller state.",
                "controllerOriginalFrames" to probe.energyTrace.size,
                "radiationOriginalFrames" to probe.radiationTrace.orEmpty().size,
                "controller" to controller, "radiation" to radiation,
            ))) + "\n") }
            val d = probe.diagnostics
            val md = probe.materialDiagnostics
            val nativeHash = sha256(File(root, nativePath))
            val matchedHash = sha256(File(root, matchedPath))
            rows += linkedMapOf(
                "id" to id, "model" to patch.model, "material" to material.name, "motion" to motion.name,
                "midi" to midi, "velocity" to velocity, "macros" to patch.macros,
                "durationSeconds" to native.durationSeconds, "frames" to native.frameCount,
                "nativePath" to nativePath, "matchedPath" to matchedPath, "recipePath" to recipePath, "tracePath" to tracePath,
                "nativeMetrics" to metrics(native), "matchedMetrics" to metrics(matched), "rawProbeMetrics" to metrics(raw),
                "nativeLoudnessTarget" to Dsp.MELODIC_LOUDNESS_TARGET * velocity.pow(.65f),
                "matchedGain" to Loudness.of(matched) / Loudness.of(native).coerceAtLeast(1e-9f),
                "nativeSha256" to nativeHash, "matchedSha256" to matchedHash,
                "sha256" to linkedMapOf("native" to nativeHash, "matched" to matchedHash, "recipe" to sha256(File(root, recipePath)), "trace" to sha256(File(root, tracePath))),
                "nativeFloatSha256" to floatHash(native.samples), "leveledProbeFloatSha256" to floatHash(expectedNative),
                "repeatFloatSha256" to floatHash(repeat.samples),
                "nativePatchEquivalent" to true, "recipeRoundTripExact" to true, "deterministicRepeat" to true,
                "nativeProbeMaxDifference" to maxDifference(native.samples, expectedNative),
                "repeatMaxDifference" to maxDifference(native.samples, repeat.samples),
                "diagnostics" to linkedMapOf(
                    "internalRawPeak" to d.rawPeak, "internalRawRms" to d.rawRms, "internalRawDc" to d.rawDc,
                    "minControllerEnergy" to d.minEnergy, "maxControllerEnergySum" to d.maxEnergySum,
                    "maxTransferResidual" to d.maxTransferResidual, "activePartials" to d.activePartials,
                    "maxFrequencyHz" to d.maxFrequencyHz,
                ),
                "materialDiagnostics" to md?.let { linkedMapOf(
                    "modeHz" to it.modeHz, "modeRatios" to it.modeRatios, "modeT60Seconds" to it.modeT60Seconds,
                    "rootT60Seconds" to it.rootT60Seconds, "rootGain" to it.rootGain,
                    "contactSeconds" to it.contactSeconds, "contactCutoffHz" to it.contactCutoffHz,
                    "excitationWeights" to it.excitationWeights, "headroomTrim" to it.headroomTrim,
                    "radiationEnergyScale" to it.radiationEnergyScale, "retainedEnergyRatio" to it.retainedEnergyRatio,
                    "rootPowerFraction" to it.rootPowerFraction, "sourceDurationSeconds" to it.sourceDurationSeconds,
                    "passiveTailSeconds" to it.passiveTailSeconds, "maxRadiationEnergySum" to it.maxRadiationEnergySum,
                    "maxRootEnergy" to it.maxRootEnergy, "velocityEnergyBudget" to it.velocityEnergyBudget,
                ) },
                "renderMillisecondsIncludingChecks" to renderMs,
            )
            println("SHOAL playable ${rows.size}/48 $id · ${native.frameCount} frames · ${renderMs.roundToInt()} ms")
        }
        check(rows.size == 48 && rows.map { it.getValue("id") }.distinct().size == 48)
        val manifest = linkedMapOf(
            "schemaVersion" to 1, "engine" to "SHOAL", "modelVersion" to 3, "revision" to REVISION,
            "title" to "Shoal · playable materials", "finiteOnly" to true,
            "sourceRevision" to git("rev-parse", "HEAD"), "sourceTreeDirty" to git("status", "--porcelain")?.isNotBlank(),
            "sourceSnapshotSha256" to sources.first, "sourceSnapshotFiles" to sources.second,
            "sampleRate" to Dsp.RATE, "internalSampleRate" to Dsp.RATE * Dsp.OVERSAMPLE,
            "channels" to 1, "bitDepth" to 24, "clipCount" to rows.size, "wavCount" to rows.size * 2,
            "materials" to materials.map { linkedMapOf("id" to it.name, "label" to materialLabel(it), "description" to materialDescription(it)) },
            "motions" to motions.map { linkedMapOf("id" to it.name, "label" to it.name.lowercase().replaceFirstChar(Char::uppercase)) },
            "noteMatrix" to notes, "velocityMatrix" to velocities, "defaultVelocity" to .6f,
            "defaultPlaybackMode" to "native", "matchedLoudnessTarget" to .03,
            "nativeDescription" to "Exactly the production ShoalPatch.render output, including the engine's velocity-sensitive level.",
            "matchedDescription" to "The same native sound at the shared AuditionLevel target, for comparing contact and color without its level difference.",
            "metricNotes" to METRIC_NOTES, "packPath" to "shoal-playable-materials.zip", "readmePath" to "README.md",
            "previousStudyPath" to "../materials.html", "recipeFormat" to "Native SnipSnap ShoalPatch JSON, model 3 with explicit material.",
            "generationMilliseconds" to (System.nanoTime() - started) / 1_000_000.0,
            "clips" to rows,
        )
        File(root, "manifest.json").writeText(Json.write(json(manifest)) + "\n")
        File(root, "measurements.json").writeText(Json.write(json(linkedMapOf(
            "engine" to "SHOAL", "modelVersion" to 3, "revision" to REVISION,
            "sourceRevision" to manifest["sourceRevision"], "sourceTreeDirty" to manifest["sourceTreeDirty"],
            "sourceSnapshotSha256" to sources.first, "metricNotes" to METRIC_NOTES, "clips" to rows,
        ))) + "\n")
        File(root, "README.md").writeText("""
            # Shoal — playable materials, model 3

            48 complete finite production cases: HARMONIC/BOWL/WOOD/WIRE × GLASS/RIPPLE
            motion × MIDI48/60 × velocity.25/.6/1. No rack effects. 96 mono24-bit WAVs.
            Native files are exactly ShoalPatch.render, including velocity-sensitive level.
            Matched files apply the shared AuditionLevel target to the same native samples.
            Prelevel probe metrics are retained separately; no prelevel audio is mislabeled Native.

            Every downloaded recipe is native ShoalPatch JSON with model 3 and explicit material.
            For each case, the production probe independently reconstructs the native render;
            a JSON-decoded patch then repeats it bit-for-bit. Generation aborts on any mismatch.
            No UI or browser synthesis is involved. Full source snapshot fingerprints accompany
            the Git revision and dirty flag in manifest.json. Actual controller/radiation traces
            are sampled to at most96 frames each, keeping the initial page small.

            Reproduce with `./gradlew :synth:generateShoalMaterialAudition`.
            The listening ZIP contains all native/matched WAVs, recipes, README and measurements.
            The previous material study and voice auditions remain separate and unchanged.

            $METRIC_NOTES
        """.trimIndent().replace("model 3", "model 3").replace("MIDI48/60", "MIDI 48/60")
            .replace("velocity.25", "velocity .25").replace("mono24-bit", "mono 24-bit").replace("at most96", "at most 96") + "\n")
        val paths = listOf("README.md", "measurements.json") + rows.flatMap { row ->
            listOf("nativePath", "matchedPath", "recipePath").map { row.getValue(it) as String }
        }
        ZipOutputStream(File(root, "shoal-playable-materials.zip").outputStream()).use { zip ->
            for (path in paths.sorted()) {
                zip.putNextEntry(ZipEntry(path).apply { time = 0 })
                File(root, path).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        println("SHOAL playable complete: ${root.canonicalPath}, ${root.walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1_000_000.0} MB")
    }

    private fun <T> sampled(frames: List<T>): List<T> =
        if (frames.size <= 96) frames else (0 until 96).map { frames[(it * (frames.size - 1) / 95.0).roundToInt()] }

    private fun metrics(snip: Snip) = linkedMapOf(
        "peak" to snip.peak(), "rms" to sqrt(snip.samples.sumOf { it.toDouble() * it } / snip.samples.size),
        "dc" to snip.samples.sumOf { it.toDouble() } / snip.samples.size, "loudness" to Loudness.of(snip),
    )
    private fun maxDifference(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size)
        var difference = 0.0
        for (i in a.indices) difference = maxOf(difference, abs(a[i].toDouble() - b[i]))
        return difference
    }
    private fun materialLabel(material: ShoalMaterial) = when (material) {
        ShoalMaterial.HARMONIC -> "Harmonic"
        ShoalMaterial.BOWL -> "Bronze bowl"
        ShoalMaterial.WOOD -> "Wooden bar"
        ShoalMaterial.WIRE -> "Tensioned wire"
    }
    private fun materialDescription(material: ShoalMaterial) = when (material) {
        ShoalMaterial.HARMONIC -> "The harmonic population, with the original pitched articulation."
        ShoalMaterial.BOWL -> "Split, inharmonic mode groups with a long, bell-like ring."
        ShoalMaterial.WOOD -> "Flexural bar modes, uneven strike coupling and a short colored decay."
        ShoalMaterial.WIRE -> "Paired stretched modes, pluck-position coupling and a wiry tail."
    }
    private fun repositoryRoot(): File {
        var file: File? = File(".").canonicalFile
        while (file != null) {
            if (File(file, ".git").exists()) return file
            file = file.parentFile
        }
        error("Run this generator inside the SnipSnap repository")
    }
    private fun sourceSnapshot(repo: File): Pair<String, List<Map<String, String>>> {
        val files = listOf("synth/src/main/kotlin", "audio/src/main/kotlin", "json/src/main/kotlin").flatMap { folder ->
            File(repo, folder).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        } + File(repo, "synth/src/test/kotlin/com/snipsnap/synth/ShoalMaterialAuditionGenerator.kt")
        val digest = MessageDigest.getInstance("SHA-256")
        val rows = files.distinct().sortedBy { it.relativeTo(repo).invariantSeparatorsPath }.map { file ->
            val path = file.relativeTo(repo).invariantSeparatorsPath
            val bytes = file.readBytes()
            digest.update(path.toByteArray(Charsets.UTF_8)); digest.update(0.toByte()); digest.update(bytes); digest.update(0.toByte())
            linkedMapOf("path" to path, "sha256" to hash(bytes))
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } to rows
    }
    private fun sha256(file: File) = hash(file.readBytes())
    private fun floatHash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) bytes.putFloat(sample)
        return hash(bytes.array())
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun git(vararg args: String): String? = runCatching {
        val process = ProcessBuilder(listOf("git") + args).redirectError(File("/dev/null")).start()
        val result = process.inputStream.bufferedReader().use { it.readText() }.trim()
        result.takeIf { process.waitFor() == 0 }
    }.getOrNull()
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) { "Non-finite material evidence: $value" } })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        is DoubleArray -> JsonValue.Arr(value.map(::json))
        is FloatArray -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported material manifest value: ${value.javaClass}")
    }
    private const val METRIC_NOTES = "Native velocity means the exact production Patch.render buffer, including velocity-sensitive output level. Matched loudness means AuditionLevel(native), target .03 loudest 200 ms RMS with its .99 peak guard. Velocity can still change contact and modal brightness in matched playback. Raw probe metrics precede production leveling; internal oversampled diagnostics remain separate. Every native buffer is checked against an independent production probe normalized by the production velocity rule; a JSON-decoded patch renders it again with zero sample difference. These are deterministic native recipes, not sound-quality approval. Controller and material radiation state are different traces, sampled from actual production state; neither is an audio spectrogram. The 48 cases use finite HOLD 0; material HOLD is not claimed. No chorus, distortion or reverb is applied. Render cost includes probe/native/replay checks and varies with hardware/JIT."
}
