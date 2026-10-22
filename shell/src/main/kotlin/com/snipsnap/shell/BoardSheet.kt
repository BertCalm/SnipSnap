package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.AtomicFile
import com.snipsnap.kit.KitLayer
import com.snipsnap.kit.KitPad
import com.snipsnap.synth.BoardRecipe
import com.snipsnap.synth.BoardType
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** Editable, printed compound boards. Baselines and one-step undo live outside the disposable bin. */
object BoardSheet {
    const val DEFAULT_AMOUNT = .7f
    data class Applied(val amount: Float, val type: BoardType = BoardType.WIDE)

    private val digestPattern = Regex("[0-9a-f]{64}")
    private data class AudioFile(val name: String, val hash: String)
    private data class Inventory(val main: String, val layers: List<KitLayer>, val files: List<AudioFile>)
    private data class Snapshot(val id: String, val inventory: Inventory, val recipe: JsonValue.Obj?)
    private data class Wrapper(val board: BoardRecipe, val source: String, val inventory: Inventory)

    /** Display readers tolerate damaged or future recipes; editing doors refuse them. */
    fun read(recipe: JsonValue.Obj?): Applied? = try {
        wrapper(recipe)?.let { Applied(it.board.amount, it.board.type) }
    } catch (_: Exception) { null }

    /** AMT zero returns the unchanged pre-board main sample, including its original channel count. */
    fun preview(model: KitBuilderModel, slot: Int, amount: Float, type: BoardType = BoardType.WIDE): Snip = synchronized(model) {
        val board = BoardRecipe(type = type, amount = amount)
        val pad = pad(model, slot)
        val applied = wrapper(pad.recipe)
        val original = if (applied == null) {
            val inventory = liveInventory(model, pad)
            checkedAudio(liveFile(model, inventory.main))
        } else {
            requireCurrent(model, pad, applied.inventory)
            val source = loadSnapshot(model, applied.source)
            checkedAudio(backupFile(model, source.inventory.files.single { it.name == source.inventory.main }.hash))
        }
        board.process(original)
    }

    /** Every KEEP re-renders every velocity zone from the immutable pre-board baseline. */
    fun apply(model: KitBuilderModel, slot: Int, amount: Float, type: BoardType = BoardType.WIDE): KitPad = synchronized(model) {
        val board = BoardRecipe(type = type, amount = amount)
        val old = pad(model, slot)
        val applied = wrapper(old.recipe)
        if (amount == 0f) return@synchronized if (applied == null) old else remove(model, slot)
        if (applied != null) requireCurrent(model, old, applied.inventory)
        val savedSource = applied?.let { loadSnapshot(model, it.source) }
        if (applied?.board == board) return@synchronized old
        val before = capture(model, old)
        val source = savedSource ?: before

        // Render and validate all zones before creating any live WAV or changing the pad.
        val rendered = source.inventory.files.associate { input ->
            val result = board.process(checkedAudio(backupFile(model, input.hash)))
            validateAudio(result)
            val bytes = ByteArrayOutputStream().also { WavWriter.write(it, result, WavWriter.BitDepth.PCM_24) }.toByteArray()
            input.name to bytes
        }
        val names = rendered.mapValues { (_, bytes) ->
            val hash = sha(bytes)
            val name = freshOutputName(model, slot, hash, type)
            writeImmutable(liveFile(model, name), bytes, hash)
            name
        }
        val inventory = Inventory(
            names.getValue(source.inventory.main),
            source.inventory.layers.map { it.copy(sampleFile = names.getValue(it.sampleFile)) },
            source.inventory.files.map { AudioFile(names.getValue(it.name), sha(rendered.getValue(it.name))) }.distinct(),
        )
        val recipe = obj(
            "boardPad" to num(1), "board" to board.toJsonValue(), "source" to str(source.id),
            "inventory" to inventoryJson(inventory),
        )
        val next = old.copy(sampleFile = inventory.main, velocityLayers = inventory.layers, recipe = recipe)
        commit(model, old, next, before)
    }

    /** Restore original audio and the opaque original recipe, preserving today's pad controls. */
    fun remove(model: KitBuilderModel, slot: Int): KitPad = synchronized(model) {
        val old = pad(model, slot)
        val applied = wrapper(old.recipe) ?: return@synchronized old
        requireCurrent(model, old, applied.inventory)
        val source = loadSnapshot(model, applied.source)
        val before = capture(model, old)
        restoreFiles(model, source.inventory)
        val next = old.copy(sampleFile = source.inventory.main, velocityLayers = source.inventory.layers, recipe = source.recipe)
        commit(model, old, next, before)
    }

    /** Undo the previous KEEP or REMOVE, but never cross an unrelated audio/recipe edit. */
    fun undo(model: KitBuilderModel, slot: Int): KitPad = synchronized(model) {
        val old = pad(model, slot)
        val (before, expected) = undoSnapshots(model, slot)
        require(old.recipe == expected.recipe) { "Board undo no longer matches this pad's recipe" }
        requireCurrent(model, old, expected.inventory)
        restoreFiles(model, before.inventory)
        val next = old.copy(sampleFile = before.inventory.main, velocityLayers = before.inventory.layers, recipe = before.recipe)
        requireCurrent(model, old, expected.inventory)
        val result = model.replaceBoardPad(slot, old, next)
        undoFile(model, slot).delete()
        result
    }

    fun canUndo(model: KitBuilderModel, slot: Int): Boolean = synchronized(model) {
        try {
            val old = pad(model, slot)
            val (before, expected) = undoSnapshots(model, slot)
            require(old.recipe == expected.recipe)
            requireCurrent(model, old, expected.inventory)
            requireRestoreSafe(model, before.inventory)
            true
        } catch (_: Exception) { false }
    }

    /**
     * Carry an active board into another kit after its current WAVs have been copied.
     * The immutable baseline travels too, with destination-safe restore names. Source
     * recipes stay opaque and exact; this new pad deliberately carries no undo history.
     */
    fun importPad(sourceDir: File, targetDir: File, original: KitPad, copied: KitPad): KitPad {
        val applied = wrapper(original.recipe) ?: return copied
        require(original.chain == null && copied.chain == null) { "Board cannot carry a round-robin chain" }
        require(original.velocityLayers.map { it.velStart to it.velEnd } == copied.velocityLayers.map { it.velStart to it.velEnd }) { "The board's copied velocity layers do not match their source" }
        requireCurrent(sourceDir, original, applied.inventory)
        val baseline = loadSnapshot(sourceDir, applied.source)
        val pairs = listOf(original.sampleFile to copied.sampleFile) + original.velocityLayers.zip(copied.velocityLayers).map { (a, b) -> a.sampleFile to b.sampleFile }
        require(pairs.groupBy { it.first }.values.all { group -> group.map { it.second }.distinct().size == 1 }) { "The board's copied samples have an inconsistent mapping" }
        val names = pairs.toMap()
        val inventory = Inventory(copied.sampleFile, copied.velocityLayers, applied.inventory.files.map { AudioFile(names.getValue(it.name), it.hash) }.distinct())
        requireCurrent(targetDir, copied, inventory)
        val backups = baseline.inventory.files.associate { entry ->
            entry.hash to backupFile(sourceDir, entry.hash).readBytes().also { require(sha(it) == entry.hash) { "The board's saved audio changed during copy" } }
        }
        // Full content digests avoid collisions between several imported baselines,
        // including baselines whose restore WAVs do not yet exist in the live folder.
        val sourceNames = baseline.inventory.files.associate { it.name to freshSourceName(targetDir, it.hash) }
        val sourceInventory = Inventory(
            sourceNames.getValue(baseline.inventory.main),
            baseline.inventory.layers.map { it.copy(sampleFile = sourceNames.getValue(it.sampleFile)) },
            baseline.inventory.files.map { AudioFile(sourceNames.getValue(it.name), it.hash) }.distinct(),
        )
        for ((hash, bytes) in backups) writeImmutable(backupFile(targetDir, hash), bytes, hash)
        val source = saveSnapshot(targetDir, sourceInventory, baseline.recipe)
        val result = copied.copy(recipe = obj(
            "boardPad" to num(1), "board" to applied.board.toJsonValue(), "source" to str(source.id),
            "inventory" to inventoryJson(inventory),
        ))
        // A copy is a new editing history, even if its target slot had an old sidecar.
        val targetUndo = safeChild(safeChild(targetDir, ".boards"), "undo-pad-${copied.slot}.json")
        require(!targetUndo.exists() || targetUndo.delete()) { "Could not reset copied board undo history" }
        return result
    }

    private fun pad(model: KitBuilderModel, slot: Int): KitPad {
        val pad = model.pad(slot) ?: throw IllegalArgumentException("no pad on slot $slot")
        require(pad.chain == null) { "Board cannot change a round-robin chain; undo the chain first" }
        return pad
    }

    private fun wrapper(recipe: JsonValue.Obj?): Wrapper? {
        if (recipe == null || "boardPad" !in recipe.entries) return null
        exactKeys(recipe, setOf("boardPad", "board", "source", "inventory"))
        require(recipe.entries.getValue("boardPad") == num(1)) { "Unsupported board pad recipe version" }
        val board = BoardRecipe.fromJsonValue(recipe.entries.getValue("board"))
        require(board.amount > 0f) { "Invalid stored board amount" }
        val source = hash(recipe.entries.getValue("source"))
        return Wrapper(board, source, parseInventory(recipe.entries.getValue("inventory")))
    }

    private fun commit(model: KitBuilderModel, old: KitPad, next: KitPad, before: Snapshot): KitPad {
        val expected = capture(model, next)
        requireCurrent(model, old, before.inventory)
        require(model.pad(old.slot) == old) { "The pad changed while the board was rendering; try again" }
        AtomicFile.writeText(undoFile(model, old.slot), Json.write(obj(
            "boardUndo" to num(1), "before" to str(before.id), "expected" to str(expected.id),
        )))
        return model.replaceBoardPad(old.slot, old, next)
    }

    private fun capture(model: KitBuilderModel, pad: KitPad): Snapshot {
        val inventory = liveInventory(model, pad)
        for (entry in inventory.files) {
            val bytes = liveFile(model, entry.name).readBytes()
            require(sha(bytes) == entry.hash) { "Pad audio changed while saving the board's source" }
            writeImmutable(backupFile(model, entry.hash), bytes, entry.hash)
        }
        return saveSnapshot(model.kitDir, inventory, pad.recipe)
    }

    private fun saveSnapshot(root: File, inventory: Inventory, recipe: JsonValue.Obj?): Snapshot {
        val value = obj("boardState" to num(1), "inventory" to inventoryJson(inventory), "recipe" to (recipe ?: JsonValue.Null))
        val bytes = Json.write(value).toByteArray(Charsets.UTF_8)
        val id = sha(bytes)
        writeImmutable(stateFile(root, id), bytes, id)
        return Snapshot(id, inventory, recipe)
    }

    private fun loadSnapshot(model: KitBuilderModel, id: String): Snapshot = loadSnapshot(model.kitDir, id)
    private fun loadSnapshot(root: File, id: String): Snapshot {
        require(digestPattern.matches(id)) { "Invalid board source reference" }
        val file = stateFile(root, id)
        require(file.isFile) { "The board's saved source is missing; restore the full kit folder from backup" }
        val bytes = file.readBytes()
        require(sha(bytes) == id) { "The board's saved source is damaged" }
        val value = Json.parse(bytes.toString(Charsets.UTF_8)) as? JsonValue.Obj
            ?: throw IllegalArgumentException("Invalid board source snapshot")
        exactKeys(value, setOf("boardState", "inventory", "recipe"))
        require(value.entries.getValue("boardState") == num(1)) { "Unsupported board source version" }
        val inventory = parseInventory(value.entries.getValue("inventory"))
        val recipe = when (val r = value.entries.getValue("recipe")) {
            JsonValue.Null -> null
            is JsonValue.Obj -> r
            else -> throw IllegalArgumentException("Invalid saved pad recipe")
        }
        for (entry in inventory.files) {
            val backup = backupFile(root, entry.hash)
            require(backup.isFile && sha(backup.readBytes()) == entry.hash) { "The board's saved audio is missing or damaged; restore the full kit folder from backup" }
            checkedAudio(backup)
        }
        return Snapshot(id, inventory, recipe)
    }

    private fun undoSnapshots(model: KitBuilderModel, slot: Int): Pair<Snapshot, Snapshot> {
        val file = undoFile(model, slot)
        require(file.isFile) { "No board edit to undo on this pad" }
        val value = Json.parse(file.readText()) as? JsonValue.Obj ?: throw IllegalArgumentException("Invalid board undo record")
        exactKeys(value, setOf("boardUndo", "before", "expected"))
        require(value.entries.getValue("boardUndo") == num(1)) { "Unsupported board undo version" }
        return loadSnapshot(model, hash(value.entries.getValue("before"))) to loadSnapshot(model, hash(value.entries.getValue("expected")))
    }

    private fun liveInventory(model: KitBuilderModel, pad: KitPad): Inventory {
        val files = (listOf(pad.sampleFile) + pad.velocityLayers.map { it.sampleFile }).distinct().map { name ->
            val file = liveFile(model, name)
            require(file.isFile) { "Pad audio is missing: $name" }
            checkedAudio(file)
            AudioFile(name, sha(file.readBytes()))
        }
        return Inventory(pad.sampleFile, pad.velocityLayers, files)
    }

    private fun requireCurrent(model: KitBuilderModel, pad: KitPad, inventory: Inventory) = requireCurrent(model.kitDir, pad, inventory)
    private fun requireCurrent(root: File, pad: KitPad, inventory: Inventory) {
        require(pad.sampleFile == inventory.main && pad.velocityLayers == inventory.layers) {
            "This pad's sample or velocity layers changed after the board; restore the previous pad before editing the board"
        }
        for (entry in inventory.files) {
            val file = liveFile(root, entry.name)
            require(file.isFile && sha(file.readBytes()) == entry.hash) { "This pad's audio changed after the board; restore the previous pad before editing the board" }
        }
    }

    private fun requireRestoreSafe(model: KitBuilderModel, inventory: Inventory) {
        for (entry in inventory.files) {
            val file = liveFile(model, entry.name)
            require(!file.exists() || (file.isFile && sha(file.readBytes()) == entry.hash)) {
                "Board cannot restore ${entry.name}: that filename now contains different audio"
            }
        }
    }

    private fun restoreFiles(model: KitBuilderModel, inventory: Inventory) {
        requireRestoreSafe(model, inventory)
        val bytes = inventory.files.associateWith { entry ->
            backupFile(model, entry.hash).readBytes().also { require(sha(it) == entry.hash) { "The board's saved audio is damaged" } }
        }
        for ((entry, audio) in bytes) writeImmutable(liveFile(model, entry.name), audio, entry.hash)
    }

    private fun freshOutputName(model: KitBuilderModel, slot: Int, hash: String, type: BoardType): String {
        val base = "${type.name}_${slot}_${hash.take(24)}"
        for (suffix in 0..999) {
            val name = if (suffix == 0) "$base.wav" else "${base}_$suffix.wav"
            val file = liveFile(model, name)
            if (!file.exists() || (file.isFile && sha(file.readBytes()) == hash)) return name
        }
        throw IllegalStateException("Could not create a new board sample filename")
    }

    private fun freshSourceName(root: File, hash: String): String {
        // Base 32 retains the full digest while fitting the MPC's 64-character
        // filename budget, even with the longest collision suffix and .wav.
        val base = "WS_${java.math.BigInteger(hash, 16).toString(32).padStart(52, '0')}"
        for (suffix in 0..999) {
            val name = if (suffix == 0) "$base.wav" else "${base}_$suffix.wav"
            val file = liveFile(root, name)
            if (!file.exists() || (file.isFile && sha(file.readBytes()) == hash)) return name
        }
        throw IllegalStateException("Could not choose a safe copied board source filename")
    }

    private fun writeImmutable(file: File, bytes: ByteArray, hash: String) {
        if (file.exists()) {
            require(file.isFile && sha(file.readBytes()) == hash) { "Board storage collision: ${file.name}" }
            return
        }
        AtomicFile.writeBytes(file, bytes)
        require(sha(file.readBytes()) == hash) { "Board could not verify ${file.name}" }
    }

    private fun inventoryJson(inventory: Inventory): JsonValue.Obj = obj(
        "main" to str(inventory.main),
        "layers" to JsonValue.Arr(inventory.layers.map { obj("file" to str(it.sampleFile), "start" to num(it.velStart), "end" to num(it.velEnd)) }),
        "files" to JsonValue.Arr(inventory.files.map { obj("file" to str(it.name), "sha256" to str(it.hash)) }),
    )

    private fun parseInventory(value: JsonValue): Inventory {
        val o = value as? JsonValue.Obj ?: throw IllegalArgumentException("Invalid board sample inventory")
        exactKeys(o, setOf("main", "layers", "files"))
        val main = filename(o.entries.getValue("main"))
        val layers = o.entries.getValue("layers").arr().map { entry ->
            val layer = entry as? JsonValue.Obj ?: throw IllegalArgumentException("Invalid board velocity layer")
            exactKeys(layer, setOf("file", "start", "end"))
            KitLayer(filename(layer.entries.getValue("file")), layer.entries.getValue("start").int(), layer.entries.getValue("end").int())
        }
        // KitPad is the authoritative validator for zone count/order and loudest-file identity.
        KitPad(slot = 1, sampleFile = main, velocityLayers = layers)
        val files = o.entries.getValue("files").arr().map { entry ->
            val f = entry as? JsonValue.Obj ?: throw IllegalArgumentException("Invalid board audio reference")
            exactKeys(f, setOf("file", "sha256"))
            AudioFile(filename(f.entries.getValue("file")), hash(f.entries.getValue("sha256")))
        }
        require(files.map { it.name }.distinct().size == files.size && files.map { it.name }.toSet() == (listOf(main) + layers.map { it.sampleFile }).toSet()) { "Board sample inventory is incomplete" }
        return Inventory(main, layers, files)
    }

    private fun liveFile(model: KitBuilderModel, name: String): File = liveFile(model.kitDir, name)
    private fun liveFile(root: File, name: String): File {
        filename(str(name))
        return safeChild(root, name)
    }
    private fun storage(root: File, folder: String): File = safeChild(safeChild(root, ".boards"), folder)
    private fun backupFile(model: KitBuilderModel, hash: String): File = backupFile(model.kitDir, hash)
    private fun backupFile(root: File, hash: String): File = safeChild(storage(root, "audio"), "$hash.wav")
    private fun stateFile(root: File, hash: String): File = safeChild(storage(root, "states"), "$hash.json")
    private fun undoFile(model: KitBuilderModel, slot: Int): File = safeChild(safeChild(model.kitDir, ".boards"), "undo-pad-$slot.json")
    private fun safeChild(parent: File, name: String): File {
        val child = File(parent, name)
        require(child.canonicalFile.parentFile == parent.canonicalFile) { "Board path leaves the kit folder" }
        return child
    }
    private fun filename(value: JsonValue): String = value.str().also {
        require(it.isNotBlank() && it != "." && it != ".." && '/' !in it && '\\' !in it && it.none { c -> c.code < 32 } && it.endsWith(".wav", ignoreCase = true)) { "Invalid board sample filename" }
    }
    private fun hash(value: JsonValue): String = value.str().also { require(digestPattern.matches(it)) { "Invalid board source reference" } }
    private fun checkedAudio(file: File): Snip = WavReader.read(file).also(::validateAudio)
    private fun validateAudio(snip: Snip) {
        require(snip.frameCount > 0 && snip.channels in 1..2 && snip.sampleRate == WavWriter.MPC_SAMPLE_RATE && snip.samples.all { it.isFinite() }) { "Board needs valid 44.1 kHz mono or stereo audio" }
    }
    private fun exactKeys(value: JsonValue.Obj, keys: Set<String>) { require(value.entries.keys == keys) { "Unsupported or incomplete board storage record" } }
    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun obj(vararg fields: Pair<String, JsonValue>): JsonValue.Obj = JsonValue.Obj(linkedMapOf(*fields))
    private fun str(value: String) = JsonValue.Str(value)
    private fun num(value: Int) = JsonValue.Num(value.toDouble())
}
