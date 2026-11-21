package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.ExportFormat
import com.snipsnap.kit.ExportReadBack
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.kit.Severity
import com.snipsnap.synth.BoroughKits
import com.snipsnap.synth.BoroughKits.Stack
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The PERC STACK picker (docs/BOROUGH.md, round 3.2): [BoroughStackSwap] on STOOP DUST rendered from the shelf. Which
 * kit and stack a folder holds; the untouched-or-changed rule, edit by edit; a swap to each of the three other stacks
 * (A01-A12 byte for byte, A13-A16 the engine's own [BoroughKits.stackPads] for STOOP, the velocity zones and takes
 * through save, reload, the pad resolver and an MPC 3 track, the glue and the rest of kit.json kept, no old stack WAV
 * left in the folder, VERSIONS able to roll it back); and the refusals, which write nothing.
 */
class BoroughStackSwapTest {
    private val k = BoroughKits.Kit.STOOP

    /** STOOP DUST from the shelf, rendered once for the class; each test works on its own copy. */
    private fun starter(): File {
        val root = createTempDirectory("borough-swap").toFile()
        val dir = root.resolve(k.id)
        Template.dir.copyRecursively(dir)
        return dir
    }

    private object Template {
        val dir: File by lazy {
            val root = createTempDirectory("borough-swap-template").toFile()
            Runtime.getRuntime().addShutdownHook(Thread { root.deleteRecursively() })
            val dir = root.resolve("borough-stoop")
            assertNotNull(StarterKits.byId("borough-stoop")).render("BOROUGH STOOP DUST", dir)
            dir
        }
    }

    private fun <T> withKit(block: (File) -> T): T {
        val dir = starter()
        try {
            return block(dir)
        } finally {
            dir.parentFile.deleteRecursively()
        }
    }

    /** Every file under [dir], by relative path, with its bytes. */
    private fun snapshot(dir: File): Map<String, List<Byte>> =
        dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path to it.readBytes().toList() }

    private fun sine(hz: Double) = Snip(FloatArray(4410) { i -> (.4 * sin(2 * PI * hz * i / 44100.0) * exp(-i / 1400.0)).toFloat() }, 1, 44100)

    @Test fun `a BOROUGH starter is detected from its pads, through save and reload`() = withKit { dir ->
        val kit = KitStore.load(dir)
        assertEquals(BoroughStackSwap.Held(k, Stack.TAMBOURINE), BoroughStackSwap.held(kit))
        // The provenance the detection reads is what the assembler wrote and kit.json kept.
        for (pad in kit.pads) {
            assertEquals("BOROUGH", pad.source["engine"]); assertEquals("STOOP", pad.source["kit"]); assertEquals(pad.slot.toString(), pad.source["slot"])
        }
        assertEquals(listOf("TAMBOURINE"), kit.pads.filter { it.slot in 13..16 }.map { it.source["stack"] }.distinct())
        val choices = assertNotNull(BoroughStackSwap.choices(kit))
        assertEquals(listOf("HANDS", "TAMBOURINE", "BELLS", "TOPS"), choices.map { it.label })
        assertEquals(listOf(Stack.TAMBOURINE), choices.filter { it.selected }.map { it.stack })
        // A kit saved again by the editor reads the same.
        KitBuilderModel.open(dir).apply { setTempo(91f); save() }
        assertEquals(BoroughStackSwap.Held(k, Stack.TAMBOURINE), BoroughStackSwap.held(KitStore.load(dir)))
    }

    @Test fun `the pads name the kit when most of them do, and a mixed A13-A16 names no stack`() = withKit { dir ->
        val kit = KitStore.load(dir)
        // A16 from another stack: four pads no longer agree on one.
        val mixed = kit.copy(pads = kit.pads.map { if (it.slot == 16) it.copy(source = it.source + ("stack" to "HANDS")) else it })
        assertEquals(BoroughStackSwap.Held(k, null), BoroughStackSwap.held(mixed))
        assertTrue(BoroughStackSwap.choices(mixed)!!.none { it.selected })
        assertEquals(listOf(13, 14, 15, 16), BoroughStackSwap.changedSlots(dir, mixed))
        // A user sample on A16: the other three still name the stack.
        val own = kit.copy(pads = kit.pads.map { if (it.slot == 16) KitPad(16, it.sampleFile, drumClass = DrumClass.CLAP) else it })
        assertEquals(BoroughStackSwap.Held(k, Stack.TAMBOURINE), BoroughStackSwap.held(own))
        // One drum from another kit does not outvote eleven.
        val stray = kit.copy(pads = kit.pads.map { if (it.slot == 1) it.copy(source = it.source + ("kit" to "WAX")) else it })
        assertEquals(k, BoroughStackSwap.held(stray)!!.kit)
    }

    @Test fun `a kit with no BOROUGH pads is refused and nothing is written`() {
        val dir = createTempDirectory("not-borough").toFile()
        try {
            val model = KitBuilderModel.create("PLAIN", dir)
            model.assign(13, sine(220.0), DrumClass.PERC)
            model.save()
            val kit = KitStore.load(dir)
            assertNull(BoroughStackSwap.held(kit))
            assertNull(BoroughStackSwap.choices(kit))
            val before = snapshot(dir)
            assertEquals(BoroughStackSwap.Result.NotBorough, BoroughStackSwap.swap(dir, Stack.HANDS))
            assertEquals(BoroughStackSwap.Result.NotBorough, BoroughStackSwap.swap(dir, Stack.HANDS, replaceChanges = true))
            assertEquals(before, snapshot(dir))
            assertEquals(Copy.PERC_STACK_NONE, BoroughStackSwap.line(BoroughStackSwap.Result.NotBorough))
        } finally { dir.deleteRecursively() }
    }

    @Test fun `the stack it already holds is a no-op`() = withKit { dir ->
        val before = snapshot(dir)
        assertEquals(BoroughStackSwap.Result.SameStack(Stack.TAMBOURINE), BoroughStackSwap.swap(dir, Stack.TAMBOURINE))
        assertEquals(BoroughStackSwap.Result.SameStack(Stack.TAMBOURINE), BoroughStackSwap.swap(dir, Stack.TAMBOURINE, replaceChanges = true))
        assertEquals(before, snapshot(dir))
        assertNull(BoroughStackSwap.line(BoroughStackSwap.Result.SameStack(Stack.TAMBOURINE)))
    }

    @Test fun `the starter's stack pads are untouched`() = withKit { dir ->
        assertEquals(emptyList(), BoroughStackSwap.changedSlots(dir, KitStore.load(dir)))
        // A kit-wide setting is not a change to A13-A16.
        KitBuilderModel.open(dir).apply { setKey(com.snipsnap.audio.KeySpec(2, com.snipsnap.audio.Scale.MINOR)); save() }
        assertEquals(emptyList(), BoroughStackSwap.changedSlots(dir, KitStore.load(dir)))
        // Nor is an edit to a drum pad.
        KitBuilderModel.open(dir).apply { update(1) { it.copy(level = .5f) }; save() }
        assertEquals(emptyList(), BoroughStackSwap.changedSlots(dir, KitStore.load(dir)))
    }

    /** One edit to the starter, and the slots the rule should then call changed. */
    private fun assertChanged(expected: List<Int>, edit: (File, KitBuilderModel) -> Unit) = withKit { dir ->
        val model = KitBuilderModel.open(dir)
        edit(dir, model)
        if (model.dirty) model.save()
        val kit = KitStore.load(dir)
        assertEquals(expected, BoroughStackSwap.changedSlots(dir, kit))
        // Unconfirmed, the swap writes nothing and names the same slots.
        val before = snapshot(dir)
        assertEquals(BoroughStackSwap.Result.NeedsConfirm(Stack.BELLS, expected), BoroughStackSwap.swap(dir, Stack.BELLS))
        assertEquals(before, snapshot(dir))
    }

    @Test fun `a new sample counts as changed`() = assertChanged(listOf(13)) { _, m -> m.assign(13, sine(300.0), DrumClass.PERC) }

    @Test fun `level pan and tune count as changed`() = assertChanged(listOf(14, 15, 16)) { _, m ->
        m.update(14) { it.copy(level = .5f) }
        m.update(15) { it.copy(pan = .3f) }
        m.update(16) { it.copy(tuneCoarse = 2) }
    }

    @Test fun `a cleared pad and a moved pad count as changed`() = assertChanged(listOf(13, 16)) { _, m ->
        m.clear(16)
        m.move(13, 20)
    }

    @Test fun `a shape on the pad counts as changed`() = assertChanged(listOf(15)) { _, m -> m.update(15) { it.copy(decay = .4f) } }

    @Test fun `audio rewritten outside the app counts as changed`() = assertChanged(listOf(14)) { dir, m ->
        val file = File(dir, m.kit.pad(14)!!.sampleFile)
        val wav = WavReader.read(file)
        WavWriter.write(file, Snip(wav.samples.copyOf().also { it[100] += .01f }, wav.channels, wav.sampleRate))
    }

    @Test fun `a stack pad from another version counts as changed`() = assertChanged(listOf(13)) { _, m ->
        m.update(13) { it.copy(source = it.source + ("kitVersion" to "1")) }
    }

    @Test fun `a swap to each other stack moves only A13-A16`() = withKit { dir ->
        val original = KitStore.load(dir)
        var from: Stack = Stack.TAMBOURINE
        for (stack in listOf(Stack.HANDS, Stack.BELLS, Stack.TOPS)) {
            val before = KitStore.load(dir)
            val drumBytes = before.pads.filter { it.slot <= 12 }.associate { it.slot to File(dir, it.sampleFile).readBytes() }
            val oldFiles = before.pads.filter { it.slot in 13..16 }.map { it.sampleFile }
            val versionsBefore = KitBuilderModel.open(dir).takes().size

            val result = assertIs<BoroughStackSwap.Result.Swapped>(BoroughStackSwap.swap(dir, stack))
            assertEquals(from, result.from); assertEquals(stack, result.to)
            assertEquals(Copy.percStackSwapped(BoroughStackSwap.label(stack)), BoroughStackSwap.line(result))
            val saved = KitStore.load(dir)
            assertEquals(saved, result.kit)

            // A01-A12: the same kit.json entries and the same bytes.
            assertEquals(before.pads.filter { it.slot <= 12 }, saved.pads.filter { it.slot <= 12 })
            for ((slot, bytes) in drumBytes) assertContentEquals(bytes, File(dir, saved.pad(slot)!!.sampleFile).readBytes(), "A$slot")
            // The glue and everything else in kit.json as they were.
            assertEquals(BoroughKits.glueOf(k), saved.glue)
            assertEquals(before.copy(pads = emptyList()), saved.copy(pads = emptyList()))
            assertEquals(original.copy(pads = emptyList()), saved.copy(pads = emptyList()))

            // A13-A16: the engine's stack as it plays in STOOP, take for take, with its provenance.
            val expected = BoroughKits.stackPads(k, stack)
            val stackPads = saved.pads.filter { it.slot in 13..16 }
            assertEquals(listOf(13, 14, 15, 16), stackPads.map { it.slot })
            TriadKitProof.verify(saved.copy(pads = stackPads), dir, expected)
            for ((pad, made) in stackPads.zip(expected)) {
                assertEquals(made.displayName, pad.displayName)
                assertEquals(made.drumClass, pad.drumClass)
                assertEquals(0, pad.muteGroup)
                assertEquals(stack.name, pad.source["stack"]); assertEquals("STOOP", pad.source["kit"])
                assertEquals(BoroughKits.VERSION.toString(), pad.source["kitVersion"])
                assertNull(pad.recipe)
            }
            assertEquals(BoroughStackSwap.Held(k, stack), BoroughStackSwap.held(saved))
            // What the swap wrote is untouched, so the next swap goes straight away.
            assertEquals(emptyList(), BoroughStackSwap.changedSlots(dir, saved))

            // Velocity zones and takes through the pad resolver, on the kit as reloaded.
            for (pad in stackPads) {
                val chain = assertNotNull(pad.chain)
                val zones = assertNotNull(chain.zones)
                assertEquals(3, zones.size)
                assertTrue(zones.all { it.cycle == BoroughKits.TAKES })
                val frames = WavReader.read(File(dir, pad.sampleFile)).frameCount.toLong()
                for (v in listOf(1, 20, 41, 42, 64, 84, 85, 110, 127)) for (hit in 0 until BoroughKits.TAKES * 2) {
                    val resolved = assertNotNull(PadHit.resolve(pad, v / 127f, hit) { frames })
                    val zone = if (v < 42) 0 else if (v < 85) 1 else 2
                    val w = chain.window(zone * BoroughKits.TAKES + hit % BoroughKits.TAKES, frames)
                    assertEquals(w.first, resolved.startFrame, "A${pad.slot} v$v hit $hit")
                    assertEquals(w.last + 1, resolved.endFrameExclusive)
                }
            }

            // MPC 3: zones, slices and cycles read back from the track; the samples byte for byte.
            val export = Mpc3Exporter.exportTrack(saved, dir, dir.parentFile.resolve("mpc3-${stack.id}"))
            TriadKitProof.verifyTrack(export.program, saved)
            assertTrue(ExportReadBack.verifyProgram(saved, export.program, ExportFormat.MPC3_TRACK).none { it.severity == Severity.FAIL })
            for (pad in saved.pads) assertContentEquals(File(dir, pad.sampleFile).readBytes(), export.samples.single { it.name == pad.sampleFile }.readBytes())

            // No old stack WAV left in the folder: every top-level file is kit.json or a pad's, and the old stack
            // files wait in THE BIN; VERSIONS holds the kit as it was.
            val referenced = saved.pads.flatMap { listOf(it.sampleFile) + it.velocityLayers.map { l -> l.sampleFile } }.toSet()
            val loose = dir.listFiles()!!.filter { it.isFile }.map { it.name }.toSet()
            assertEquals(referenced + KitStore.FILE_NAME, loose)
            assertEquals(setOf(KitBuilderModel.BIN_DIR, KitBuilderModel.TAKES_DIR), dir.listFiles()!!.filter { it.isDirectory }.map { it.name }.toSet())
            val binned = KitBuilderModel.open(dir).binContents().map { it.originalName }
            assertTrue(binned.containsAll(oldFiles), "binned $binned, wanted $oldFiles")
            assertTrue(oldFiles.none { File(dir, it).exists() })
            assertEquals(versionsBefore + 1, KitBuilderModel.open(dir).takes().size)
            from = stack
        }
    }

    @Test fun `VERSIONS rolls a swap back to the stack it replaced`() = withKit { dir ->
        val before = KitStore.load(dir)
        val bytes = before.pads.filter { it.slot in 13..16 }.associate { it.sampleFile to File(dir, it.sampleFile).readBytes() }
        assertIs<BoroughStackSwap.Result.Swapped>(BoroughStackSwap.swap(dir, Stack.HANDS))
        val model = KitBuilderModel.open(dir)
        model.restoreTake(model.takes().last())
        model.save()
        val back = KitStore.load(dir)
        assertEquals(before.pads.sortedBy { it.slot }, back.pads.sortedBy { it.slot })
        for ((name, b) in bytes) assertContentEquals(b, File(dir, name).readBytes(), name)
        assertEquals(BoroughStackSwap.Held(k, Stack.TAMBOURINE), BoroughStackSwap.held(back))
        assertEquals(emptyList(), BoroughStackSwap.changedSlots(dir, back))
    }

    @Test fun `a confirmed swap replaces the changed pads and keeps the drums`() = withKit { dir ->
        val model = KitBuilderModel.open(dir)
        model.assign(13, sine(300.0), DrumClass.PERC, displayName = "MY BELL")
        model.update(15) { it.copy(level = .4f) }
        model.save()
        val mine = KitStore.load(dir).pad(13)!!.sampleFile
        val drums = KitStore.load(dir).pads.filter { it.slot <= 12 }
        assertEquals(BoroughStackSwap.Result.NeedsConfirm(Stack.TOPS, listOf(13, 15)), BoroughStackSwap.swap(dir, Stack.TOPS))
        assertEquals("A13 and A15 changed. What is there now goes in the trash, and VERSIONS rolls the kit back.", Copy.percStackChanged(listOf(13, 15)))
        // The user's own sample leaves A13, so its file leaves the folder for THE BIN.
        val swapped = assertIs<BoroughStackSwap.Result.Swapped>(BoroughStackSwap.swap(dir, Stack.TOPS, replaceChanges = true))
        // Three of the four still named TAMBOURINE, so that is the stack the swap replaced.
        assertEquals(Stack.TAMBOURINE, swapped.from)
        val saved = KitStore.load(dir)
        assertEquals(drums, saved.pads.filter { it.slot <= 12 })
        assertEquals(listOf("Clap", "Ride", "Ride Bell", "Sticks"), saved.pads.filter { it.slot in 13..16 }.map { it.displayName })
        assertTrue(saved.pads.filter { it.slot in 13..16 }.all { it.level == 0.707946f })
        assertFalse(File(dir, mine).exists())
        assertTrue(mine in KitBuilderModel.open(dir).binContents().map { it.originalName })
        TriadKitProof.verify(saved.copy(pads = saved.pads.filter { it.slot in 13..16 }), dir, BoroughKits.stackPads(k, Stack.TOPS))
        assertEquals(emptyList(), BoroughStackSwap.changedSlots(dir, saved))
    }

    @Test fun `a moved stack pad keeps its file where it went`() = withKit { dir ->
        val model = KitBuilderModel.open(dir)
        model.move(14, 30)
        model.save()
        val kept = KitStore.load(dir).pad(30)!!
        assertIs<BoroughStackSwap.Result.Swapped>(BoroughStackSwap.swap(dir, Stack.HANDS, replaceChanges = true))
        val saved = KitStore.load(dir)
        assertEquals(kept, saved.pad(30))
        assertTrue(File(dir, kept.sampleFile).isFile)
        assertEquals(listOf(13, 14, 15, 16), saved.pads.filter { it.slot in 13..16 }.map { it.slot })
    }
}
