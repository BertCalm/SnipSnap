package com.snipsnap.shell

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import com.snipsnap.synth.MurmurPatch
import com.snipsnap.synth.MurmurRoundRobinInstrumentSource
import com.snipsnap.synth.MurmurRoundRobinInstrumentSource.RoundRobinPolicy
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/**
 * Versioned saved RR bank: four complete fixed-take keygroup packets, one copy
 * of each root/touch/take WAV. Each child is an honest fixed-take MPC export.
 * Automatic rotation belongs to [MurmurRoundRobinPlayer], not those descriptors.
 * Old selected-source v1 packages remain the old maker's responsibility.
 */
object MurmurRoundRobinInstrumentBank {
    const val MANIFEST = "murmur-round-robin-bank.json"
    const val CURSOR_POLICY = "PER_PITCH_ZONE_SHARED_TOUCH_MOD4_RESET_ON_LOAD_AND_ALL_OFF_V1"
    const val EXPORT_POLICY = "FOUR_FIXED_TAKE_PROGRAMS_NO_AUTOMATIC_MPC_ROTATION_V1"
    private const val KIND = "MURMUR_ROUND_ROBIN_INSTRUMENT_BANK"
    private const val MAX_MANIFEST_BYTES = 64L * 1024

    /** [policy] is opt-in: the default breath policy writes exactly the version-1 bank it always did. */
    class Spec(
        patch: MurmurPatch,
        releaseSeconds: Float = MurmurRoundRobinInstrumentSource.DEFAULT_RELEASE_SECONDS,
        val policy: RoundRobinPolicy = RoundRobinPolicy.BREATH_V1,
    ) {
        private val frozen = MurmurRoundRobinTakePackage.Spec(patch, 0, releaseSeconds, policy)
        val sourcePatchJson: String get() = frozen.sourcePatchJson
        val releaseSeconds: Float get() = frozen.releaseSeconds
        val supportedMidi: IntRange get() = frozen.supportedMidi
        val held: Boolean get() = frozen.held
        fun sourcePatch(): MurmurPatch = frozen.sourcePatch()
        fun takeSpec(take: Int) = MurmurRoundRobinTakePackage.Spec(sourcePatch(), take, releaseSeconds, policy)
    }

    class Packet internal constructor(val name: String, val spec: Spec, takes: List<MurmurRoundRobinTakePackage.Packet>) {
        val takes: List<MurmurRoundRobinTakePackage.Packet> = takes.toList()
        val cellCount: Int get() = takes.sumOf { it.rows.size }
    }

    fun takeFolder(take: Int): String { require(take in 0..3); return "take-${take + 1}" }
    fun programName(name: String, take: Int): String { checkName(name); require(take in 0..3); return "$name R${take + 1}" }
    fun zones(spec: Spec): List<MurmurRoundRobinTakePackage.Zone> = MurmurRoundRobinTakePackage.zones(spec.takeSpec(0))

    /** A complete private bank is validated before its single directory move. */
    fun writeNew(name: String, spec: Spec, destination: File, cancelled: () -> Boolean = { false },
                 progress: (Int, Int) -> Unit = { _, _ -> }): Packet =
        writeNewUsing(name, spec, destination, cancelled, progress, MurmurRoundRobinInstrumentSource::renderCell)

    internal fun writeNewUsing(name: String, spec: Spec, destination: File, cancelled: () -> Boolean,
                               progress: (Int, Int) -> Unit,
                               renderCell: (MurmurRoundRobinInstrumentSource.CellRecipe) -> MurmurRoundRobinInstrumentSource.Capture,
                               staged: (File) -> Unit = {}): Packet {
        checkName(name)
        require(!Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) { "MURMUR RR bank already exists" }
        val parent = requireNotNull(destination.absoluteFile.parentFile).apply { mkdirs() }
        val stage = Files.createTempDirectory(parent.toPath(), ".murmur-rr-bank-").toFile()
        try {
            checkCancelled(cancelled)
            val count = MurmurRoundRobinTakePackage.recipes(spec.takeSpec(0)).size
            val packets = (0..3).map { take ->
                checkCancelled(cancelled)
                MurmurRoundRobinTakePackage.writeNewPackageUsing(programName(name, take), spec.takeSpec(take),
                    File(stage, takeFolder(take)), cancelled, { done, total ->
                        require(total == count); progress(take * count + done, count * 4)
                    }, renderCell)
            }
            File(stage, MANIFEST).writeText(manifest(name, spec, packets))
            staged(stage)
            checkCancelled(cancelled)
            val packet = validate(name, stage, spec)
            checkCancelled(cancelled)
            Files.move(stage.toPath(), destination.toPath())
            return packet
        } finally { if (stage.exists()) stage.deleteRecursively() }
    }

    /** Strict closure plus all WAV/recipe/descriptor readback. Hashes assert consistency, not trusted authorship. */
    fun validate(name: String, root: File, expected: Spec? = null): Packet = validateUsing(name, root, expected, null)

    internal fun validateWithReads(name: String, root: File, read: (File) -> ByteArray): Packet =
        validateUsing(name, root, null, read)

    private fun validateUsing(name: String, root: File, expected: Spec?, read: ((File) -> ByteArray)?): Packet {
        checkName(name)
        require(!Files.isSymbolicLink(root.toPath()) && root.isDirectory) { "MURMUR RR bank must be a real directory" }
        require(root.listFiles()!!.map { it.name }.toSet() == (0..3).map(::takeFolder).toSet() + MANIFEST) {
            "MURMUR RR bank must contain its manifest and exactly four take packets"
        }
        val file = File(root, MANIFEST)
        require(!Files.isSymbolicLink(file.toPath()) && file.isFile && file.length() in 1..MAX_MANIFEST_BYTES)
        val manifestText = read?.invoke(file)?.toString(Charsets.UTF_8) ?: file.readText()
        val value = Json.parse(manifestText)
        val obj = value.obj()
        // Version 1 is the breath policy and never names it; version 2 names its policy.
        val version = obj["version"]?.int()
        val v1Keys = setOf("kind", "version", "name", "sourcePatch", "releaseSeconds", "cursorPolicy", "exportPolicy", "takes")
        require(obj.keys == if (version == 2) v1Keys + "roundRobinPolicy" else v1Keys) { "Unknown or incomplete MURMUR RR bank fields" }
        require(obj.getValue("kind").str() == KIND && (version == 1 || version == 2)) { "Unsupported MURMUR RR bank kind/version" }
        val policy = if (version == 1) RoundRobinPolicy.BREATH_V1 else
            requireNotNull(RoundRobinPolicy.fromJson(obj.getValue("roundRobinPolicy").str())
                ?.takeIf { it != RoundRobinPolicy.BREATH_V1 }) { "Unsupported MURMUR RR bank round-robin policy" }
        require(obj.getValue("name").str() == name && obj.getValue("cursorPolicy").str() == CURSOR_POLICY &&
            obj.getValue("exportPolicy").str() == EXPORT_POLICY)
        val patch = MurmurPatch.fromJsonValue(obj.getValue("sourcePatch")) as MurmurPatch
        require(patch.toJsonValue() == obj.getValue("sourcePatch")) { "Complete canonical MURMUR source is required" }
        val release = obj.getValue("releaseSeconds").num()
        require(release.isFinite() && release == release.toFloat().toDouble())
        val spec = Spec(patch, release.toFloat(), policy)
        require(expected == null || (expected.sourcePatchJson == spec.sourcePatchJson && expected.releaseSeconds == spec.releaseSeconds &&
            expected.policy == spec.policy))
        val packets = (0..3).map { take ->
            if (read == null) MurmurRoundRobinTakePackage.validatePacket(programName(name, take), File(root, takeFolder(take)), spec.takeSpec(take))
            else MurmurRoundRobinTakePackage.validateWithReads(programName(name, take), File(root, takeFolder(take)), spec.takeSpec(take), read)
        }
        require((if (read == null) file.readText() else manifestText) == manifest(name, spec, packets)) { "MURMUR RR manifest differs from the complete four-take grid" }
        return Packet(name, spec, packets)
    }

    /**
     * Copy one exact fixed-take package to the ordinary shelf, without rendering
     * or claiming automatic MPC rotation. All provenance stays in TrackData.
     */
    fun exportFixedTake(name: String, root: File, take: Int, destination: File,
                        hooks: InstrumentPackageAdmission.Hooks = InstrumentPackageAdmission.Hooks()): MurmurRoundRobinTakePackage.Packet {
        require(take in 0..3)
        val bank = validate(name, root)
        val exportName = programName(name, take)
        val source = File(root, takeFolder(take))
        return InstrumentPackageAdmission.publishNew(exportName, destination, build = { packet ->
            require(!packet.exists()); check(packet.mkdir())
            for (entry in source.listFiles()!!) {
                if (entry.isDirectory) {
                    val target = File(packet, entry.name); check(target.mkdir())
                    for (child in entry.listFiles()!!) {
                        require(child.isFile && !Files.isSymbolicLink(child.toPath()))
                        Files.copy(child.toPath(), File(target, child.name).toPath())
                    }
                } else {
                    require(entry.isFile && !Files.isSymbolicLink(entry.toPath()))
                    Files.copy(entry.toPath(), File(packet, entry.name).toPath())
                }
            }
            MurmurRoundRobinTakePackage.validatePacket(exportName, packet, bank.spec.takeSpec(take))
        }, validate = { packet -> MurmurRoundRobinTakePackage.validatePacket(exportName, packet, bank.spec.takeSpec(take)); Unit }, hooks = hooks)
    }

    private fun manifest(name: String, spec: Spec, packets: List<MurmurRoundRobinTakePackage.Packet>): String = Json.write(JsonValue.Obj(linkedMapOf(
        "kind" to JsonValue.Str(KIND), "version" to JsonValue.Num(if (spec.policy == RoundRobinPolicy.BREATH_V1) 1.0 else 2.0),
        "name" to JsonValue.Str(name),
        "sourcePatch" to spec.sourcePatch().toJsonValue(), "releaseSeconds" to JsonValue.Num(spec.releaseSeconds.toDouble()),
        "cursorPolicy" to JsonValue.Str(CURSOR_POLICY), "exportPolicy" to JsonValue.Str(EXPORT_POLICY),
        "takes" to JsonValue.Arr(packets.mapIndexed { take, packet -> JsonValue.Obj(linkedMapOf(
            "take" to JsonValue.Num(take.toDouble()), "folder" to JsonValue.Str(takeFolder(take)),
            "program" to JsonValue.Str(programName(name, take)), "cells" to JsonValue.Num(packet.rows.size.toDouble()),
            "recipesSha256" to JsonValue.Str(hashText(packet.rows.joinToString("\n") { it.recipeJson })),
            "wavHashesSha256" to JsonValue.Str(hashText(packet.rows.joinToString("\n") { it.wavSha256 })),
        )) }),
    ).apply { if (spec.policy != RoundRobinPolicy.BREATH_V1) put("roundRobinPolicy", JsonValue.Str(spec.policy.json)) }))
    private fun hashText(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun checkName(name: String) { require(Names.isMpcSafe(name) && name.length <= OneNote.MAX_NAME_LENGTH - 3) { "Invalid MURMUR RR bank name" } }
    private fun checkCancelled(cancelled: () -> Boolean) { if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("MURMUR RR bank cancelled") }
}
