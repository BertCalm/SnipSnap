package com.snipsnap.synth

import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Asserts that [block] is refused with a [T] whose message names [cause]. A check on the
 * exception type alone passes for the wrong reason whenever some other guard throws the same
 * type first (an unknown engine, a version mismatch, another field's range), so every refusal
 * a test feeds names the guard it means to reach.
 */
inline fun <reified T : Throwable> assertRefused(cause: String, block: () -> Unit): T {
    val refusal = assertFailsWith<T>(block = block)
    assertTrue(refusal.message.orEmpty().contains(cause),
        "refused for another reason: expected a ${T::class.simpleName} naming \"$cause\", got \"${refusal.message}\"")
    return refusal
}
