package com.snipsnap.synth

import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.xpm.XpmWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The seven extension sounds as native recipes and unchanged full-range PCM24 samples. */
object OnsetExtensionKitGenerator {
    const val KIT_NAME = "SnipSnap ONSET Extension"
    const val ARCHIVE_NAME = "onset-extension-native-kit.zip"

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "usage: OnsetExtensionKitGenerator [output-directory]" }
        val root = File(args.firstOrNull() ?: "../testkit/onset-extension-native")
        val archive = write(root)
        println("wrote seven editable model-4 ONSET pads, full-range RAW WAVs, XTD/XPM and ${archive.absolutePath}")
    }

    fun write(root: File): File {
        require(root.mkdirs() || root.isDirectory) { "could not create $root" }
        val work = File(root, ".onset-extension-work")
        check(!work.exists() || work.deleteRecursively()) { "could not clear $work" }
        try {
            val kit = KitAssembler.assembleArranged(KIT_NAME, OnsetKits.extensionStudy(), work)
            check(kit.pads.size == 7)
            val destination = File(root, KIT_NAME)
            val result = Mpc3Exporter.exportTrack(kit, work, destination, overwrite = true)
            val data = File(destination, Mpc3TrackWriter.trackDataDirName(KIT_NAME))
            // Use the exporter's actual renamed files and hash-bound sidecar.
            // Both programs play the raw samples at unity, with complete tails.
            val exported = KitStore.load(data)
            val program = exported.toDrumProgram { WavReader.read(File(data, it.sampleFile)).frameCount.toLong() }
                .copy(level = 1f)
            Mpc3TrackWriter().writeTo(destination, program)
            XpmWriter().writeTo(data, program.copy(pads = program.pads.map { it?.copy(decay = 0f) }))
            check(result.program.isFile)
            val rows = exported.pads.mapIndexed { index, pad ->
                val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
                val patch = recipe.patch as OnsetPatch
                check(patch == OnsetPresets.extensionStudy()[index] && patch.modelVersion == 4)
                check(recipe.fx == null && recipe.board == null && recipe.pedals == null && recipe.layers == null)
                check(pad.level == 1f && pad.oneShot && pad.muteGroup == 0 && pad.velocityLayers.isEmpty())
                val sample = File(data, pad.sampleFile)
                val replay = ByteArrayOutputStream().also { WavWriter.write(it, recipe.render()) }.toByteArray()
                check(sample.readBytes().contentEquals(replay)) { "native recipe does not regenerate pad ${pad.slot}" }
                check(File(work, kit.pads[index].sampleFile).readBytes().contentEquals(sample.readBytes())) {
                    "export altered the raw sample at pad ${pad.slot}"
                }
                val sha = hash(sample)
                check(pad.source["rawSha256"] == sha)
                linkedMapOf<String, Any?>("slot" to pad.slot, "name" to pad.displayName,
                    "voice" to patch.voiceName, "modelVersion" to patch.modelVersion, "macros" to patch.macros,
                    "recipe" to recipe.toJsonValue(), "sample" to "${data.name}/${pad.sampleFile}",
                    "frames" to WavReader.read(sample).frameCount, "sha256" to sha)
            }
            File(destination, "settings.json").writeText(Json.write(json(linkedMapOf(
                "format" to "snipsnap-onset-native-extension", "version" to 1,
                "renderer" to "ONSET", "modelVersion" to 4, "sampleRate" to Dsp.RATE,
                "bitDepth" to 24, "channels" to 1, "dry" to true,
                "programLevel" to 1, "padLevel" to 1, "fullSampleWindows" to true,
                "sourceIdentity" to sourceIdentity(), "pads" to rows,
            ))) + "\n")
            File(destination, "README.txt").writeText(readme())
            val archive = File(root, ARCHIVE_NAME)
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                destination.walkTopDown().filter { it.isFile }
                    .sortedBy { it.relativeTo(destination).invariantSeparatorsPath }.forEach { file ->
                        zip.putNextEntry(ZipEntry("$KIT_NAME/${file.relativeTo(destination).invariantSeparatorsPath}")
                            .apply { time = 0L })
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
            }
            return archive
        } finally {
            work.deleteRecursively()
        }
    }

    private fun sourceIdentity(): Map<String, String> {
        val directory = File("src/main/kotlin/com/snipsnap/synth").takeIf { it.isDirectory }
            ?: File("synth/src/main/kotlin/com/snipsnap/synth")
        require(directory.isDirectory) { "run the kit generator from the repository or synth module" }
        return directory.listFiles().orEmpty().filter { it.name.startsWith("Onset") && it.extension == "kt" }
            .sortedBy { it.name }.associate { it.name to hash(it) }
    }

    private fun hash(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Number -> JsonValue.Num(value.toDouble())
        is Boolean -> JsonValue.Bool(value)
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("unsupported native kit metadata ${value.javaClass}")
    }

    private fun readme() = """
        ONSET native extension kit: seven editable model-4 pads

        A01 Floor Tom, A02 Mid Tom, A03 High Tom, A04 Dry Pluck,
        A05 Wire Pluck, A06 Wood Knock, A07 Metal Ring. Each uses BLEND .5.
        This is a separate kit. The accepted sixteen-pad four-voice kit stays on model 3.

        MPC 3: load "$KIT_NAME.xtd" beside its unchanged _[TrackData] folder.
        MPC 2: open that folder and load "$KIT_NAME.xpm" beside its samples.
        SnipSnap: import this ZIP, reopen a pad in SYNTH, edit its six controls and save.
        kit.json beside the WAVs retains the complete typed PadRecipe and modelVersion 4.
        Standalone XTD/XPM re-exports retain this history; MPC project/session audio
        exports currently do not carry editable recipes. MPC itself plays the WAVs.

        Samples are dry mono 44.1 kHz PCM24, with no audition matching, extra FX,
        resampling, trim or export gain. Both programs and all pads use unity level.
        At full velocity, each pad plays the full native-rendered sample window.
        Every saved recipe is decoded and replayed through the normal WAV writer;
        its bytes must equal the supplied WAV before this download is packaged.

        settings.json records all six macros, native voice identities and source
        hashes. Pad names are labels and do not choose a prototype signal branch.
        Format/readback checks do not establish MPC hardware playback acceptance.

        Reproduce: ./gradlew :synth:generateOnsetExtensionKit
    """.trimIndent() + "\n"
}
