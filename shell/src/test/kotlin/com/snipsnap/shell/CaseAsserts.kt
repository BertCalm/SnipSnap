package com.snipsnap.shell

import kotlin.test.assertTrue

/**
 * The case law's one test-side reading of "this line is a sentence" (clarity spec 7.5):
 * sentence case, so it opens on a capital and is not all caps. The reflective law in
 * `PersonalityTest` holds the constants; this is for the lines a test builds by calling a
 * function. It replaces the old "TapeOS shouts" check (`line.uppercase() == line`).
 */
internal fun assertSentence(line: String, what: String = "line") {
    val first = line.firstOrNull { it.isLetter() }
    assertTrue(first == null || first.isUpperCase(), "$what must open with a capital (sentence case): '$line'")
    assertTrue(line.any { it.isLowerCase() }, "$what is all caps; a sentence is in sentence case: '$line'")
}
