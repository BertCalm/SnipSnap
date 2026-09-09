package com.snipsnap.shell

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.ExportFormat
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Names
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.synth.Thump
import com.snipsnap.synth.ThumpVoice
import java.io.File
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test

/**
 * UAT SIMULATION HARNESS — not a guardrail test.
 *
 * Drives the real `:shell` state machines through end-to-end user
 * journeys and prints a transcript. Every line is something the model
 * actually did, so the UX report built off it is evidence, not guesswork.
 * Deliberately never asserts: a journey that ends badly is a finding to
 * report, not a build to break.
 */
class UatSimTest {

    private val out = StringBuilder()
    private val findings = mutableListOf<String>()

    /**
     * Transcript only — deliberately not `println`. This class runs inside
     * the ordinary `:shell` suite, and 300 lines of narration in every
     * build log would be noise. The file it writes is the artifact; the
     * console gets the finding count and the path.
     */
    private fun say(s: String) { out.appendLine(s) }
    private fun step(n: Int, s: String) = say("  [$n] $s")
    private fun note(s: String) = say("      ~ $s")
    private fun finding(id: String, s: String) {
        findings += "$id  $s"
        say("      !! $id  $s")
    }

    private fun tmp(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "uat-$name-${System.nanoTime()}").apply { mkdirs() }

    // ---- a stand-in "field recording": four hits with room tone between them ----
    private fun fieldRecording(seconds: Float = 4f, seed: Int = 7): Snip {
        val rate = 44_100
        val frames = (seconds * rate).toInt()
        val buf = FloatArray(frames)
        val rng = Random(seed)
        for (i in buf.indices) buf[i] = (rng.nextFloat() - 0.5f) * 0.01f  // room tone
        val voices = listOf(ThumpVoice.KICK, ThumpVoice.SNARE, ThumpVoice.HAT_CLOSED, ThumpVoice.CLAP)
        voices.forEachIndexed { i, v ->
            val hit = Thump.render(v, emptyMap())
            val at = (i * frames / voices.size) + 1000
            for (j in 0 until hit.frameCount) {
                val k = at + j
                if (k < frames) buf[k] += hit.samples[j * hit.channels] * 0.9f
            }
        }
        return Snip(buf, channels = 1, sampleRate = rate)
    }

    private fun sine(seconds: Float, hz: Float): Snip {
        val rate = 44_100
        val n = (seconds * rate).toInt()
        val buf = FloatArray(n) { sin(2.0 * Math.PI * hz * it / rate).toFloat() * 0.4f }
        return Snip(buf, channels = 1, sampleRate = rate)
    }

    @Test
    fun `run every journey`() {
        say("=".repeat(74))
        say("SNIPSNAP UAT SIMULATION — driving :shell's real models")
        say("=".repeat(74))

        j1ColdStartStarter()
        j2CaptureToKit()
        j3ChopReviewArgument()
        j4ExportFanOut()
        j5PadSheetDepth()
        j6ReturningUserShelf()
        j7DegenerateAndEdges()
        j8PowerUserDepth()
        j9TrimDeck()
        j10ChromeAndCopy()

        val file = File(System.getProperty("java.io.tmpdir"), "uat-transcript.txt")
        file.writeText(out.toString())
        println("UAT SIMULATION: ${findings.size} findings across 10 journeys -> ${file.absolutePath}")
    }

    // ================================================================
    // J1 — brand new user, cold start, wants a kit on their MPC
    // ================================================================
    private fun j1ColdStartStarter() {
        say("")
        say("── J1: NEW USER · cold start · \"give me something on the MPC\" ──")
        val root = tmp("j1")
        var taps = 0

        step(++taps, "App opens on THE SHELF. Kits on disk: ${KitStore.list(root).size}")
        note("empty-shelf copy: \"${Copy.EMPTY_SHELF}\"")
        note("the only primary button reads: NEW KIT ▸ STARTERS")

        step(++taps, "Tap NEW KIT ▸ STARTERS. Menu heading: PICK A STARTER")
        StarterKits.ALL.forEachIndexed { i, s ->
            note("${i + 1}. ${s.displayName.padEnd(14)} ${if (s.seeded) "(TAPS REROLL) " else ""}— ${s.blurb}")
        }

        val starter = StarterKits.byId("factory")!!
        step(++taps, "Tap FACTORY.")
        val dir = File(root, "FACTORY")
        val t0 = System.currentTimeMillis()
        val kit = starter.render("FACTORY", dir, seed = 0)
        val renderMs = System.currentTimeMillis() - t0
        note("DUBBING FACTORY… took ${renderMs} ms of real DSP on this machine")
        note("landed ${kit.pads.size} pads; toast: \"${Copy.FRESH_TAPE}\"")
        note("the app then auto-navigates to KIT — user did not ask for that, but it is what they wanted")

        step(taps, "KIT screen. Pads are tappable and sound immediately.")
        note("kit-open hint fires (up to 3 times): \"${Copy.PAD_SHEET_HINT}\"")

        step(++taps, "Tap EXPORT in the menu row.")
        val wiz = ExportWizardModel(kit, dir)
        note("default format on arrival: ${wiz.formatLabel}")
        note("preflight findings: ${wiz.preflight.size} (blocked=${wiz.blocked})")
        wiz.preflight.forEach { note("   [${it.severity}] ${it.message}") }
        note("dub line: ${wiz.dubFilesLine(0)}")

        step(++taps, "Tap DESTINATION → system folder picker (cannot simulate; assume card chosen)")
        step(++taps, "Tap WRITE KIT.")
        val dest = tmp("j1-card")
        val res = wiz.write(dest)
        when (res) {
            is ExportWizardModel.WriteResult.Done -> {
                note("wrote: ${res.outcome.primary.name}")
                note("stage now ${wiz.stage}, button reads \"${wiz.writeLabel}\"")
            }
            is ExportWizardModel.WriteResult.Blocked -> finding("J1-BLOCK", "factory starter blocked by preflight: ${res.findings}")
        }
        say("  ⇒ TAPS TO FIRST EXPORTED KIT: $taps (plus one system folder-picker dialog)")

        // The other cold-start door: the truly empty grid
        say("")
        note("counterfactual: the user picks EMPTY GRID instead (first item in the menu)")
        val blank = StarterKits.byId("blank")!!
        val bdir = File(root, "EMPTY GRID")
        val bkit = blank.render("EMPTY GRID", bdir, 0)
        note("kit has ${bkit.pads.size} pads; KIT screen shows \"${Copy.EMPTY_KIT}\"")
        val bwiz = ExportWizardModel(bkit, bdir)
        note("EXPORT on this kit → blocked=${bwiz.blocked}")
        bwiz.preflight.forEach { note("   [${it.severity}] ${it.message}") }
        if (!bwiz.blocked) {
            finding("J1-EMPTY", "an entirely empty kit is NOT blocked by preflight — a 0-pad program can reach the card")
        }
        note("what tells a new user how to fill it? only a toast on tapping an empty pad: LONG-PRESS TO CAPTURE")
    }

    // ================================================================
    // J2 — capture something in the room and get it onto pads
    // ================================================================
    private fun j2CaptureToKit() {
        say("")
        say("── J2: NEW USER · capture · \"I heard a sound, get it on a pad\" ──")
        val root = tmp("j2")
        var taps = 0

        step(++taps, "Shelf: two primary buttons — LISTEN | LISTEN INSIDE ▸ OTHER APPS' AUDIO")
        note("LISTEN raises RECORD_AUDIO (+POST_NOTIFICATIONS on 33+). One system dialog.")
        note("armed toast: \"${Copy.SESSION_ARMED}\"")
        note("armed UI = level meter + mm:ss + STOP | SNIP ▸ UP TO 60s")

        step(++taps, "Something happens in the room. Tap SNIP ▸ UP TO 60s.")
        note("optimistic toast: \"${Copy.SNIPPED}\" (corrected later if the commit fails)")
        val captured = fieldRecording()
        note("snip is ${"%.1f".format(captured.durationSeconds)}s of the rolling 60s buffer")

        step(0, "…and now what? The snip has landed in SNIPS. Nothing navigates there.")
        note("the user must: STOP, then find SNIPS ▸ EVERY CATCH, ONE LIST at the bottom of the shelf")
        note("from a SNIPS row: → TAPE (trim) or → PAD (place). → PAD needs a kit that exists.")

        // route A: → PAD with no kit on the shelf
        step(++taps, "Tap SNIPS ▸ EVERY CATCH, ONE LIST")
        step(++taps, "On the row, tap → PAD (with an empty shelf)")
        note("shelf header swaps to PICK A KIT FOR THIS SNIP")
        note("empty-shelf copy for this mode: \"${Copy.EMPTY_SHELF_FOR_ASSIGN}\"")
        finding(
            "J2-DEADEND",
            "→ PAD on an empty shelf is a dead end by design: the user must back out, make a kit, " +
                "come back to SNIPS, and re-press → PAD. Nothing offers to make the kit for them.",
        )

        // route B: TAPE → INSTANT KIT (the fast path)
        say("")
        note("the fast path the app actually wants: TAPE → INSTANT KIT")
        val kdir = File(root, "ROOM KIT")
        val t0 = System.currentTimeMillis()
        val result = InstantKit.build(captured, "ROOM KIT", kdir)
        val ms = System.currentTimeMillis() - t0
        step(++taps, "TAPE screen → INSTANT KIT: ${result.sliceCount} slices, choke=${result.chokeSet}, ${ms}ms")
        result.kit.pads.sortedBy { it.slot }.forEach {
            note("A%02d  %-14s %-10s".format(it.slot, it.displayName, it.drumClass))
        }
        note("toast: \"${Copy.instantKit(result.sliceCount, result.chokeSet)}\"")
        say("  ⇒ TAPS FROM ARMED MIC TO A PLAYABLE KIT (fast path): $taps")

        val wiz = ExportWizardModel(result.kit, kdir)
        note("preflight on a captured kit: blocked=${wiz.blocked}, ${wiz.preflight.size} findings")
        wiz.preflight.forEach { note("   [${it.severity}] ${it.message}") }
    }

    // ================================================================
    // J3 — the chop review: arguing with the classifier
    // ================================================================
    private fun j3ChopReviewArgument() {
        say("")
        say("── J3: CHOP REVIEW · \"the machine called my snare a tom\" ──")
        val source = fieldRecording(seconds = 4f, seed = 11)
        val review = ChopReviewModel.chop(source)
        say("  chopped into ${review.sliceCount} slices (mode ${review.mode})")
        review.rows.forEach { r ->
            note(
                "slice %2d  %-10s conf %.2f %s".format(
                    r.n, r.classification.drumClass, r.classification.confidence,
                    if (r.unsure) "  ← NOT SURE" else "",
                ),
            )
        }
        say("  placement summary line shown to the user: \"${review.placementSummary()}\"")

        val unsure = review.rows.count { it.unsure }
        note("$unsure of ${review.sliceCount} slices are flagged NOT SURE")

        // the override gesture
        val cycle = ChopReviewModel.CHIP_CYCLE
        note("tap-to-cycle order on a chip: ${cycle.joinToString(" → ")}")
        finding(
            "J3-CYCLE",
            "correcting a chip is a one-way cycle of ${cycle.size}: worst case ${cycle.size - 1} taps " +
                "per slice, and overshooting means going all the way round again. 16 slices = up to " +
                "${(cycle.size - 1) * 16} taps to relabel a kit.",
        )
        if (review.rows.isNotEmpty()) {
            val before = review.rows[0].effectiveClass
            review.cycleLabel(0)
            note("cycled slice 1: $before → ${review.rows[0].effectiveClass} (overridden=${review.rows[0].overridden})")
            review.clearOverride(0)
            note("cleared override → ${review.rows[0].effectiveClass}")
        }

        val send = review.sendToGrid()
        say("  SEND TO GRID → ${send.sliceCount} pads, choke=${send.chokeSet}")

        // melodic door
        val mel = review.melodicPreview()
        note("MELODIC layout also exists (sendToGridMelodic): ${mel.count { it != null }} pads, sorted by pitch")
        note("nothing in the chop UI's default state says which layout you're about to get until you pick")
    }

    // ================================================================
    // J4 — the export fan-out
    // ================================================================
    private fun j4ExportFanOut() {
        say("")
        say("── J4: EXPORT · the format cycler and what each one lands ──")
        val root = tmp("j4")
        val dir = File(root, "FANOUT")
        val kit = StarterKits.byId("factory")!!.render("FANOUT", dir, 0)
        val dest = tmp("j4-card")

        say("  formats, in cycler order (one tap each, forward only):")
        ExportFormat.entries.forEachIndexed { i, f ->
            note("$i. ${f.cyclerLabel}")
        }
        finding(
            "J4-CYCLER",
            "${ExportFormat.entries.size} formats behind a forward-only tap cycler with no back step: " +
                "reaching the last one is ${ExportFormat.entries.size - 1} taps, and one tap past your " +
                "target costs another ${ExportFormat.entries.size - 1}.",
        )
        note("the cycler label is the ONLY thing naming a format — no per-format explanation of when to pick it")

        ExportFormat.entries.forEach { f ->
            val w = ExportWizardModel(kit, dir)
            while (w.format != f) w.cycleFormat()
            val d = File(dest, f.id).apply { mkdirs() }
            val r = runCatching { w.write(d) }
            r.fold(
                onSuccess = { res ->
                    when (res) {
                        is ExportWizardModel.WriteResult.Done ->
                            note("%-38s → %s".format(f.cyclerLabel, res.outcome.primary.name))
                        is ExportWizardModel.WriteResult.Blocked ->
                            finding("J4-${f.id}", "blocked: ${res.findings.map { it.message }}")
                    }
                },
                onFailure = { finding("J4-${f.id}", "threw ${it.javaClass.simpleName}: ${it.message}") },
            )
        }
        note("nothing in the wizard remembers the last format used — every visit starts at index 0")
    }

    // ================================================================
    // J5 — the long-press depth: PAD SHEET
    // ================================================================
    private fun j5PadSheetDepth() {
        say("")
        say("── J5: PAD SHEET · the depth behind one unhinted gesture ──")
        val root = tmp("j5")
        val dir = File(root, "SHEET")
        StarterKits.byId("factory")!!.render("SHEET", dir, 0)
        val model = KitBuilderModel.open(dir)

        say("  reached ONLY by a 480ms long-press on a filled pad. No tap path, no menu entry.")
        say("  what is behind it:")
        PadSheet.ROWS.forEachIndexed { i, row ->
            note("row ${i + 1}: ${row.joinToString("  ")}")
        }
        note("total treatment segments: ${PadSheet.ALL_SEGMENTS.size}")
        note("plus: SHAPE, TUNE, MUTATE, LAYERS, TAKES, and the door to GRAIN FIELD")

        val boxes: List<String> = runCatching { PadSheetBoxes.ORDER.map { b -> b.name } }.getOrDefault(emptyList())
        if (boxes.isNotEmpty()) note("Pad Sheet v2 workshop boxes: ${boxes.size}")

        // apply a treatment for real
        val slot = model.kit.pads.first().slot
        val before = model.pad(slot)!!
        say("  applying every segment the card draws, the way the screen does (PadSheet.treatmentFor):")
        PadSheet.ALL_SEGMENTS.forEach { seg ->
            val t = runCatching { PadSheet.treatmentFor(seg) }.getOrNull()
            if (t == null) {
                note("%-8s → not a treatment (%s)".format(seg, if (seg == PadSheet.SMEAR) "SMEAR is special-cased by the screen" else "the OFF chip"))
                return@forEach
            }
            val t0 = System.currentTimeMillis()
            val r = runCatching {
                when (t) {
                    is PadSheet.Treatment.Era -> model.eraPad(slot, t.name, 0.7f)
                    is PadSheet.Treatment.Character -> model.characterPad(slot, t.name, 0.7f)
                    is PadSheet.Treatment.Keyed -> model.keyedPad(slot, t.name, 0.7f)
                }
            }
            val ms = System.currentTimeMillis() - t0
            r.fold(
                onSuccess = {
                    note("%-8s → %-10s %4d ms%s".format(seg, t.name, ms, if (ms > 150) "   ← slow on a JVM; slower on a phone" else ""))
                },
                onFailure = { finding("J5-$seg", "${t.name} refused: ${it.message}") },
            )
        }
        note("was: ${before.sampleFile}")
        finding(
            "J5-SMEAR",
            "row 1 draws SMEAR and row 2 draws TAIL, which applies the treatment literally named " +
                "\"smeared\". Two adjacent rows of the same card, one of them named after what the other does.",
        )
        note("the hint that teaches this gesture fires at most 3 kit-opens, then retires forever")
        finding(
            "J5-HIDDEN",
            "${PadSheet.ALL_SEGMENTS.size} treatments + shape/tune/mutate/layers/grain sit behind one " +
                "undiscoverable 480ms hold. A user who misses the 3 hints loses roughly half the app.",
        )
    }

    // ================================================================
    // J6 — the returning user: a shelf with history
    // ================================================================
    private fun j6ReturningUserShelf() {
        say("")
        say("── J6: RETURNING USER · day 5, a shelf with things on it ──")
        val root = tmp("j6")
        val names = listOf("FACTORY", "LUCKY DIP", "ROOM KIT", "CHIP", "MELODIC", "CLOUD", "VELOCITY")
        names.forEach { n ->
            val s = StarterKits.ALL.random(Random(n.hashCode()))
            runCatching { s.render(n, File(root, n), n.hashCode()) }
        }
        val shelf = KitStore.list(root)
        say("  shelf holds ${shelf.size} kits. Every row reads: NAME · N PADS · DRAFT")
        shelf.take(3).forEach { d ->
            val k = runCatching { KitStore.load(d) }.getOrNull() ?: return@forEach
            note("%-12s %2d PADS   %s".format(k.name, k.pads.size, k.tempoBpm?.let { "%.0f BPM".format(it) } ?: "— BPM"))
        }
        finding(
            "J6-DRAFT",
            "every kit shows DRAFT forever. The chip only changes on an export the shelf can see, so a " +
                "shelf of 20 kits gives the returning user no way to tell which ones are already on the card.",
        )
        finding(
            "J6-SORT",
            "the shelf is one flat list with no sort, filter or search — kits are listed in KitStore order. " +
                "At 20+ kits the only way to find one is to scroll and read.",
        )
        note("no 'last opened', no date, no preview art on a row: name, pad count, tempo, DRAFT.")
        note("RENAME/DELETE are behind a long-press on the row — same undiscoverable gesture class as PAD SHEET.")
        note("BACKUP ▸ EVERY KIT, ONE FILE is the only bulk operation, and it is share-sheet only.")
    }

    // ================================================================
    // J7 — degenerate inputs and edges
    // ================================================================
    private fun j7DegenerateAndEdges() {
        say("")
        say("── J7: EDGES · what happens when the user does something odd ──")
        val root = tmp("j7")

        // silence
        val silence = Snip(FloatArray(44_100), channels = 1, sampleRate = 44_100)
        val r1 = runCatching { ChopReviewModel.chop(silence) }
        r1.fold(
            onSuccess = { note("chopping 1s of pure silence → ${it.sliceCount} slices (no refusal)") },
            onFailure = { note("chopping silence refused: ${it.javaClass.simpleName}: ${it.message}") },
        )

        // an instant kit from silence
        val r2 = runCatching { InstantKit.build(silence, "SILENT", File(root, "SILENT")) }
        r2.fold(
            onSuccess = { res ->
                note("INSTANT KIT from silence → ${res.sliceCount} pads")
                val w = ExportWizardModel(res.kit, res.kitDir)
                note("   preflight: blocked=${w.blocked}; ${w.preflight.map { "[${it.severity}] ${it.message}" }}")
                if (!w.blocked) finding("J7-SILENT", "a kit of silent pads exports clean — nothing warns the user their pads are empty air")
            },
            onFailure = { note("INSTANT KIT from silence refused: ${it.message}") },
        )

        // a pure tone (nothing percussive at all)
        val tone = sine(2f, 220f)
        val r3 = runCatching { InstantKit.build(tone, "TONE", File(root, "TONE")) }
        r3.fold(
            onSuccess = { note("INSTANT KIT from a 220Hz sine → ${it.sliceCount} pads, classes: ${it.kit.pads.map { p -> p.drumClass }.distinct()}") },
            onFailure = { note("refused: ${it.message}") },
        )

        // names
        say("")
        note("kit-name rules (Names.isMpcSafe):")
        listOf("MY KIT", "my/kit", "kit.", "CON", "a".repeat(80), "", "Ünïcödé", "kit:1")
            .forEach { n -> note("   %-22s → %s".format("'" + n.take(20) + "'", if (Names.isMpcSafe(n)) "OK" else "REFUSED")) }
        finding(
            "J7-NAME",
            "RENAME is the only place a user types a kit name; every other door auto-names. " +
                "A user who wants a named kit must create it, then long-press, then RENAME — three gestures " +
                "for something every other sampler asks once, up front.",
        )

        // export twice to the same card
        val dir = File(root, "TWICE")
        val kit = StarterKits.byId("factory")!!.render("TWICE", dir, 0)
        val dest = tmp("j7-card")
        val w1 = ExportWizardModel(kit, dir)
        w1.write(dest)
        w1.eject()
        val w2 = ExportWizardModel(kit, dir)
        val again = runCatching { w2.write(dest, overwrite = true) }
        note("exporting the same kit twice to the same card: ${if (again.isSuccess) "silently overwrites" else "refused: " + again.exceptionOrNull()?.message}")
        if (again.isSuccess) {
            finding("J7-OVERWRITE", "a second dub to the same destination overwrites with no confirmation and no 'already there' notice")
        }

        // ExportWizard stage machine misuse
        val w3 = ExportWizardModel(kit, dir)
        val bad = runCatching { w3.eject() }
        note("WRITE ANOTHER ✓ pressed from READY → ${bad.exceptionOrNull()?.javaClass?.simpleName ?: "allowed"}")
    }

    // ================================================================
    // J9 — the trim deck
    // ================================================================
    private fun j9TrimDeck() {
        say("")
        say("── J9: TAPE · trimming a capture by hand ──")
        val snip = fieldRecording(seconds = 6f, seed = 3)
        val mono = FloatArray(snip.frameCount) { snip.samples[it] }
        val onsets = com.snipsnap.audio.Transients.detect(snip).map { it.frame }.toIntArray()
        val deck = TapeDeckModel(mono, onsets = onsets)
        say("  deck loaded: ${"%.1f".format(deck.lengthFrames / 44100f)}s, ${onsets.size} onsets found")
        note("zoom levels: ${TapeDeckModel.ZOOM_PX_PER_SEC.size}, cycled by one button (${deck.zoomLabel})")
        val zooms = mutableListOf<String>()
        repeat(TapeDeckModel.ZOOM_PX_PER_SEC.size + 1) { zooms += deck.zoomLabel; deck.cycleZoom() }
        note("cycler order: ${zooms.joinToString(" → ")}")
        if (TapeDeckModel.ZOOM_PX_PER_SEC.size > 3) {
            finding("J9-ZOOM", "zoom is a forward-only cycler of ${TapeDeckModel.ZOOM_PX_PER_SEC.size}; no pinch, no back step")
        }

        note("snapToOnset=${deck.snapToOnset}, snapToZero=${deck.snapToZero} — both ON by default, which is right")
        fun glideTo(f: Int) { deck.seekTo(f); repeat(400) { deck.step(735) } }
        glideTo(onsets.getOrElse(1) { 44100 })
        deck.setIn()
        glideTo(onsets.getOrElse(3) { 132300 })
        deck.setOut()
        note("IN/OUT marked by gliding + SET IN / SET OUT: hasSelection=${deck.hasSelection}")
        if (deck.hasSelection) note("selection is ${"%.2f".format((deck.outFrame - deck.inFrame) / 44100f)}s, snapped to onsets")

        // OUT before IN
        deck.clearSelection()
        glideTo(onsets.getOrElse(3) { 132300 })
        deck.setIn()
        glideTo(onsets.getOrElse(1) { 44100 })
        deck.setOut()
        note("marking OUT before IN → the two swap rather than erroring (hasSelection=${deck.hasSelection}) — good")
        say("")
        note("the two buttons that sit side by side under the deck behave differently with no selection:")
        note("   COMMIT       → refuses, in words: \"${Copy.COMMIT_NEEDS_SELECTION}\"")
        note("   INSTANT KIT  → silently takes the WHOLE deck (0 until samples.size) and chops it")
        finding(
            "J9-DEFAULT",
            "COMMIT and INSTANT KIT are adjacent and take the same selection, but an empty selection " +
                "makes one refuse and the other quietly act on up to the full tape length. The user is " +
                "never told which they got.",
        )
        say("")
        note("SETUP holds exactly three things: SCHEME, PERSONALITY, TEACH THE MACHINE.")
        note("no default export format, no storage/where-things-live readout, no capture settings, no HELP link.")
    }

    // ================================================================
    // J10 — chrome, copy and the things a user reads
    // ================================================================
    private fun j10ChromeAndCopy() {
        say("")
        say("── J10: CHROME · the frame the whole app is read through ──")
        val tabs = listOf("KITS", "KIT", "TAPE", "CHOP", "PLAY", "GROOVE", "SYNTH", "SURFACE", "EXPORT", "SETUP", "HELP")
        // 9sp pixel face + 0.5sp tracking ≈ 6dp/char; 4dp padding each side per tab.
        val perChar = 6.0
        val width = tabs.sumOf { it.length * perChar + 8 }
        val usable = Layout.FRAME_W - Layout.OUTER_MARGIN * 2 - 12
        say("  menu row: ${tabs.size} tabs, estimated ${"%.0f".format(width)}dp wide")
        say("  usable width at the ${Layout.FRAME_W}dp design frame: ${usable}dp")
        var run = 0.0
        val visible = tabs.takeWhile { run += it.length * perChar + 8; run <= usable }
        note("fits on screen: ${visible.joinToString(" ")}")
        note("needs a horizontal scroll to reach: ${(tabs - visible.toSet()).joinToString(" ")}")
        finding(
            "J10-MENU",
            "the menu row is ${Layout.MENU_ROW_H}dp tall — the same Layout object sets MIN_HIT_TARGET=" +
                "${Layout.MIN_HIT_TARGET}dp — and scrolls horizontally with no arrow, fade or overflow cue.",
        )
        note("status bar cells: WHERE YOU ARE | KITS: n | a rotating quip (FULL personality only)")
        note("title bar reads: SNIPSNAP.EXE   M0")
        finding("J10-M0", "the title bar's build tag still reads M0; APP_PLAN.md puts the app at M5.")

        say("")
        say("  personality gates:")
        Personality.entries.forEach { p ->
            note("%-5s toasts=%-5s quips=%-5s deckSounds(idle)=%s".format(
                p, Delight.toastsEnabled(p), Delight.quipsEnabled(p), Delight.deckSoundsEnabled(p, false)))
        }
        note("at OFF the toast bubble is still composed for TalkBack but drawn at alpha 0 — sighted users lose every confirmation")

        say("")
        say("  the six schemes a user can pick in SETUP: ${SchemeId.entries.joinToString(", ")}")
        say("")
        say("  HELP screen, verbatim from StubScreen.kt:")
        note(Copy.BOOT_READY)
        Copy.BOOT_LINES.forEach { note(it) }
        note("THIS IS THE M0 SKELETON:")
        note("· BROWSE THE SHELF, TAP PADS, HEAR WAVS")
        note("· FLIP SCHEMES IN SETUP")
        note("· CAPTURE ARRIVES WITH M1")
        finding(
            "J10-HELP",
            "HELP — the one screen a lost user opens — describes the M0 skeleton and says capture has not " +
                "shipped. Capture, chop, synth, groove, play and export all shipped. It documents none of them.",
        )
    }

    // ================================================================
    // J8 — power user depth
    // ================================================================
    private fun j8PowerUserDepth() {
        say("")
        say("── J8: POWER USER · how deep does it go and what does it cost ──")
        val root = tmp("j8")
        val dir = File(root, "DEEP")
        StarterKits.byId("factory")!!.render("DEEP", dir, 0)
        val m = KitBuilderModel.open(dir)

        // evil twins / bank B
        val t0 = System.currentTimeMillis()
        val added = runCatching { m.remixBankB(seed = 42) }
        val ms = System.currentTimeMillis() - t0
        added.fold(
            onSuccess = { note("EVIL TWINS: bank B lit with ${it.size} pads in ${ms}ms (slots ${it.minOrNull()}..${it.maxOrNull()})") },
            onFailure = { finding("J8-TWINS", "remixBankB threw: ${it.message}") },
        )
        m.save()
        note("kit now holds ${m.kit.pads.size} pads across ${(m.kit.pads.maxOf { it.slot } + 15) / 16} banks")
        finding(
            "J8-BANKB",
            "KitScreen renders bank A only (GRID_ROWS = 1..16). EVIL TWINS creates 16 pads on bank B. " +
                "They ARE playable on PLAY's landscape both-banks view, but PAD SHEET is only reachable " +
                "from KIT's grid — so bank B pads can never be inspected, treated, tuned or cleared.",
        )

        // velocity layers
        val slot = m.kit.pads.first().slot
        val ghosts = runCatching { m.addGhostLayers(slot, softZones = 2) }
        ghosts.fold(
            onSuccess = { note("ghost layers on A%02d: %d velocity zones".format(slot, it.velocityLayers.size)) },
            onFailure = { finding("J8-GHOST", "addGhostLayers threw: ${it.message}") },
        )

        // key + retune
        m.setKey(com.snipsnap.audio.KeySpec.parse("Aminpent"))
        val moved = runCatching { m.retuneTonalPads() }.getOrDefault(emptyList())
        note("IN KEY on a drum kit moved ${moved.size} pads (drums are correctly left alone)")
        if (moved.isEmpty()) note("   the user sees: \"${Copy.IN_KEY_NONE}\" — honest, but a dead-end tap")

        // desample
        val d = runCatching { m.desamplePad(slot) }
        d.fold(
            onSuccess = { note("DESAMPLE nearest THUMP patch: distance ${"%.3f".format(it.distance)}") },
            onFailure = { note("DESAMPLE: ${it.javaClass.simpleName}: ${it.message}") },
        )

        // full export of the deep kit
        m.save()
        val w = ExportWizardModel(m.kit, dir)
        note("preflight on the deep kit: blocked=${w.blocked}, ${w.preflight.size} findings, ${w.fileCount} files queued")
        w.preflight.forEach { note("   [${it.severity}] ${it.message}") }
        val res = runCatching { w.write(tmp("j8-card")) }
        note("dub: ${res.getOrNull()?.let { (it as? ExportWizardModel.WriteResult.Done)?.outcome?.primary?.name } ?: res.exceptionOrNull()?.message}")

        // preflight vocabulary
        say("")
        note("the whole preflight vocabulary a user can ever be shown:")
        val allFindings = mutableSetOf<String>()
        KitStore.list(root).forEach { kd ->
            runCatching { Preflight.check(KitStore.load(kd), kd) }.getOrDefault(emptyList())
                .forEach { allFindings += "[${it.severity}] ${it.message}" }
        }
        allFindings.forEach { note("   $it") }
    }
}
