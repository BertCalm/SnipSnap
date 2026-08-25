package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import com.snipsnap.json.JsonValue
import com.snipsnap.mpc3.Mpc3Project
import java.io.File
import java.io.IOException

/**
 * Reads a native MPC 3 drum track back into a kit folder — the reverse
 * loop: kits the MPC itself saved (or any commercial `.xtd`) become
 * editable on the phone and re-exportable in every format.
 *
 * Mirrors [XpnImporter]'s discipline: faithful, not clever. Levels, pans,
 * tunes, mute groups, trigger modes, velocity layers and per-pad colours
 * come through as the file declares them (clamped into model range); no
 * class is invented — imported pads stay [DrumClass.UNKNOWN] until someone
 * says otherwise. Samples are expected in the sibling `_[TrackData]/`
 * folder, the pair every real `.xtd` ships as; whatever is missing is
 * refused by name, not silently dropped.
 */
object Mpc3Importer {

    data class ImportResult(
        val kit: Kit,
        val directory: File,
        /** The track name inside the container. */
        val trackName: String,
    )

    fun import(xtdFile: File, destRoot: File, overwrite: Boolean = false): ImportResult {
        require(xtdFile.isFile) { "no such file: $xtdFile" }
        val project = Mpc3Project.read(xtdFile)
        require(!project.isProject) {
            "'${xtdFile.name}' is a whole project - import the kit's .xtd (project import is a later step)"
        }
        val track = project.tracks.firstOrNull()
            ?: throw IllegalArgumentException("'${xtdFile.name}' holds no track")

        val trackName = (track["name"] as? JsonValue.Str)?.value
            ?: xtdFile.nameWithoutExtension
        val program = (track["program"] as? JsonValue.Obj)?.entries
            ?: throw IllegalArgumentException("'${xtdFile.name}' has no program")
        val type = (program["type"] as? JsonValue.Num)?.value?.toInt()
        require(type != 1) {
            "'${xtdFile.name}' is a keygroup instrument - only drum tracks import as kits"
        }
        val drum = (program["drum"] as? JsonValue.Obj)?.entries
            ?: throw IllegalArgumentException("'${xtdFile.name}' has no drum block")
        val instruments = (drum["instruments"] as? JsonValue.Arr)?.items.orEmpty()
        require(instruments.isNotEmpty()) { "'${xtdFile.name}' has no instrument slots" }

        // Per-pad colours: packed 0xRRGGBB unless the Universal switch is on.
        val padColours = padColours(program)

        data class ParsedPad(
            val slot: Int,
            val layers: List<Triple<String, Int, Int>>, // file, velStart, velEnd — soft first
            val level: Float,
            val pan: Float,
            val tuneCoarse: Int,
            val tuneFine: Int,
            val muteGroup: Int,
            val oneShot: Boolean,
        )

        val parsed = instruments.mapIndexedNotNull { index, instJson ->
            val inst = (instJson as? JsonValue.Obj)?.entries ?: return@mapIndexedNotNull null
            // MPC 3 layers descend from loudest at index 0; kits ascend soft-first.
            val layers = ((inst["layersv"] as? JsonValue.Arr)?.items.orEmpty())
                .mapNotNull { layerJson ->
                    val layer = (layerJson as? JsonValue.Obj)?.entries ?: return@mapNotNull null
                    val file = (layer["sampleFile"] as? JsonValue.Str)?.value
                        ?.takeIf { it.isNotBlank() }
                        ?: (layer["sampleName"] as? JsonValue.Str)?.value
                            ?.takeIf { it.isNotBlank() }?.plus(".wav")
                        ?: return@mapNotNull null
                    Triple(
                        File(file.replace('\\', '/')).name,
                        (layer["velocityStart"] as? JsonValue.Num)?.value?.toInt() ?: 0,
                        (layer["velocityEnd"] as? JsonValue.Num)?.value?.toInt() ?: 127,
                    )
                }
                .asReversed()
            if (layers.isEmpty()) return@mapIndexedNotNull null
            if (index >= 128) return@mapIndexedNotNull null

            val mixable = (inst["mixable"] as? JsonValue.Obj)?.entries
            ParsedPad(
                slot = index + 1, // instruments are 0-based; hardware-verified
                layers = layers,
                level = ((mixable?.get("volume") as? JsonValue.Num)?.value?.toFloat() ?: 0.707946f)
                    .coerceIn(0f, 1f),
                pan = ((mixable?.get("pan") as? JsonValue.Num)?.value?.toFloat() ?: 0.5f)
                    .coerceIn(0f, 1f),
                tuneCoarse = ((inst["coarseTune"] as? JsonValue.Num)?.value?.toInt() ?: 0)
                    .coerceIn(-36, 36),
                tuneFine = ((inst["fineTune"] as? JsonValue.Num)?.value?.toInt() ?: 0)
                    .coerceIn(-100, 100),
                muteGroup = ((inst["whichMuteGroup"] as? JsonValue.Num)?.value?.toInt() ?: 0)
                    .coerceIn(0, 32),
                // 0 = One Shot; 1 (note-off) and 2 (note-on) both gate.
                oneShot = ((inst["triggerMode"] as? JsonValue.Num)?.value?.toInt() ?: 0) == 0,
            )
        }
        require(parsed.isNotEmpty()) { "'${xtdFile.name}' has no pads with samples" }

        // Samples live flat in the sibling _[TrackData]/ folder.
        val dataDir = File(xtdFile.parentFile, "${xtdFile.nameWithoutExtension}_[TrackData]")
        val referenced = parsed.flatMap { it.layers.map { l -> l.first } }.distinct()
        val missing = referenced.filter { !File(dataDir, it).isFile }
        require(missing.isEmpty()) {
            "samples missing from ${dataDir.name}/: " + missing.joinToString(", ")
        }

        val kitName = trackName.takeIf { Names.isMpcSafe(it) } ?: Names.sanitizeStem(trackName)
        val destDir = File(destRoot, kitName)
        if (File(destDir, "kit.json").exists() && !overwrite) {
            throw IOException("kit already exists: $destDir (pass overwrite=true to replace it)")
        }
        destDir.mkdirs()
        for (file in referenced) {
            File(dataDir, file).copyTo(File(destDir, file), overwrite = true)
        }

        val pads = parsed.map { p ->
            val main = p.layers.last()
            KitPad(
                slot = p.slot,
                sampleFile = main.first,
                displayName = main.first.substringBeforeLast('.'),
                drumClass = DrumClass.UNKNOWN,
                colorHex = padColours[p.slot - 1],
                level = p.level,
                pan = p.pan,
                tuneCoarse = p.tuneCoarse,
                tuneFine = p.tuneFine,
                muteGroup = p.muteGroup,
                oneShot = p.oneShot,
                source = mapOf("importedFrom" to xtdFile.name),
                velocityLayers = if (p.layers.size < 2) emptyList() else {
                    p.layers.map { (file, velStart, velEnd) -> KitLayer(file, velStart, velEnd) }
                },
            )
        }

        val kit = Kit(kitName, pads)
        KitStore.save(kit, destDir)
        return ImportResult(kit, destDir, trackName)
    }

    /** Slot-indexed (0-based) colour hex strings, or nulls when uncoloured. */
    private fun padColours(program: Map<String, JsonValue>): List<String?> {
        val pp = (program["programPads"] as? JsonValue.Obj)?.entries ?: return List(128) { null }
        val universal = ((pp["Universal"] as? JsonValue.Obj)?.entries?.get("value0")
            as? JsonValue.Bool)?.value ?: true
        if (universal) return List(128) { null }
        val pads = (pp["pads"] as? JsonValue.Obj)?.entries ?: return List(128) { null }
        return List(128) { n ->
            (pads["value$n"] as? JsonValue.Num)?.value?.toInt()
                ?.takeIf { it in 1..0xFFFFFF }
                ?.let { "#%06x".format(it) }
        }
    }
}
