package com.fujikon.app.nikonpc

import com.fujikon.app.ncp.PictureControlParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NikonPcNcpRepairTest {

    @Test
    fun repairsTheKnownMalformedPicCtrlFalseFile() {
        val malformed = resource("piccon12_malformed.ncp")
        val repaired = NikonPcNcpRepair.repair(malformed)

        assertEquals(malformed.size + 2, repaired.size)
        val control = PictureControlParser.parse(repaired, "PICCON12.NCP")
        assertEquals("D2X-safe hi-lo bump", control.name)
        assertEquals(5f, control.sharpening, 0.001f)
        assertNotNull(control.curve)
        assertTrue(control.baseProfile == 0x02FF)
    }

    @Test
    fun leavesValidPresetsUntouched() {
        val valid = resource("agfa_optima.ncp")
        assertSame(valid, NikonPcNcpRepair.repair(valid))
    }

    private fun resource(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()
}
