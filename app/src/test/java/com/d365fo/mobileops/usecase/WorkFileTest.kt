package com.d365fo.mobileops.usecase

import com.d365fo.mobileops.data.sync.WorkFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkFileTest {

    @Test
    fun `a sealed file opens with its PIN`() {
        val sealed = WorkFile.seal("""{"items":[]}""", "1234")
        assertTrue(sealed.startsWith(WorkFile.PREFIX))
        assertEquals("""{"items":[]}""", WorkFile.open(sealed, "1234"))
    }

    @Test
    fun `a wrong PIN or another file opens nothing`() {
        val sealed = WorkFile.seal("secret", "1234")
        assertNull(WorkFile.open(sealed, "4321"))
        assertNull(WorkFile.open("not a work file", "1234"))
        assertNull(WorkFile.open(sealed.dropLast(4), "1234"))
    }

    @Test
    fun `the same content seals differently each time`() {
        assertTrue(WorkFile.seal("x", "1234") != WorkFile.seal("x", "1234"))
    }
}
