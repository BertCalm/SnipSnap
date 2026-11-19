package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.KitPad
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.io.File
import kotlin.test.Test

/**
 * The listening reel for SONG's last-bar transitions: one short song, written four times, with the boundary into each
 * section as NONE, DROP, CLOSE and REVERSE CRASH, so the owner can hear what each does to the same music.
 *
 * Nothing is written unless `REEL_OUT` names a directory, so CI passes through it:
 * `REEL_OUT=/some/dir ./gradlew :shell:test --tests '*SongTransitionReelTest*'`. The song is the FACTORY starter kit
 * (a kick, a snare, hats and an open hat) playing three two-bar sections.
 */
class SongTransitionReelTest {

    @Test
    fun `writes the reel when REEL_OUT is set`() {
        val out = System.getenv("REEL_OUT")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        require(out.isDirectory || out.mkdirs()) { "cannot create the reel directory $out" }
        val work = java.nio.file.Files.createTempDirectory("reel").toFile()
        try {
            val kit = requireNotNull(StarterKits.byId("factory")).render("Reel Kit", File(work, "kit"))
            fun slot(c: DrumClass) = requireNotNull(kit.pads.filter { it.drumClass == c }.minByOrNull(KitPad::slot)) { "the factory kit has no $c" }.slot
            val kick = Mpc3Note.noteFor(slot(DrumClass.KICK))
            val snare = Mpc3Note.noteFor(slot(DrumClass.SNARE))
            val hat = Mpc3Note.noteFor(slot(DrumClass.HAT_CLOSED))
            val s16 = Mpc3Clip.PULSES_PER_16TH
            val bar = Mpc3Clip.PULSES_PER_BAR
            val notes = (0 until 2).flatMap { b ->
                val at = b * bar
                listOf(Mpc3Note(kick, at, 0.95f), Mpc3Note(kick, at + 8 * s16, 0.85f), Mpc3Note(snare, at + 4 * s16, 0.9f), Mpc3Note(snare, at + 12 * s16, 0.9f)) +
                    (0 until 8).map { Mpc3Note(hat, at + it * 2 * s16, if (it % 2 == 0) 0.8f else 0.55f) }
            }
            fun song(kind: String): Arranger.Arrangement {
                val sections = listOf("one", "two", "three").mapIndexed { i, name ->
                    val into = i < 2
                    Arranger.Section(
                        name, Mpc3Clip("Reel $name", 2, notes), 1, "reel", pullUp = false,
                        drop = into && kind == "drop", close = into && kind == "close", reverseCrash = into && kind == "reverse-crash",
                    )
                }
                return Arranger.Arrangement("Reel", 0, sections)
            }
            val lines = mutableListOf<String>()
            for ((n, kind) in listOf("none", "drop", "close", "reverse-crash").withIndex()) {
                val mix = Arranger.mixdown(kit, File(work, "kit"), song(kind))
                val file = File(out, "${n + 1}-$kind.wav")
                WavWriter.write(file, mix.snip)
                val peak = mix.snip.samples.maxOf { kotlin.math.abs(it) }
                lines += "${file.name}: ${mix.snip.frameCount} frames, peak $peak, ${mix.transitions.joinToString { "${it.kind} ${it.applied}: ${it.reason}" }}${mix.drops.joinToString { " drop ${it.kept}: ${it.reason}" }}"
            }
            File(out, "reel.txt").writeText(lines.joinToString("\n") + "\n")
        } finally {
            work.deleteRecursively()
        }
    }
}
