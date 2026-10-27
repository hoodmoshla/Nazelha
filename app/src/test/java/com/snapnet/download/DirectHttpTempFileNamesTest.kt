package com.snapnet.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectHttpTempFileNamesTest {
    @Test
    fun partialNameIsStableAndIndependentOfHashCodeCollisions() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val first = DirectHttpTempFileNames.partialName("Aa")
        val second = DirectHttpTempFileNames.partialName("BB")
        assertNotEquals(first, second)
        assertEquals(first, DirectHttpTempFileNames.partialName("Aa"))
        assertTrue(first.matches(Regex("direct-http-[0-9a-f]{64}\\.part")))
    }
}
