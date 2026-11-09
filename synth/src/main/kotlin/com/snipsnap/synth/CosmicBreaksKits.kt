package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.kit.ArrangedPad

/**
 * The approved sixteen-pad browser audition, frozen as a sample starter.
 * Loading its printed WAVs preserves the heard source and processing together;
 * a substitute synth or an additional finishing chain would change that sound.
 * No editable synth recipe or extra velocity timbre layers are implied.
 */
object CosmicBreaksKits {
    const val VERSION = 1
    const val ID = "cosmic-breaks"
    const val NAME = "Cosmic Breaks"
    const val TEMPO = 170f
    data class Entry(val slot: Int, val name: String, val file: String, val sha256: String)
    val entries: List<Entry> by lazy {
        Json.parse(resource("manifest.json").decodeToString()).obj().getValue("pads").arr().map {
            val o = it.obj()
            Entry(o.getValue("slot").int(), o.getValue("name").str(), o.getValue("file").str(), o.getValue("sha256").str())
        }.also { require(it.map(Entry::slot) == (1..16).toList()) }
    }
    internal fun resource(name: String): ByteArray {
        require('/' !in name && '\\' !in name && ".." !in name)
        return requireNotNull(javaClass.getResourceAsStream("/cosmic-breaks/v1/$name")) {
            "missing Cosmic Breaks asset: $name"
        }.use { it.readBytes() }
    }
    /** Each load owns its buffers, so pad editing cannot mutate the frozen factory bank. */
    fun build(): List<ArrangedPad> = entries.map { entry ->
        val role = when (entry.slot) {
            1, 15 -> DrumClass.KICK
            2, 16 -> DrumClass.SNARE
            3 -> DrumClass.HAT_CLOSED
            4 -> DrumClass.HAT_OPEN
            5, 6 -> DrumClass.TOM
            else -> DrumClass.PERC
        }
        ArrangedPad(WavReader.read(resource(entry.file)), role, displayName = entry.name,
            oneShot = true, source = mapOf(
                "factory" to ID, "model" to VERSION.toString(), "role" to entry.name,
                "rendered" to "true", "asset" to entry.file, "sha256" to entry.sha256,
                "treatment" to "low-orbit", "approval" to "direction-approved",
            ))
    }
}
