package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PatchTypesTest {
    @Test
    fun `a fixed voice patch exposes its sole named voice`() {
        assertEquals(listOf(ObbligatoPatch.VOICE), PatchTypes.voicesOf(ObbligatoPatch::class.java))
    }

    @Test
    fun `every enum voice getter still exposes its complete enum`() {
        val enumPatches = PatchTypes.all.filter { type -> type.methods.any { it.name == "getVoice" && it.parameterCount == 0 } }
        assertTrue(enumPatches.isNotEmpty())
        for (type in enumPatches) {
            val expected = requireNotNull(type.getMethod("getVoice").returnType.enumConstants).map { (it as Enum<*>).name }
            assertEquals(expected, PatchTypes.voicesOf(type), type.simpleName)
        }
    }
}
