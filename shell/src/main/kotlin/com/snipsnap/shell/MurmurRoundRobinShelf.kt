package com.snipsnap.shell

import com.snipsnap.audio.WavWriter
import com.snipsnap.audio.WavReader
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.MurmurRoundRobinInstrumentSource
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import com.snipsnap.synth.MurmurKeptSources
import com.snipsnap.synth.MurmurPatch
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/**
 * A separate shelf namespace for saved finite MURMUR RR banks. An entry owns a
 * complete, unmodified bank and an admission inventory. It is deliberately not
 * an ordinary InstrumentStore sidecar: the phone's existing single-program
 * player cannot rotate its four takes or admit a whole RR chord atomically.
 *
 * All operations are blocking IO and belong on an IO dispatcher. No method
 * renders a source. Validation proves consistency, not owner listening or the
 * truth of a third party's native provenance claims.
 */
object MurmurRoundRobinShelf {
    const val DIRECTORY = "murmur-round-robin-banks"
    const val MAX_BANK_BYTES = 64L * 1024 * 1024
    const val MIN_REMAINING_FREE_BYTES = 32L * 1024 * 1024
    private const val ENTRY = "admission.json"
    private const val BANK = "bank"
    private const val MAX_ENTRY_BYTES = 64L * 1024
    private const val MAX_FILES = 128
    private const val MAX_NODES = 160
    private const val KIND = "MURMUR_FINITE_RR_SHELF_ENTRY"

    /** The exact admission seal is part of the handle; a name alone is not an identity. */
    class SavedBank internal constructor(val name: String, val identitySha256: String,
                                         val decodedPcmBytes: Long, val cellCount: Int,
                                         val supportedMidi: IntRange,
                                         val roundRobinPolicy: MurmurRoundRobinInstrumentSource.RoundRobinPolicy =
                                             MurmurRoundRobinInstrumentSource.RoundRobinPolicy.BREATH_V1)
    data class Refused(val name: String, val reason: String)
    data class Listing(val banks: List<SavedBank>, val refused: List<Refused>)
    internal data class Hooks(val sourceValidated: () -> Unit = {}, val copied: () -> Unit = {},
                              val beforeMove: () -> Unit = {})
    private data class OwnedFile(val path: String, val bytes: Long, val sha256: String)
    private data class Checked(val root: File, val packet: MurmurRoundRobinInstrumentBank.Packet,
                               val saved: SavedBank)

    /** Copy a strict saved bank into a fresh entry; handled failures leave no visible entry. */
    @Synchronized
    fun admitSaved(name: String, source: File, shelf: File,
                   cancelled: () -> Boolean = { false }, progress: (Int, Int) -> Unit = { _, _ -> }): SavedBank =
        admitSavedUsing(name, source, shelf, cancelled, progress, Hooks())

    @Synchronized
    internal fun admitSavedUsing(name: String, source: File, shelf: File,
                                cancelled: () -> Boolean, progress: (Int, Int) -> Unit, hooks: Hooks): SavedBank {
        checkName(name)
        stop(cancelled)
        val sourcePath = source.absoluteFile.toPath().normalize()
        val shelfPath = File(shelf, DIRECTORY).absoluteFile.toPath().normalize()
        require(!shelfPath.startsWith(sourcePath) && !sourcePath.startsWith(shelfPath)) {
            "MURMUR RR source and shelf namespace must not overlap"
        }
        finitePolicy(source)
        val original = inventory(source, cancelled)
        val packet = MurmurRoundRobinInstrumentBank.validate(name, source)
        eligible(packet)
        require(inventory(source, cancelled) == original) { "MURMUR RR bank changed during admission" }
        hooks.sourceValidated()
        stop(cancelled)
        val namespace = namespace(shelf, create = true)!!
        val target = File(namespace, name)
        require(!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) { "MURMUR RR bank already exists" }
        require(namespace.usableSpace >= original.sumOf { it.bytes } + MAX_ENTRY_BYTES + MIN_REMAINING_FREE_BYTES) {
            "Insufficient space to keep the complete MURMUR RR bank"
        }
        val stage = Files.createTempDirectory(namespace.toPath(), ".admitting-").toFile()
        try {
            val bank = File(stage, BANK).apply { check(mkdir()) }
            for ((index, row) in original.withIndex()) {
                stop(cancelled)
                val input = File(source, row.path)
                requireRegular(input)
                val output = File(bank, row.path)
                output.parentFile.mkdirs()
                // The cap is enforced while copying as well as before reading:
                // a source replaced/grown after validation cannot fill the shelf.
                copyExact(input, output, row, cancelled)
                progress(index + 1, original.size)
            }
            hooks.copied()
            stop(cancelled)
            require(inventory(source, cancelled) == original && inventory(bank, cancelled) == original) {
                "MURMUR RR source or copied bank changed during admission"
            }
            val copied = MurmurRoundRobinInstrumentBank.validate(name, bank, packet.spec)
            eligible(copied)
            val seal = seal(name, original)
            File(stage, ENTRY).writeText(seal)
            checkEntry(name, stage, hashText(seal))
            hooks.beforeMove()
            stop(cancelled)
            checkEntry(name, stage, hashText(seal))
            // No REPLACE_EXISTING: another entry, including an orphan or link,
            // is never overwritten. Stage and target share their filesystem.
            Files.move(stage.toPath(), target.toPath())
            // The move committed; a late cancellation does not unpublish success.
            return handle(name, hashText(seal), copied)
        } finally {
            if (Files.exists(stage.toPath(), LinkOption.NOFOLLOW_LINKS)) deleteStage(stage.toPath())
        }
    }

    /** Invalid entries are reported, never presented as playable or silently repaired. */
    @Synchronized
    fun list(shelf: File): Listing {
        val namespace = namespace(shelf, create = false) ?: return Listing(emptyList(), emptyList())
        val banks = mutableListOf<SavedBank>()
        val refused = mutableListOf<Refused>()
        for (entry in namespace.listFiles()!!.sortedBy { it.name }) {
            if (entry.name.startsWith(".admitting-")) continue
            try { banks += checkEntry(entry.name, entry).saved }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { refused += Refused(entry.name, e.message ?: "Invalid MURMUR RR bank") }
        }
        return Listing(banks, refused)
    }

    /** The existing bounded JVM reference player, explicitly not Android/JNI adoption. */
    @Synchronized
    fun loadReference(saved: SavedBank, shelf: File, outputRate: Int = WavWriter.MPC_SAMPLE_RATE): MurmurRoundRobinPlayer {
        val checked = resolve(saved, shelf)
        val player = MurmurRoundRobinPlayer.load(saved.name, checked.root, outputRate)
        try {
            resolve(saved, shelf) // Retain the exact identity through the load, too.
            return player
        } catch (failure: Throwable) { player.close(); throw failure }
    }

    const val MAX_TRANSFER_PCM_BYTES = 128L * 1024 * 1024
    private const val TRANSFER_METADATA_RESERVE = 32L * 1024 * 1024
    data class PcmCell(val ordinal: Int, val root: Int, val low: Int, val high: Int,
                       val take: Int, val touch: Int, val frames: Int, val decodedSha256: String)
    class PcmPlan internal constructor(val saved: SavedBank, val releaseMs: Float, cells: List<PcmCell>,
                                      val peakPcmWorkingBytes: Long) {
        private val frozen = cells.toList()
        val cells: List<PcmCell> get() = frozen.toList()
    }

    /**
     * Blocking IO. A consumer must copy each cell synchronously and must not retain
     * the FloatArray. Nothing is usable until this returns after the final seal check.
     * Failure/cancellation requires the consumer to destroy its private staged bank.
     * The128MiB cap covers resident old/new PCM plus conservative cell/validation
     * copies and32MiB metadata reserve, NOT Android/Oboe/Aquarium or total process RSS.
     */
    @Synchronized
    fun streamPcm(saved: SavedBank, shelf: File, residentPcmBytes: Long = 0,
                  cancelled: () -> Boolean = { false }, onPlan: (PcmPlan) -> Unit = {},
                  onCell: (PcmCell, FloatArray) -> Unit): PcmPlan {
        require(residentPcmBytes in 0..MAX_TRANSFER_PCM_BYTES)
        stop(cancelled)
        val checked = resolvePcm(saved, shelf, residentPcmBytes, true, cancelled)
        val packet = checked.packet
        val zones = MurmurRoundRobinInstrumentBank.zones(packet.spec).associateBy { it.root }
        val rows = packet.takes.flatMap { it.rows }
        val cells = rows.mapIndexed { ordinal, row ->
            val zone = zones.getValue(row.cell.midi)
            PcmCell(ordinal, zone.root, zone.low, zone.high, row.cell.take, row.cell.touchIndex,
                row.frameCount, row.decodedPcmSha256)
        }
        val largest = rows.maxOf { it.frameCount.toLong() * 3 + 4096 }
        val peak = residentPcmBytes + checked.saved.decodedPcmBytes + 16 * largest + TRANSFER_METADATA_RESERVE
        require(peak <= MAX_TRANSFER_PCM_BYTES) { "MURMUR RR old/new PCM transfer budget exceeded" }
        val plan = PcmPlan(checked.saved, packet.spec.releaseSeconds * 1000f, cells, peak)
        onPlan(plan); stop(cancelled)
        for ((ordinal, row) in rows.withIndex()) {
            stop(cancelled)
            val take = packet.takes[row.cell.take]
            val file = File(File(checked.root, MurmurRoundRobinInstrumentBank.takeFolder(row.cell.take)),
                Mpc3TrackWriter.trackDataDirName(take.program.name) + "/" + row.sample)
            val length = file.length()
            require(length in 1..(row.frameCount.toLong() * 3 + 4096))
            val bytes = MurmurRoundRobinPcm.read(file, length, row.wavSha256, cancelled)
            require(MurmurRoundRobinPcm.info(bytes).frameCount == row.frameCount.toLong())
            val snip = WavReader.read(bytes, row.frameCount)
            require(snip.channels == 1 && snip.sampleRate == WavWriter.MPC_SAMPLE_RATE && snip.frameCount == row.frameCount &&
                snip.samples.all { it.isFinite() } &&
                MurmurRoundRobinInstrumentSource.floatSha256(snip.samples) == row.decodedPcmSha256)
            onCell(cells[ordinal], snip.samples); stop(cancelled)
        }
        // New native PCM is now resident while final validation reads one cell at a time.
        resolvePcm(saved, shelf, residentPcmBytes + checked.saved.decodedPcmBytes, false, cancelled)
        stop(cancelled)
        return plan
    }

    private fun resolvePcm(saved: SavedBank, shelf: File, resident: Long, incoming: Boolean,
                           cancelled: () -> Boolean): Checked {
        stop(cancelled); checkName(saved.name)
        val parent = requireNotNull(namespace(shelf, false)) { "MURMUR RR shelf is missing" }
        val entry = File(parent, saved.name); requireDirectory(entry)
        require(entry.listFiles()!!.map { it.name }.toSet() == setOf(BANK, ENTRY))
        val sealFile = File(entry, ENTRY)
        val sealSize = sealFile.length(); require(sealSize in 1..MAX_ENTRY_BYTES)
        val sealBytes = MurmurRoundRobinPcm.read(sealFile, sealSize, saved.identitySha256, cancelled)
        val root = File(entry, BANK)
        val files = inventory(root, cancelled)
        require(sealBytes.toString(Charsets.UTF_8) == seal(saved.name, files)) { "Saved MURMUR RR bank changed" }
        // Before any decode or metadata parser: pessimistic8-bit expansion4xraw;
        // 16x largest WAV covers raw+decoded+hash+smpl+JNI copies without GC promises.
        val wavs = files.filter { it.path.endsWith(".wav") }; require(wavs.size == 84)
        val metadata = files.filterNot { it.path.endsWith(".wav") }
        require(metadata.sumOf { it.bytes } <= 4L * 1024 * 1024 && metadata.all { it.bytes <= 512L * 1024 }) { "RR metadata transfer cap exceeded" }
        val bound = resident + (if (incoming) 4 * wavs.sumOf { it.bytes } else 0) +
            16 * wavs.maxOf { it.bytes } + TRANSFER_METADATA_RESERVE
        require(bound <= MAX_TRANSFER_PCM_BYTES) { "MURMUR RR predecode working budget exceeded" }
        val byPath = files.associateBy { it.path }
        val read: (File) -> ByteArray = { file ->
            val row = byPath.getValue(file.relativeTo(root).invariantSeparatorsPath)
            MurmurRoundRobinPcm.read(file, row.bytes, row.sha256, cancelled)
        }
        finitePolicy(root, read)
        val packet = MurmurRoundRobinInstrumentBank.validateWithReads(saved.name, root, read)
        eligible(packet)
        require(inventory(root, cancelled) == files) { "Saved MURMUR RR bank changed during load" }
        require(MurmurRoundRobinPcm.read(sealFile, sealSize, saved.identitySha256, cancelled).contentEquals(sealBytes))
        return Checked(root, packet, handle(saved.name, saved.identitySha256, packet))
    }

    /** Explicitly export ONE fixed-take program; this is not automatic MPC round robin. */
    @Synchronized
    fun exportFixedTake(saved: SavedBank, shelf: File, take: Int, instrumentShelf: File): MurmurRoundRobinTakePackage.Packet =
        exportFixedTakeUsing(saved, shelf, take, instrumentShelf, InstrumentPackageAdmission.Hooks())

    @Synchronized
    internal fun exportFixedTakeUsing(saved: SavedBank, shelf: File, take: Int, instrumentShelf: File,
                                     hooks: InstrumentPackageAdmission.Hooks): MurmurRoundRobinTakePackage.Packet {
        require(take in 0..3)
        require(instrumentShelf.absoluteFile == instrumentShelf.canonicalFile &&
            !Files.isSymbolicLink(instrumentShelf.toPath())) { "Instrument shelf must not traverse symbolic links" }
        require(!instrumentShelf.absoluteFile.toPath().normalize().startsWith(File(shelf, DIRECTORY).absoluteFile.toPath().normalize())) {
            "Fixed-take exports must not be written inside the RR bank namespace"
        }
        val checked = resolve(saved, shelf)
        val expectedTake = inventory(File(checked.root, MurmurRoundRobinInstrumentBank.takeFolder(take)))
        return MurmurRoundRobinInstrumentBank.exportFixedTake(saved.name, checked.root, take, instrumentShelf,
            InstrumentPackageAdmission.Hooks(staged = { packet ->
                hooks.staged(packet)
                require(inventory(packet) == expectedTake) { "Fixed-take export differs from the admitted bank" }
                resolve(saved, shelf)
            }, moved = { entry ->
                hooks.moved(entry)
                resolve(saved, shelf)
            }))
    }

    private fun resolve(saved: SavedBank, shelf: File): Checked {
        checkName(saved.name)
        val namespace = requireNotNull(namespace(shelf, create = false)) { "MURMUR RR shelf is missing" }
        return checkEntry(saved.name, File(namespace, saved.name), saved.identitySha256)
    }

    private fun checkEntry(name: String, entry: File, expectedIdentity: String? = null): Checked {
        checkName(name)
        requireDirectory(entry)
        require(entry.listFiles()!!.map { it.name }.toSet() == setOf(BANK, ENTRY)) { "Incomplete MURMUR RR shelf entry" }
        val entryFile = File(entry, ENTRY)
        requireRegular(entryFile)
        require(entryFile.length() in 1..MAX_ENTRY_BYTES)
        val text = entryFile.readText()
        val identity = hashText(text)
        require(expectedIdentity == null || identity == expectedIdentity) { "Stale MURMUR RR shelf handle" }
        val bank = File(entry, BANK)
        finitePolicy(bank)
        val files = inventory(bank)
        require(text == seal(name, files)) { "Saved MURMUR RR bank differs from its admission inventory" }
        val packet = MurmurRoundRobinInstrumentBank.validate(name, bank)
        eligible(packet)
        require(inventory(bank) == files) { "Saved MURMUR RR bank changed during validation" }
        return Checked(bank, packet, handle(name, identity, packet))
    }

    /** Refuse held/other selections before reading their potentially much larger grids. */
    private fun finitePolicy(root: File, read: ((File) -> ByteArray)? = null) {
        requireDirectory(root)
        val file = File(root, MurmurRoundRobinInstrumentBank.MANIFEST)
        requireRegular(file)
        require(file.length() in 1..MAX_ENTRY_BYTES)
        val obj = Json.parse(read?.invoke(file)?.toString(Charsets.UTF_8) ?: file.readText()).obj()
        val patch = MurmurPatch.fromJsonValue(obj.getValue("sourcePatch")) as MurmurPatch
        val canonical = MurmurKeptSources.byId(MurmurKeptSources.STEADY_C2).factoryPatch()
        require(patch.toJsonValue() == obj.getValue("sourcePatch") && patch.toJsonText() == canonical.toJsonText()) {
            "This shelf admits only the finite STEADY ROOT C2 RR bank; held and other sources need separate admission"
        }
    }

    private fun eligible(packet: MurmurRoundRobinInstrumentBank.Packet) {
        require(!packet.spec.held && packet.cellCount == 84 && packet.takes.size == 4)
        require(decodedBytes(packet) <= MurmurRoundRobinPlayer.MAX_LOADED_PCM_BYTES) { "MURMUR RR playback memory budget exceeded" }
    }
    private fun decodedBytes(packet: MurmurRoundRobinInstrumentBank.Packet) =
        packet.takes.sumOf { take -> take.rows.sumOf { it.frameCount.toLong() * Float.SIZE_BYTES } }
    private fun handle(name: String, identity: String, packet: MurmurRoundRobinInstrumentBank.Packet) =
        SavedBank(name, identity, decodedBytes(packet), packet.cellCount, packet.spec.supportedMidi, packet.spec.policy)

    private fun namespace(shelf: File, create: Boolean): File? {
        if (!Files.exists(shelf.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (!create) return null
            require(shelf.absoluteFile == shelf.canonicalFile) { "Shelf path must not traverse symbolic links" }
            check(shelf.mkdirs())
        }
        requireDirectory(shelf)
        val root = File(shelf, DIRECTORY)
        if (!Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (!create) return null
            check(root.mkdir())
        }
        requireDirectory(root)
        return root
    }

    private fun inventory(root: File, cancelled: () -> Boolean = { false }): List<OwnedFile> {
        requireDirectory(root)
        val files = mutableListOf<OwnedFile>()
        var total = 0L
        var nodes = 0
        fun visit(dir: File, prefix: String, depth: Int) {
            require(depth <= 3) { "MURMUR RR bank directory depth exceeds its format" }
            for (file in dir.listFiles()!!.sortedBy { it.name }) {
                stop(cancelled)
                require(++nodes <= MAX_NODES && !Files.isSymbolicLink(file.toPath())) { "MURMUR RR bank has too many entries or a symbolic link" }
                val path = prefix + file.name
                if (file.isDirectory) { requireDirectory(file); visit(file, "$path/", depth + 1) }
                else {
                    requireRegular(file)
                    require(files.size < MAX_FILES)
                    val size = file.length()
                    require(size <= MAX_BANK_BYTES - total) { "MURMUR RR saved bank byte budget exceeded" }
                    total += size
                    files += OwnedFile(path, size, hashFile(file, size, cancelled))
                }
            }
        }
        visit(root, "", 0)
        return files.sortedBy { it.path }
    }

    private fun copyExact(input: File, output: File, expected: OwnedFile, cancelled: () -> Boolean) {
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        input.inputStream().use { source -> output.outputStream().use { target ->
            val buffer = ByteArray(65536)
            while (true) {
                stop(cancelled)
                val count = source.read(buffer)
                if (count < 0) break
                require(count.toLong() <= expected.bytes - copied) { "MURMUR RR source grew while copying" }
                target.write(buffer, 0, count); digest.update(buffer, 0, count); copied += count
            }
        } }
        require(copied == expected.bytes && hex(digest.digest()) == expected.sha256) { "MURMUR RR source changed while copying" }
    }

    private fun seal(name: String, files: List<OwnedFile>) = Json.write(JsonValue.Obj(linkedMapOf(
        "kind" to JsonValue.Str(KIND), "version" to JsonValue.Num(1.0), "name" to JsonValue.Str(name),
        "files" to JsonValue.Arr(files.map { JsonValue.Obj(linkedMapOf(
            "path" to JsonValue.Str(it.path), "bytes" to JsonValue.Num(it.bytes.toDouble()), "sha256" to JsonValue.Str(it.sha256),
        )) }),
    )))
    private fun hashFile(file: File, expectedSize: Long, cancelled: () -> Boolean): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var read = 0L
        file.inputStream().use { source ->
            val buffer = ByteArray(65536)
            while (true) {
                stop(cancelled)
                val n = source.read(buffer)
                if (n < 0) break
                require(n.toLong() <= expectedSize - read) { "MURMUR RR file grew while checking" }
                digest.update(buffer, 0, n); read += n
            }
        }
        require(read == expectedSize) { "MURMUR RR file changed while checking" }
        return hex(digest.digest())
    }
    private fun hashText(text: String) = hex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun requireDirectory(file: File) {
        require(!Files.isSymbolicLink(file.toPath()) && file.isDirectory && file.absoluteFile == file.canonicalFile) {
            "MURMUR RR directory must be real and must not traverse symbolic links"
        }
    }
    private fun requireRegular(file: File) {
        require(!Files.isSymbolicLink(file.toPath()) && Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "MURMUR RR input must be a regular file"
        }
    }
    /** Owned temporary entries only; never follow a link introduced during a failed admission. */
    private fun deleteStage(stage: Path) {
        Files.walkFileTree(stage, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
    private fun checkName(name: String) {
        require(Names.isMpcSafe(name) && name.length <= OneNote.MAX_NAME_LENGTH - 3 && !name.startsWith(".")) {
            "Invalid MURMUR RR bank name"
        }
    }
    private fun stop(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("MURMUR RR admission cancelled")
    }
}
