package com.snipsnap.shell

import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.Keys
import com.snipsnap.synth.Murmur
import com.snipsnap.synth.MurmurKeptSources
import com.snipsnap.synth.MurmurPatch
import com.snipsnap.synth.MurmurSelectedInstrumentSource
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.KeygroupWriter
import com.snipsnap.xpm.VelocityLayer
import com.snipsnap.xpm.WavInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** Bounded selected-source instruments. This never calls the generic forced-HOLD maker. */
object MurmurSelectedInstrumentMaker {
    const val SOURCES = "murmur-selected-instrument-sources.json"
    private const val KIND = "MURMUR_SELECTED_INSTRUMENT_PACKAGE"
    private const val MAX_METADATA_BYTES = 8L * 1024 * 1024
    private const val MAX_WAV_HEADER_BYTES = 4096L
    private const val ROOT_POLICY = "MINOR_THIRDS_EDGES_AND_AUTHORED_ROOT_MIDPOINT_V1"
    private const val TOUCH_POLICY = MurmurSelectedInstrumentSource.TOUCH_POLICY
    val VELOCITY_WINDOWS: List<IntRange> get() = listOf(1..42, 43..84, 85..127)

    /** Freeze the complete source now, rather than retaining the caller's mutable collections. */
    class Spec(patch: MurmurPatch, val releaseSeconds: Float = MurmurSelectedInstrumentSource.DEFAULT_RELEASE_SECONDS) {
        val sourcePatchJson: String = patch.toJsonText()
        private val selected = requireNotNull(MurmurSelectedInstrumentSource.eligibility(sourcePatch())) {
            "MURMUR instruments require an exact selected shape with a positive authored touch"
        }
        val selectionId: String get() = selected.selectionId
        val supportedMidi: IntRange get() = selected.supportedMidi
        val held: Boolean get() = selected.held

        init {
            require(releaseSeconds.isFinite() && releaseSeconds in MurmurSelectedInstrumentSource.MIN_RELEASE_SECONDS..MurmurSelectedInstrumentSource.MAX_RELEASE_SECONDS) { "MURMUR sampler release must be ${MurmurSelectedInstrumentSource.MIN_RELEASE_SECONDS}..${MurmurSelectedInstrumentSource.MAX_RELEASE_SECONDS} seconds" }
            require(sourcePatch().midi in supportedMidi) { "MURMUR authored root is outside its selected domain" }
        }
        fun sourcePatch(): MurmurPatch = MurmurPatch.fromJsonText(sourcePatchJson)
    }

    data class Cell(val midi: Int, val touchIndex: Int)
    data class Zone(val root: Int, val low: Int, val high: Int)
    data class Row internal constructor(
        val cell: Cell, val sample: String, val recipeJson: String, val frameCount: Int,
        val loopStartFrame: Int, val loopEndFrameExclusive: Int,
        val rawSampleSha256: String, val rawPreviousSha256: String,
        val finishedFloatSha256: String, val decodedPcmSha256: String, val wavSha256: String,
        val seamError: Double, val encodedSeamError: Double, val rootHz: Double,
    )
    data class Packet internal constructor(val program: KeygroupProgram, val spec: Spec, val rows: List<Row>)

    fun supports(patch: MurmurPatch): Boolean = MurmurSelectedInstrumentSource.eligibility(patch) != null
    fun roots(spec: Spec): List<Int> =
        ((spec.supportedMidi step 3).toList() + spec.supportedMidi.last + spec.sourcePatch().midi).distinct().sorted()

    fun zones(spec: Spec): List<Zone> {
        val roots = roots(spec)
        val zones = roots.mapIndexed { i, root -> Zone(root,
            if (i == 0) spec.supportedMidi.first else (roots[i - 1] + root) / 2 + 1,
            if (i == roots.lastIndex) spec.supportedMidi.last else (root + roots[i + 1]) / 2) }
        require(zones.flatMap { (it.low..it.high).toList() } == spec.supportedMidi.toList()) { "MURMUR zones must tile the selected domain exactly" }
        require(zones.all { z -> (z.low..z.high).all { kotlin.math.abs(it - z.root) <= 1 } }) { "MURMUR zones may transpose at most one semitone" }
        return zones
    }

    fun recipes(spec: Spec): Map<Cell, MurmurSelectedInstrumentSource.CellRecipe> {
        val source = spec.sourcePatch()
        return linkedMapOf<Cell, MurmurSelectedInstrumentSource.CellRecipe>().apply {
            for (midi in roots(spec)) for (touch in 0..2) put(Cell(midi, touch),
                MurmurSelectedInstrumentSource.cellRecipe(source, midi, touch, spec.releaseSeconds))
        }
    }

    /** Native cells render serially into a private stage; no full-grid PCM is retained. */
    fun writeNewPackage(name: String, spec: Spec, destination: File,
                        cancelled: () -> Boolean = { false }, progress: (Int, Int) -> Unit = { _, _ -> }): Packet =
        writeNewPackageUsing(name, spec, destination, cancelled, progress, MurmurSelectedInstrumentSource::renderCell)

    internal fun writeNewPackageUsing(name: String, spec: Spec, destination: File,
                                     cancelled: () -> Boolean, progress: (Int, Int) -> Unit,
                                     renderCell: (MurmurSelectedInstrumentSource.CellRecipe) -> MurmurSelectedInstrumentSource.Capture): Packet {
        checkName(name)
        require(!Files.isSymbolicLink(destination.toPath()) && !destination.exists()) { "MURMUR packet destination already exists" }
        val parent = requireNotNull(destination.absoluteFile.parentFile).apply { mkdirs() }
        val stage = Files.createTempDirectory(parent.toPath(), ".murmur-selected-keys-").toFile()
        try {
            val scratch = File(stage, ".rendered").apply { check(mkdir()) }
            val expected = recipes(spec)
            val rows = mutableListOf<Row>()
            for ((cell, recipe) in expected) {
                checkCancelled(cancelled)
                val capture = renderCell(recipe)
                checkCancelled(cancelled)
                checkCapture(spec, recipe, capture)
                val file = File(scratch, sampleName(cell))
                WavWriter.write(file, capture.snip, WavWriter.BitDepth.PCM_24, smpl = sheet(cell, capture.loopStartFrame, capture.loopEndFrameExclusive))
                val decoded = WavReader.read(file)
                val encodedSeam = if (spec.held) Keys.seamError(decoded.samples, capture.loopStartFrame) else 0.0
                require(encodedSeam.isFinite() && encodedSeam < Keys.MAX_SEAM_ERROR) { "MURMUR encoded loop does not close" }
                rows += Row(cell, file.name, recipe.toJsonText(), capture.snip.frameCount,
                    capture.loopStartFrame, capture.loopEndFrameExclusive, capture.rawSampleSha256, capture.rawPreviousSha256,
                    capture.finishedFloatSha256, floatHash(decoded.samples), hash(file), capture.seamError, encodedSeam, capture.rootHz)
                progress(rows.size, expected.size)
            }
            checkCancelled(cancelled)
            val program = program(name, spec, rows)
            // Only descriptors are created here; the real encoded cells were streamed above.
            // The packet is private until every exact WAV and its provenance have validated.
            OneNote.writePackage(name, program, emptyMap(), stage, overwrite = false, spanKeyboard = false)
            val data = File(stage, Mpc3TrackWriter.trackDataDirName(name))
            for (row in rows) Files.move(File(scratch, row.sample).toPath(), File(data, row.sample).toPath())
            check(scratch.delete())
            File(data, SOURCES).writeText(metadata(spec, rows))
            checkCancelled(cancelled)
            val checked = validatePacket(name, stage, spec)
            checkCancelled(cancelled)
            Files.move(stage.toPath(), destination.toPath())
            return checked
        } finally { if (stage.exists()) stage.deleteRecursively() }
    }

    fun exportFresh(name: String, spec: Spec, destination: File,
                    cancelled: () -> Boolean = { false }, progress: (Int, Int) -> Unit = { _, _ -> },
                    hooks: InstrumentPackageAdmission.Hooks = InstrumentPackageAdmission.Hooks()): Packet =
        exportFreshUsing(name, spec, destination, cancelled, progress, hooks, MurmurSelectedInstrumentSource::renderCell)

    internal fun exportFreshUsing(name: String, spec: Spec, destination: File,
                                  cancelled: () -> Boolean, progress: (Int, Int) -> Unit,
                                  hooks: InstrumentPackageAdmission.Hooks,
                                  renderCell: (MurmurSelectedInstrumentSource.CellRecipe) -> MurmurSelectedInstrumentSource.Capture): Packet =
        InstrumentPackageAdmission.publishNew(name, destination,
            build = { packet -> writeNewPackageUsing(name, spec, packet, cancelled, progress, renderCell) },
            validate = { packet -> checkCancelled(cancelled); validatePacket(name, packet, spec); Unit },
            hooks = InstrumentPackageAdmission.Hooks(
                staged = { packet -> checkCancelled(cancelled); hooks.staged(packet); checkCancelled(cancelled) },
                moved = { entry -> checkCancelled(cancelled); hooks.moved(entry); checkCancelled(cancelled) },
            ))

    /**
     * Internal-consistency/fresh-delivery validation; independent replay is a separate proof.
     * The fresh POLY descriptor profile is exact. Sampler edits are not silently rebound.
     */
    fun validatePacket(name: String, root: File, expectedSpec: Spec? = null): Packet = validateOwned(name, root, expectedSpec, true)
    fun validate(name: String, root: File, expectedSpec: Spec? = null): Packet = validatePacket(name, root, expectedSpec)

    /** A shelf contains sibling packages; only this package's three owned entries are validated. */
    fun validateOnShelf(name: String, root: File, expectedSpec: Spec? = null): Packet = validateOwned(name, root, expectedSpec, false)

    private fun validateOwned(name: String, root: File, expectedSpec: Spec?, requireRootClosure: Boolean): Packet {
        checkName(name)
        val dataName = Mpc3TrackWriter.trackDataDirName(name)
        val data = File(root, dataName)
        val sourceFile = File(data, SOURCES)
        for (file in listOf(root, data, sourceFile)) require(!Files.isSymbolicLink(file.toPath())) { "MURMUR source paths must not be symlinks" }
        require(root.isDirectory && data.isDirectory && sourceFile.isFile) { "Incomplete MURMUR source packet" }
        val rootNames = setOf(dataName, "$name.xty", "$name${InstrumentStore.SUFFIX}")
        val present = root.listFiles()!!.map { it.name }.toSet()
        require(if (requireRootClosure) present == rootNames else present.containsAll(rootNames)) { "Unexpected or missing MURMUR packet root entry" }
        require(sourceFile.length() in 1..MAX_METADATA_BYTES) { "MURMUR metadata size exceeds the bounded packet format" }
        val obj = Json.parse(sourceFile.readText()).obj()
        require(obj.keys == setOf("kind", "version", "sourcePatch", "sourcePatchSha256", "selectedSource", "lowMidi", "highMidi",
            "releaseSeconds", "rootPolicy", "touchPolicy", "encoding", "samples")) { "Unknown or incomplete MURMUR package fields" }
        require(obj.getValue("kind").str() == KIND && obj.getValue("version").int() == 1) { "Unsupported MURMUR package kind/version" }
        require(obj.getValue("rootPolicy").str() == ROOT_POLICY && obj.getValue("touchPolicy").str() == TOUCH_POLICY &&
            obj.getValue("encoding").str() == "PCM24_WITH_SMPL_V1") { "Unknown MURMUR adaptation policy" }
        val patch = MurmurPatch.fromJsonValue(obj.getValue("sourcePatch")) as MurmurPatch
        require(obj.getValue("sourcePatch") == patch.toJsonValue()) { "Unsupported MURMUR source recipe fields" }
        require(obj.getValue("sourcePatchSha256").str() == hashText(patch.toJsonText())) { "MURMUR source recipe hash changed" }
        val release = obj.getValue("releaseSeconds").num()
        require(release.isFinite() && release == release.toFloat().toDouble()) { "MURMUR sampler release must retain its canonical Float value" }
        val spec = Spec(patch, release.toFloat())
        require(expectedSpec == null || (expectedSpec.sourcePatchJson == spec.sourcePatchJson && expectedSpec.releaseSeconds == spec.releaseSeconds)) {
            "Unexpected MURMUR source specification"
        }
        require(obj.getValue("selectedSource") == MurmurKeptSources.byId(spec.selectionId).toJsonValue()) { "MURMUR historical selection profile changed" }
        require(obj.getValue("lowMidi").int() == spec.supportedMidi.first && obj.getValue("highMidi").int() == spec.supportedMidi.last) { "MURMUR selected domain changed" }
        val expected = recipes(spec)
        val rawRows = obj.getValue("samples").arr()
        require(rawRows.size == expected.size) { "Incomplete MURMUR cell grid" }
        val rows = mutableListOf<Row>()
        val seen = mutableSetOf<Cell>()
        for (raw in rawRows) {
            val row = readRow(raw)
            require(row.cell in expected && seen.add(row.cell)) { "Duplicate or out-of-grid MURMUR cell" }
            require(row.sample == sampleName(row.cell)) { "MURMUR sample path or cell label changed" }
            require(row.recipeJson == expected.getValue(row.cell).toJsonText()) { "MURMUR cell source or output policy changed" }
            checkBounds(spec, row.frameCount, row.loopStartFrame, row.loopEndFrameExclusive)
            if (spec.held) require(expected.getValue(row.cell).onsetFrames < row.loopStartFrame - 256) { "MURMUR onset overlaps real seam context" }
            require(row.rootHz.isFinite() && row.rootHz > 0 && row.seamError.isFinite() && row.seamError >= 0 &&
                row.encodedSeamError.isFinite() && row.encodedSeamError >= 0 && row.encodedSeamError < Keys.MAX_SEAM_ERROR) { "Invalid MURMUR native evidence" }
            if (spec.held) require(row.seamError < Keys.MAX_SEAM_ERROR) { "MURMUR native loop does not close" }
            else require(row.seamError == 0.0 && row.encodedSeamError == 0.0 && row.rawPreviousSha256 == hashBytes(byteArrayOf())) {
                "MURMUR finite cell must have no preceding cycle or loop evidence"
            }
            for (digest in listOf(row.rawSampleSha256, row.rawPreviousSha256, row.finishedFloatSha256, row.decodedPcmSha256, row.wavSha256)) requireHash(digest)
            val file = File(data, row.sample)
            require(!Files.isSymbolicLink(file.toPath()) && file.isFile) { "MURMUR cell is missing or a symlink" }
            require(file.length() <= row.frameCount.toLong() * WavWriter.BitDepth.PCM_24.bytesPerSample + MAX_WAV_HEADER_BYTES) {
                "MURMUR WAV size exceeds its bounded PCM24 cell"
            }
            require(hash(file) == row.wavSha256) { "MURMUR WAV bytes changed" }
            val info = WavInfo.read(file)
            require(info.sampleRate == WavWriter.MPC_SAMPLE_RATE && info.channels == 1 && info.bitsPerSample == 24 && info.frameCount == row.frameCount.toLong()) {
                "MURMUR cell must retain mono MPC-rate PCM24 and exact frame count"
            }
            val snip = WavReader.read(file)
            require(snip.samples.all { it.isFinite() } && floatHash(snip.samples) == row.decodedPcmSha256) { "MURMUR decoded PCM changed" }
            require(WavReader.readSmplStrict(file) == sheet(row.cell, row.loopStartFrame, row.loopEndFrameExclusive)) { "MURMUR WAV root/loop sheet changed" }
            if (spec.held) {
                require(snip.samples.first() == 0f) { "MURMUR encoded held onset must start at zero" }
                require(Keys.seamError(snip.samples, row.loopStartFrame) == row.encodedSeamError) { "MURMUR encoded seam evidence changed" }
            }
            else require(snip.samples.last() == 0f) { "MURMUR finite cell has an unfinished EOF" }
            rows += row
        }
        require(seen == expected.keys) { "MURMUR source grid is incomplete" }
        require(rows.map { it.cell } == expected.keys.toList()) { "MURMUR cell order changed" }
        val program = program(name, spec, rows)
        val sidecar = InstrumentStore.sidecar(root, name)
        val native = File(root, "$name.xty")
        val legacy = File(data, "$name.xpm")
        for (file in listOf(sidecar, native, legacy)) require(!Files.isSymbolicLink(file.toPath()) && file.isFile) { "MURMUR descriptors must be regular files" }
        require(data.listFiles()!!.map { it.name }.toSet() == rows.map { it.sample }.toSet() + setOf(SOURCES, legacy.name)) { "Unexpected or missing MURMUR TrackData entry" }
        require(InstrumentStore.read(sidecar).toProgram() == program) { "MURMUR phone mapping changed" }
        require(sidecar.readText() == InstrumentStore.render(program, dataName)) { "MURMUR phone descriptor changed" }
        require(native.readBytes().contentEquals(Mpc3TrackWriter().writeKeygroup(program))) { "MURMUR native descriptor changed" }
        require(legacy.readText() == KeygroupWriter().write(program)) { "MURMUR legacy descriptor changed" }
        return Packet(program, spec, rows.toList())
    }

    /** Re-render each executable cell recipe and compare the exact encoded WAV plus Float evidence. */
    fun replayAll(name: String, root: File, cancelled: () -> Boolean = { false },
                  progress: (Int, Int) -> Unit = { _, _ -> }, onShelf: Boolean = false): Int =
        replayAllUsing(name, root, cancelled, progress, MurmurSelectedInstrumentSource::renderCell, onShelf)

    internal fun replayAllUsing(name: String, root: File, cancelled: () -> Boolean, progress: (Int, Int) -> Unit,
                                renderCell: (MurmurSelectedInstrumentSource.CellRecipe) -> MurmurSelectedInstrumentSource.Capture,
                                onShelf: Boolean = false): Int {
        val packet = if (onShelf) validateOnShelf(name, root) else validatePacket(name, root)
        for ((index, row) in packet.rows.withIndex()) {
            checkCancelled(cancelled)
            val recipe = MurmurSelectedInstrumentSource.CellRecipe.fromJsonValue(Json.parse(row.recipeJson))
            val capture = renderCell(recipe)
            checkCancelled(cancelled)
            checkCapture(packet.spec, recipe, capture)
            require(capture.rawSampleSha256 == row.rawSampleSha256 && capture.rawPreviousSha256 == row.rawPreviousSha256 &&
                capture.finishedFloatSha256 == row.finishedFloatSha256 && capture.rootHz == row.rootHz && capture.seamError == row.seamError) {
                "MURMUR independent cell replay evidence differs"
            }
            val bytes = ByteArrayOutputStream().also {
                WavWriter.write(it, capture.snip, WavWriter.BitDepth.PCM_24, smpl = sheet(row.cell, capture.loopStartFrame, capture.loopEndFrameExclusive))
            }.toByteArray()
            require(hashBytes(bytes) == row.wavSha256) { "MURMUR independent PCM24/smpl replay differs" }
            progress(index + 1, packet.rows.size)
        }
        return packet.rows.size
    }

    private fun checkCapture(spec: Spec, expected: MurmurSelectedInstrumentSource.CellRecipe, capture: MurmurSelectedInstrumentSource.Capture) {
        require(capture.recipe.toJsonText() == expected.toJsonText()) { "MURMUR capture source recipe differs" }
        val snip = capture.snip
        require(snip.channels == 1 && snip.sampleRate == WavWriter.MPC_SAMPLE_RATE && snip.samples.all { it.isFinite() && kotlin.math.abs(it) < 1f }) {
            "MURMUR capture must be finite mono MPC-rate PCM below clipping"
        }
        checkBounds(spec, snip.frameCount, capture.loopStartFrame, capture.loopEndFrameExclusive)
        require(floatHash(snip.samples) == capture.finishedFloatSha256) { "MURMUR capture Float evidence changed" }
        requireHash(capture.rawSampleSha256); requireHash(capture.rawPreviousSha256)
        require(capture.rootHz.isFinite() && capture.rootHz > 0 && capture.seamError.isFinite() && capture.seamError >= 0) { "MURMUR capture evidence is invalid" }
        if (spec.held) {
            require(snip.samples.first() == 0f) { "MURMUR held onset must start at zero" }
            require(expected.onsetFrames < capture.loopStartFrame - 256) { "MURMUR onset overlaps real seam context" }
            require(Keys.seamError(snip.samples, capture.loopStartFrame) == capture.seamError && capture.seamError < Keys.MAX_SEAM_ERROR) {
                "MURMUR delivered context has no valid real seam"
            }
        } else require(capture.seamError == 0.0 && snip.samples.last() == 0f) { "MURMUR finite capture must retain its no-loop finite tail" }
    }

    private fun checkBounds(spec: Spec, frames: Int, start: Int, end: Int) {
        val maximum = (Murmur.MAX_SECONDS * WavWriter.MPC_SAMPLE_RATE * (if (spec.held) 2 else 1)).toInt()
        require(frames in 1..maximum && end == frames) { "MURMUR cell frame bounds changed" }
        if (spec.held) require(start >= 256 && frames.toLong() == start.toLong() * 2L) { "MURMUR held cell needs two real adjacent periods" }
        else require(start == 0) { "MURMUR finite cell must not loop" }
    }

    private fun program(name: String, spec: Spec, rows: List<Row>): KeygroupProgram {
        val byCell = rows.associateBy { it.cell }
        require(byCell.size == rows.size && byCell.keys == recipes(spec).keys) { "MURMUR assembly needs the complete unique grid" }
        val groups = zones(spec).map { zone -> Keygroup(zone.low, zone.high, zone.root,
            (0..2).map { touch ->
                val row = byCell.getValue(Cell(zone.root, touch))
                val window = VELOCITY_WINDOWS[touch]
                VelocityLayer(row.sample.removeSuffix(".wav"), row.frameCount.toLong(), window.first, window.last, row.loopStartFrame.toLong())
            }) }
        return KeygroupProgram(name, groups, volumeRelease = spec.releaseSeconds, explicitNoteOn = true)
    }

    private fun metadata(spec: Spec, rows: List<Row>): String = Json.write(JsonValue.Obj(linkedMapOf(
        "kind" to JsonValue.Str(KIND), "version" to num(1),
        "sourcePatch" to spec.sourcePatch().toJsonValue(), "sourcePatchSha256" to JsonValue.Str(hashText(spec.sourcePatchJson)),
        "selectedSource" to MurmurKeptSources.byId(spec.selectionId).toJsonValue(),
        "lowMidi" to num(spec.supportedMidi.first), "highMidi" to num(spec.supportedMidi.last),
        "releaseSeconds" to num(spec.releaseSeconds), "rootPolicy" to JsonValue.Str(ROOT_POLICY),
        "touchPolicy" to JsonValue.Str(TOUCH_POLICY), "encoding" to JsonValue.Str("PCM24_WITH_SMPL_V1"),
        "samples" to JsonValue.Arr(rows.map(::rowJson)),
    )))

    private fun rowJson(row: Row): JsonValue.Obj = JsonValue.Obj(linkedMapOf(
        "midi" to num(row.cell.midi), "touchIndex" to num(row.cell.touchIndex), "sample" to JsonValue.Str(row.sample),
        "recipe" to Json.parse(row.recipeJson), "recipeSha256" to JsonValue.Str(hashText(row.recipeJson)),
        "frameCount" to num(row.frameCount), "loopStartFrame" to num(row.loopStartFrame), "loopEndFrameExclusive" to num(row.loopEndFrameExclusive),
        "rawSampleSha256" to JsonValue.Str(row.rawSampleSha256), "rawPreviousSha256" to JsonValue.Str(row.rawPreviousSha256),
        "finishedFloatSha256" to JsonValue.Str(row.finishedFloatSha256), "decodedPcmSha256" to JsonValue.Str(row.decodedPcmSha256),
        "wavSha256" to JsonValue.Str(row.wavSha256), "seamError" to num(row.seamError),
        "encodedSeamError" to num(row.encodedSeamError), "rootHz" to num(row.rootHz),
    ))

    private fun readRow(value: JsonValue): Row {
        val obj = value.obj()
        require(obj.keys == setOf("midi", "touchIndex", "sample", "recipe", "recipeSha256", "frameCount", "loopStartFrame", "loopEndFrameExclusive",
            "rawSampleSha256", "rawPreviousSha256", "finishedFloatSha256", "decodedPcmSha256", "wavSha256", "seamError", "encodedSeamError", "rootHz")) {
            "Unknown or incomplete MURMUR cell binding"
        }
        val recipe = MurmurSelectedInstrumentSource.CellRecipe.fromJsonValue(obj.getValue("recipe"))
        require(obj.getValue("recipe") == recipe.toJsonValue()) { "Unknown MURMUR executable cell fields" }
        require(obj.getValue("recipeSha256").str() == hashText(recipe.toJsonText())) { "MURMUR executable cell hash changed" }
        val cell = Cell(obj.getValue("midi").int(), obj.getValue("touchIndex").int())
        require(recipe.midi == cell.midi && recipe.touchIndex == cell.touchIndex) { "MURMUR cell MIDI/touch relabelled" }
        return Row(cell, obj.getValue("sample").str(), recipe.toJsonText(), obj.getValue("frameCount").int(), obj.getValue("loopStartFrame").int(),
            obj.getValue("loopEndFrameExclusive").int(), obj.getValue("rawSampleSha256").str(), obj.getValue("rawPreviousSha256").str(),
            obj.getValue("finishedFloatSha256").str(), obj.getValue("decodedPcmSha256").str(), obj.getValue("wavSha256").str(),
            obj.getValue("seamError").num(), obj.getValue("encodedSeamError").num(), obj.getValue("rootHz").num())
    }

    private fun sheet(cell: Cell, start: Int, end: Int) = SmplChunk(cell.midi, if (start > 0) SmplChunk.Loop(start.toLong(), end.toLong()) else null)
    private fun sampleName(cell: Cell) = "Murmur_${cell.midi}_T${cell.touchIndex + 1}.wav"
    private fun num(value: Number) = JsonValue.Num(value.toDouble())
    private fun checkName(name: String) { require(Names.isMpcSafe(name) && name.length <= OneNote.MAX_NAME_LENGTH) { "Invalid MURMUR instrument name" } }
    private fun checkCancelled(cancelled: () -> Boolean) { if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("MURMUR instrument cancelled") }
    private fun requireHash(hash: String) { require(hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid MURMUR evidence hash" } }
    private fun floatHash(samples: FloatArray): String = hashBytes(ByteBuffer.allocate(samples.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).also { b -> samples.forEach { b.putFloat(it) } }.array())
    private fun hashText(text: String): String = hashBytes(text.toByteArray(Charsets.UTF_8))
    private fun hashBytes(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun hash(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
