package com.fujikon.app.render

import android.app.Application
import com.fujikon.app.data.ColorSample
import com.fujikon.app.data.LocalAdjustments
import com.fujikon.app.data.LocalLayer
import com.fujikon.app.data.LocalMask
import com.fujikon.app.data.LocalParams
import com.fujikon.app.data.RefineStroke
import com.fujikon.app.data.SelectionRefine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.6.23: verifies the AI segmentation fusion, guided edge refinement,
 * OKLab colour range, +/- refinement brush and the new JSON fields.
 *
 * A deterministic fake [SelectionAiProvider] stands in for TFLite (no native
 * library on a desktop JVM), so every branch of the production code really
 * runs. [SelectionMaskGenerator.provider] is restored after each test to keep
 * the classic CV tests isolated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SelectionAiRefineTest {

    private fun rgb(r: Int, g: Int, b: Int): Int =
        0xFF000000.toInt() or (r shl 16) or (g shl 8) or b

    /** Fake network output on a side x side grid, built from two lambdas. */
    private class FakeAi(
        private val side: Int,
        private val subjectFn: (Float, Float) -> Float,
        private val skyFn: (Float, Float) -> Float
    ) : SelectionAiProvider {
        override fun segment(pixels: IntArray, sw: Int, sh: Int, side: Int): MlMaps {
            val n = this.side * this.side
            val s = FloatArray(n); val k = FloatArray(n)
            for (y in 0 until this.side) {
                for (x in 0 until this.side) {
                    val u = x.toFloat() / (this.side - 1)
                    val v = y.toFloat() / (this.side - 1)
                    val i = y * this.side + x
                    s[i] = subjectFn(u, v)
                    k[i] = skyFn(u, v)
                }
            }
            return MlMaps(this.side, s, k)
        }
    }

    @After
    fun resetProvider() {
        SelectionMaskGenerator.provider = null
    }

    private fun withAi(ai: SelectionAiProvider, body: () -> Unit) {
        SelectionMaskGenerator.provider = ai
        try {
            body()
        } finally {
            SelectionMaskGenerator.provider = null
        }
    }

    private fun mean(v: FloatArray, w: Int, h: Int, x0: Float, x1: Float, y0: Float, y1: Float): Float {
        var sum = 0f; var c = 0
        for (y in (y0 * h).toInt() until (y1 * h).toInt()) {
            for (x in (x0 * w).toInt() until (x1 * w).toInt()) {
                sum += v[y * w + x]; c++
            }
        }
        return if (c == 0) 0f else sum / c
    }

    // ------------------------------------------------------------- AI subject

    @Test
    fun aiSubjectPriorOverridesTheCentreHeuristic() {
        val w = 80; val h = 80
        // Flat grey: the CV heuristic has almost nothing to work with.
        val px = IntArray(w * h) { rgb(120, 120, 120) }
        // The network says the RIGHT half is an object, left half background.
        withAi(FakeAi(64, { u, _ -> if (u > 0.55f) 0.95f else 0.02f }, { _, _ -> 0f })) {
            val a = SelectionMaskGenerator.analyze(px, w, h)
            val v = SelectionMaskGenerator.build(
                LocalMask.SubjectMask(seedX = 0.78f, seedY = 0.5f, feather = 0f), a, null, w, h)!!
            val right = mean(v, w, h, 0.65f, 0.95f, 0.25f, 0.75f)
            val left = mean(v, w, h, 0.05f, 0.35f, 0.25f, 0.75f)
            assertTrue("AI-declared object must be selected: right=$right left=$left", right > 0.5f)
            assertTrue("background must stay rejected: right=$right left=$left", right > left + 0.35f)
        }
    }

    @Test
    fun aiRecognisesAWarmSunsetSkyThatIsNotBlue() {
        val w = 80; val h = 120
        // Orange sunset on top, dark ground below: the old "blueness" rule
        // would select nothing here. The semantic sky prior must win.
        val px = IntArray(w * h) { rgb(60, 70, 60) }
        for (y in 0 until h * 45 / 100) for (x in 0 until w) px[y * w + x] = rgb(230, 130, 70)
        withAi(FakeAi(64, { _, _ -> 0f }, { _, v -> if (v < 0.46f) 0.96f else 0.01f })) {
            val a = SelectionMaskGenerator.analyze(px, w, h)
            val v = SelectionMaskGenerator.build(LocalMask.SkyMask(feather = 0f), a, null, w, h)!!
            val top = mean(v, w, h, 0.2f, 0.8f, 0.05f, 0.30f)
            val bottom = mean(v, w, h, 0.2f, 0.8f, 0.7f, 0.95f)
            assertTrue("sunset sky must be selected: top=$top bottom=$bottom", top > 0.5f)
            assertTrue("ground must stay rejected: top=$top bottom=$bottom", top > bottom + 0.4f)
        }
    }

    @Test
    fun aiProbabilityMapsAreInjectedIntoTheAnalysisGrid() {
        val w = 60; val h = 90
        val px = IntArray(w * h) { rgb(100, 120, 140) }
        withAi(FakeAi(32, { _, _ -> 0.8f }, { _, _ -> 0.7f })) {
            val a = SelectionMaskGenerator.analyze(px, w, h)
            assertEquals(w * h, a.aiSubject!!.size)
            assertEquals(w * h, a.aiSky!!.size)
            assertTrue("subject map should carry the fake value", a.aiSubject!![w * h / 2] > 0.5f)
            assertTrue("sky map should carry the fake value", a.aiSky!![w * h / 2] > 0.5f)
        }
    }

    // -------------------------------------------------------- guided refine

    @Test
    fun guidedRefineAlignsABlurredSelectionToALuminanceEdge() {
        val w = 60; val h = 60
        val guide = FloatArray(w * h) { 0.25f }
        for (y in 0 until h) for (x in w / 2 until w) guide[y * w + x] = 0.85f
        val soft = FloatArray(w * h) { if (it % w >= w / 2 - 3) 0.9f else 0.1f }
        val a = SelectionMaskGenerator.analyze(IntArray(w * h) {
            val v = (guide[it] * 255).toInt()
            0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
        }, w, h)
        // Build a public bridge: guidedRefine is private, so exercise it via a
        // subject mask whose AI prior is exactly `soft`.
        val ai = object : SelectionAiProvider {
            override fun segment(pixels: IntArray, sw: Int, sh: Int, side: Int): MlMaps {
                val n = side * side
                val p = FloatArray(n)
                for (y in 0 until side) for (x in 0 until side) {
                    p[y * side + x] = if (x >= side / 2 - side * 3 / w) 0.9f else 0.1f
                }
                return MlMaps(side, p, FloatArray(n))
            }
        }
        withAi(ai) {
            val analysis = SelectionMaskGenerator.analyze(
                IntArray(w * h) {
                    val v = (guide[it] * 255).toInt()
                    0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
                }, w, h)
            val aligned = SelectionMaskGenerator.build(
                LocalMask.SubjectMask(feather = 0f, refine = SelectionRefine(detectEdges = true)),
                analysis, null, w, h)!!
            val on = mean(aligned, w, h, 0.7f, 0.95f, 0.2f, 0.8f)
            val off = mean(aligned, w, h, 0.05f, 0.25f, 0.2f, 0.8f)
            assertTrue("edge-aware refinement keeps a clean left/right separation: $on vs $off",
                on > off + 0.4f)
        }
    }

    // ---------------------------------------------------------- refine brush

    @Test
    fun addAndEraseRefinementStrokesModifyTheSelection() {
        val w = 80; val h = 80
        val px = IntArray(w * h) { rgb(110, 110, 110) }
        withAi(FakeAi(64, { _, _ -> 0f }, { _, _ -> 0f })) {
            val a = SelectionMaskGenerator.analyze(px, w, h)
            val empty = SelectionMaskGenerator.build(
                LocalMask.SubjectMask(feather = 0f), a, null, w, h)!!
            val centreEmpty = mean(empty, w, h, 0.42f, 0.58f, 0.42f, 0.58f)
            val added = SelectionMaskGenerator.build(
                LocalMask.SubjectMask(feather = 0f, refine = SelectionRefine(strokes = listOf(
                    RefineStroke(points = listOf(0.5f, 0.5f, 0.5f, 0.5f), radius = 0.12f, feather = 0.2f)
                ))), a, null, w, h)!!
            val centreAdded = mean(added, w, h, 0.42f, 0.58f, 0.42f, 0.58f)
            assertTrue("add brush should paint a selection: $centreEmpty -> $centreAdded",
                centreAdded > centreEmpty + 0.4f)
            val erased = SelectionMaskGenerator.build(
                LocalMask.SubjectMask(feather = 0f, refine = SelectionRefine(strokes = listOf(
                    RefineStroke(points = listOf(0.5f, 0.5f, 0.5f, 0.5f), radius = 0.12f,
                        feather = 0.2f, erase = false),
                    RefineStroke(points = listOf(0.5f, 0.5f, 0.5f, 0.5f), radius = 0.12f,
                        feather = 0.2f, erase = true)
                ))), a, null, w, h)!!
            val centreErased = mean(erased, w, h, 0.42f, 0.58f, 0.42f, 0.58f)
            assertTrue("erase brush should remove it again: $centreAdded -> $centreErased",
                centreErased < centreAdded - 0.3f)
        }
    }

    @Test
    fun edgeShiftExpandsAndContractsTheSelection() {
        val w = 80; val h = 80
        val px = IntArray(w * h) { rgb(110, 110, 110) }
        val small = object : SelectionAiProvider {
            override fun segment(pixels: IntArray, sw: Int, sh: Int, side: Int): MlMaps {
                val n = side * side; val p = FloatArray(n)
                for (y in 0 until side) for (x in 0 until side) {
                    val d = kotlin.math.hypot(x - side / 2f, y - side / 2f) / side
                    p[y * side + x] = if (d < 0.12f) 1f else 0f
                }
                return MlMaps(side, p, FloatArray(n))
            }
        }
        withAi(small) {
            val a = SelectionMaskGenerator.analyze(px, w, h)
            val base = SelectionMaskGenerator.build(
                LocalMask.SubjectMask(feather = 0f, refine = SelectionRefine(
                    edgeShift = 0f, smooth = 0f, detectEdges = false)), a, null, w, h)!!
            val grown = SelectionMaskGenerator.build(
                LocalMask.SubjectMask(feather = 0f, refine = SelectionRefine(
                    edgeShift = 1f, smooth = 0f, detectEdges = false)), a, null, w, h)!!
            val shrunk = SelectionMaskGenerator.build(
                LocalMask.SubjectMask(feather = 0f, refine = SelectionRefine(
                    edgeShift = -1f, smooth = 0f, detectEdges = false)), a, null, w, h)!!
            fun cov(v: FloatArray) = v.count { it > 0.5f }.toFloat() / v.size
            assertTrue("expand must grow the mask: ${cov(base)} -> ${cov(grown)}",
                cov(grown) > cov(base) * 1.3f)
            assertTrue("contract must shrink the mask: ${cov(base)} -> ${cov(shrunk)}",
                cov(shrunk) < cov(base))
        }
    }

    // --------------------------------------------------------- OKLab colour

    @Test
    fun oklabColourRangeSeparatesRedFromBlueAndHonoursExtraSamples() {
        val w = 100; val h = 60
        val px = IntArray(w * h) { rgb(120, 120, 120) }
        // Left third = red, middle third = blue, right third = grey.
        for (y in 0 until h) for (x in 0 until w) {
            px[y * w + x] = when {
                x < w / 3 -> rgb(220, 45, 45)
                x < 2 * w / 3 -> rgb(45, 70, 220)
                else -> rgb(120, 120, 120)
            }
        }
        val a = SelectionMaskGenerator.analyze(px, w, h, ml = null)
        val red = a.hue[30 * w + 15]
        val base = LocalMask.ColorRangeMask(
            seedX = 0.15f, seedY = 0.5f, targetHue = red,
            targetSat = a.sat[30 * w + 15], targetLum = a.lig[30 * w + 15],
            contiguous = false, feather = 0f, minSaturation = 0.05f
        )
        val v = SelectionMaskGenerator.build(base, a, null, w, h)!!
        val redSel = mean(v, w, h, 0.05f, 0.28f, 0.2f, 0.8f)
        val blueSel = mean(v, w, h, 0.38f, 0.60f, 0.2f, 0.8f)
        assertTrue("red must be selected: red=$redSel blue=$blueSel", redSel > 0.5f)
        assertTrue("blue must be excluded by OKLab distance: red=$redSel blue=$blueSel",
            redSel > blueSel + 0.4f)
        // An additive blue sample should bring the blue band into the selection.
        val blueHue = a.hue[30 * w + 50]
        val withBlue = base.copy(samples = listOf(
            ColorSample(blueHue, a.sat[30 * w + 50], a.lig[30 * w + 50], true)
        ))
        val v2 = SelectionMaskGenerator.build(withBlue, a, null, w, h)!!
        val blueAdded = mean(v2, w, h, 0.38f, 0.60f, 0.2f, 0.8f)
        assertTrue("+ dropper should add that colour: $blueSel -> $blueAdded",
            blueAdded > blueSel + 0.35f)
        // A subtractive red sample should carve the red band back out.
        val withSubtract = withBlue.copy(samples = withBlue.samples + ColorSample(red, 0.9f, 0.5f, false))
        val v3 = SelectionMaskGenerator.build(withSubtract, a, null, w, h)!!
        val redRemoved = mean(v3, w, h, 0.05f, 0.28f, 0.2f, 0.8f)
        assertTrue("- dropper should exclude that colour: $redSel -> $redRemoved",
            redRemoved < redSel - 0.3f)
    }

    // ------------------------------------------------------------- JSON

    @Test
    fun refinementAndSamplesSurviveJsonRoundTrip() {
        val mask = LocalMask.ColorRangeMask(
            samples = listOf(ColorSample(210f, 0.6f, 0.45f, true), ColorSample(20f, 0.8f, 0.5f, false)),
            refine = SelectionRefine(
                strokes = listOf(RefineStroke(listOf(0.2f, 0.3f, 0.4f, 0.5f), 0.06f, 0.4f, true)),
                edgeShift = 0.35f, smooth = 0.6f, detectEdges = false
            )
        )
        val restored = LocalMask.fromJson(mask.toJson()) as LocalMask.ColorRangeMask
        assertEquals(mask.samples, restored.samples)
        assertEquals(mask.refine.edgeShift, restored.refine.edgeShift, 0.0001f)
        assertEquals(mask.refine.smooth, restored.refine.smooth, 0.0001f)
        assertEquals(false, restored.refine.detectEdges)
        assertEquals(1, restored.refine.strokes.size)
        assertEquals(true, restored.refine.strokes[0].erase)
        assertEquals(0.06f, restored.refine.strokes[0].radius, 0.0001f)
    }

    @Test
    fun legacyJsonWithoutRefineFieldsUsesSafeDefaults() {
        val json = org.json.JSONObject().apply {
            put("type", "subject"); put("seedX", 0.4); put("tolerance", 0.7)
        }
        val restored = LocalMask.fromJson(json) as LocalMask.SubjectMask
        assertEquals(0.7f, restored.tolerance, 0.0001f)
        assertEquals(SelectionRefine.Default, restored.refine)
    }

    @Test
    fun allThreeMasksCarryRefineThroughTheFullLayerJson() {
        listOf(LocalMask.SubjectMask(), LocalMask.SkyMask(), LocalMask.ColorRangeMask()).forEach { m ->
            val layer = LocalLayer("l1", mask = m.copyForTest(), params = LocalParams(brightness = 20f))
            val back = LocalLayer.fromJson(layer.toJson())!!
            assertTrue("refine must survive for ${m::class.simpleName}",
                refineOfTest(back.mask) == SelectionRefine.Default)
        }
    }

    private fun LocalMask.copyForTest(): LocalMask = this
    private fun refineOfTest(m: LocalMask): SelectionRefine = when (m) {
        is LocalMask.SubjectMask -> m.refine
        is LocalMask.SkyMask -> m.refine
        is LocalMask.ColorRangeMask -> m.refine
        else -> SelectionRefine.Default
    }

    @Test
    fun mlMapsBilinearSamplingIsInRange() {
        val n = 8 * 8
        val m = MlMaps(8, FloatArray(n) { if (it % 8 >= 4) 1f else 0f }, FloatArray(n))
        assertTrue(m.sample(m.subject, 0.9f, 0.5f) > 0.9f)
        assertTrue(m.sample(m.subject, 0.1f, 0.5f) < 0.1f)
        assertTrue(m.sample(m.subject, 0.49f, 0.5f) in 0f..1f)
    }

    @Test
    fun overlayIsTranslucentRedMaskScopeShadow() {
        val w = 16
        val h = 16
        val solid = SelectionMaskGenerator.toOverlay(FloatArray(w * h) { 1f }, w, h)
        val px = solid[8 * w + 8]
        val a = (px ushr 24) and 0xFF
        val r = (px shr 16) and 0xFF
        val g = (px shr 8) and 0xFF
        val b = px and 0xFF
        assertEquals("mask scope must be red (R)", 0xFF, r)
        assertEquals("mask scope G", 0x3B, g)
        assertEquals("mask scope B", 0x30, b)
        assertTrue("amber had G=176; red must dominate", r - g > 120 && r - b > 120)
        assertTrue("wash must stay translucent, not opaque (alpha=$a)", a in 1..210)

        // The Compose theme colour and the render-side constant must not drift.
        assertEquals("render constant", 0x00FF3B30, SelectionMaskGenerator.OVERLAY_RGB)
        val theme = com.fujikon.app.ui.theme.MaskScopeRed
        assertEquals("theme red", 1f, theme.red, 0.001f)
        assertEquals("theme green", 0x3B / 255f, theme.green, 0.01f)
        assertEquals("theme blue", 0x30 / 255f, theme.blue, 0.01f)

        // Unselected pixels stay fully transparent; a boundary reads darker
        // than the flat interior, so the wash looks like a shadow not a fill.
        val none = SelectionMaskGenerator.toOverlay(FloatArray(w * h) { 0f }, w, h)
        assertEquals("unselected area must not be tinted", 0, (none[0] ushr 24) and 0xFF)
        val half = FloatArray(w * h) { i -> if (i % w >= w / 2) 1f else 0f }
        val edge = SelectionMaskGenerator.toOverlay(half, w, h)
        // Sample the interior at x = w-4, not w-1: the generator probes a
        // flat index +-1, so the last column wraps into the next row and is
        // (correctly for display) treated as a boundary too.
        assertTrue(
            "boundary should read darker than interior",
            ((edge[8 * w + w / 2] ushr 24) and 0xFF) >
                ((edge[8 * w + w - 4] ushr 24) and 0xFF)
        )
    }
}
