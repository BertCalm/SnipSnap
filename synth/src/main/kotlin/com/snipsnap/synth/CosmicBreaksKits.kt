package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.kit.ArrangedPad

/**
 * The Low orbit sample starter, expanded with velocity timbres and repeated strikes.
 * Loading its printed WAVs preserves the heard source and processing together;
 * a substitute synth or an additional finishing chain would change that sound.
 * Three strike timbres each cycle four deterministic takes; no editable synth recipe.
 */
object CosmicBreaksKits {
    const val VERSION = 2
    const val ID = "cosmic-breaks"
    const val NAME = "Cosmic Breaks"
    const val TEMPO = 170f
    data class Variant(val layer: Int, val take: Int, val file: String, val sha256: String)
    data class Entry(val slot: Int, val name: String, val file: String, val sha256: String, val variants: List<Variant>)
    val entries: List<Entry> by lazy {
        Json.parse(resource("manifest.json").decodeToString()).obj().getValue("pads").arr().map {
            val o = it.obj()
            Entry(o.getValue("slot").int(), o.getValue("name").str(), o.getValue("file").str(), o.getValue("sha256").str(),
                o.getValue("variants").arr().map { v -> val a = v.obj()
                    Variant(a.getValue("layer").int(), a.getValue("take").int(), a.getValue("file").str(), a.getValue("sha256").str())
                }.also { vs -> require(vs.map { it.layer to it.take } == (0..2).flatMap { l -> (0..3).map { l to it } }) })
        }.also { require(it.map(Entry::slot) == (1..16).toList()) }
    }
    internal fun resource(name: String): ByteArray {
        require('/' !in name && '\\' !in name && ".." !in name)
        return requireNotNull(javaClass.getResourceAsStream("/cosmic-breaks/v$VERSION/$name")) {
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
        val grid = (0..2).map { layer -> entry.variants.filter { it.layer == layer }.map { WavReader.read(resource(it.file)) } }
        // The headline hit shares the hard anchor inside this load, avoiding a duplicate PCM buffer.
        ArrangedPad(grid[2][0], role, displayName = entry.name,
            velocityTakes = grid,
            oneShot = true, source = mapOf(
                "factory" to ID, "model" to VERSION.toString(), "role" to entry.name,
                "rendered" to "true", "asset" to entry.file, "sha256" to entry.sha256,
                "treatment" to "low-orbit", "approval" to "velocity-rr-candidate",
            ))
    }
}
