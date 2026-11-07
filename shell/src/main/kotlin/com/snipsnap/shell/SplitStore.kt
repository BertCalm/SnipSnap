package com.snipsnap.shell

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.AtomicFile
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Only the explicit FX route/tail, not stems, KIT FX values or the temporary desk. */
object SplitStore {
    const val FILE_NAME = "split.json"
    fun load(dir: File): SplitAquarium.Settings {
        val file = File(dir, FILE_NAME)
        if (!file.isFile) return SplitAquarium.Settings()
        val obj = Json.parse(file.readText()).obj()
        require(obj["version"]?.int() == 1) { "Unsupported SPLIT settings version" }
        return SplitAquarium.Settings(obj["aquariumFromKit"]?.bool() ?: false,
            (obj["tailSeconds"]?.num() ?: 3.0).toFloat())
    }
    fun save(dir: File, settings: SplitAquarium.Settings): File {
        require(dir.isDirectory) { "kit folder no longer exists: $dir" }
        val file = File(dir, FILE_NAME)
        AtomicFile.writeText(file, Json.write(JsonValue.Obj(linkedMapOf(
            "version" to JsonValue.Num(1.0),
            "aquariumFromKit" to JsonValue.Bool(settings.fromKit),
            "tailSeconds" to JsonValue.Num(settings.tailSeconds.toDouble()),
        ))) + "\n")
        return file
    }
}

/** One kit-scoped owner. Caller holds KitWrites while saving; UI staging stays atomic. */
class SplitSettingsWrites(private val dir: File) {
    private val latest = AtomicReference<SplitAquarium.Settings?>(null)
    fun stage(settings: SplitAquarium.Settings) { latest.set(settings) }
    fun persist(): File = SplitStore.save(dir, requireNotNull(latest.get()) { "no staged SPLIT settings" })
}
