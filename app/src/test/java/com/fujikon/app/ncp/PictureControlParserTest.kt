package com.fujikon.app.ncp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureControlParserTest {

    private fun resource(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()

    @Test
    fun parsesLegacyNcpAgfaOptima() {
        val control = PictureControlParser.parse(resource("agfa_optima.ncp"), "agfa_optima.ncp")
        assertEquals(PictureControlFormat.NCP, control.format)
        assertEquals("Agfa Optima", control.name)
        assertEquals(0x03C2, control.baseProfile)
        assertEquals("自然 Neutral", control.baseProfileName)
        assertEquals(2f, control.sharpening, 0.001f)
        assertNotNull(control.curve)
        assertEquals(256, control.curve!!.size)
        assertEquals(108, control.curve!![0])
        assertEquals(32767, control.curve!![255])
        assertTrue(control.hasCurve)
    }

    @Test
    fun parsesLegacyNcpWithCrushedBlacks() {
        val control = PictureControlParser.parse(resource("nc400.ncp"), "nc400.ncp")
        assertEquals("NC 400", control.name)
        assertEquals(-1, control.saturation)
        assertEquals(-3, control.hue)
        assertEquals(0, control.curve!![0])
        assertEquals(0, control.curve!![10])
        assertEquals(32767, control.curve!![255])
    }

    @Test
    fun parsesNp3FlexibleColorNegativeCyan() {
        val control = PictureControlParser.parse(resource("negative_cyan_01a.np3"), "negative_cyan_01a.np3")
        assertEquals(PictureControlFormat.NP3, control.format)
        assertEquals("Negative Cyan01a", control.name)
        assertEquals(0x0020, control.baseProfile)
        assertEquals(2f, control.sharpening, 0.001f)
        assertEquals(0.5f, control.clarity, 0.001f)
        assertEquals(1f, control.midRangeSharpening, 0.001f)
        assertNull(control.curve)
        assertEquals(-70, control.shadows)
        assertEquals(-15, control.blacks)
        assertEquals(8, control.colorBlender.size)
        assertEquals(20, control.colorBlender[0].hue)
        assertEquals(5, control.colorBlender[0].brightness)
        assertTrue(control.hasColorGrading)
        assertEquals(100, control.colorGrading.highlights.hue)
        assertEquals(7, control.colorGrading.highlights.chroma)
        assertEquals(25, control.colorGrading.shadows.hue)
        assertEquals(20, control.colorGrading.shadows.chroma)
        assertEquals(30, control.colorGrading.blending)
    }

    @Test
    fun parsesNp3ColorBlenderAndGrading() {
        val control = PictureControlParser.parse(resource("clr_bu_01a.np3"), "clr_bu_01a.np3")
        assertEquals("CLR BU 01A", control.name)
        assertEquals(8, control.colorBlender.size)
        assertEquals(10, control.colorBlender[0].hue)
        assertEquals(-25, control.colorBlender[0].chroma)
        assertEquals(25, control.colorBlender[0].brightness)
        assertEquals(140, control.colorGrading.highlights.hue)
        assertEquals(150, control.colorGrading.midtones.hue)
        assertEquals(150, control.colorGrading.shadows.hue)
        assertEquals(60, control.colorGrading.blending)
        assertEquals(0, control.colorGrading.balance)
    }

    @Test
    fun parsesNp3ToneCurveRecord() {
        val control = PictureControlParser.parse(resource("tonecurve-noop.NP3"), "tonecurve-noop.NP3")
        assertEquals(PictureControlFormat.NP3, control.format)
        assertNotNull(control.curve)
        assertEquals(256, control.curve!!.size)
        assertEquals(128, control.curve!![0])
        assertEquals(32767, control.curve!![255])
    }

    @Test(expected = PictureControlParser.ParseException::class)
    fun rejectsNonNcpFile() {
        PictureControlParser.parse("not a preset at all".toByteArray(), "x.ncp")
    }

    @Test
    fun rejectsTruncatedRecord() {
        val bytes = resource("agfa_optima.ncp").copyOf(30)
        var failed = false
        try {
            PictureControlParser.parse(bytes, "broken.ncp")
        } catch (e: PictureControlParser.ParseException) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun plainNcpIsNotNp3() {
        val control = PictureControlParser.parse(resource("nc400_match.ncp"), "nc400_match.ncp")
        assertFalse(control.format == PictureControlFormat.NP3)
        assertEquals("NC 400 MATCH", control.name)
    }
}
