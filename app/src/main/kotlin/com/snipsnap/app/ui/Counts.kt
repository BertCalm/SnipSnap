package com.snipsnap.app.ui

/**
 * Counted nouns, in TapeOS's shouting register.
 *
 * Trivial, and worth its own file anyway: the first version of this was a
 * `"$n TAPES"` literal duplicated across the shelf header and the status
 * bar, and it read "1 TAPES" on the device. Logic that renders text is
 * still logic — it belongs somewhere a test can reach.
 */
fun tapes(n: Int): String = if (n == 1) "1 TAPE" else "$n TAPES"

fun snips(n: Int): String = if (n == 1) "1 SNIP" else "$n SNIPS"
