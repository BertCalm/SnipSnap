package com.snipsnap.app.ui

import com.snipsnap.shell.SplayFiniteKeygroupMaker
import com.snipsnap.shell.SplayFinitePadMaker
import com.snipsnap.synth.KeyNote
import java.io.File
import java.util.Locale

/** The common instrument sheet can deliver finite sources without offering unimplemented SPLAY HOLD. */
internal class SplayFiniteHeldSpec(private val spec: SplayFinitePadMaker.Spec) : HeldSpec {
    override val zoneMidis get() = SplayFinitePadMaker.zoneMidis(spec)
    override val hasAttack get() = false
    override val hasRelease get() = false
    override fun shelfNote(name: String) = if (name.isEmpty()) "" else
        "LANDS IN THE LIBRARY AS ${name.uppercase(Locale.ROOT)}, PLAYABLE ON KEYS."
    override val progressPerZone get() = SplayFiniteKeygroupMaker.TOUCHES.size
    override val knobLabel get() = "FINITE SOURCE · GAIN-ONLY VELOCITY"
    override val instrumentNote get() = SplayFinitePadMaker.instrumentNote(spec)
    override fun renderZone(midi: Int, cancelled: () -> Boolean) = SplayFinitePadMaker.renderZone(spec, midi, cancelled)
    override fun preview(cancelled: () -> Boolean) = SplayFinitePadMaker.preview(spec, cancelled)
    override fun export(name: String, notes: List<List<KeyNote>>, destRoot: File) =
        SplayFinitePadMaker.export(name, spec, notes, destRoot)
}
