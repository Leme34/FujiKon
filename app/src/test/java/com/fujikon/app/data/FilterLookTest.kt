package com.fujikon.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The 写真 look is a composite recipe, not just a tone grade. These tests lock
 * down that the recipe is resolved at render time from the filter id + strength
 * (so it never needs new persisted JSON fields), that manual edits win, and —
 * most importantly — that it keeps a solid black point, real contrast/chroma
 * and micro-texture instead of washing the portrait into a grey haze.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FilterLookTest {

    private val studio = EditAdjustments(
        creativeFilter = CreativeFilter.PORTRAIT_STUDIO.id,
        filterStrength = 100f
    )

    @Test
    fun studioFilterExistsInPortraitGroupAndCarriesAFullRecipe() {
        val filter = CreativeFilter.fromId("portrait_studio")
        assertEquals(CreativeFilter.PORTRAIT_STUDIO, filter)
        assertEquals("写真", filter.displayName)
        assertEquals("人像", filter.group)
        assertTrue(CreativeFilter.portrait.contains(filter))

        val profile = filter.profile
        assertFalse(profile.look.isEmpty())
        assertTrue(profile.tip.isNotBlank())

        // Anti-grey guardrails. 1.6.20 stacked lifted blacks + fade + negative
        // contrast + global de-saturation and washed the subject out. The
        // reworked grade keeps a solid black point and real contrast/chroma.
        assertTrue("black point must not be lifted", profile.blacks <= 0f)
        assertTrue("no matte fade wash", profile.fade <= 0f)
        assertTrue("contrast must be preserved, not flattened", profile.contrast > 0f)
        assertTrue("no global de-saturation", profile.saturation >= 0f)
        assertTrue("low-chroma pixels should be revived", profile.vibrance > 0f)
        assertTrue("only a gentle shadow lift", profile.shadows > 0f && profile.shadows < 0.10f)
        assertTrue("highlights still rolled off", profile.highlights < 0f)
        assertTrue("split tone must not grey out face shadows", profile.splitStrength <= 0.10f)
        assertTrue("temperature should not push the skin cold", profile.temperature >= 0f)
        assertTrue(profile.shadowTint != null)
        assertTrue(profile.highlightTint != null)
    }

    @Test
    fun lookKeepsSkinBrightAndFoliageCleanWithoutGreyHaze() {
        val look = CreativeFilter.PORTRAIT_STUDIO.profile.look
        val hsl = look.hsl
        // Skin bands brighten (whitening) while KEEPING chroma — no more
        // across-the-board saturation cuts that used to grey the whole frame.
        assertTrue(hsl.band(0).luminance > 0f)
        assertTrue(hsl.band(1).luminance > 0f)
        assertTrue(hsl.band(0).saturation >= 0f)
        assertTrue(hsl.band(1).saturation >= 0f)
        assertTrue(hsl.band(1).hue < 0f)
        // Green band: de-saturate and shift toward cyan, still directional.
        assertTrue(hsl.band(3).saturation < 0f)
        assertTrue(hsl.band(3).hue > 0f)
        assertTrue(hsl.band(3).luminance > 0f)
        // Retouch: light smoothing, real whitening, strong contour/detail
        // payback. contour > smooth is what keeps pores and facial structure.
        assertTrue(look.portrait.smooth > 0f)
        assertTrue(look.portrait.whiten > 0f)
        assertTrue(look.portrait.blush > 0f)
        assertTrue("micro-contrast must be paid back", look.portrait.contour > look.portrait.smooth)
        assertTrue("smoothing must stay gentle", look.portrait.smooth < 25f)
        // Positive texture restores micro-detail; clarity stays almost neutral.
        assertTrue("texture must restore detail", look.texture > 0f)
        assertTrue("clarity must not flatten local contrast", look.clarity < 0f)
        assertTrue("clarity softening must be minimal", look.clarity > -5f)
        assertTrue(look.vignette > 0f)
    }

    @Test
    fun stockFilmLooksHaveNoCompositeRecipe() {
        CreativeFilter.landscape.forEach { assertTrue(it.profile.look.isEmpty()) }
        listOf(
            CreativeFilter.PORTRAIT_SOFT,
            CreativeFilter.PORTRAIT_CREAM,
            CreativeFilter.PORTRAIT_FILM,
            CreativeFilter.PORTRAIT_MONO
        ).forEach { assertTrue(it.profile.look.isEmpty()) }
    }

    @Test
    fun noneFilterAndZeroStrengthAreTrueNoOps() {
        assertSame(EditAdjustments.Neutral, EditAdjustments.Neutral.withFilterLook())
        val zero = studio.copy(filterStrength = 0f)
        assertSame(zero, zero.withFilterLook())
        // A stock look has an empty recipe, so resolving it changes nothing.
        val fuji = EditAdjustments(creativeFilter = CreativeFilter.FUJI.id, filterStrength = 100f)
        assertSame(fuji, fuji.withFilterLook())
    }

    @Test
    fun resolutionInjectsEveryLayerAtFullStrength() {
        val resolved = studio.withFilterLook()
        assertFalse(resolved.hsl.isNeutral())
        assertFalse(resolved.portrait.isOff())
        assertTrue(resolved.clarity < 0f)
        assertTrue(resolved.texture > 0f)
        assertTrue(resolved.vignette > 0f)
        // Full strength equals the recipe exactly.
        assertEquals(
            CreativeFilter.PORTRAIT_STUDIO.profile.look.hsl.band(3).saturation,
            resolved.hsl.band(3).saturation,
            0.001f
        )
        assertEquals(
            CreativeFilter.PORTRAIT_STUDIO.profile.look.portrait.smooth,
            resolved.portrait.smooth,
            0.001f
        )
        // The grade (FilterProfile) is NOT folded in here; buildFilterPlan does
        // that separately, so exposure/contrast stay neutral in adjustments.
        assertEquals(0f, resolved.exposure, 0.001f)
        assertEquals(0f, resolved.contrast, 0.001f)
    }

    @Test
    fun strengthScalesTheWholeRecipe() {
        val half = studio.copy(filterStrength = 50f).withFilterLook()
        val full = studio.withFilterLook()
        assertEquals(full.hsl.band(3).saturation * 0.5f, half.hsl.band(3).saturation, 0.001f)
        assertEquals(full.portrait.smooth * 0.5f, half.portrait.smooth, 0.001f)
        assertEquals(full.clarity * 0.5f, half.clarity, 0.001f)
        assertEquals(full.texture * 0.5f, half.texture, 0.001f)
        assertEquals(full.vignette * 0.5f, half.vignette, 0.001f)
    }

    @Test
    fun manualEditsTakeOverTheirLayer() {
        val manualHsl = HslAdjustments.Neutral.withBand(3, HslBand(saturation = -55f))
        val manualPortrait = PortraitRetouch.Off.withStyle(PortraitStyle.TEXTURE)
        val edited = studio.copy(
            hsl = manualHsl,
            portrait = manualPortrait,
            clarity = 20f,
            texture = 12f,
            vignette = -30f
        ).withFilterLook()

        // Those layers keep the user's values instead of being overwritten.
        assertEquals(-55f, edited.hsl.band(3).saturation, 0.001f)
        assertEquals(manualPortrait.smooth, edited.portrait.smooth, 0.001f)
        assertEquals(20f, edited.clarity, 0.001f)
        assertEquals(12f, edited.texture, 0.001f)
        assertEquals(-30f, edited.vignette, 0.001f)
    }

    @Test
    fun onlyUntouchedLayersAreInjected() {
        val edited = studio.copy(
            hsl = HslAdjustments.Neutral.withBand(3, HslBand(saturation = -55f))
        ).withFilterLook()
        // HSL was manually edited, so it stays; the other layers still fill in.
        assertNotEquals(0f, edited.hsl.band(3).saturation)
        assertFalse(edited.portrait.isOff())
        assertTrue(edited.clarity < 0f)
    }

    @Test
    fun jsonRoundTripStaysBackwardCompatible() {
        // Only the filter id + strength are persisted; the recipe is rebuilt
        // from the enum, so old app versions see an ordinary adjustments blob.
        val json = studio.toJson()
        assertFalse(json.has("look"))
        assertFalse(json.has("portraitStudio"))
        assertEquals(CreativeFilter.PORTRAIT_STUDIO.id, json.getString("creativeFilter"))

        val restored = EditAdjustments.fromJson(json).withFilterLook()
        assertFalse(restored.hsl.isNeutral())
        assertFalse(restored.portrait.isOff())
        assertEquals(studio.filterStrength, restored.filterStrength, 0.001f)
    }
}
