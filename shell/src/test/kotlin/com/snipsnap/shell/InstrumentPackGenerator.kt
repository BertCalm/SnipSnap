package com.snipsnap.shell

import com.snipsnap.kit.ExpansionMeta
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.kit.PackBuilder
import com.snipsnap.synth.Arco
import com.snipsnap.synth.ArcoPatch
import com.snipsnap.synth.ArcoVoice
import com.snipsnap.synth.BorePresets
import com.snipsnap.synth.BoreVoice
import com.snipsnap.synth.Keel
import com.snipsnap.synth.KeelVoice
import com.snipsnap.synth.Mercury
import com.snipsnap.synth.MercuryVoice
import com.snipsnap.synth.Presets
import java.io.File

/**
 * The factory expansion of the instruments made reachable on 2026-11-04: MERCURY's three
 * picker voices and KEEL's three through their own makers, and every [LoopPadMaker]
 * qualifier through the shared one, each voice from its first factory preset. ARCO's two
 * voices joined on 2026-11-06 through [ArcoPadMaker] (the bow's stroke, then the loop, in
 * two touch layers), at their defaults, and BORE's two through [BorePadMaker] (the note's
 * own breath, then the loop) from FLUTE STEADY LOOP and SAX SOLO LOOP: each from the patch
 * its listening clips use. Each is
 * written as the phone writes one (a keygroup package with its sidecar) and the lot is
 * packed by [PackBuilder] as one MPC expansion, both the on-card folder and the `.xpn`.
 *
 * Writes `Instruments/` (the packages) and `Expansions/SnipSnap New Keys/` plus
 * `SnipSnap New Keys.xpn` under the first argument. Engine names after it limit the
 * pack to those engines. Run via `./gradlew :shell:generateInstrumentPack`. Nothing here
 * has been heard: the pack is for the owner's listen on the MPC.
 *
 * `--split` after the folder renders nothing: it packs the `Instruments/` already there
 * once more, one expansion per engine (`SnipSnap Keys THAW.xpn` and so on, under
 * `By engine/`), because the whole pack is too large to hand over in one file. Run via
 * `./gradlew :shell:splitInstrumentPack`.
 */
object InstrumentPackGenerator {

    const val TITLE = "SnipSnap New Keys"
    private const val RELEASE = .5f

    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(args.firstOrNull() ?: "../testkit/loop-instruments-pack").also { it.mkdirs() }
        if (args.getOrNull(1) == "--split") return split(out)
        val only = args.drop(1).map { it.uppercase() }.toSet()
        fun wanted(engine: String) = only.isEmpty() || engine in only
        val instruments = File(out, "Instruments").also { it.deleteRecursively(); it.mkdirs() }
        val made = ArrayList<File>()
        val failed = ArrayList<String>()

        fun make(name: String, block: () -> Unit) {
            val t0 = System.nanoTime()
            runCatching(block).onSuccess {
                made += InstrumentStore.sidecar(instruments, name)
                println("%-26s %6.1f s".format(name, (System.nanoTime() - t0) / 1e9))
            }.onFailure {
                failed += "$name: ${it.message}"
                println("%-26s FAILED %s".format(name, it.message))
            }
        }

        if (wanted("MERCURY")) for (voice in listOf(MercuryVoice.PING, MercuryVoice.SING, MercuryVoice.BLADE)) {
            val spec = MercuryPadMaker.spec(voice, Mercury.defaults(voice), RELEASE)
            make("MERCURY ${voice.name}") {
                MercuryPadMaker.export("MERCURY ${voice.name}", spec, MercuryPadMaker.zoneMidis(spec).map { MercuryPadMaker.renderZone(spec, it) }, instruments)
            }
        }
        if (wanted("KEEL")) for (voice in KeelVoice.entries) {
            val spec = KeelPadMaker.spec(voice, Keel.defaults(voice), RELEASE)
            make("KEEL ${voice.name}") {
                KeelPadMaker.export("KEEL ${voice.name}", spec, KeelPadMaker.zoneMidis(spec).map { KeelPadMaker.renderZone(spec, it) }, instruments)
            }
        }
        if (wanted("ARCO")) for (voice in ArcoVoice.entries) {
            val spec = ArcoPadMaker.spec(ArcoPatch("ARCO ${voice.name}", voice, Arco.defaults(voice)), RELEASE)
            make("ARCO ${voice.name}") {
                ArcoPadMaker.export("ARCO ${voice.name}", spec, ArcoPadMaker.zoneMidis(spec).map { ArcoPadMaker.renderZone(spec, it) }, instruments)
            }
        }
        if (wanted("BORE")) for ((voice, preset) in listOf(BoreVoice.FLUTE to "STEADY LOOP", BoreVoice.SAX to "SOLO LOOP")) {
            val patch = BorePresets.forVoice(voice).single { it.name == preset }
            val spec = BorePadMaker.spec(patch, RELEASE)
            make("BORE ${voice.name}") {
                BorePadMaker.export("BORE ${voice.name}", spec, BorePadMaker.zoneMidis(spec).map { BorePadMaker.renderZone(spec, it) }, instruments)
            }
        }
        val presets = Presets.all().groupBy { it.engine }
        for ((engine, voices) in LoopPadMaker.QUALIFIED.toSortedMap()) {
            if (!wanted(engine)) continue
            val byVoice = presets[engine].orEmpty().groupBy { it.voiceName }
            for (voice in voices.sorted()) {
                val preset = byVoice[voice]?.first() ?: run { failed += "$engine $voice: no factory preset"; null } ?: continue
                val spec = LoopPadMaker.spec(preset, RELEASE)
                val name = "$engine $voice"
                make(name) { LoopPadMaker.export(name, spec, LoopPadMaker.zoneMidis(spec).map { LoopPadMaker.renderZone(spec, it) }, instruments) }
            }
        }

        check(made.isNotEmpty()) { "no instrument was made: $failed" }
        val meta = ExpansionMeta(
            title = TITLE,
            identifier = "app.snipsnap.newkeys",
            description = "Held keys instruments from SnipSnap's synth engines: MERCURY, KEEL, ARCO, BORE and the HOLD-loop qualifiers.",
        )
        val result = PackBuilder.build(emptyList(), out, meta, asXpn = true, overwrite = true, instruments = made)
        println("packed ${result.instruments.size} instruments into ${result.directory.absolutePath} and ${result.xpn?.absolutePath}")
        result.skipped.forEach { (name, why) -> println("skipped $name: $why") }
        failed.forEach { println("not made: $it") }
    }

    /** One expansion per engine from the packages already under [out]'s `Instruments/`, keyed by the name's first word. */
    private fun split(out: File) {
        val instruments = File(out, "Instruments")
        val sidecars = InstrumentStore.list(instruments).map { it.first }
        check(sidecars.isNotEmpty()) { "nothing to split: run generateInstrumentPack first" }
        val dest = File(out, "By engine").also { it.deleteRecursively(); it.mkdirs() }
        for ((engine, group) in sidecars.groupBy { it.name.substringBefore(' ') }.toSortedMap()) {
            val meta = ExpansionMeta(
                title = "SnipSnap Keys $engine",
                identifier = "app.snipsnap.keys.${engine.lowercase()}",
                description = "Held keys instruments from SnipSnap's $engine engine.",
            )
            val result = PackBuilder.build(emptyList(), dest, meta, asXpn = true, overwrite = true, instruments = group)
            val mb = (result.xpn?.length() ?: 0L) / 1e6
            println("%-24s %2d instruments  %6.1f MB".format(meta.title, result.instruments.size, mb))
            result.skipped.forEach { (name, why) -> println("  skipped $name: $why") }
        }
    }
}
