package com.fujikon.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CreativeFilterTest {

    @Test
    fun landscapeAndPortraitSeriesAreAvailable() {
        assertEquals(3, CreativeFilter.landscape.size)
        assertEquals("富士", CreativeFilter.landscape[0].displayName)
        assertEquals("理光", CreativeFilter.landscape[1].displayName)
        assertEquals("徕卡", CreativeFilter.landscape[2].displayName)
        assertTrue(CreativeFilter.portrait.size >= 3)
        assertTrue(CreativeFilter.portrait.all { it.group == "人像" })
    }

    @Test
    fun idsResolveAndUnknownIdsFallBackToNone() {
        CreativeFilter.entries.forEach { filter ->
            assertEquals(filter, CreativeFilter.fromId(filter.id))
        }
        assertEquals(CreativeFilter.NONE, CreativeFilter.fromId("missing"))
        assertEquals(CreativeFilter.NONE, CreativeFilter.fromId(null))
        assertTrue(CreativeFilter.NONE.isNone)
        assertFalse(CreativeFilter.FUJI.isNone)
    }

    @Test
    fun profilesCarryUsefulRenderingValues() {
        val fuji = CreativeFilter.FUJI.profile
        assertTrue(fuji.temperature > 0f)
        assertTrue(fuji.vibrance > 0f)
        assertTrue(fuji.splitStrength > 0f)
        assertTrue(CreativeFilter.PORTRAIT_MONO.profile.monochrome)
    }
}
