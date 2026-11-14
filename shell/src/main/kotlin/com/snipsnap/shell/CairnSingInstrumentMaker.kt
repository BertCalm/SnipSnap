package com.snipsnap.shell

import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.kit.AtomicFile
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.CairnAcceptedSingSources
import com.snipsnap.synth.CairnSamplerDelivery
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.KeygroupWriter
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CancellationException

/**
 * Explicit retained-SING instrument packaging. No native renderer is called.
 * Five delivered studies still represent ONE voice; entry/release and device listening remain separate.
 * This API does not register a factory, picker entry or automatic shelf import.
 */
object CairnSingInstrumentMaker {
    const val NATIVE_VOICES = 1
    const val SOURCES = "cairn-retained-sing.json"
    const val POLICY = "cairn-sampler-policy.json"
    const val RECIPES = "cairn-source-recipes"

    enum class Program(val id: String) {
        EARLIER("earlier-c3-c5"), MOVING("moving-c3-c5"),
        GROOVE_0("groove-0"), GROOVE_50("groove-50"), GROOVE_100("groove-100"),
    }

    private data class Pins(val floatSha256: String, val metadataSha256: String)
    private val pins = mapOf(
        "cairn-before-48" to Pins("1e7ae8e808d64f00916ee702d6b6c9b0edbe5e487cae1df9d2cfdc629565ff4b", "ae85d73f31d56acf1bcf81b8961d0f23e03d65459fe4f2bb4659a34878234eda"),
        "cairn-after-48" to Pins("c6520b15004fbeebddd938b406839e87cb9a4ea3b24342a0205f8e8362946b6d", "fc58cd5c4154353792670f64e4f6323b1cf85bf6c622381d8f71089cfbd66fa5"),
        "cairn-before-60" to Pins("2bca5539693f206c3ac9706367057c44c076b76bd1fb23323e09a5e800c962f5", "c6e9b4218230d33c9c9c99936ceab9d6d0b3b6eefeed75d1acdfcea5a914b8bb"),
        "cairn-after-60" to Pins("b4b9bc4aad946b6a0795247565cf62c4ea59d6fd5fe3c8ae7b0940373a18f952", "a87381eac9a50303405463a44c52580ff71911441dee7a7dacb85d1b3b602b2e"),
        "cairn-before-72" to Pins("8583082950cc0c9e3108df6e01c8f1b44260e73b8ca2eef9cda8bdd0828b04af", "4c4b33cf75a07841559bc08d30844a40379cfc263862385d7e83a7f97a6b81f5"),
        "cairn-after-72" to Pins("56387ffe6a267b58c6efafed4bd758c4d182d110b670b87aec2fdbdc489262a9", "51bb157877e6d71917de9624fac7f8acf022fa4ed903d23b56722629fe4a2e98"),
        "cairn-groove-0" to Pins("a0d36564c9f3b67c5ef041891142808b74adbb0befb54b338b6acbfeca14903b", "caa8286b29aa5b423d10e9d1043f87f0d90fcef8cbb8b32242b4a25ed491ffe8"),
        "cairn-groove-50" to Pins("59df7cc9625212e13f50e4add9115331b1b44b8ba4af842e6cd5c87e4aeeb2e1", "a868d630dda6f50dbada1e4310afef712e3ec9d4f64e099b5891819fe4e21ce9"),
        "cairn-groove-100" to Pins("9ce2efa081e6f68027e888b3363c7e86923d57ed6cc53b7a7eead1de6c972fe1", "2cbfc848ab6c83f2965aad7c2da96b478509e5f7e0fc4d931850b4b8d7efe127"),
    )

    data class Assembly(val program: KeygroupProgram, val samples: Map<String, Snip>)
    /** Own the program/source association; legacy prototype catalogues are not a trusted oracle. */
    fun specification(program: Program): CairnSamplerDelivery.Spec = when (program) {
        Program.EARLIER -> CairnSamplerDelivery.Spec("earlier-c3-c5", "CAIRN Earlier C3 C5",
            listOf("cairn-before-48", "cairn-before-60", "cairn-before-72"))
        Program.MOVING -> CairnSamplerDelivery.Spec("moving-c3-c5", "CAIRN Moving C3 C5",
            listOf("cairn-after-48", "cairn-after-60", "cairn-after-72"))
        Program.GROOVE_0 -> CairnSamplerDelivery.Spec("groove-0", "CAIRN Groove 0 C4", listOf("cairn-groove-0"))
        Program.GROOVE_50 -> CairnSamplerDelivery.Spec("groove-50", "CAIRN Groove 50 C4", listOf("cairn-groove-50"))
        Program.GROOVE_100 -> CairnSamplerDelivery.Spec("groove-100", "CAIRN Groove 100 C4", listOf("cairn-groove-100"))
    }
    fun sourceIds(program: Program): List<String> = specification(program).sourceIds.toList()
    fun zones(program: Program): List<CairnSamplerDelivery.NoteZone> = sourceIds(program).map { id ->
        val source = requireNotNull(CairnAcceptedSingSources.byId(id))
        CairnSamplerDelivery.noteZone(specification(program), source.midi)
    }

    /** Pins name already qualified native f32 captures, not a PCM decode or a caller's hash. */
    fun readCapture(sourceId: String, nativeFloat: File, metadata: File): CairnSamplerDelivery.Capture {
        val expected = requireNotNull(pins[sourceId]) { "Unknown retained CAIRN source" }
        return CairnSamplerDelivery.readCapture(sourceId, nativeFloat, metadata,
            expected.floatSha256, expected.metadataSha256)
    }

    /**
     * Read only this fixed program's exact retained pairs from an explicit bank.
     * The callback receives canonical leaf names, not workspace paths. This
     * function owns all opened streams and returns only a complete pinned set.
     */
    fun readCaptures(program: Program, open: (String) -> InputStream,
        cancelled: () -> Boolean = { false }): Map<String, CairnSamplerDelivery.Capture> {
        fun stop() {
            if (cancelled() || Thread.currentThread().isInterrupted)
                throw CancellationException("CAIRN retained capture bank cancelled")
        }
        stop()
        val captures = linkedMapOf<String, CairnSamplerDelivery.Capture>()
        for (id in sourceIds(program)) {
            stop()
            val source = requireNotNull(CairnAcceptedSingSources.byId(id))
            val expected = pins.getValue(id)
            val capture = open(source.recordingId + ".full.f32le").use { raw ->
                stop()
                open(source.recordingId + ".full.json").use { metadata ->
                    CairnSamplerDelivery.readCapture(id, raw, metadata,
                        expected.floatSha256, expected.metadataSha256, cancelled)
                }
            }
            captures[id] = capture
        }
        stop()
        return captures.toMap()
    }

    fun assemble(name: String, program: Program,
                 captures: Map<String, CairnSamplerDelivery.Capture>): Assembly {
        checkName(name)
        val spec = specification(program)
        require(captures.keys == spec.sourceIds.toSet()) { "Choose the complete exact CAIRN source set" }
        for ((id, capture) in captures) {
            val expected = pins.getValue(id)
            require(capture.source.id == id && capture.captureSha256 == expected.floatSha256 &&
                capture.metadataSha256 == expected.metadataSha256) { "CAIRN native capture binding changed" }
        }
        val (mapping, samples) = CairnSamplerDelivery.assemble(spec, captures)
        return Assembly(mapping.copy(name = name), samples)
    }

    /** New portable package only; existing folders and instruments are never replaced. */
    @Synchronized
    fun writeNewPackage(name: String, program: Program,
                        captures: Map<String, CairnSamplerDelivery.Capture>, destination: File): Assembly {
        require(!Files.isSymbolicLink(destination.toPath()) && !destination.exists()) { "CAIRN package already exists" }
        val assembly = assemble(name, program, captures)
        val parent = destination.absoluteFile.parentFile.apply { mkdirs() }
        val stage = Files.createTempDirectory(parent.toPath(), ".cairn-retained-keygroup-").toFile()
        try {
            OneNote.writePackage(name, assembly.program, assembly.samples, stage, overwrite = false, spanKeyboard = false)
            val data = File(stage, Mpc3TrackWriter.trackDataDirName(name))
            val recipes = File(data, RECIPES).apply { mkdirs() }
            for (id in sourceIds(program)) {
                val source = captures.getValue(id).source
                AtomicFile.writeText(File(recipes, source.recordingId + ".json"), source.originalRecipeJson())
            }
            AtomicFile.writeText(File(data, POLICY), CairnSamplerDelivery.POLICY_JSON)
            AtomicFile.writeText(File(data, SOURCES), manifest(program, assembly, captures))
            validate(name, stage, program, captures)
            Files.move(stage.toPath(), destination.toPath())
            return assembly
        } finally {
            if (stage.exists()) stage.deleteRecursively()
        }
    }

    /** Shelf-visible siblings use shared descriptor-last admission and rollback. */
    fun exportFresh(name: String, program: Program, captures: Map<String, CairnSamplerDelivery.Capture>,
                    destination: File,
                    hooks: InstrumentPackageAdmission.Hooks = InstrumentPackageAdmission.Hooks()): Assembly =
        InstrumentPackageAdmission.publishNew(name, destination,
            build = { packet -> writeNewPackage(name, program, captures, packet) },
            validate = { packet -> validate(name, packet, program, captures); Unit }, hooks = hooks)

    /** Rebind mapping, original recipes, policy and complete PCM to pinned captures. */
    fun validate(name: String, root: File, program: Program,
                 captures: Map<String, CairnSamplerDelivery.Capture>): Assembly {
        require(root.isDirectory && !Files.isSymbolicLink(root.toPath()))
        val assembly = assemble(name, program, captures)
        val data = File(root, Mpc3TrackWriter.trackDataDirName(name))
        val recipes = File(data, RECIPES)
        val sidecar = InstrumentStore.sidecar(root, name)
        val native = File(root, "$name.xty")
        val legacy = File(data, "$name.xpm")
        val expectedFiles = setOf(native.name, sidecar.name,
            data.name + "/" + legacy.name, data.name + "/" + SOURCES, data.name + "/" + POLICY) +
            assembly.samples.keys.map { data.name + "/" + it + ".wav" } +
            sourceIds(program).map { data.name + "/" + RECIPES + "/" + captures.getValue(it).source.recordingId + ".json" }
        val all = root.walkTopDown().onEnter { directory ->
            require(!Files.isSymbolicLink(directory.toPath())); true
        }.toList()
        require(all.none { Files.isSymbolicLink(it.toPath()) })
        require(all.filter { it.isDirectory && it != root }.map { it.relativeTo(root).invariantSeparatorsPath }.toSet() ==
            setOf(data.name, data.name + "/" + RECIPES))
        require(all.filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.toSet() == expectedFiles) {
            "CAIRN packet contains missing or unbound files"
        }
        require(File(data, SOURCES).readText() == manifest(program, assembly, captures)) { "CAIRN source manifest changed" }
        require(File(data, POLICY).readText() == CairnSamplerDelivery.POLICY_JSON) { "CAIRN delivery policy changed" }
        for (id in sourceIds(program)) {
            val source = captures.getValue(id).source
            require(File(recipes, source.recordingId + ".json").readText() == source.originalRecipeJson())
            val stem = CairnSamplerDelivery.stem(id)
            val wav = File(data, "$stem.wav")
            val decoded = WavReader.read(wav)
            require(decoded.channels == 1 && decoded.sampleRate == CairnSamplerDelivery.RATE &&
                decoded.frameCount == CairnSamplerDelivery.FULL_FRAMES)
            require(WavReader.readSmplStrict(wav) == SmplChunk(source.midi,
                SmplChunk.Loop(CairnSamplerDelivery.LOOP_START.toLong(), CairnSamplerDelivery.FULL_FRAMES.toLong())))
            require(CairnSamplerDelivery.wavPcmData(wav.readBytes()).contentEquals(
                CairnSamplerDelivery.pcm24(assembly.samples.getValue(stem).samples))) { "CAIRN retained PCM changed" }
            val expectedWav = ByteArrayOutputStream().also { out ->
                WavWriter.write(out, assembly.samples.getValue(stem), smpl = SmplChunk(source.midi,
                    SmplChunk.Loop(CairnSamplerDelivery.LOOP_START.toLong(), CairnSamplerDelivery.FULL_FRAMES.toLong())))
            }.toByteArray()
            require(wav.readBytes().contentEquals(expectedWav)) { "CAIRN complete WAV encoding changed" }
        }
        require(InstrumentStore.read(sidecar).toProgram() == assembly.program) { "CAIRN phone mapping changed" }
        require(sidecar.readText() == InstrumentStore.render(assembly.program, data.name))
        require(native.readBytes().contentEquals(Mpc3TrackWriter().writeKeygroup(assembly.program))) { "CAIRN native mapping changed" }
        require(legacy.readText() == KeygroupWriter().write(assembly.program)) { "CAIRN legacy mapping changed" }
        return assembly
    }

    private fun manifest(program: Program, assembly: Assembly,
                         captures: Map<String, CairnSamplerDelivery.Capture>): String =
        Json.write(CairnSamplerDelivery.json(linkedMapOf(
            "schema" to 1, "kind" to "CAIRN retained SING keygroup candidate",
            "programId" to program.id, "nativeVoiceCount" to NATIVE_VOICES,
            "sourceBank" to CairnAcceptedSingSources.BANK_ID,
            "coverage" to CairnSamplerDelivery.coverageLabel(specification(program)),
            "samplerReleaseSeconds" to CairnSamplerDelivery.RELEASE_SECONDS.toString().toDouble(),
            "samplerVelocity" to "gain only; no new native velocity or round-robin takes",
            "deliveryPolicySha256" to CairnSamplerDelivery.policySha256,
            "naturalAttack" to false, "naturalRelease" to false,
            "automaticFactoryOrPickerAdmission" to false, "listeningAndDevice" to "pending",
            "sources" to sourceIds(program).map { id ->
                val capture = captures.getValue(id); val source = capture.source
                val stem = CairnSamplerDelivery.stem(id)
                linkedMapOf("sourceId" to id, "model" to source.model, "rootMidi" to source.midi,
                    "sample" to "$stem.wav", "nativeFloatSha256" to capture.captureSha256,
                    "captureMetadataSha256" to capture.metadataSha256,
                    "originalRecipeFile" to "$RECIPES/${source.recordingId}.json",
                    "originalRecipeSha256" to source.originalRecipe.sha256,
                    "savedPatch" to Json.parse(source.originalRecipeJson()),
                    "deliveredPcmSha256" to CairnSamplerDelivery.sha(
                        CairnSamplerDelivery.pcm24(assembly.samples.getValue(stem).samples)))
            },
        ))) + "\n"

    private fun checkName(name: String) {
        require(Names.isMpcSafe(name) && name.length <= OneNote.MAX_NAME_LENGTH) { "Invalid CAIRN instrument name" }
    }
}
