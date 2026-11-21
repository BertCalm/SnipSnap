package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DRAWL's starters ([DrawlKits]): the sixteen-pad DRAWL kit in D major pentatonic, and DRAWL TAKES,
 * the same pads with three touches of all four engine takes. Every pad of a heard preset deals only
 * the renders the owner kept (round 1 and round 2 pins).
 */
class DrawlKitsTest {
    private val pins by lazy { DrawlNativePins.pinned() }

    private fun rms(x: FloatArray) = sqrt(x.sumOf { it.toDouble() * it } / x.size)

    /** The pin a render of [patch] at [velocity] and [take] was heard as, if the owner heard it. */
    private fun heardId(patch: DrawlPatch, velocity: Float, take: Int): String? {
        val v = patch.voice.name.lowercase()
        val presets = DrawlPresets.forVoice(patch.voice)
        val asPreset = presets.firstOrNull { it == patch.copy(name = it.name) } ?: return null
        return when (presets.indexOf(asPreset)) {
            0 -> when {
                velocity == .75f -> if (take == 0) "$v-mid" else "$v-take$take"
                take == 0 -> if (velocity == .3f) "$v-soft" else "$v-hard"
                else -> null
            }
            1 -> if (velocity == .75f && take == 0) "$v-slide1-waver1" else null
            else -> if (velocity == .75f && take == 0) "$v-strum-down" else null
        }
    }

    @Test fun `DRAWL is sixteen finite pads in D major pentatonic, every voice, its heard presets unchanged`() {
        val patches = DrawlKits.patches()
        val pads = DrawlKits.arranged()
        assertEquals(16, pads.size)
        assertEquals(DrawlVoice.entries.toSet(), patches.map { it.voice }.toSet(), "every voice is on the kit")
        assertEquals(DrawlKits.SCALE_MIDI, patches.take(8).map { DrawlKits.midiOf(it) }, "A01-A08 walk the scale")
        assertTrue(patches.take(8).all { it.voice == DrawlVoice.CONE && it.copy(name = "RESO TWANG", macros = it.macros - "TUNE") ==
            DrawlPresets.named(DrawlVoice.CONE, "RESO TWANG").let { p -> p.copy(macros = p.macros - "TUNE") } }, "the scale is RESO TWANG retuned")
        val key = setOf(2, 4, 6, 9, 11)
        for (p in patches) {
            val notes = (p.strum?.intervals ?: listOf(0)).map { DrawlKits.midiOf(p) + it }
            if (p.strum == null) assertTrue(notes.all { it % 12 in key }, "${p.name} plays ${notes} out of D major pentatonic")
        }
        // The two strums are the key's I (open D on the flat-top) and V (open A on the resonator).
        assertEquals(listOf(50, 54, 57, 62, 66, 69), patches[14].strum!!.intervals.map { DrawlKits.midiOf(patches[14]) + it }.sorted())
        assertEquals(listOf(57, 64, 69, 73), patches[15].strum!!.intervals.map { DrawlKits.midiOf(patches[15]) + it }.sorted())
        assertEquals(patches.drop(8), patches.drop(8).map { DrawlPresets.named(it.voice, it.name) }, "A09-A16 are factory presets")
        for ((i, pad) in pads.withIndex()) {
            val patch = patches[i]
            assertEquals(patch.name, pad.displayName)
            assertTrue(patch.name.length <= 14, patch.name)
            assertTrue(pad.oneShot, "${pad.displayName}: plays its finite note")
            assertEquals(DrumClass.LOOP, pad.drumClass, "${pad.displayName}: longer than 1.5 s files as LOOP, a duration class")
            assertEquals(Drawl.drumClassFor(patch.voice, patch.macros), pad.drumClass)
            val recipe = assertNotNull(PadRecipe.fromJsonValue(assertNotNull(pad.recipe)))
            assertEquals(patch, recipe.patch, "${pad.displayName}: its recipe")
            assertContentEquals(patch.render().samples, pad.snip.samples, "${pad.displayName}: the engine's render")
            assertEquals(DrawlKits.midiOf(patch).toString(), pad.source["rootMidi"])
            heardId(patch, .75f, 0)?.let { id ->
                assertEquals(pins.getValue(id).second, DrawlNativePins.sha(pad.snip.samples), "${pad.displayName} is the heard $id")
            }
        }
        assertEquals(9, patches.count { heardId(it, .75f, 0) != null }, "RESO A3 and A09-A16 are heard renders")
    }

    @Test fun `DRAWL TAKES is three touches of four takes, the DRAWL pad first in its own touch, every take the engine's`() {
        val pads = DrawlKits.takesArranged()
        val kept = DrawlKits.arranged()
        val patches = DrawlKits.patches()
        assertEquals(16, pads.size)
        assertEquals(listOf(0, 1, 2, 3), DrawlKits.TAKES, "the four takes ROBIN deals")
        assertEquals(listOf(.3f, .75f, 1f), DrawlKits.TOUCHES)
        var heard = 0
        for ((i, pad) in pads.withIndex()) {
            val patch = patches[i]
            assertEquals(kept[i].displayName, pad.displayName)
            assertEquals(kept[i].drumClass, pad.drumClass)
            assertEquals(kept[i].colorHex, pad.colorHex)
            assertTrue(pad.oneShot)
            assertNull(pad.recipe, "one performance's recipe cannot regenerate the grid; it rides in the source")
            assertEquals(patch, Patches.fromJsonText(pad.source.getValue("recipe")))
            assertEquals(listOf(4, 4, 4), pad.velocityTakes.map { it.size })
            assertContentEquals(kept[i].snip.samples, pad.velocityTakes[1][0].samples, "${pad.displayName}: medium take 1 is the DRAWL pad")
            for ((z, v) in DrawlKits.TOUCHES.withIndex()) {
                for ((n, k) in DrawlKits.TAKES.withIndex()) {
                    val x = pad.velocityTakes[z][n].samples
                    assertContentEquals(patch.copy(velocity = v, take = k).render().samples, x, "${pad.displayName} touch $v take $k")
                    assertTrue(x.all { it.isFinite() } && x.maxOf { abs(it) } <= .99f, "${pad.displayName} touch $v take $k peak")
                    heardId(patch, v, k)?.let { id ->
                        assertEquals(pins.getValue(id).second, DrawlNativePins.sha(x), "${pad.displayName} touch $v take $k is the heard $id")
                        heard++
                    }
                }
                // Four different passes, not copies of one.
                val zone = pad.velocityTakes[z]
                for (a in zone.indices) for (b in a + 1 until zone.size)
                    assertTrue(!zone[a].samples.contentEquals(zone[b].samples), "${pad.displayName} touch $v: takes ${a + 1} and ${b + 1} are one strike")
            }
            // A harder pick is louder at the sound's one gain.
            val level = pad.velocityTakes.map { rms(it[0].samples) }
            assertTrue(level[0] < level[1] && level[1] < level[2], "${pad.displayName}: soft, medium, hard levels $level")
        }
        // RESO A3 and the four lead defaults: soft, hard and four medium takes heard each (5 x 6); the slides
        // and strums: their medium first take (4).
        assertEquals(5 * 6 + 4, heard)
        val frames = pads.sumOf { pad -> pad.velocityTakes.sumOf { zone -> zone.sumOf { it.samples.size.toLong() } } }
        println("DRAWL TAKES size: $frames frames, ${"%.1f".format(frames / 44_100.0)} s, ${"%.1f".format(frames * 3 / 1e6)} MB PCM24")
        assertTrue(frames < 11L * 60 * 44_100, "the kit is ${frames / 44_100} s of audio")
    }

    /** A failure here means kept DRAWL kit audio changed: re-listen before re-pinning. */
    @Test fun `every pad and take of both kits hashes as the owner heard and kept it in round 3`() {
        val pinned = javaClass.getResourceAsStream("/drawl/kits-v1.sha256")!!.bufferedReader().readLines()
            .filterNot { it.startsWith("#") || it.isBlank() }.map { it.split(' ') }
            .associate { f -> f[0] to (f[1].toInt() to f[2]) }
        val rendered = DrawlKits.arranged().withIndex().map { (i, pad) -> "drawl-%02d".format(i + 1) to pad.snip } +
            DrawlKits.takesArranged().withIndex().flatMap { (i, pad) ->
                pad.velocityTakes.withIndex().flatMap { (z, zone) -> zone.withIndex().map { (t, snip) -> "takes-%02d-z$z-t$t".format(i + 1) to snip } }
            }
        assertEquals(16 + 16 * 12, rendered.size)
        assertEquals(rendered.map { it.first }.toSet(), pinned.keys)
        for ((id, snip) in rendered) {
            val (frames, sha) = pinned.getValue(id)
            assertEquals(frames, snip.frameCount, "$id frames")
            assertEquals(sha, DrawlNativePins.sha(snip.samples), id)
        }
    }
}
