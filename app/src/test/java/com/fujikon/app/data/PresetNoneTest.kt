package com.fujikon.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetNoneTest {

    @Test
    fun nonePresetIsNeutralAndNotTreatedAsAFilePreset() {
        val none = Preset.none()

        assertEquals(Preset.NONE_ID, none.id)
        assertTrue(none.isNone)
        assertTrue(none.adjustments.isNeutral())
        assertFalse(none.hasCurve)
        assertFalse(none.hasToneCurve)
        assertFalse(none.hasColorBlender)
        assertFalse(none.hasColorGrading)
    }

    @Test
    fun savedCopyOfNoneIsANormalDerivedPreset() {
        val saved = Preset.none().copy(id = "saved-look", name = "日光胶片")
        assertFalse(saved.isNone)
        assertTrue(saved.isDerived)
        assertEquals("日光胶片", saved.name)
    }
}
