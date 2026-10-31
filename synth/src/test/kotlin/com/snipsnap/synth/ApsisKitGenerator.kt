package com.snipsnap.synth

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitLayer
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.mpc3.Mpc3Project
import com.snipsnap.xpm.WavInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Dry native bank, one shared WAV set, complete layer recipes, and static program readback. */
object ApsisKitGenerator {
    const val KIT_NAME = "SnipSnap Apsis Kit"
    const val ARCHIVE_NAME = "SnipSnap-Apsis-Kit.zip"

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "supply only the destination root directory" }
        val archive = generate(File(args.firstOrNull() ?: "../testkit/apsis-bank"))
        println("APSIS native bank: ${archive.absolutePath} (${archive.length()} bytes; ${"%.2f".format(Locale.ROOT, archive.length() / 1048576.0)} MiB)")
    }

    fun generate(root: File): File {
        check(root.isDirectory || root.mkdirs()) { "Cannot create $root" }
        val work = File(root, ".apsis-work")
        val destination = File(root, KIT_NAME)
        check(!work.exists() || work.deleteRecursively()) { "Cannot clear generated staging $work" }
        try {
            val bank = ApsisKitBank.all()
            check(bank.size == 16 && bank.map { it.slot } == (1..16).toList())
            check(bank.all { it.patch.config.modelVersion == Apsis.MODEL_VERSION })
            val arranged = SynthKits.apsis().map { requireNotNull(it) }
            val initial = KitAssembler.assembleArranged(KIT_NAME, arranged, work, key = KeySpec.parse("Cminpent"))
            val kit = initial.copy(pads = initial.pads.map { pad ->
                pad.copy(muteGroup = bank[pad.slot - 1].muteGroup,
                    loop = bank[pad.slot - 1].held,
                    attack = if (bank[pad.slot - 1].held) 0f else null,
                    decay = if (bank[pad.slot - 1].held) 1f else null,
                    velocityLayers = pad.velocityLayers.ifEmpty { listOf(KitLayer(pad.sampleFile, 1, 127)) })
            })
            KitStore.save(kit, work)
            val expectedPcm = linkedMapOf<String, FloatArray>()
            for ((index, pad) in kit.pads.withIndex()) {
                val entry = bank[index]
                val snips = arranged[index].softVariants + arranged[index].snip
                check(snips.size == pad.velocityLayers.size && snips.size == entry.layers().size)
                for ((layerIndex, layer) in pad.velocityLayers.withIndex()) {
                    val snip = snips[layerIndex]
                    check(snip.channels == 1 && snip.sampleRate == Dsp.RATE && snip.frameCount > 0)
                    check(snip.samples.all { it.isFinite() } && snip.peak() in .000001f..1f)
                    val plain = ByteArrayOutputStream().also { WavWriter.write(it, snip) }.toByteArray()
                    expectedPcm[layer.sampleFile] = WavReader.read(plain).samples
                    val loop = if (entry.held) SmplChunk.Loop(0, snip.frameCount.toLong()) else null
                    WavWriter.write(File(work, layer.sampleFile), snip, smpl = SmplChunk(entry.patch.midi, loop))
                }
            }
            check(!Preflight.check(kit, work).blocked()) { "APSIS source preflight failed" }
            check(!destination.exists() || destination.deleteRecursively()) { "Cannot replace generated kit $destination" }
            val exported = Mpc3Exporter.exportTrack(kit, work, destination, overwrite = true, mpc2Twin = true)
            val data = exported.samples.first().parentFile
            val mpc2 = File(data, "$KIT_NAME.xpm")
            KitStore.save(kit, data)
            verifyPrograms(destination, kit)
            check(KitStore.load(data) == kit) { "Exported editable kit changed recipes" }
            val findings = Preflight.check(kit, data)
            check(!findings.blocked()) { "APSIS exported preflight failed: $findings" }
            check(exported.samples.size == expectedPcm.size && exported.samples.size == 40)

            val rows = kit.pads.mapIndexed { index, pad ->
                val entry = bank[index]
                val layers = entry.layers()
                val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
                check(recipe == PadRecipe(entry.patch) && recipe.fx == null && recipe.board == null)
                val patchPath = "recipes/${entry.label}.patch"
                val recipePath = "recipes/${entry.label}.json"
                write(destination, patchPath, entry.patch.toJsonText())
                write(destination, recipePath, recipe.toJsonText())
                check(PadRecipe.fromJsonText(File(destination, recipePath).readText()) == recipe)
                val layerRows = pad.velocityLayers.mapIndexed { layerIndex, layer ->
                    val definition = layers[layerIndex]
                    check(layer.velStart == definition.velStart && layer.velEnd == definition.velEnd)
                    val sample = File(data, layer.sampleFile)
                    val source = File(work, layer.sampleFile)
                    check(sha256(source) == sha256(sample)) { "Export changed WAV metadata or PCM for ${layer.sampleFile}" }
                    val decoded = WavReader.read(sample)
                    check(decoded.samples.contentEquals(expectedPcm.getValue(layer.sampleFile))) {
                        "Export changed native PCM frames for ${layer.sampleFile}"
                    }
                    val info = WavInfo.read(sample)
                    check(info.channels == 1 && info.sampleRate == 44_100 && info.bitsPerSample == 24)
                    val sheet = requireNotNull(WavReader.readSmpl(sample))
                    check(sheet.rootNote == entry.patch.midi)
                    check(sheet.loop == if (entry.held) SmplChunk.Loop(0, info.frameCount) else null)
                    val layerRecipePath = "recipes/${entry.label}-v${layerIndex + 1}.json"
                    write(destination, layerRecipePath, PadRecipe(definition.patch).toJsonText())
                    check(PadRecipe.fromJsonText(File(destination, layerRecipePath).readText()).patch == definition.patch)
                    json(linkedMapOf("index" to layerIndex, "velStart" to layer.velStart, "velEnd" to layer.velEnd,
                        "renderVelocity" to definition.patch.velocity, "frames" to info.frameCount,
                        "seconds" to info.frameCount.toDouble() / 44_100,
                        "sample" to sample.relativeTo(destination).invariantSeparatorsPath,
                        "sha256" to sha256(sample), "recipe" to layerRecipePath,
                        "recipeSha256" to sha256(File(destination, layerRecipePath)),
                        "loopStartFrame" to if (entry.held) 0 else null,
                        "loopEndFrameExclusive" to if (entry.held) info.frameCount else null,
                        "nativeRenderPcmExact" to true, "smplReadbackPassed" to true))
                }
                json(linkedMapOf("slot" to entry.slot, "label" to entry.label, "id" to entry.id,
                    "name" to entry.patch.name, "role" to entry.role.name, "presetName" to entry.presetName,
                    "midi" to entry.patch.midi, "note" to entry.note, "velocity" to entry.patch.velocity,
                    "model" to entry.patch.config.modelVersion, "level" to pad.level, "pan" to pad.pan,
                    "muteGroup" to pad.muteGroup, "oneShot" to pad.oneShot,
                    "playback" to if (entry.held) "NOTE_ON_FORWARD_LOOP" else "ONE_SHOT",
                    "noteOff" to if (entry.held) "IMMEDIATE_GATE_OFF" else "IGNORED_FULL_FINITE_SAMPLE",
                    "retrigger" to "Restart at frame 0; self mute group replaces preceding voice of this pad.",
                    "releaseControl" to ApsisKitBank.NOTE_OFF_RELEASE_CONTROL,
                    "patch" to entry.patch.toJsonValue(), "patchPath" to patchPath,
                    "recipe" to recipePath, "layers" to layerRows))
            }
            val manifest = json(linkedMapOf("schemaVersion" to 1, "engine" to "APSIS", "name" to KIT_NAME,
                "model" to Apsis.MODEL_VERSION, "padCount" to 16, "sampleCount" to expectedPcm.size,
                "audio" to linkedMapOf("sampleRate" to 44_100, "channels" to 1, "bitsPerSample" to 24,
                    "effects" to false, "auditionMatching" to false),
                "layout" to "A01–04 bass C2/Eb2/G2/Bb2; A05–08 keys C3/Eb3/G3/Bb3; A09–12 chord tones C4/Eb4/G4/Bb4; A13–14 gathered/spaced C4; A15–16 held C3/G3.",
                "leveling" to "Native dry recipe PCM; pad level 1.0; no further matching, FX, tuning or humanization.",
                "velocity" to "A01–12 use 1–41 soft, 42–84 medium, 85–127 hard rendered excitation zones; A13–16 use 1–127. Hardware velocity-to-amplitude curve is unmeasured.",
                "loopPolicy" to "A15–16: full native cycle [0,frameCount), Note On, immediate note-off (release control 0), no crossfade; each pad has its own mute group for same-pad restart.",
                "reexportLimit" to "Recipes and kit.json regenerate PCM. The saved full-cycle loop flag lets generic kit exporters preserve native held playback; physical MPC acceptance remains separate.",
                "program" to exported.program.relativeTo(destination).invariantSeparatorsPath,
                "programSha256" to sha256(exported.program),
                "mpc2Program" to mpc2.relativeTo(destination).invariantSeparatorsPath,
                "mpc2ProgramSha256" to sha256(mpc2),
                "sourceKit" to File(data, KitStore.FILE_NAME).relativeTo(destination).invariantSeparatorsPath,
                "sourceKitSha256" to sha256(File(data, KitStore.FILE_NAME)),
                "sourceRevision" to git("rev-parse", "HEAD"),
                "sourceTreeDirty" to git("status", "--porcelain").isNotEmpty(),
                "validation" to linkedMapOf("sourcePreflightPassed" to true, "exportPreflightPassed" to true,
                    "nativeProgramReadbackPassed" to true, "nativeRenderPcmExact" to true,
                    "hardwareAcceptance" to "Not performed; static format and PCM checks only."),
                "pads" to rows))
            write(destination, "kit-manifest.json", Json.write(manifest))
            write(destination, "README.txt", buildString {
                appendLine(KIT_NAME)
                appendLine("MPC 3: load $KIT_NAME.xtd beside its _[TrackData] folder.")
                appendLine("MPC 2: open $KIT_NAME.xpm inside that folder, beside all 40 WAVs.")
                appendLine("Copy the complete kit folder. Both programs share one 44.1 kHz mono PCM24 WAV set.")
                appendLine("The TrackData kit.json and recipes/ retain complete editable source recipes.")
                appendLine("Recipes and kit.json regenerate PCM. The shared exporters preserve the saved full-cycle loop flag.")
                appendLine()
                appendLine("A01–12 have three rendered velocity colors: 1–41 soft, 42–84 medium, 85–127 hard.")
                appendLine("A01–14 are finite one-shots; note-off does not shorten them.")
                appendLine("A15–16 play the settled full-cycle HOLD buffer repeatedly while pressed.")
                appendLine("Held note-off is immediate (release control 0), with no added fade or attack.")
                appendLine("Each pad's separate mute group replaces only its own preceding voice on retrigger.")
                appendLine("Orbital cycles are free-time, with no tempo-sync promise. Chord-tone pads play one root each.")
                appendLine("Native velocity-to-amplitude curves and hardware playback remain owner acceptance checks.")
                appendLine()
                bank.forEach { appendLine("${it.label}  ${it.patch.name}  ${it.role}  MIDI ${it.patch.midi}  ${it.presetName}") }
                appendLine()
                appendLine("Static preflight, both program readbacks, WAV SMPL metadata and exact PCM checks passed.")
                appendLine("These checks do not establish MPC hardware acceptance.")
            })
            val archive = File(root, ARCHIVE_NAME)
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                destination.walkTopDown().filter { it.isFile }
                    .sortedBy { it.relativeTo(destination).invariantSeparatorsPath }.forEach { file ->
                        val path = destination.name + "/" + file.relativeTo(destination).invariantSeparatorsPath
                        zip.putNextEntry(ZipEntry(path).also { it.time = 0L })
                        file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                    }
            }
            write(root, "$ARCHIVE_NAME.sha256", "${sha256(archive)}  $ARCHIVE_NAME")
            println("APSIS kit: 16 pads, ${expectedPcm.size} WAVs, ${archive.length()} compressed bytes; static checks passed, hardware acceptance pending.")
            return archive
        } finally {
            work.deleteRecursively()
        }
    }

    /** Reopen the real native containers, not the source model, to prove each pad's export contract. */
    fun verifyPrograms(destination: File, kit: Kit) {
        val data = File(destination, "${kit.name}_[TrackData]")
        val project = Mpc3Project.read(File(destination, "${kit.name}.xtd"))
        check(project.isTrack && project.trackNames == listOf(kit.name))
        val instruments = project.drumPrograms().single().getValue("instruments").arr()
        val document = xmlFactory().newDocumentBuilder().parse(File(data, "${kit.name}.xpm"))
        val xmlInstruments = document.getElementsByTagName("Instrument")
        for (pad in kit.pads) {
            val entry = ApsisKitBank.pad(pad.slot)
            val instrument = instruments[pad.slot - 1].obj()
            check(instrument.getValue("triggerMode").int() == if (entry.held) 2 else 0)
            check(instrument.getValue("whichMuteGroup").int() == entry.muteGroup)
            val mixable = instrument.getValue("mixable").obj()
            check(mixable.getValue("volume").num() == 1.0 && mixable.getValue("pan").num() == .5)
            val layers = instrument.getValue("layersv").arr().filter { it.obj().getValue("sampleFile").str().isNotEmpty() }
            check(layers.size == pad.velocityLayers.size)
            val xmlInstrument = xmlInstruments.item(pad.slot - 1) as Element
            check(text(xmlInstrument, "OneShot") == if (entry.held) "False" else "True")
            check(text(xmlInstrument, "MuteGroup").toInt() == entry.muteGroup)
            check(text(xmlInstrument, "Volume").toDouble() == 1.0 && text(xmlInstrument, "Pan").toDouble() == .5)
            val xmlLayers = xmlInstrument.getElementsByTagName("Layer")
            val filledXml = (0 until xmlLayers.length).map { xmlLayers.item(it) as Element }
                .filter { text(it, "SampleName").isNotEmpty() }
            check(filledXml.size == layers.size)
            for (layer in pad.velocityLayers) {
                val info = WavInfo.read(File(data, layer.sampleFile))
                val native = layers.single { it.obj().getValue("sampleFile").str() == layer.sampleFile }.obj()
                val slice = native.getValue("sliceInfo").obj()
                check(native.getValue("velocityStart").int() == layer.velStart && native.getValue("velocityEnd").int() == layer.velEnd)
                check(slice.getValue("Start").long() == 0L && slice.getValue("End").long() == info.frameCount)
                check(slice.getValue("LoopMode").int() == if (entry.held) 1 else 0)
                check(slice.getValue("LoopStart").long() == 0L)
                val xml = filledXml.single { text(it, "SampleName") == layer.sampleStem }
                check(text(xml, "VelStart").toInt() == layer.velStart && text(xml, "VelEnd").toInt() == layer.velEnd)
                check(text(xml, "SliceStart").toLong() == 0L && text(xml, "SliceEnd").toLong() == info.frameCount)
                check(text(xml, "SliceLoop").toInt() == if (entry.held) 1 else 0)
                check(text(xml, "SliceLoopStart").toLong() == 0L)
            }
            if (entry.held) {
                val envelope = instrument.getValue("synthSection").obj().getValue("ampEnvelope").obj()
                check(envelope.getValue("Attack").obj().getValue("value0").num() == 0.0)
                check(envelope.getValue("Decay").obj().getValue("value0").num() == 1.0)
                check(envelope.getValue("Sustain").obj().getValue("value0").num() == 1.0)
                check(!envelope.getValue("OneShot").obj().getValue("value0").bool())
                check(envelope.getValue("DecayFromEnd").obj().getValue("value0").bool())
                check(envelope.getValue("Release").obj().getValue("value0").num() == 0.0)
                check(text(xmlInstrument, "VolumeAttack").toDouble() == 0.0)
                check(text(xmlInstrument, "VolumeADEnvelope") == "False")
                check(text(xmlInstrument, "VolumeDecay").toDouble() == 1.0)
                check(text(xmlInstrument, "VolumeSustain").toDouble() == 1.0)
                check(text(xmlInstrument, "VolumeRelease").toDouble() == 0.0)
            }
        }
    }

    private fun xmlFactory() = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }
    private fun text(parent: Element, tag: String) = parent.getElementsByTagName(tag).item(0).textContent
    private fun write(root: File, path: String, value: String) { File(root, path).also { it.parentFile.mkdirs() }.writeText(value.trimEnd() + "\n") }
    private fun num(value: Number) = JsonValue.Num(value.toDouble())
    private fun obj(value: Map<String, JsonValue>) = JsonValue.Obj(LinkedHashMap(value))
    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    private fun git(vararg args: String): String = runCatching {
        ProcessBuilder(listOf("git") + args).redirectErrorStream(true).start().let { process ->
            val text = process.inputStream.bufferedReader().readText().trim()
            check(process.waitFor() == 0); text
        }
    }.getOrDefault("unavailable")
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Number -> num(value)
        is Boolean -> JsonValue.Bool(value)
        is Map<*, *> -> obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported JSON value ${value.javaClass}")
    }
}
