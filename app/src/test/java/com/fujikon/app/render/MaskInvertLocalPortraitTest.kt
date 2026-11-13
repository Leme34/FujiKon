package com.fujikon.app.render

import com.fujikon.app.data.LocalAdjustments
import com.fujikon.app.data.LocalLayer
import com.fujikon.app.data.LocalMask
import com.fujikon.app.data.LocalParams
import com.fujikon.app.data.EraserStroke
import com.fujikon.app.data.PortraitRetouch
import com.fujikon.app.data.isInverted
import com.fujikon.app.data.withInverted
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.6.58 回归：蒙版反选（全 7 种，含新增的控制点）+ 「选择主体」局部人像美颜。
 *
 * 覆盖：
 *  - [LocalMask.isInverted] / [withInverted] 对 7 种蒙版统一读写、其余参数保留；
 *  - 控制点 invert 让 buildGrid 结果成为精确补集；
 *  - LocalLayer.portrait JSON 往返与旧预设兼容、active() 语义；
 *  - 局部美颜只作用于「选择主体」图层的选区，选区外逐像素不变；
 *  - 无局部美颜 / 非主体图层时 applyLocalPortrait 零影响（不回归既有渲染）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MaskInvertLocalPortraitTest {

    private fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    // ------------------------------------------------------------- invert data

    @Test
    fun invertToggleCoversAllSevenMaskKindsAndPreservesOtherFields() {
        val masks = listOf(
            LocalMask.ControlPoint(x = 0.3f, radius = 0.42f),
            LocalMask.LinearGradient(startY = 0.2f, feather = 0.7f),
            LocalMask.RadialGradient(radiusX = 0.33f, rotationDeg = 27f),
            LocalMask.Brush(radius = 0.09f, rotationDeg = 15f),
            LocalMask.SubjectMask(seedX = 0.22f, tolerance = 0.66f),
            LocalMask.SkyMask(tolerance = 0.44f),
            LocalMask.ColorRangeMask(targetHue = 140f, hueRange = 33f)
        )
        masks.forEach { original ->
            assertFalse("初始应为未反选: $original", original.isInverted)
            val flipped = original.withInverted(true)
            assertTrue("切换后应为已反选: $flipped", flipped.isInverted)
            // 只改 invert：类型与关键字段保持
            assertEquals(original.javaClass, flipped.javaClass)
            val restored = flipped.withInverted(false)
            assertFalse(restored.isInverted)
            assertEquals("再切回应与原始蒙版完全一致: $original", original, restored)
        }
    }

    @Test
    fun controlPointInvertRoundTripsThroughJson() {
        val cp = LocalMask.ControlPoint(0.3f, 0.7f, 0.4f, 0.8f, true)
        val restored = LocalLayer("id", mask = cp).let {
            LocalLayer.fromJson(it.toJson())!!.mask as LocalMask.ControlPoint
        }
        assertEquals(cp, restored)
        assertTrue(restored.invert)
        // 旧预设无 invert 键 -> false
        val legacy = LocalLayer.fromJson(LocalLayer("id", mask = LocalMask.ControlPoint()).toJson())!!
        assertFalse((legacy.mask as LocalMask.ControlPoint).invert)
    }

    @Test
    fun controlPointInvertIsExactComplementInGrid() {
        val base = LocalMaskRenderer.buildGrid(LocalMask.ControlPoint(), 64, 64)
        // 反选由 prepare 在 eraser 之后统一执行，这里验证其结果为精确补集。
        val layer = LocalLayer(
            id = "p", mask = LocalMask.ControlPoint(invert = true),
            params = LocalParams(brightness = 5f)
        )
        val prepared = LocalMaskRenderer.prepare(LocalAdjustments(listOf(layer)), 64, 64, null)
        val inverted = prepared[0].grid
        assertEquals(base.values.size, inverted.values.size)
        var maxDelta = 0f
        for (i in base.values.indices) {
            maxDelta = maxOf(maxDelta, kotlin.math.abs((1f - base.values[i]) - inverted.values[i]))
        }
        assertTrue("控制点反选必须是精确补集: maxDelta=$maxDelta", maxDelta < 0.001f)
        // 中心：正常为 1（选区内），反选为 0；角落相反。
        assertTrue("正常控制点中心应命中", base.sample(0.5f, 0.5f) > 0.9f)
        assertTrue("反选控制点中心应为 0", inverted.sample(0.5f, 0.5f) < 0.1f)
        assertTrue("反选控制点角落应命中", inverted.sample(0f, 0f) > 0.9f)
    }

    /**
     * 1.6.59 回归：选中蒙版区域后用橡皮擦擦除局部，再反选，被擦除的「洞」必须
     * 包含在反选结果中（即反选 = 选中当前蒙版覆盖范围之外的所有区域）。
     */
    @Test
    fun invertedSelectionIncludesErasedHole() {
        val eraser = listOf(
            EraserStroke(
                points = listOf(0.5f, 0.5f, 0.52f, 0.5f),
                radius = 0.1f, feather = 0.1f, strength = 1f
            )
        )
        val layer = LocalLayer(
            id = "p",
            mask = LocalMask.ControlPoint(x = 0.5f, y = 0.5f, radius = 0.4f, feather = 0.1f, invert = true),
            eraser = eraser,
            params = LocalParams(brightness = 5f)
        )
        val prepared = LocalMaskRenderer.prepare(LocalAdjustments(listOf(layer)), 128, 128, null)
        val grid = prepared[0].grid

        // 被擦除的洞（原选区内中心）：反选后必须选中。
        assertTrue("被擦除的洞在反选后应被选中", grid.sample(0.5f, 0.5f) > 0.8f)
        // 原选区、但不在擦除范围内的区域：反选后必须不选中。
        assertTrue("原选区未被擦除处反选后不选", grid.sample(0.5f, 0.75f) < 0.2f)
        // 原选区之外：反选后必须选中。
        assertTrue("原选区之外反选后选中", grid.sample(0f, 0f) > 0.8f)
    }

    /**
     * 反向对照：未反选时，被擦除的洞应保持不被选中（覆盖区域之外）。
     */
    @Test
    fun nonInvertedMaskKeepsErasedHoleUnselected() {
        val eraser = listOf(
            EraserStroke(
                points = listOf(0.5f, 0.5f, 0.52f, 0.5f),
                radius = 0.1f, feather = 0.1f, strength = 1f
            )
        )
        val layer = LocalLayer(
            id = "p",
            mask = LocalMask.ControlPoint(x = 0.5f, y = 0.5f, radius = 0.4f, feather = 0.1f, invert = false),
            eraser = eraser,
            params = LocalParams(brightness = 5f)
        )
        val prepared = LocalMaskRenderer.prepare(LocalAdjustments(listOf(layer)), 128, 128, null)
        val grid = prepared[0].grid
        assertTrue("被擦除的洞应不被选中", grid.sample(0.5f, 0.5f) < 0.2f)
        assertTrue("原选区未被擦除处仍选中", grid.sample(0.5f, 0.75f) > 0.8f)
        assertTrue("原选区之外不选", grid.sample(0f, 0f) < 0.2f)
    }

    // --------------------------------------------------------- portrait layer

    @Test
    fun localPortraitRoundTripsAndLegacyJsonStaysOff() {
        val layer = LocalLayer(
            id = "s",
            mask = LocalMask.SubjectMask(seedX = 0.4f, feather = 0.2f),
            portrait = PortraitRetouch(smooth = 30f, whiten = 22f, blush = 8f, contour = 6f)
        )
        val restored = LocalLayer.fromJson(layer.toJson())!!
        assertEquals(layer.portrait, restored.portrait)
        assertEquals(layer.mask, restored.mask)

        // 旧预设 JSON 没有 portrait 键：默认 Off，不产生局部美颜。
        val legacyJson = LocalLayer(
            id = "old", mask = LocalMask.SubjectMask(), params = LocalParams(brightness = 20f)
        ).toJson()
        legacyJson.remove("portrait")
        val legacy = LocalLayer.fromJson(legacyJson)!!
        assertTrue("旧预设 portrait 应为 Off", legacy.portrait.isOff())
    }

    @Test
    fun activeLayerAccountsForSubjectPortraitOnly() {
        // 「选择主体」图层即使影调参数全 0，只要设置了美颜也要参与渲染。
        val subject = LocalLayer(
            id = "s", mask = LocalMask.SubjectMask(),
            portrait = PortraitRetouch(whiten = 40f)
        )
        assertTrue("主体图层带美颜应 active", subject.active())

        // 非主体图层带 portrait（异常/导入数据）不应因美颜而 active。
        val point = LocalLayer(
            id = "p", mask = LocalMask.ControlPoint(),
            portrait = PortraitRetouch(whiten = 40f)
        )
        assertFalse("非主体图层的 portrait 不参与判定", point.active())

        // 普通中性图层、停用图层仍 inactive。
        assertFalse(LocalLayer("n").active())
        assertFalse(subject.copy(enabled = false).active())
        assertFalse(subject.copy(strength = 0f).active())
    }

    @Test
    fun normalizedClampsLocalPortraitStrengths() {
        val layer = LocalLayer(
            id = "s", mask = LocalMask.SubjectMask(),
            portrait = PortraitRetouch(smooth = 500f, whiten = -20f)
        ).normalized()
        assertEquals(PortraitRetouch.MAX_STRENGTH, layer.portrait.smooth, 0.001f)
        assertEquals(0f, layer.portrait.whiten, 0.001f)
    }

    // ---------------------------------------------------------- render wiring

    @Test
    fun prepareOnlyBuildsPortraitRegionForSubjectLayers() {
        val subject = LocalLayer(
            id = "s", mask = LocalMask.SubjectMask(),
            portrait = PortraitRetouch(whiten = 50f)
        )
        val point = LocalLayer(
            id = "p", mask = LocalMask.ControlPoint(),
            params = LocalParams(brightness = 20f),
            portrait = PortraitRetouch(whiten = 50f) // 非主体：应被忽略
        )
        val prepared = LocalMaskRenderer.prepare(LocalAdjustments(listOf(subject, point)), 80, 80, null)
        assertEquals(2, prepared.size)
        assertFalse(prepared[0].portrait.isOff())
        assertNotNull(prepared[0].portraitRegion)
        assertTrue("非主体图层不应生成美颜", prepared[1].portrait.isOff())
        assertNull(prepared[1].portraitRegion)
    }

    @Test
    fun localPortraitWhitensOnlyInsideSubjectSelection() = runBlocking {
        val w = 80; val h = 80
        val base = IntArray(w * h) { rgb(198, 158, 138) } // 肤色，落在 skinMask 内
        val layer = LocalLayer(
            id = "s",
            mask = LocalMask.SubjectMask(seedX = 0.5f, seedY = 0.5f, feather = 0f),
            portrait = PortraitRetouch(whiten = 100f)
        )
        val prepared = LocalMaskRenderer.prepare(LocalAdjustments(listOf(layer)), w, h, null)

        val pixels = base.copyOf()
        LocalMaskRenderer.applyLocalPortrait(pixels, w, h, prepared, null)

        val centre = pixels[40 * w + 40]
        assertTrue(
            "选区中心皮肤应被美白提亮: R=${(centre shr 16) and 0xFF}",
            ((centre shr 16) and 0xFF) > 198
        )
        assertEquals("选区外（角落）必须逐像素不变", base[0], pixels[0])
        assertEquals("选区外（对侧角落）必须逐像素不变", base[w * h - 1], pixels[w * h - 1])
    }

    @Test
    fun localPortraitRespectsInvertedSubjectSelection() = runBlocking {
        val w = 80; val h = 80
        val base = IntArray(w * h) { rgb(198, 158, 138) }
        suspend fun run(invert: Boolean): IntArray {
            val layer = LocalLayer(
                id = "s",
                mask = LocalMask.SubjectMask(seedX = 0.5f, seedY = 0.5f, feather = 0f, invert = invert),
                portrait = PortraitRetouch(whiten = 100f)
            )
            val prepared = LocalMaskRenderer.prepare(LocalAdjustments(listOf(layer)), w, h, null)
            val px = base.copyOf()
            return px.also { LocalMaskRenderer.applyLocalPortrait(it, w, h, prepared, null) }
        }
        val normal = run(false)
        val inverted = run(true)
        // 正常：中心变亮；反选：角落变亮。两者互补。
        assertTrue("正常选区中心变亮", ((normal[40 * w + 40] shr 16) and 0xFF) > 198)
        assertEquals("正常选区角落不变", base[0], normal[0])
        assertEquals("反选后中心不变", base[40 * w + 40], inverted[40 * w + 40])
        assertTrue("反选后角落变亮", ((inverted[0] shr 16) and 0xFF) > 198)
    }

    @Test
    fun noPortraitLeavesPixelsByteIdentical() = runBlocking {
        val w = 60; val h = 60
        val base = IntArray(w * h) { rgb(198, 158, 138) }
        // 只有影调参数、没有局部美颜：applyLocalPortrait 必须完全不动像素。
        val layer = LocalLayer(
            id = "s", mask = LocalMask.SubjectMask(), params = LocalParams(brightness = 30f)
        )
        val prepared = LocalMaskRenderer.prepare(LocalAdjustments(listOf(layer)), w, h, null)
        val px = base.copyOf()
        LocalMaskRenderer.applyLocalPortrait(px, w, h, prepared, null)
        assertTrue("无局部美颜时逐像素零影响", px.contentEquals(base))

        // 空图层列表同样零影响。
        val px2 = base.copyOf()
        LocalMaskRenderer.applyLocalPortrait(px2, w, h, emptyList(), null)
        assertTrue(px2.contentEquals(base))
    }
}
