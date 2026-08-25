package com.snipsnap.app.ui

import com.snipsnap.kit.Names
import com.snipsnap.shell.StarterKits
import java.util.Locale

/**
 * Naming for the NEW KIT menu, kept out of the composable so it's testable
 * without a Compose test harness.
 *
 * The trap: [StarterKits.ALL] holds `LUCKY DIP A/B`, and `/` is not
 * MPC-safe. [starterKitName] always sanitises before comparing or
 * returning, so what reaches `KitLibrary.createFromStarter` is never the
 * raw, unsafe display name.
 */

/** A free, MPC-safe kit name for [starter], given what is already on the shelf. */
fun starterKitName(starter: StarterKits.Starter, existing: List<String>): String {
    val base = Names.sanitizeStem(starter.displayName)
    val taken = existing.map { it.lowercase(Locale.ROOT) }.toSet()
    if (base.lowercase(Locale.ROOT) !in taken) return base

    var counter = 2
    while (true) {
        val candidate = Names.sanitizeStem(String.format(Locale.ROOT, "%s %02d", base, counter))
        if (candidate.lowercase(Locale.ROOT) !in taken) return candidate
        counter++
    }
}

/** What the shelf says while [kitName] is being rendered. */
fun renderingLine(kitName: String): String = "MAKING $kitName, FROM SCRATCH."
