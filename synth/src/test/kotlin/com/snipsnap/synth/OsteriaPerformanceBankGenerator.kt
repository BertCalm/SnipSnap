package com.snipsnap.synth

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.ExportResult
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.kit.Names
import com.snipsnap.mpc3.MpcXRay
import com.snipsnap.xpm.WavInfo
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Render and export six accepted families while preserving earlier audition artifacts. */
object OsteriaPerformanceBankGenerator {
    internal data class Exported(val result: ExportResult, val archive: File, val manifest: JsonValue.Obj)

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1 && args.none { it.startsWith("--") }) { "supply at most one output directory" }
        val root = File(args.firstOrNull() ?: "../testkit/osteria-six-voice-bank").absoluteFile
        val hashes = sourceHashes()
        var count = 0
        val rendered = OsteriaPerformanceBank.render { pad, touch, snip ->
            println("Osteria performance ${++count}/48: ${pad.slot} ${pad.displayName} ${touch.id} · ${snip.durationSeconds} seconds")
        }
        check(rendered.size == 16 && count == 48)
        val exported = export(root, rendered, hashes)
        check(hashes == sourceHashes()) { "bank sources changed while rendering; regenerate from one settled source state" }
        println("Wrote 16 pads, 48 production WAVs, 48 per-zone recipes, MPC 2/3 programs and native kit.json")
        println("Complete bank: ${exported.archive.absolutePath} (${exported.archive.length()} bytes)")
        println("Browser packaging manifest: ${File(root, "manifest.json").absolutePath}")
    }

    /** The test uses one real pad per family; the public generator always renders all sixteen. */
    internal fun export(
        root: File,
        rendered: List<OsteriaPerformanceBank.RenderedPad>,
        hashes: Map<String, String> = sourceHashes(),
    ): Exported {
        require(rendered.isNotEmpty() && rendered.map { it.pad.slotNumber }.distinct().size == rendered.size)
        root.mkdirs()
        val work = File(root, ".work")
        check(!work.exists() || work.deleteRecursively())
        try {
            val arranged = MutableList<com.snipsnap.kit.ArrangedPad?>(rendered.maxOf { it.pad.slotNumber }) { null }
            for (entry in rendered) arranged[entry.pad.slotNumber - 1] = entry.arranged()
            val assembled = KitAssembler.assembleArranged(OsteriaPerformanceBank.NAME, arranged, work, KeySpec.parse("Cminpent"))
            val specsBySlot = rendered.associateBy { it.pad.slotNumber }
            // MPC imports use sample stems as their pad labels and do not read
            // kit.json's displayName. Put the useful instrument/root in those
            // stems while all formats continue to share the same WAV bytes.
            val kit = assembled.copy(pads = assembled.pads.map { pad ->
                val spec = specsBySlot.getValue(pad.slot).pad
                val stem = Names.sanitizeStem("${spec.slot}_${spec.displayName}".replace(' ', '_'))
                check(pad.velocityLayers.size == OsteriaPerformanceBank.touches.size)
                val layers = pad.velocityLayers.mapIndexed { index, layer ->
                    val suffix = when (OsteriaPerformanceBank.touches[index].id) {
                        "soft" -> "_S"
                        "medium" -> "_M"
                        else -> ""
                    }
                    val name = "$stem$suffix.wav"
                    if (layer.sampleFile != name) {
                        val source = File(work, layer.sampleFile)
                        val destination = File(work, name)
                        check(!destination.exists() && source.renameTo(destination)) { "could not name bank sample $name" }
                    }
                    layer.copy(sampleFile = name)
                }
                pad.copy(sampleFile = layers.last().sampleFile, velocityLayers = layers)
            })
            KitStore.save(kit, work)
            check(kit.pads.all { it.oneShot && it.muteGroup == 0 && it.level == 1f })
            check(kit.pads.all { p -> p.velocityLayers.map { it.velStart to it.velEnd } ==
                OsteriaPerformanceBank.touches.map { it.velocityStart to it.velocityEnd } })
            val directory = File(root, OsteriaPerformanceBank.NAME)
            val result = Mpc3Exporter.exportTrack(kit, work, directory, overwrite = true, mpc2Twin = true)
            check(result.samples.size == rendered.size * 3)
            val dataDir = result.samples.first().parentFile
            File(work, KitStore.FILE_NAME).copyTo(File(dataDir, KitStore.FILE_NAME), overwrite = true)
            check(KitStore.load(dataDir) == kit)
            val xpm = File(dataDir, "${kit.name}.xpm")
            for (program in listOf(result.program, xpm)) {
                val reading = MpcXRay.read(program)
                check(reading.unreadable == null && reading.programs.size == 1)
                val sounding = reading.programs.single().pads.filter { it.hasSample }
                check(sounding.size == rendered.size)
                for (pad in sounding) {
                    val layers = pad.layers.filter { it.sampleName != null }.sortedBy { it.velocityStart }
                    check(layers.map { it.velocityStart to it.velocityEnd } ==
                        OsteriaPerformanceBank.touches.map { it.velocityStart to it.velocityEnd })
                    check(layers.all { layer -> File(dataDir, "${layer.sampleName!!.removeSuffix(".wav")}.wav").isFile })
                }
            }

            val recipeDir = File(root, "recipes").apply { mkdirs() }
            val packagedRecipes = File(dataDir, "recipes").apply { mkdirs() }
            val clips = ArrayList<Map<String, Any?>>()
            val padRows = ArrayList<Map<String, Any?>>()
            for (entry in rendered) {
                val spec = entry.pad
                val kitPad = kit.pads.single { it.slot == spec.slotNumber }
                val clipRows = OsteriaPerformanceBank.touches.mapIndexed { index, touch ->
                    val patch = spec.patch(touch)
                    val snip = entry.samples[index]
                    val production = entry.productionSamples[index]
                    val measures = measure(spec, touch, snip, production)
                    val file = File(dataDir, kitPad.velocityLayers[index].sampleFile)
                    val original = File(work, file.name)
                    check(sha256(file) == sha256(original)) { "export altered ${file.name}" }
                    val info = WavInfo.read(file)
                    check(info.sampleRate == Dsp.RATE && info.channels == 2 && info.bitsPerSample == 24 && info.frameCount == snip.frameCount.toLong())
                    val recipe = PadRecipe(patch = patch)
                    val recipeFile = File(recipeDir, "${spec.clipId(touch)}.json")
                    val preparedRecipe = JsonValue.Obj(recipe.toJsonValue().entries + ("bankPreparation" to json(mapOf(
                        "samplePreparationGain" to 1f / touch.velocity,
                        "previewVelocityGain" to touch.velocity,
                        "reason" to "The pad player applies incoming velocity; remove the nominal render gain from the bank WAV to avoid applying dynamics twice.",
                    ))))
                    recipeFile.writeText(Json.write(preparedRecipe) + "\n")
                    recipeFile.copyTo(File(packagedRecipes, recipeFile.name), overwrite = true)
                    check(PadRecipe.fromJsonText(recipeFile.readText()) == recipe)
                    linkedMapOf<String, Any?>(
                        "id" to spec.clipId(touch), "padId" to spec.id, "slot" to spec.slot,
                        "candidateId" to spec.candidate.id, "presetName" to spec.candidate.patch.name,
                        "presetRevision" to spec.candidate.revision, "voice" to patch.voice.name,
                        "midi" to spec.midi, "noteName" to spec.noteName,
                        "touch" to touch.id, "velocity" to touch.velocity,
                        "samplePreparationGain" to 1f / touch.velocity, "previewVelocityGain" to touch.velocity,
                        "velocityStart" to touch.velocityStart, "velocityEnd" to touch.velocityEnd,
                        "wav" to file.relativeTo(root).invariantSeparatorsPath,
                        "recipe" to recipeFile.relativeTo(root).invariantSeparatorsPath,
                        "sha256" to sha256(file), "recipeSha256" to sha256(recipeFile),
                        "frames" to snip.frameCount, "sampleRate" to snip.sampleRate, "channels" to snip.channels,
                        "durationSeconds" to snip.durationSeconds, "gateSeconds" to OsteriaPerformanceBank.PRESS_SECONDS,
                        "attackSeconds" to patch.config.attackSeconds, "releaseSeconds" to patch.config.releaseSeconds,
                        "mixerLevel" to 1.0, "balanceTrimDb" to Osteria.balanceTrimDbFor(patch.voice),
                        "oneAttack" to true, "fixedRootPitch" to true, "oneShot" to true,
                        "seed" to patch.config.seed, "macros" to patch.macros,
                    ).apply { putAll(measures) }
                }
                val rmsLevels = clipRows.map { (it.getValue("productionRms") as Number).toDouble() }
                check(rmsLevels[1] > rmsLevels[0] * 1.3 && rmsLevels[2] > rmsLevels[1] * 1.15) {
                    "${spec.slot} lost audible velocity levels: $rmsLevels"
                }
                val loudness = entry.productionSamples.map { Loudness.of(it).toDouble() }
                check(loudness[1] > loudness[0] * 1.3 && loudness[2] > loudness[1] * 1.15)
                clips += clipRows
                padRows += linkedMapOf(
                    "id" to spec.id, "slot" to spec.slot, "slotNumber" to spec.slotNumber,
                    "candidateId" to spec.candidate.id, "presetName" to spec.candidate.patch.name,
                    "presetRevision" to spec.candidate.revision,
                    "voice" to spec.candidate.patch.voice.name, "midi" to spec.midi, "noteName" to spec.noteName,
                    "description" to spec.candidate.description,
                    "attackSeconds" to spec.candidate.patch.config.attackSeconds,
                    "releaseSeconds" to spec.candidate.patch.config.releaseSeconds,
                    "gateSeconds" to OsteriaPerformanceBank.PRESS_SECONDS, "mixerLevel" to 1.0,
                    "clips" to clipRows.map { it.getValue("id") },
                )
            }
            val expectedRecipes = clips.map { File(it.getValue("recipe") as String).name }.toSet()
            recipeDir.listFiles()?.filter { it.extension == "json" && it.name !in expectedRecipes }?.forEach { check(it.delete()) }
            val index = linkedMapOf<String, Any?>(
                "schemaVersion" to 1, "name" to kit.name, "provisional" to false,
                "productionLevels" to true, "runtimeVelocityPrepared" to true,
                "sampleRate" to Dsp.RATE, "channels" to 2, "bitDepth" to 24,
                "voiceCount" to 6, "presetCount" to OsteriaSixVoiceCandidates.candidates().size,
                "gateSeconds" to OsteriaPerformanceBank.PRESS_SECONDS,
                "velocityZones" to OsteriaPerformanceBank.touches.map { mapOf("touch" to it.id, "velocity" to it.velocity,
                    "velocityStart" to it.velocityStart, "velocityEnd" to it.velocityEnd) },
                "pads" to padRows, "clips" to clips,
                "sourceSha256" to hashes,
            )
            File(dataDir, "osteria-bank-recipes.json").writeText(Json.write(json(index)) + "\n")
            val userPresetsFile = File(root, "presets.json")
            userPresetsFile.writeText(Json.write(json(mapOf(
                "version" to 1,
                "presets" to OsteriaSixVoiceCandidates.candidates().map { candidate ->
                    val name = OsteriaSixVoiceCandidates.userPresetName(candidate)
                    mapOf("savedAt" to 1_793_923_200_000L, "patch" to candidate.patch.copy(name = name).toJsonValue())
                },
            ))) + "\n")
            userPresetsFile.copyTo(File(directory, "presets.json"), overwrite = true)
            File(directory, "README.txt").writeText(readme())
            File(dataDir, "README.txt").writeText(readme())
            val archive = File(root, OsteriaPerformanceBank.ARCHIVE_NAME)
            zip(directory, archive)
            val manifest = LinkedHashMap(index).apply {
                put("auditionKind", "performance-bank")
                put("title", "Osteria · Six voice performance bank")
                put("description", "Twelve provisional sounds across Note, Bass, Pluck, Reed, Bowed and Pad, each with three separately rendered touch layers. Every hit is one finite evolving note.")
                put("padCount", rendered.size)
                put("clipCount", clips.size)
                put("archive", mapOf("file" to archive.name, "bytes" to archive.length(), "sha256" to sha256(archive),
                    "formats" to listOf("MPC 2", "MPC 3", "SnipSnap")))
                put("programs", mapOf("mpc3" to result.program.relativeTo(root).invariantSeparatorsPath,
                    "mpc2" to xpm.relativeTo(root).invariantSeparatorsPath,
                    "native" to File(dataDir, KitStore.FILE_NAME).relativeTo(root).invariantSeparatorsPath))
                put("userPresets", mapOf("file" to userPresetsFile.name, "bytes" to userPresetsFile.length(),
                    "sha256" to sha256(userPresetsFile), "count" to OsteriaSixVoiceCandidates.candidates().size))
                put("limits", "Finite authored notes, not live key-release synthesis or seamless loops. Bank WAVs remove nominal render gain because the MPC and SnipSnap apply incoming velocity; browser previews restore it. Device audition remains pending. Editing a main recipe does not automatically rebuild its saved soft zones; all 48 complete recipes and preparation gains are supplied for regeneration.")
            }
            val encoded = json(manifest) as JsonValue.Obj
            File(root, "manifest.json").writeText(Json.write(encoded) + "\n")
            return Exported(result, archive, encoded)
        } finally {
            work.deleteRecursively()
        }
    }

    private fun measure(spec: OsteriaPerformanceBank.Pad, touch: OsteriaPerformanceBank.Touch, snip: Snip, production: Snip): Map<String, Any?> {
        val patch = spec.patch(touch)
        check(snip.channels == 2 && snip.sampleRate == Dsp.RATE && snip.frameCount == (patch.config.seconds * Dsp.RATE).roundToInt())
        check(snip.samples.all { it.isFinite() && abs(it) <= .990001f }) { "${spec.slot} ${touch.id} has invalid production output" }
        check(production.samples.all { it.isFinite() && abs(it) <= .990001f })
        check(snip.peak() > .001f && snip.samples.takeLast(2).all { abs(it) < .0001f })
        var dcLeft = 0.0
        var dcRight = 0.0
        var stereoEnergy = 0.0
        var monoEnergy = 0.0
        for (frame in 0 until snip.frameCount) {
            val left = snip.samples[frame * 2].toDouble()
            val right = snip.samples[frame * 2 + 1].toDouble()
            dcLeft += left; dcRight += right
            stereoEnergy += (left * left + right * right) * .5
            monoEnergy += ((left + right) * .5).let { it * it }
        }
        check(abs(dcLeft / snip.frameCount) < .005 && abs(dcRight / snip.frameCount) < .005)
        val monoRetention = monoEnergy / stereoEnergy
        check(monoRetention > .80)
        val pitches = listOf(.85f, 1.8f).map { start ->
            val pitch = checkNotNull(Pitch.detect(snip, start, .30f)) { "${spec.slot} ${touch.id} lost root at $start" }
            val cents = 1200 * ln(pitch.hz / Keys.midiHz(spec.midi).toDouble()) / ln(2.0)
            check(abs(cents) < 25 && pitch.confidence >= .3f) { "${spec.slot} ${touch.id} root changed $cents cents" }
            mapOf("fromSeconds" to start, "hz" to pitch.hz, "confidence" to pitch.confidence, "centsFromRequestedRoot" to cents)
        }
        val productionRms = sqrt(production.samples.sumOf { it.toDouble() * it } / production.samples.size)
        return linkedMapOf("peak" to snip.peak(), "rms" to sqrt(stereoEnergy / snip.frameCount),
            "productionPeak" to production.peak(), "productionRms" to productionRms,
            "productionLoudestWindowRms" to Loudness.of(production),
            "loudestWindowRms" to Loudness.of(snip), "monoEnergyRetention" to monoRetention,
            "maxChannelAbsoluteMean" to maxOf(abs(dcLeft), abs(dcRight)) / snip.frameCount,
            "pitchWindows" to pitches)
    }

    private fun readme() = """
        OSTERIA — provisional six-voice performance bank

        A01–A04 Bass: Anchor and Roaming, each C2/G2.
        A05–A06 Pluck: Rounded and Bright, both C3.
        A07–A08 Reed: Still and Remembered, both C3.
        A09–A10 Bowed: Warm Bow and Rosin Bow, both C3.
        A11–A12 Note: Anchored Note and Lingering Color, both C3.
        A13–A16 Pad: Warm Swell and Memory Drift, each C3/G3.

        Every pad has separately rendered soft (0.25), medium (0.60) and hard
        (1.00) excitation. MIDI zones are 1–41, 42–84 and 85–127; velocity zero
        is note-off. Production levels retain Reed -1.5 dB and Pad +1.5 dB.
        SnipSnap and the MPC apply incoming velocity at playback. Each bank WAV
        therefore removes only its nominal render velocity gain (4x soft,
        1.6666666x medium, 1x hard), so dynamics are applied once. The browser
        restores the nominal gain for auditioning. No per-zone peak normalization
        or additional pad mixer gain is applied. Device audition is pending.

        Each file is one fixed-root note with a 5.2-second simulated press,
        followed by its preset's full authored release. The long Pad onset and
        motion have not been shortened. One-shot playback finishes that file;
        lifting a pad does not move the saved release. These files do not loop.

        MPC 3: load the .xtd beside this bank's _[TrackData] folder.
        MPC 2: open that folder and load the .xpm beside its WAVs.
        Both MPC generations share the same 48 stereo 44.1 kHz/24-bit WAVs.

        SnipSnap: use the audition page's Download SnipSnap button and import
        that backup ZIP. It combines this bank with twelve editable user presets
        in YOURS. Select a YOURS preset on a fresh, unlayered pad to edit its
        synthesis. The imported velocity-layered pads remain play-only under
        the current app's editing guard. Anchored Note appears as ANCHOR NOTE
        to keep its editable copy distinct from the factory name. Remembered
        Reed appears as REMEMBER REED, and Lingering Color as LINGER COLOR,
        to fit the name limit.

        kit.json records all sixteen main/hard-zone source recipes. The
        recipes/ folder supplies complete PadRecipe JSON and bankPreparation
        gain for all 48 velocity renders; osteria-bank-recipes.json maps them to
        the exported samples. Render each source recipe, then apply its documented
        samplePreparationGain to rebuild the bank WAV.
        Editing a main sample does not automatically rebuild its saved soft
        zones. Rebuild the complete bank after editing the authored recipes:
        ./gradlew :synth:generateOsteriaPerformanceBank

        These are listening proposals outside the factory preset roster. The
        two Note recipes copy the original voice; the earlier original-Note and
        four-voice audition/export folders remain separate historical artifacts.
        Choose factory entries after actual pad playing.
    """.trimIndent() + "\n"

    private fun zip(directory: File, archive: File) {
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            for (file in directory.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(directory).invariantSeparatorsPath }) {
                zip.putNextEntry(ZipEntry("${directory.name}/${file.relativeTo(directory).invariantSeparatorsPath}").apply { time = 0L })
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(Locale.ROOT, it) }

    private fun sourceHashes(): Map<String, String> {
        val paths = listOf("src/main/kotlin/com/snipsnap/synth/Osteria.kt", "src/main/kotlin/com/snipsnap/synth/OsteriaPatch.kt",
            "src/main/kotlin/com/snipsnap/synth/OsteriaPresets.kt",
            "src/main/kotlin/com/snipsnap/synth/OsteriaPresetCandidates.kt", "src/main/kotlin/com/snipsnap/synth/OsteriaSixVoiceCandidates.kt",
            "src/main/kotlin/com/snipsnap/synth/OsteriaPerformanceBank.kt",
            "src/test/kotlin/com/snipsnap/synth/OsteriaPerformanceBankGenerator.kt")
        val module = listOf(File("."), File("synth")).firstOrNull { File(it, paths.first()).isFile }
            ?: error("run from the synth module or repository root")
        return paths.associate { "synth/$it" to sha256(File(module, it)) }
    }

    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map { json(it) })
        else -> error("unsupported metadata ${value::class.simpleName}")
    }
}
