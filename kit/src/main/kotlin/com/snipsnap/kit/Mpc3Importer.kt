package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import com.snipsnap.json.JsonValue
import com.snipsnap.mpc3.Mpc3Project
import java.io.File
import java.io.IOException

/**
 * Reads native MPC 3 content back into kit folders — the reverse loop:
 * kits the MPC itself saved become editable on the phone and
 * re-exportable in every format.
 *
 * Two doors: [import] takes a standalone drum track (`.xtd` with its
 * `_[TrackData]/` beside it); [importProject] takes a whole `.xpj` and
 * lands **every drum track inside it** as its own kit folder, samples
 * from the flat `_[ProjectData]/`, with non-drum tracks skipped and
 * named rather than silently dropped.
 *
 * Mirrors [XpnImporter]'s discipline: faithful, not clever. Levels, pans,
 * tunes, mute groups, trigger modes, velocity layers and per-pad colours
 * come through as the file declares them (clamped into model range); no
 * class is invented — imported pads stay [DrumClass.UNKNOWN] until someone
 * says otherwise. Missing samples are refused by name.
 */
object Mpc3Importer {

    data class ImportResult(
        val kit: Kit,
        val directory: File,
        /** The track name inside the container. */
        val trackName: String,
        /** Clips found in the track and kept in `groove.json`. */
        val grooveCount: Int = 0,
    )

    data class ProjectImportResult(
        val kits: List<ImportResult>,
        /** Track name → why it didn't become a kit. */
        val skipped: Map<String, String>,
    )

    /** A standalone drum track. Whole projects go through [importProject]. */
    fun import(xtdFile: File, destRoot: File, overwrite: Boolean = false): ImportResult {
        require(xtdFile.isFile) { "no such file: $xtdFile" }
        val project = Mpc3Project.read(xtdFile)
        require(!project.isProject) {
            "'${xtdFile.name}' is a whole project - use importProject (the CLI does this automatically)"
        }
        val track = project.tracks.firstOrNull()
            ?: throw IllegalArgumentException("'${xtdFile.name}' holds no track")
        val dataDir = File(xtdFile.parentFile, "${xtdFile.nameWithoutExtension}_[TrackData]")
        return importTrack(track, dataDir, destRoot, overwrite, xtdFile.name)
    }

    /** Every drum track in an `.xpj`, each as its own kit folder. */
    fun importProject(xpjFile: File, destRoot: File, overwrite: Boolean = false): ProjectImportResult {
        require(xpjFile.isFile) { "no such file: $xpjFile" }
        val project = Mpc3Project.read(xpjFile)
        require(project.isProject) { "'${xpjFile.name}' is not a project - use import for tracks" }
        require(project.tracks.isNotEmpty()) { "'${xpjFile.name}' holds no tracks" }

        val dataDir = File(xpjFile.parentFile, "${xpjFile.nameWithoutExtension}_[ProjectData]")
        val kits = mutableListOf<ImportResult>()
        val skipped = linkedMapOf<String, String>()
        val usedNames = HashSet<String>()

        for (track in project.tracks) {
            val trackName = (track["name"] as? JsonValue.Str)?.value ?: "(unnamed)"
            try {
                kits += importTrack(
                    track, dataDir, destRoot, overwrite, xpjFile.name,
                    uniqueName = { base ->
                        var candidate = base
                        var n = 2
                        while (!usedNames.add(candidate.lowercase())) candidate = "$base $n".also { n++ }
                        candidate
                    },
                )
            } catch (e: IllegalArgumentException) {
                skipped[trackName] = e.message ?: "unreadable"
            }
        }
        require(kits.isNotEmpty()) {
            "'${xpjFile.name}' yielded no kits: " +
                skipped.entries.joinToString("; ") { "${it.key}: ${it.value}" }
        }
        return ProjectImportResult(kits, skipped)
    }

    // ---------- the shared track walk ----------

    private fun importTrack(
        track: Map<String, JsonValue>,
        dataDir: File,
        destRoot: File,
        overwrite: Boolean,
        sourceName: String,
        uniqueName: (String) -> String = { it },
    ): ImportResult {
        val trackName = (track["name"] as? JsonValue.Str)?.value ?: "(unnamed)"
        val program = (track["program"] as? JsonValue.Obj)?.entries
            ?: throw IllegalArgumentException("'$trackName' has no program")
        val type = (program["type"] as? JsonValue.Num)?.value?.toInt()
        require(type != 1) {
            "'$trackName' is a keygroup instrument - only drum tracks import as kits"
        }
        val drum = (program["drum"] as? JsonValue.Obj)?.entries
            ?: throw IllegalArgumentException("'$trackName' has no drum block")
        val instruments = (drum["instruments"] as? JsonValue.Arr)?.items.orEmpty()
        require(instruments.isNotEmpty()) { "'$trackName' has no instrument slots" }

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
            /** Shape fields, default-collapsed to null so re-exports stay byte-identical. */
            val attack: Float?,
            val decay: Float?,
            val cutoff: Float?,
            val resonance: Float?,
            val humanize: Float?,
        )

        fun shapeOrNull(v: Double?, default: Double): Float? {
            if (v == null || kotlin.math.abs(v - default) < 1e-4) return null
            return v.toFloat().coerceIn(0f, 1f)
        }

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
                        // Bare names are the rule; a nonconforming file's
                        // legit subpath is flattened, but a traversal is
                        // refused (see SafePath.basename) - untrusted input.
                        SafePath.basename(file),
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
                attack = shapeOrNull(ampField(inst, "Attack"), default = 0.0),
                decay = shapeOrNull(ampField(inst, "Decay"), default = 1.0),
                cutoff = shapeOrNull(filterField(inst, "filterCutoff"), default = 1.0),
                resonance = shapeOrNull(filterField(inst, "filterResonance"), default = 0.0),
                // humanize writes VolumeRandom = h * 0.2 on every layer;
                // the first filled layer's value inverts back to h.
                humanize = shapeOrNull(
                    (((inst["layersv"] as? JsonValue.Arr)?.items?.firstOrNull() as? JsonValue.Obj)
                        ?.entries?.get("VolumeRandom") as? JsonValue.Num)?.value?.let { it / 0.2 },
                    default = 0.0,
                ),
            )
        }
        require(parsed.isNotEmpty()) { "'$trackName' has no pads with samples" }

        // Sample names were made safe basenames at parse time; SafePath.child
        // is the enforced invariant that reads and writes stay in their folder.
        val referenced = parsed.flatMap { it.layers.map { l -> l.first } }.distinct()
        val missing = referenced.filter { !SafePath.child(dataDir, it).isFile }
        require(missing.isEmpty()) {
            "samples missing from ${dataDir.name}/: " + missing.joinToString(", ")
        }

        val kitName = uniqueName(
            trackName.takeIf { Names.isMpcSafe(it) } ?: Names.sanitizeStem(trackName),
        )
        val destDir = File(destRoot, kitName)
        if (File(destDir, "kit.json").exists() && !overwrite) {
            throw IOException("kit already exists: $destDir (pass overwrite=true to replace it)")
        }
        destDir.mkdirs()
        for (file in referenced) {
            SafePath.child(dataDir, file).copyTo(SafePath.child(destDir, file), overwrite = true)
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
                attack = p.attack,
                decay = p.decay,
                cutoff = p.cutoff,
                resonance = p.resonance,
                humanize = p.humanize,
                source = mapOf("importedFrom" to sourceName),
                velocityLayers = if (p.layers.size < 2) emptyList() else {
                    p.layers.map { (file, velStart, velEnd) -> KitLayer(file, velStart, velEnd) }
                },
            )
        }

        val kit = Kit(kitName, pads)
        KitStore.save(kit, destDir)

        // Total recall: the clips the track carries come home too.
        val clips = parseClips(track)
        if (clips.isNotEmpty()) GrooveStore.save(destDir, clips)

        return ImportResult(kit, destDir, trackName, clips.size)
    }

    /** The track's `sharedClipMap` entries, note events only, empties dropped. */
    private fun parseClips(track: Map<String, JsonValue>): List<com.snipsnap.mpc3.Mpc3Clip> =
        ((track["sharedClipMap"] as? JsonValue.Arr)?.items.orEmpty()).mapNotNull { entryJson ->
            val entry = (entryJson as? JsonValue.Obj)?.entries ?: return@mapNotNull null
            val value = (entry["value"] as? JsonValue.Obj)?.entries ?: return@mapNotNull null
            val events = ((value["eventList"] as? JsonValue.Obj)?.entries
                ?.get("events") as? JsonValue.Arr)?.items.orEmpty()
            val notes = events.mapNotNull { eventJson ->
                val event = (eventJson as? JsonValue.Obj)?.entries ?: return@mapNotNull null
                val note = (event["note"] as? JsonValue.Obj)?.entries ?: return@mapNotNull null
                com.snipsnap.mpc3.Mpc3Note(
                    note = (note["note"] as? JsonValue.Num)?.value?.toInt()?.coerceIn(0, 127)
                        ?: return@mapNotNull null,
                    timePulses = ((event["time"] as? JsonValue.Num)?.value?.toLong() ?: 0L)
                        .coerceAtLeast(0),
                    velocity = ((note["velocity"] as? JsonValue.Num)?.value?.toFloat() ?: 0.75f)
                        .coerceIn(0f, 1f),
                    lengthPulses = ((note["length"] as? JsonValue.Num)?.value?.toLong() ?: 240L)
                        .coerceAtLeast(1),
                )
            }
            if (notes.isEmpty()) return@mapNotNull null
            val endPulses = (value["endPulses"] as? JsonValue.Num)?.value?.toLong()
            val bars = (endPulses?.let { (it / com.snipsnap.mpc3.Mpc3Clip.PULSES_PER_BAR).toInt() }
                ?: ((notes.maxOf { it.timePulses } / com.snipsnap.mpc3.Mpc3Clip.PULSES_PER_BAR).toInt() + 1))
                .coerceIn(1, 64)
            val kept = notes.filter { it.timePulses < bars * com.snipsnap.mpc3.Mpc3Clip.PULSES_PER_BAR }
            if (kept.isEmpty()) return@mapNotNull null
            com.snipsnap.mpc3.Mpc3Clip(
                name = (value["name"] as? JsonValue.Str)?.value?.takeIf { it.isNotBlank() } ?: "Clip",
                bars = bars,
                notes = kept,
            )
        }

    /** `synthSection.ampEnvelope.<name>.value0` on one instrument, or null. */
    private fun ampField(inst: Map<String, JsonValue>, name: String): Double? {
        val synth = (inst["synthSection"] as? JsonValue.Obj)?.entries ?: return null
        val env = (synth["ampEnvelope"] as? JsonValue.Obj)?.entries ?: return null
        val holder = (env[name] as? JsonValue.Obj)?.entries ?: return null
        return (holder["value0"] as? JsonValue.Num)?.value
    }

    /** `synthSection.filterData.value0.<name>` on one instrument, or null. */
    private fun filterField(inst: Map<String, JsonValue>, name: String): Double? {
        val synth = (inst["synthSection"] as? JsonValue.Obj)?.entries ?: return null
        val data = (synth["filterData"] as? JsonValue.Obj)?.entries ?: return null
        val slot = (data["value0"] as? JsonValue.Obj)?.entries ?: return null
        return (slot[name] as? JsonValue.Num)?.value
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
