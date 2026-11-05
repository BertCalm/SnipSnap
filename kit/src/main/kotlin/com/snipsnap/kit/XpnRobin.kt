package com.snipsnap.kit

import java.io.File

/**
 * Deals a robin pad's takes again after an import. `:kit` carries round robin through an `.xpn`
 * (take files plus the pad's settings) but cannot render takes, which is `:shell`'s `Robin`; the
 * importers hand each landed kit to one of these.
 */
fun interface RobinDealer {
    /**
     * [kit] as it landed in [dir]: every pad with robin settings and no chain plays its untouched
     * original. Returns the kit with those pads dealt, their chains written in [dir] (the original
     * kept where the pad's own undo finds it).
     */
    fun deal(kit: Kit, dir: File): Kit
}

/**
 * The round-robin half of an `.xpn`'s SnipSnap source metadata: one entry beside the program, bound
 * to its exact bytes like [XpnSourceMetadata], naming for each split robin pad its take-one file
 * (the untouched original, by hash) and the settings that deal the rest. The MPC ignores it; an
 * importer that finds it lands take one as the pad's original and deals the takes again, so all
 * of them come back, not only the four the format carries. A separate entry, not a field of the
 * source sidecar, so an older SnipSnap reading a newer archive still imports it (as today's fold).
 */
internal object XpnRobin {
    const val MAX_BYTES: Long = 64L * 1024
    private const val FORMAT = "snipsnap-xpn-robin"

    data class Pad(val slot: Int, val take: String, val sha256: String, val settings: RobinSettings)
    data class Metadata(val kit: KitRobin?, val pads: List<Pad>)

    fun entryName(programEntry: String): String = "$programEntry.snipsnap-robin.json"

    /** Null when no pad was split into takes: such archives stay byte-identical to before. */
    fun render(kit: Kit, programEntry: String, programBytes: ByteArray, takes: Map<Int, List<Pair<String, ByteArray>>>): ByteArray? {
        val pads = kit.pads.filter { it.slot in takes && it.robin != null }.sortedBy { it.slot }
        if (pads.isEmpty()) return null
        fun n(v: Number) = com.snipsnap.json.JsonValue.Num(v.toDouble())
        val root = linkedMapOf<String, com.snipsnap.json.JsonValue>(
            "format" to com.snipsnap.json.JsonValue.Str(FORMAT),
            "version" to n(1),
            "programEntry" to com.snipsnap.json.JsonValue.Str(programEntry),
            "programSha256" to com.snipsnap.json.JsonValue.Str(sha256(programBytes)),
        )
        kit.robin?.let { root["kit"] = com.snipsnap.json.JsonValue.Obj(linkedMapOf("amount" to n(it.amount), "takes" to n(it.takes))) }
        root["pads"] = com.snipsnap.json.JsonValue.Arr(pads.map { pad ->
            val s = pad.robin!!
            val (stem, bytes) = takes.getValue(pad.slot).first()
            val p = linkedMapOf<String, com.snipsnap.json.JsonValue>(
                "slot" to n(pad.slot),
                "take" to com.snipsnap.json.JsonValue.Str(stem),
                "sha256" to com.snipsnap.json.JsonValue.Str(sha256(bytes)),
                "takes" to n(s.takes),
                "seed" to n(s.seed),
                "follows" to com.snipsnap.json.JsonValue.Bool(s.followsKit),
            )
            s.amount?.let { p["amount"] = n(it) }
            com.snipsnap.json.JsonValue.Obj(p)
        })
        return (com.snipsnap.json.Json.write(com.snipsnap.json.JsonValue.Obj(root)) + "\n").toByteArray(Charsets.UTF_8)
    }

    /** The entry for [programEntry], validated as untrusted input; null when the archive has none. */
    fun read(zip: java.util.zip.ZipFile, entries: List<java.util.zip.ZipEntry>, programEntry: String, programBytes: ByteArray, budget: XpnImporter.WriteBudget): Metadata? {
        val entry = XpnSourceMetadata.singleEntry(entries, entryName(programEntry)) ?: return null
        val bytes = zip.getInputStream(entry).use { XpnSourceMetadata.readBudgeted(it, MAX_BYTES, "round-robin metadata for $programEntry", budget) }
        try {
            val root = com.snipsnap.json.Json.parse(bytes.toString(Charsets.UTF_8)).obj()
            require(root.keys - "kit" == setOf("format", "version", "programEntry", "programSha256", "pads")) { "invalid round-robin metadata fields" }
            require(root.getValue("format").str() == FORMAT && root.getValue("version").int() == 1) { "unsupported round-robin metadata" }
            require(root.getValue("programEntry").str() == programEntry && root.getValue("programSha256").str() == sha256(programBytes)) {
                "round-robin metadata does not match program $programEntry"
            }
            val kit = root["kit"]?.obj()?.let { k ->
                require(k.keys == setOf("amount", "takes")) { "invalid round-robin kit fields" }
                KitRobin(k.getValue("amount").num().toFloat(), k.getValue("takes").int())
            }
            val raw = root.getValue("pads").arr()
            require(raw.size in 1..128) { "round-robin metadata must name 1..128 pads" }
            val pads = raw.map { value ->
                val p = value.obj()
                require(p.keys - "amount" == setOf("slot", "take", "sha256", "takes", "seed", "follows")) { "invalid round-robin pad fields" }
                val slot = p.getValue("slot").int()
                require(slot in 1..128) { "round-robin pad slot out of range" }
                val take = p.getValue("take").str()
                require(take.isNotBlank() && take.length <= 256 && '/' !in take && '\\' !in take) { "invalid round-robin take name" }
                val hash = p.getValue("sha256").str()
                require(hash.matches(Regex("[0-9a-f]{64}"))) { "invalid round-robin take hash" }
                Pad(slot, take, hash, RobinSettings(
                    amount = p["amount"]?.num()?.toFloat(),
                    takes = p.getValue("takes").int(),
                    seed = p.getValue("seed").int(),
                    followsKit = p.getValue("follows").bool(),
                ))
            }
            require(pads.map { it.slot }.distinct().size == pads.size) { "duplicate round-robin pad slot" }
            return Metadata(kit, pads)
        } catch (e: com.snipsnap.json.JsonException) {
            throw IllegalArgumentException("invalid round-robin metadata for $programEntry: ${e.message}", e)
        } catch (e: NoSuchElementException) {
            throw IllegalArgumentException("incomplete round-robin metadata for $programEntry: ${e.message}", e)
        }
    }

    /**
     * The sample hashes a backup binds for [pad]: what the importer lands, which for a split robin
     * pad is take one (the original) rather than the chain the take files replace.
     */
    fun landedHashes(pad: KitPad, kitDir: File, files: List<String>): List<String> =
        if (pad.isRobinChain && pad.velocityLayers.isEmpty()) listOf(sha256(RobinSplit.takeOne(pad, File(kitDir, pad.sampleFile))))
        else files.map { XpnSourceMetadata.sha256(SafePath.child(kitDir, it)) }

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
