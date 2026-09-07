package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import com.snipsnap.xpm.WavInfo
import java.io.File
import java.io.IOException

/**
 * The receive half of the SFZ door (JJ3): an `.sfz` instrument becomes a
 * kit folder. Regions land on pads by key (36 = A01, the same map the
 * writer uses), `lovel`/`hivel` ranges become velocity layers, and the
 * writer's own chains and grids come back as chains and grids — the
 * `seq_position`/`offset` regions reassembled into [ChainInfo], zones
 * and all.
 *
 * Foreign files get the format's own courtesy: unknown opcodes are
 * ignored (that IS the sfz rule), opcode inheritance from
 * `<global>`/`<master>`/`<group>` is honoured, and anything that can't
 * become a pad — a multi-key region, a missing or non-WAV sample, a
 * chain that doesn't reassemble — is named and skipped, never fatal.
 * Samples are copied into the kit folder under sanitized stems;
 * provenance stamps `importedFrom`.
 */
object SfzImporter {

    data class Result(
        val kit: Kit,
        val directory: File,
        val skipped: List<Pair<String, String>>,
    )

    /** A text sidecar should never be this big; past it we refuse to parse. */
    const val MAX_TEXT_BYTES = 4_000_000L

    private val TOKEN = Regex("<([a-zA-Z0-9_]+)>|([a-zA-Z0-9_]+)=")

    fun import(sfzFile: File, destRoot: File, overwrite: Boolean = false): Result {
        if (!sfzFile.isFile) throw IOException("no such file: $sfzFile")
        if (sfzFile.length() > MAX_TEXT_BYTES) {
            throw IOException("${sfzFile.name} is ${sfzFile.length()} bytes - too large for an .sfz")
        }
        val (defaultPath, regions) = parse(sfzFile.readText(Charsets.UTF_8))
        if (regions.isEmpty()) throw IOException("${sfzFile.name} has no regions - nothing to import")

        val kitName = Names.sanitizeStem(sfzFile.nameWithoutExtension)
        val kitDir = File(destRoot, kitName)
        if (File(kitDir, KitStore.FILE_NAME).exists() && !overwrite) {
            throw IOException("kit already exists: $kitDir (pass overwrite to replace it)")
        }
        kitDir.mkdirs()

        val skipped = mutableListOf<Pair<String, String>>()
        val bySlot = LinkedHashMap<Int, MutableList<Map<String, String>>>()
        for (r in regions) {
            val slot = slotOf(r)
            if (slot == null) {
                skipped += (r["sample"] ?: "(no sample)") to "no single key in pad range (36..163)"
                continue
            }
            bySlot.getOrPut(slot) { mutableListOf() } += r
        }

        val used = HashSet<String>()
        val copied = HashMap<String, Pair<String, Long>>() // resolved path -> stem.wav to frames
        fun bringSample(r: Map<String, String>): Pair<String, Long>? {
            val ref = r["sample"] ?: return null
            val resolved = resolve(sfzFile.parentFile, defaultPath, ref) ?: return null
            copied[resolved.path]?.let { return it }
            val frames = try {
                WavInfo.read(resolved).frameCount
            } catch (e: Exception) {
                return null
            }
            var stem = Names.sanitizeStem(resolved.nameWithoutExtension)
            var n = 2
            while (!used.add(stem.lowercase())) stem = Names.sanitizeStem(resolved.nameWithoutExtension) + "_${n++}"
            resolved.copyTo(File(kitDir, "$stem.wav"), overwrite = true)
            return ("$stem.wav" to frames).also { copied[resolved.path] = it }
        }

        val pads = mutableListOf<KitPad>()
        for ((slot, rs) in bySlot) {
            val pad = try {
                buildPad(slot, rs, ::bringSample, sfzFile.name, skipped)
            } catch (e: Exception) {
                skipped += "pad $slot" to (e.message ?: "unreadable")
                null
            }
            pad?.let { pads += it }
        }
        if (pads.isEmpty()) {
            throw IOException(
                "nothing imported from ${sfzFile.name}: " +
                    skipped.joinToString("; ") { "'${it.first}' - ${it.second}" },
            )
        }
        val kit = Kit(kitName, pads)
        KitStore.save(kit, kitDir)
        return Result(kit, kitDir, skipped)
    }

    // ---- pad assembly -----------------------------------------------------

    private fun buildPad(
        slot: Int,
        rs: List<Map<String, String>>,
        bringSample: (Map<String, String>) -> Pair<String, Long>?,
        sourceName: String,
        skipped: MutableList<Pair<String, String>>,
    ): KitPad? {
        val head = rs.first()
        val brought = bringSample(head)
        if (brought == null) {
            skipped += (head["sample"] ?: "pad $slot") to "sample missing or not a readable WAV"
            return null
        }
        val (mainFile, mainFrames) = brought

        // The chain shapes: any region carrying seq_position + offset.
        val seq = rs.filter { it["seq_position"] != null && it["offset"] != null }
        var chainFailed = false
        val chain: ChainInfo? = if (seq.isNotEmpty() && seq.size == rs.size) {
            try {
                reassembleChain(seq, mainFrames)
            } catch (e: Exception) {
                skipped += "pad $slot" to "chain didn't reassemble (${e.message}) - kept take one"
                chainFailed = true
                null
            }
        } else {
            null
        }

        val base = KitPad(
            slot = slot,
            sampleFile = mainFile,
            drumClass = DrumClass.UNKNOWN,
            level = head["volume"]?.toFloatOrNull()
                ?.let { (0.707946 * Math.pow(10.0, it / 20.0)).toFloat().coerceIn(0f, 1f) }
                ?: 0.707946f,
            pan = head["pan"]?.toFloatOrNull()?.let { (0.5f + it / 200f).coerceIn(0f, 1f) } ?: 0.5f,
            tuneCoarse = head["transpose"]?.toFloatOrNull()?.toInt()?.coerceIn(-36, 36) ?: 0,
            tuneFine = head["tune"]?.toFloatOrNull()?.toInt()?.coerceIn(-100, 100) ?: 0,
            muteGroup = head["group"]?.toFloatOrNull()?.toInt()?.takeIf {
                it in 1..32 && head["off_by"] == head["group"]
            } ?: 0,
            oneShot = head["loop_mode"] != "no_loop",
            attack = head["ampeg_attack"]?.toFloatOrNull()?.let { (it / 0.4f).coerceIn(0f, 1f) },
            decay = head["ampeg_decay"]?.toFloatOrNull()?.let {
                (it * 44_100f / mainFrames).coerceIn(0f, 1f)
            },
            cutoff = head["cutoff"]?.toFloatOrNull()?.let {
                (Math.log10(it / 20.0) / 3.0).toFloat().coerceIn(0f, 1f)
            },
            resonance = head["resonance"]?.toFloatOrNull()?.let { (it / 12f).coerceIn(0f, 1f) },
            humanize = head["pitch_random"]?.toFloatOrNull()?.let { (it / 5f).coerceIn(0f, 1f) },
            source = mapOf("importedFrom" to sourceName),
            chain = chain,
        )
        if (chain != null || chainFailed || rs.size == 1) return base

        // Several plain regions on one key: velocity layers, soft first.
        val zones = rs.mapNotNull { r ->
            val b = bringSample(r) ?: run {
                skipped += (r["sample"] ?: "pad $slot") to "layer sample missing or not WAV"
                return@mapNotNull null
            }
            KitLayer(
                sampleFile = b.first,
                velStart = r["lovel"]?.toIntOrNull()?.coerceIn(0, 127) ?: 0,
                velEnd = r["hivel"]?.toIntOrNull()?.coerceIn(0, 127) ?: 127,
            )
        }.sortedBy { it.velStart }
        if (zones.size <= 1) return base
        val kept = zones.take(4)
        if (kept.size < zones.size) skipped += "pad $slot" to "more than 4 velocity layers - kept the softest 4"
        return base.copy(sampleFile = kept.last().sampleFile, velocityLayers = kept)
    }

    /**
     * The writer's chain/grid regions back into a [ChainInfo]: slice
     * boundaries are the distinct region starts plus each region's
     * end + 1 (the next take's doorstep), the chain's own tail excluded;
     * zones come from distinct velocity ranges when there are several.
     */
    private fun reassembleChain(seq: List<Map<String, String>>, frames: Long): ChainInfo {
        val starts = seq.mapNotNull { it["offset"]?.toLongOrNull() }
        val ends = seq.mapNotNull { it["end"]?.toLongOrNull()?.plus(1) }
        val boundaries = (starts + ends).distinct().sorted().filter { it < frames }
        require(boundaries.size >= 2 && boundaries.firstOrNull() == 0L) { "offsets don't start a chain at 0" }
        val byRange = seq.groupBy {
            (it["lovel"]?.toIntOrNull() ?: 0) to (it["hivel"]?.toIntOrNull() ?: 127)
        }
        val cycleOf = { rs: List<Map<String, String>> ->
            rs.mapNotNull { it["seq_length"]?.toIntOrNull() }.distinct().singleOrNull()
                ?: error("mixed seq_length in one range")
        }
        if (byRange.size == 1) {
            return ChainInfo(boundaries, cycle = cycleOf(seq))
        }
        val zones = byRange.entries.sortedBy { it.key.first }.map { (range, rs) ->
            val firstOffset = rs.mapNotNull { it["offset"]?.toLongOrNull() }.min()
            ChainZone(
                velStart = range.first,
                velEnd = range.second,
                baseSlice = boundaries.indexOf(firstOffset).also { require(it >= 0) { "offset off the grid" } },
                cycle = cycleOf(rs),
            )
        }
        return ChainInfo(boundaries, cycle = zones.first().cycle, zones = zones)
    }

    // ---- parsing ----------------------------------------------------------

    /** Header scopes + opcode inheritance; returns default_path and merged regions. */
    internal fun parse(text: String): Pair<String, List<Map<String, String>>> {
        var defaultPath = ""
        val global = mutableMapOf<String, String>()
        val master = mutableMapOf<String, String>()
        val group = mutableMapOf<String, String>()
        val ignore = mutableMapOf<String, String>()
        var current: MutableMap<String, String>? = null
        var inControl = false
        val regions = mutableListOf<MutableMap<String, String>>()

        for (raw in text.lineSequence()) {
            val line = raw.substringBefore("//")
            val matches = TOKEN.findAll(line).toList()
            for ((i, m) in matches.withIndex()) {
                if (m.groupValues[1].isNotEmpty()) {
                    when (m.groupValues[1].lowercase()) {
                        "control" -> { inControl = true; current = ignore }
                        "global" -> {
                            inControl = false
                            global.clear(); master.clear(); group.clear()
                            current = global
                        }
                        "master" -> { inControl = false; master.clear(); group.clear(); current = master }
                        "group" -> { inControl = false; group.clear(); current = group }
                        "region" -> {
                            inControl = false
                            val merged = (global + master + group).toMutableMap()
                            regions += merged
                            current = merged
                        }
                        else -> { inControl = false; current = ignore }
                    }
                } else {
                    val key = m.groupValues[2].lowercase()
                    // The sfz quirk: a value (sample paths especially) runs
                    // to the next token on the line, spaces included.
                    val end = matches.getOrNull(i + 1)?.range?.first ?: line.length
                    val value = line.substring(m.range.last + 1, end).trim()
                    if (inControl) {
                        if (key == "default_path") defaultPath = value
                    } else {
                        current?.put(key, value)
                    }
                }
            }
        }
        return defaultPath to regions
    }

    private fun slotOf(r: Map<String, String>): Int? {
        val key = r["key"]?.toIntOrNull()
            ?: r["lokey"]?.toIntOrNull()?.takeIf { it == r["hikey"]?.toIntOrNull() }
            ?: return null
        val slot = key - SfzWriter.BASE_KEY + 1
        return slot.takeIf { it in 1..128 }
    }

    /** default_path + ref against the .sfz's own folder — and never outside it. */
    private fun resolve(sfzDir: File?, defaultPath: String, ref: String): File? {
        val root = sfzDir ?: return null
        val rel = (defaultPath + ref).replace('\\', '/')
        if (rel.startsWith("/") || rel.split('/').any { it == ".." }) return null
        val f = File(root, rel)
        return f.takeIf { it.isFile }
    }
}
