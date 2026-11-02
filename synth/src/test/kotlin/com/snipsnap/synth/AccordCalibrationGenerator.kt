package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Build-time default-performance calibration accelerator.
 *
 * Generate: generateAccordCalibration
 * Verify:   generateAccordCalibration -PaccordCalibrationArgs="--verify spot"
 *           generateAccordCalibration -PaccordCalibrationArgs="--verify all"
 *
 * Each value comes from Accord.referenceCalibrationGain's normal/hard default
 * performances. Edited performances use the same velocity-shared constant-gain
 * recipe. Neither path applies per-hit normalization or time-varying gain.
 */
object AccordCalibrationGenerator {
    private const val VERSION = 2
    private const val RELATIVE_VERIFY_ERROR = 1e-10
    private val profiles = listOf(
        AccordVoice.BRUSH to AccordAcousticProfile.BASE,
        AccordVoice.WOOD to AccordAcousticProfile.BASE,
        AccordVoice.TINE to AccordAcousticProfile.REFERENCE,
        AccordVoice.TINE to AccordAcousticProfile.SOFT_ATTACK,
        AccordVoice.TINE to AccordAcousticProfile.BRIGHT_EDGE,
        AccordVoice.TINE to AccordAcousticProfile.LONG_RING,
    )
    private data class Row(val voice: AccordVoice, val profile: AccordAcousticProfile, val gains: List<Double>)

    @JvmStatic fun main(args: Array<String>) {
        var outputRoot: File? = null
        var verify: String? = null
        var i = 0
        while (i < args.size) {
            when {
                args[i] == "--verify" -> {
                    verify = if (i + 1 < args.size && !args[i + 1].startsWith("--")) args[++i] else "spot"
                }
                args[i].startsWith("--verify=") -> verify = args[i].substringAfter('=')
                args[i] == "--output-root" -> {
                    require(i + 1 < args.size) { "--output-root requires a directory" }
                    outputRoot = File(args[++i]).canonicalFile
                }
                args[i].startsWith("--output-root=") -> outputRoot = File(args[i].substringAfter('=')).canonicalFile
                else -> error("unknown calibration argument ${args[i]}")
            }
            i++
        }
        val root = outputRoot ?: repository()
        val source = File(root, "synth/src/main/kotlin/com/snipsnap/synth/AccordCalibration.kt")
        val provenance = File(root, "synth/src/test/resources/accord/calibration-model-${Accord.MODEL_VERSION}.json")
        if (verify != null) {
            require(verify == "spot" || verify == "all") { "--verify expects spot or all" }
            verifyTable(source, provenance, verify)
            return
        }

        Accord.clearCaches()
        val began = System.nanoTime()
        val rows = profiles.map { (voice, profile) ->
            val gains = (Accord.MIN_MIDI..Accord.MAX_MIDI).map { midi ->
                val gain = Accord.referenceCalibrationGain(voice, profile, midi)
                require(gain.isFinite() && gain > 0) { "invalid $voice/$profile/$midi calibration $gain" }
                println("ACCORD calibration $voice/$profile MIDI $midi = $gain")
                gain
            }
            Row(voice, profile, gains)
        }
        val generated = renderSource(rows)
        val metadata = renderProvenance(root, rows, generated, (System.nanoTime() - began) / 1e9)
        // Do not publish partial arrays if an interrupted reference render fails.
        replace(provenance, Json.write(json(metadata)) + "\n")
        replace(source, generated)
        println("Wrote ${rows.sumOf { it.gains.size }} reference gains to $source and $provenance")
    }

    private fun verifyTable(source: File, provenance: File, mode: String) {
        require(source.isFile && provenance.isFile) { "Generate the complete Accord calibration table before verifying it" }
        val metadata = Json.parse(provenance.readText()).obj()
        require(metadata.getValue("calibrationVersion").int() == VERSION) {
            "ACCORD calibration recipe changed; regenerate version $VERSION before verification"
        }
        require(metadata.getValue("renderModel").int() == Accord.MODEL_VERSION) {
            "ACCORD calibration belongs to a different render model"
        }
        require(metadata.getValue("midiMin").int() == Accord.MIN_MIDI && metadata.getValue("midiMax").int() == Accord.MAX_MIDI)
        val rows = metadata.getValue("rows").arr().map { entry ->
            val row = entry.obj()
            Row(AccordVoice.valueOf(row.getValue("voice").str()),
                AccordAcousticProfile.valueOf(row.getValue("profile").str()),
                row.getValue("gains").arr().map { it.num() })
        }
        require(rows.map { it.voice to it.profile } == profiles)
        require(rows.all { it.gains.size == Accord.MAX_MIDI - Accord.MIN_MIDI + 1 && it.gains.all { gain -> gain.isFinite() && gain > 0 } })
        for ((row, stored) in rows.zip(metadata.getValue("rows").arr())) {
            val values = stored.obj()
            require(values.getValue("normalFrames").arr().map { it.int() } == referenceFrames(row.voice, row.profile, .75f)) {
                "ACCORD normal capture duration changed for ${row.voice}/${row.profile}"
            }
            require(values.getValue("hardFrames").arr().map { it.int() } == referenceFrames(row.voice, row.profile, 1f)) {
                "ACCORD hard capture duration changed for ${row.voice}/${row.profile}"
            }
        }
        require(source.readText() == renderSource(rows)) { "The Kotlin calibration literals and provenance disagree" }
        require(metadata.getValue("generatedSourceSha256").str() == hash(source.readBytes()))
        Accord.clearCaches()
        val notes = if (mode == "all") (Accord.MIN_MIDI..Accord.MAX_MIDI).toList() else listOf(36, 48, 60, 72)
        var maximum = 0.0
        var checked = 0
        for (row in rows) for (midi in notes) {
            val actual = Accord.referenceCalibrationGain(row.voice, row.profile, midi)
            val expected = row.gains[midi - Accord.MIN_MIDI]
            val error = abs(actual - expected) / max(abs(expected), 1e-12)
            require(error <= RELATIVE_VERIFY_ERROR) {
                "ACCORD calibration changed for ${row.voice}/${row.profile}/$midi: stored=$expected regenerated=$actual relative=$error"
            }
            maximum = max(maximum, error)
            checked++
            println("Verified ACCORD ${row.voice}/${row.profile} MIDI $midi")
        }
        println("Verified $checked Accord gains ($mode); maximum relative error $maximum. Per-performance headroom sweeps are verified separately.")
    }

    private fun renderSource(rows: List<Row>): String = buildString {
        appendLine("package com.snipsnap.synth")
        appendLine()
        appendLine("/**")
        appendLine(" * Generated by AccordCalibrationGenerator. Regenerate and verify with its Gradle task.")
        appendLine(" * Default-performance accelerator for the same normal/hard gain recipe as edited performances.")
        appendLine(" * Each performance shares one constant gain across velocities; no per-hit or time-varying gain.")
        appendLine(" */")
        appendLine("internal object AccordCalibration {")
        appendLine("    const val VERSION = $VERSION")
        appendLine("    const val MODEL_VERSION = ${Accord.MODEL_VERSION}")
        appendLine("    private const val MIN_MIDI = ${Accord.MIN_MIDI}")
        appendLine("    private const val MAX_MIDI = ${Accord.MAX_MIDI}")
        appendLine("    private val gains = arrayOf(")
        for (row in rows) {
            appendLine("        // ${row.voice}/${row.profile}, MIDI ${Accord.MIN_MIDI}..${Accord.MAX_MIDI}")
            appendLine("        doubleArrayOf(")
            row.gains.chunked(4).forEach { chunk -> appendLine("            " + chunk.joinToString(", ") { it.toString() } + ",") }
            appendLine("        ),")
        }
        appendLine("    )")
        appendLine()
        appendLine("    fun gain(voice: AccordVoice, profile: AccordAcousticProfile, midi: Int): Double {")
        appendLine("        require(Accord.MODEL_VERSION == MODEL_VERSION) { \"ACCORD calibration requires regeneration for this model\" }")
        appendLine("        require(midi in MIN_MIDI..MAX_MIDI) { \"ACCORD calibration note out of range\" }")
        appendLine("        val row = when (voice) {")
        appendLine("            AccordVoice.BRUSH -> { require(profile == AccordAcousticProfile.BASE); 0 }")
        appendLine("            AccordVoice.WOOD -> { require(profile == AccordAcousticProfile.BASE); 1 }")
        appendLine("            AccordVoice.TINE -> when (profile) {")
        appendLine("                AccordAcousticProfile.REFERENCE -> 2")
        appendLine("                AccordAcousticProfile.SOFT_ATTACK -> 3")
        appendLine("                AccordAcousticProfile.BRIGHT_EDGE -> 4")
        appendLine("                AccordAcousticProfile.LONG_RING -> 5")
        appendLine("                AccordAcousticProfile.BASE -> error(\"TINE has no BASE acoustic profile\")")
        appendLine("            }")
        appendLine("        }")
        appendLine("        return gains[row][midi - MIN_MIDI]")
        appendLine("    }")
        appendLine("}")
    }

    private fun renderProvenance(root: File, rows: List<Row>, generated: String, seconds: Double): Map<String, Any?> = linkedMapOf(
        "calibrationVersion" to VERSION,
        "renderModel" to Accord.MODEL_VERSION,
        "status" to "Generated native default-performance calibration; edited performances use the identical velocity-shared constant-gain recipe",
        "generatedAtUtc" to Instant.now().toString(),
        "generator" to "com.snipsnap.synth.AccordCalibrationGenerator",
        "sourceCommit" to git(root, "rev-parse", "HEAD"),
        "workingTreeWasModified" to git(root, "status", "--porcelain").isNotEmpty(),
        "javaRuntime" to System.getProperty("java.runtime.version"),
        "midiMin" to Accord.MIN_MIDI,
        "midiMax" to Accord.MAX_MIDI,
        "gainCount" to rows.sumOf { it.gains.size },
        "sampleRate" to Dsp.RATE,
        "internalRate" to Dsp.RATE * Dsp.OVERSAMPLE,
        "reference" to linkedMapOf(
            "seed" to Accord.DEFAULT_SEED.toString(), "initializer" to AccordInitializer.REFERENCE_SINE_20261022.name,
            "sourceStartCycles" to 8.0, "sourceSeconds" to 8.0, "referenceCyclesPerSecond" to 2.0,
            "kappa" to 1.0, "spread" to .01, "baseMass" to .6, "damping" to .05,
            "stepsPerReferenceCycle" to AccordDynamics.DEFAULT_STEPS_PER_CYCLE,
            "normalVelocity" to .75, "normalContact" to .5, "normalRaft" to .5,
            "headroomProbeVelocity" to 1.0, "headroomProbeContact" to .5, "headroomProbeRaft" to .5,
            "headroomProbeUsesReferenceMechanics" to true, "headroomProbePeakTarget" to .85,
            "gainSharedAcrossVelocities" to true, "gainConstantOverPerformance" to true,
            "perHitNormalization" to false, "automaticGainControl" to false,
        ),
        "recipe" to "min(house loudness gain of normal default-performance active 8 seconds, .85/max(normal complete conditioned peak,hard complete conditioned peak,1e-12)); normal and hard use identical default CONTACT=.5 and RAFT=.5",
        "tailPolicy" to linkedMapOf(
            "rule" to "max(nominal profile release,1.1*max(all fully mapped tongue/body modal t60))",
            "t60Multiplier" to 1.1,
            "nominalProfileReleaseSeconds" to linkedMapOf("BASE" to .9, "REFERENCE" to .9,
                "SOFT_ATTACK" to 2.2, "BRIGHT_EDGE" to 2.2, "LONG_RING" to 2.2),
            "frameCalculation" to "round((sourceSeconds+resolvedReleaseSeconds)*44100); resolved release can vary with note, velocity and RAFT",
            "referenceFrameArrays" to "Rows store normalFrames and hardFrames at default CONTACT/RAFT for every MIDI note",
        ),
        "generatedSourceSha256" to hash(generated.toByteArray(Charsets.UTF_8)),
        "inputSourceSha256" to listOf("Accord.kt", "AccordAcoustics.kt", "AccordDynamics.kt", "Dsp.kt", "Tide.kt").associateWith { name ->
            hash(File(root, "synth/src/main/kotlin/com/snipsnap/synth/$name").readBytes())
        },
        "generationSeconds" to seconds,
        "rows" to rows.map { linkedMapOf("voice" to it.voice.name, "profile" to it.profile.name,
            "gains" to it.gains, "normalFrames" to referenceFrames(it.voice, it.profile, .75f),
            "hardFrames" to referenceFrames(it.voice, it.profile, 1f)) },
    )

    private fun referenceFrames(voice: AccordVoice, profile: AccordAcousticProfile, velocity: Float): List<Int> =
        (Accord.MIN_MIDI..Accord.MAX_MIDI).map { midi ->
            val config = AccordAcoustics.configuration(voice, profile, midi, velocity, .5f, .5f)
            AccordAcoustics.renderFrames(config, 8.0)
        }

    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is String -> JsonValue.Str(value)
        is Number -> JsonValue.Num(value.toDouble())
        is Boolean -> JsonValue.Bool(value)
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("unsupported calibration metadata $value")
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }

    private fun repository(): File = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
        .firstOrNull { File(it, "synth/src/main/kotlin/com/snipsnap/synth/Accord.kt").isFile }
        ?: error("run the calibration generator within SnipSnap or pass --output-root")

    private fun git(root: File, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-C", root.absolutePath) + arguments).redirectErrorStream(true).start()
        val result = process.inputStream.bufferedReader().readText().trim()
        require(process.waitFor() == 0) { "git failed: $result" }
        return result
    }

    private fun replace(file: File, contents: String) {
        file.parentFile.mkdirs()
        val temporary = Files.createTempFile(file.parentFile.toPath(), file.name, ".tmp")
        try {
            Files.writeString(temporary, contents)
            try {
                Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
