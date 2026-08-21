package com.snipsnap.synth

import com.snipsnap.kit.ExpansionMeta
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.XpnPackager
import java.io.File

/**
 * Packages the factory kit as a single `.xpn` file under testkit/. Run via
 * `./gradlew :synth:generateXpnFile`.
 *
 * The XO_OX toolchain ships MPC-loadable `.xpn` archives, which revises our
 * old "desktop-only" assumption. This file is the acceptance check: if the
 * MPC's expansion import takes it, SnipSnap gains one-file kit sharing.
 */
object XpnFileGenerator {

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit")
        val work = File(root, ".xpnfile-work")
        work.deleteRecursively()

        val kit = KitAssembler.assembleArranged("SnipSnap Factory Kit", ThumpKits.classic(), work)
        val out = XpnPackager.write(
            kit, work,
            outputFile = File(root, "SnipSnap_Factory.xpn"),
            meta = ExpansionMeta(
                title = "SnipSnap Factory",
                version = "1.0.0",
                identifier = "app.snipsnap.factory",
                description = "Sixteen synthesized pads from the THUMP and TINES engines.",
            ),
            preview = Groove.render(ThumpKits.classic(), bpm = 92f, bars = 4, seed = 11),
            overwrite = true,
        )
        work.deleteRecursively()
        println("wrote ${out.absolutePath} (${out.length() / 1024} KB)")
    }
}
