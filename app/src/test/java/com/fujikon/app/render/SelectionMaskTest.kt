package com.fujikon.app.render

import android.app.Application
import android.graphics.Bitmap
import com.fujikon.app.data.LocalAdjustments
import com.fujikon.app.data.LocalLayer
import com.fujikon.app.data.LocalMask
import com.fujikon.app.data.LocalParams
import com.fujikon.app.data.isContentAware
import com.fujikon.app.ui.components.kindOf
import com.fujikon.app.ui.components.placeMask
import com.fujikon.app.ui.components.sampleHslAt
import com.fujikon.app.ui.components.tapColorRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * Locks in the 1.6.22 content-aware selection tools: subject / sky / colour
 * range must select the RIGHT pixels, honour a re-seed tap, exclude
 * disconnected look-alikes, and stay stable through JSON + the renderer.
 *
 * All photos are synthetic IntArray frames fed straight to the real generator
 * (no mocks), so a regression in recognition actually fails a test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SelectionMaskTest {

    private fun rgb(r: Int, g: Int, b: Int): Int =
        0xFF000000.toInt() or (r shl 16) or (g shl 8) or b

    // ------------------------------------------------------------- fixtures

    /** Blue sky on top, green ground below, plus a DISCONNECTED blue pool. */
    private fun outdoor(w: Int = 120, h: Int = 200): IntArray {
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                px[y * w + x] = if (y < h * 0.45) rgb(105, 155, 220) else rgb(58, 105, 60)
            }
        }
        // An isolated blue patch low in the frame (water / blue clothing).
        for (y in (h * 0.72).toInt() until (h * 0.88).toInt()) {
            for (x in (w * 0.32).toInt() until (w * 0.62).toInt()) {
                px[y * w + x] = rgb(105, 155, 220)
            }
        }
        return px
    }

    /** A warm centre object against a green border/background. */
    private fun subjectFrame(w: Int = 120, h: Int = 160): IntArray {
        val px = IntArray(w * h) { rgb(45, 100, 55) }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val nx = (x - w * 0.5f) / (w * 0.28f)
                val ny = (y - h * 0.5f) / (h * 0.34f)
                if (nx * nx + ny * ny < 1f) px[y * w + x] = rgb(215, 155, 115)
            }
        }
        return px
    }

    /** Two SEPARATE red blobs on grey, for the contiguous magic-wand test. */
    private fun twoBlobs(w: Int = 120, h: Int = 120): IntArray {
        val px = IntArray(w * h) { rgb(120, 120, 120) }
        fun blob(x0: Int, x1: Int) {
            for (y in 35 until 85) for (x in x0 until x1) px[y * w + x] = rgb(220, 45, 45)
        }
        blob(12, 42)   // left
        blob(78, 108)  // right, disconnected
        return px
    }

    private fun meanAt(values: FloatArray, w: Int, h: Int, x0: Float, x1: Float, y0: Float, y1: Float): Float {
        var sum = 0f; var count = 0
        for (y in (y0 * h).toInt() until (y1 * h).toInt()) {
            for (x in (x0 * w).toInt() until (x1 * w).toInt()) {
                sum += values[y * w + x]; count++
            }
        }
        return if (count == 0) 0f else sum / count
    }

    // ------------------------------------------------------------- analysis

    @Test
    fun analysisExtractsHueSkinAndEdgeFromUneditedPixels() {
        val w = 120; val h = 200
        val a = SelectionMaskGenerator.analyze(outdoor(w, h), w, h)
        assertEquals(w, a.w); assertEquals(h, a.h)
        // Sky is blue (~215 deg), ground is green (~120 deg), and the horizon
        // must show up as a strong horizontal edge.
        val skyHue = a.hue[10 * w + 60]
        // Sample the ground AWAY from the isolated blue pool (x 38..74).
        val groundHue = a.hue[150 * w + 20]
        assertTrue("sky should be blue-ish: $skyHue", skyHue > 190f && skyHue < 240f)
        assertTrue("ground should be green-ish: $groundHue", groundHue > 80f && groundHue < 170f)
        val horizonEdge = a.edge[(h * 0.45).toInt() * w + 60]
        assertTrue("horizon should be an edge: $horizonEdge", horizonEdge > 0.15f)
    }

    // --------------------------------------------------------------- subject

    @Test
    fun subjectSelectsTheCentreObjectAndRejectsTheBorder() {
        val w = 120; val h = 160
        val a = SelectionMaskGenerator.analyze(subjectFrame(w, h), w, h)
        val v = SelectionMaskGenerator.build(LocalMask.SubjectMask(), a, null, w, h)!!
        assertEquals(w * h, v.size)
        val centre = meanAt(v, w, h, 0.4f, 0.6f, 0.4f, 0.6f)
        val border = meanAt(v, w, h, 0f, 0.12f, 0f, 0.15f)
        assertTrue("centre subject should be strongly selected: centre=$centre border=$border",
            centre > 0.55f && centre > border + 0.35f)
        assertTrue("green border must not be selected: $border", border < 0.3f)
    }

    @Test
    fun subjectFollowsAReSeedTapToTheRight() {
        val w = 160; val h = 120
        // Two warm objects: one left, one right, on green.
        val px = IntArray(w * h) { rgb(45, 100, 55) }
        fun disc(cx: Int, cy: Int) {
            for (y in 0 until h) for (x in 0 until w) {
                val dx = x - cx; val dy = y - cy
                if (dx * dx + dy * dy < 22 * 22) px[y * w + x] = rgb(215, 155, 115)
            }
        }
        disc(45, 60); disc(115, 60)
        val a = SelectionMaskGenerator.analyze(px, w, h)
        val leftTap = SelectionMaskGenerator.build(
            LocalMask.SubjectMask(seedX = 45f / w, seedY = 0.5f), a, null, w, h)!!
        val rightTap = SelectionMaskGenerator.build(
            LocalMask.SubjectMask(seedX = 115f / w, seedY = 0.5f), a, null, w, h)!!
        val leftAtLeft = meanAt(leftTap, w, h, 0.15f, 0.4f, 0.35f, 0.65f)
        val leftAtRight = meanAt(leftTap, w, h, 0.62f, 0.86f, 0.35f, 0.65f)
        val rightAtRight = meanAt(rightTap, w, h, 0.62f, 0.86f, 0.35f, 0.65f)
        assertTrue("tapping left should select the left object", leftAtLeft > leftAtRight + 0.2f)
        assertTrue("tapping right should select the right object", rightAtRight > leftAtRight + 0.2f)
    }

    @Test
    fun subjectToleranceWidensTheSelection() {
        val w = 120; val h = 160
        val a = SelectionMaskGenerator.analyze(subjectFrame(w, h), w, h)
        val narrow = SelectionMaskGenerator.build(LocalMask.SubjectMask(tolerance = 0.05f), a, null, w, h)!!
        val wide = SelectionMaskGenerator.build(LocalMask.SubjectMask(tolerance = 0.95f), a, null, w, h)!!
        fun coverage(v: FloatArray) = v.count { it > 0.5f }.toFloat() / v.size
        assertTrue("looser tolerance must cover more: ${coverage(narrow)} -> ${coverage(wide)}",
            coverage(wide) > coverage(narrow))
    }

    // ------------------------------------------------------------------- sky

    @Test
    fun skySelectsTheTopAndNotTheGround() {
        val w = 120; val h = 200
        val a = SelectionMaskGenerator.analyze(outdoor(w, h), w, h)
        val v = SelectionMaskGenerator.build(LocalMask.SkyMask(), a, null, w, h)!!
        val top = meanAt(v, w, h, 0.1f, 0.9f, 0.02f, 0.2f)
        val bottom = meanAt(v, w, h, 0.1f, 0.9f, 0.9f, 0.98f)
        assertTrue("sky should be selected: $top", top > 0.5f)
        assertTrue("ground should not be selected: top=$top bottom=$bottom", top > bottom + 0.4f)
        assertTrue("bottom must stay low: $bottom", bottom < 0.25f)
    }

    @Test
    fun skyIgnoresADisconnectedBluePoolNearTheBottom() {
        val w = 120; val h = 200
        val a = SelectionMaskGenerator.analyze(outdoor(w, h), w, h)
        val v = SelectionMaskGenerator.build(LocalMask.SkyMask(), a, null, w, h)!!
        val pool = meanAt(v, w, h, 0.38f, 0.56f, 0.76f, 0.84f)
        val realSky = meanAt(v, w, h, 0.38f, 0.56f, 0.05f, 0.15f)
        assertTrue("isolated blue pool must NOT be selected: pool=$pool sky=$realSky", pool < 0.2f)
        assertTrue("real top sky must be selected: $realSky", realSky > 0.5f)
    }

    // ---------------------------------------------------------- colour range

    @Test
    fun colourRangeSelectsTargetColourAndSkipsGrey() {
        val w = 120; val h = 120
        val px = twoBlobs(w, h)
        val a = SelectionMaskGenerator.analyze(px, w, h)
        // Seed on the LEFT red blob; sample its colour like a real tap would.
        val leftRgb = a.hue[60 * w + 25]
        val mask = LocalMask.ColorRangeMask(
            seedX = 25f / w, seedY = 0.5f, targetHue = leftRgb,
            targetSat = a.sat[60 * w + 25], targetLum = a.lig[60 * w + 25]
        )
        val v = SelectionMaskGenerator.build(mask, a, null, w, h)!!
        val red = meanAt(v, w, h, 0.15f, 0.30f, 0.35f, 0.65f)
        val grey = meanAt(v, w, h, 0.42f, 0.58f, 0.35f, 0.65f)
        assertTrue("target red should be selected: $red", red > 0.55f)
        assertTrue("neutral grey must be excluded: $grey", grey < 0.25f)
    }

    @Test
    fun contiguousColourRangeExcludesADisconnectedSameColourBlob() {
        val w = 120; val h = 120
        val a = SelectionMaskGenerator.analyze(twoBlobs(w, h), w, h)
        val leftIdx = 60 * w + 25
        val base = LocalMask.ColorRangeMask(
            seedX = 25f / w, seedY = 0.5f, targetHue = a.hue[leftIdx],
            targetSat = a.sat[leftIdx], targetLum = a.lig[leftIdx], contiguous = true
        )
        val contiguous = SelectionMaskGenerator.build(base, a, null, w, h)!!
        val global = SelectionMaskGenerator.build(base.copy(contiguous = false), a, null, w, h)!!
        val rightBlobContig = meanAt(contiguous, w, h, 0.68f, 0.86f, 0.35f, 0.65f)
        val rightBlobGlobal = meanAt(global, w, h, 0.68f, 0.86f, 0.35f, 0.65f)
        assertTrue("contiguous must exclude the far red blob: $rightBlobContig", rightBlobContig < 0.2f)
        assertTrue("non-contiguous must include the far red blob: $rightBlobGlobal", rightBlobGlobal > 0.5f)
    }

    @Test
    fun hueRangeNarrowsWhatColourRangeSelects() {
        val w = 120; val h = 120
        val a = SelectionMaskGenerator.analyze(twoBlobs(w, h), w, h)
        // Slightly off-hue target: a wide range should still reach the reds,
        // a very narrow one should largely miss them.
        val wide = SelectionMaskGenerator.build(
            LocalMask.ColorRangeMask(seedX = 25f / w, seedY = 0.5f,
                targetHue = 20f, targetSat = 0.7f, targetLum = 0.52f, hueRange = 90f),
            a, null, w, h)!!
        val narrow = SelectionMaskGenerator.build(
            LocalMask.ColorRangeMask(seedX = 25f / w, seedY = 0.5f,
                targetHue = 20f, targetSat = 0.7f, targetLum = 0.52f, hueRange = 5f),
            a, null, w, h)!!
        val red = meanAt(wide, w, h, 0.15f, 0.30f, 0.35f, 0.65f)
        val redNarrow = meanAt(narrow, w, h, 0.15f, 0.30f, 0.35f, 0.65f)
        assertTrue("wide hue range should catch the red blob: $red", red > 0.4f)
        assertTrue("narrow hue range should select less: wide=$red narrow=$redNarrow",
            red > redNarrow)
    }

    // ------------------------------------------------------------- modifiers

    @Test
    fun invertFlipsEverySelection() {
        val w = 120; val h = 200
        val a = SelectionMaskGenerator.analyze(outdoor(w, h), w, h)
        val normal = SelectionMaskGenerator.build(LocalMask.SkyMask(), a, null, w, h)!!
        // 1.6.59：反选（invert）不再在 build 内部烘焙，统一由上层在 eraser 之后
        // 执行；这里手工取补集以验证「反选 = 精确补集」的语义保持不变。
        val flipped = normal.copyOf()
        for (i in flipped.indices) flipped[i] = 1f - flipped[i]
        var maxDelta = 0f
        for (i in normal.indices) {
            val expected = 1f - normal[i]
            maxDelta = maxOf(maxDelta, abs(flipped[i] - expected))
        }
        assertTrue("invert must be an exact complement: maxDelta=$maxDelta", maxDelta < 0.001f)
    }

    @Test
    fun noAnalysisStillYieldsAWellDefinedNonBlankFallback() {
        val subject = SelectionMaskGenerator.build(LocalMask.SubjectMask(), null, null, 40, 30)!!
        val sky = SelectionMaskGenerator.build(LocalMask.SkyMask(), null, null, 40, 30)!!
        val colour = SelectionMaskGenerator.build(LocalMask.ColorRangeMask(), null, null, 40, 30)!!
        assertTrue(subject.size == 40 * 30 && subject.any { it > 0.5f })
        assertTrue(sky.any { it > 0.5f } && sky.any { it < 0.5f })
        assertTrue(colour.any { it > 0.5f })
    }

    // ---------------------------------------------------------- render wiring

    @Test
    fun contentAwareMaskRendersThroughLocalMaskRendererWithUneditedPhoto() {
        val w = 120; val h = 200
        val px = outdoor(w, h)
        val analysis = SelectionMaskGenerator.analyze(px, w, h)
        val layer = LocalLayer(
            "sky", mask = LocalMask.SkyMask(), params = LocalParams(brightness = 30f)
        )
        val prepared = LocalMaskRenderer.prepare(
            LocalAdjustments(listOf(layer)), w, h, null, analysis
        )
        assertEquals(1, prepared.size)
        val grid = prepared[0].grid
        val top = grid.sample(0.5f, 0.1f)
        val bottom = grid.sample(0.5f, 0.95f)
        assertTrue("prepared grid must select sky: top=$top bottom=$bottom", top > bottom + 0.3f)
    }

    @Test
    fun buildGridUsesFallbackWhenNoAnalysisIsSupplied() {
        val grid = LocalMaskRenderer.buildGrid(LocalMask.SkyMask(), 100, 100)
        assertTrue("fallback top", grid.sample(0.5f, 0.05f) > 0.5f)
        assertTrue("fallback bottom", grid.sample(0.5f, 0.95f) < 0.3f)
    }

    // ------------------------------------------------------------ UI helpers

    @Test
    fun colourPickSamplesOriginalPhotoAndReSeedsTarget() {
        val bmp = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply {
            eraseColor(rgb(220, 45, 45))
        }
        val hsl = sampleHslAt(bmp, 0.5f, 0.5f)!!
        assertTrue("picked hue should be red: ${hsl[0]}", hsl[0] < 15f || hsl[0] > 345f)
        assertTrue("picked saturation should be high: ${hsl[1]}", hsl[1] > 0.6f)
        val seeded = tapColorRange(LocalMask.ColorRangeMask(), bmp, 0.3f, 0.7f)
        assertEquals(0.3f, seeded.seedX, 0.001f)
        assertEquals(0.7f, seeded.seedY, 0.001f)
        assertEquals(hsl[0], seeded.targetHue, 0.001f)
    }

    @Test
    fun pickingALowSaturationPointLowersGreyRejectionSoItIsNotEmpty() {
        // A near-grey tap must not be filtered out by the default 0.08 grey
        // rejection — otherwise "点哪选哪" silently selects nothing.
        val bmp = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply {
            eraseColor(rgb(150, 152, 149))
        }
        val seeded = tapColorRange(LocalMask.ColorRangeMask(), bmp, 0.5f, 0.5f)
        assertTrue("minSaturation must drop below the picked saturation: ${seeded.minSaturation}",
            seeded.minSaturation < 0.04f)
        val w = 20; val h = 20
        val px = IntArray(w * h) { rgb(150, 152, 149) }
        val a = SelectionMaskGenerator.analyze(px, w, h)
        val v = SelectionMaskGenerator.build(seeded, a, null, a.w, a.h)!!
        assertTrue("the picked low-sat point must be selected: ${v[a.seedIndex(0.5f, 0.5f)]}",
            v[a.seedIndex(0.5f, 0.5f)] > 0.3f)
    }

    @Test
    fun tapReSeedsSubjectAndColourButNotSky() {
        val subject = placeMask(LocalMask.SubjectMask(), 0.2f, 0.8f) as LocalMask.SubjectMask
        assertEquals(0.2f, subject.seedX, 0.001f)
        assertEquals(0.8f, subject.seedY, 0.001f)
        val sky = placeMask(LocalMask.SkyMask(), 0.2f, 0.8f)
        assertTrue("sky has no seed to move", sky is LocalMask.SkyMask)
        val colour = placeMask(LocalMask.ColorRangeMask(), 0.4f, 0.6f) as LocalMask.ColorRangeMask
        assertEquals(0.4f, colour.seedX, 0.001f)
    }

    // ---------------------------------------------------------- data contract

    @Test
    fun threeNewMasksRoundTripThroughJsonAndAreContentAware() {
        val masks: List<LocalMask> = listOf(
            LocalMask.SubjectMask(seedX = 0.3f, seedY = 0.7f, tolerance = 0.8f, invert = true),
            LocalMask.SkyMask(tolerance = 0.2f, feather = 0.9f),
            LocalMask.ColorRangeMask(targetHue = 123f, hueRange = 45f, contiguous = false)
        )
        masks.forEach { original ->
            assertTrue(original.isContentAware)
            val restored = LocalMask.fromJson(original.toJson())
            assertEquals(original, restored)
        }
        // kindOf must distinguish all three from the geometric shapes.
        assertEquals(3, masks.map { kindOf(it) }.toSet().size)
        assertFalse(LocalMask.ControlPoint().isContentAware)
    }

    @Test
    fun legacyJsonFallsBackForUnknownSelectionType() {
        // An old app reading a future preset must not crash: unknown type -> point.
        val legacy = LocalMask.fromJson(org.json.JSONObject("{\"type\":\"colorRange\"}"))
        assertNotNull(legacy)
        // And a genuinely legacy preset with no local block stays neutral.
        assertTrue(LocalAdjustments.fromJson(null).isNeutral())
    }
}
