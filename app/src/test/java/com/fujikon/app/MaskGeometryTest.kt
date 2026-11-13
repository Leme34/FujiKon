package com.fujikon.app

import com.fujikon.app.data.BrushStroke
import com.fujikon.app.data.LocalAdjustments
import com.fujikon.app.data.LocalLayer
import com.fujikon.app.data.LocalMask
import com.fujikon.app.data.LocalParams
import com.fujikon.app.render.LocalMaskRenderer
import com.fujikon.app.ui.components.appendBrushStroke
import com.fujikon.app.ui.components.fitImageRect
import com.fujikon.app.ui.components.hitBrushStroke
import com.fujikon.app.ui.components.maskRotationHandle
import com.fujikon.app.ui.components.panMask
import com.fujikon.app.ui.components.rotateMaskBy
import com.fujikon.app.ui.components.transformMask
import org.json.JSONObject
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
 * Locks in the 1.6.19 mask contract:
 *  1. a linear gradient only affects the strip BETWEEN its two endpoints;
 *  2. a radial ellipse can be rotated (raster matches the drawn outline);
 *  3. a brush can be dragged / rotated, and painting still lands under the finger.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MaskGeometryTest {

    // ---------------------------------------------------------- requirement 1

    @Test
    fun linearGradientIsConfinedBetweenItsTwoEndpoints() {
        val grid = LocalMaskRenderer.buildGrid(
            LocalMask.LinearGradient(
                startX = 0.5f, startY = 0.4f, endX = 0.5f, endY = 0.6f,
                feather = 0.4f
            ), 100, 100
        )
        // Inside the strip (t=0.5) the ramp has reached full strength.
        assertTrue("两端点之间应满强度", grid.sample(0.5f, 0.5f) > 0.85f)
        // The OLD implementation leaked full strength into both outer half-planes.
        assertTrue("start 端点之外不应作用", grid.sample(0.5f, 0.15f) < 0.05f)
        assertTrue("end 端点之外不应作用", grid.sample(0.5f, 0.9f) < 0.05f)
        // Perpendicular to the gradient axis the band still spans the frame.
        assertTrue("法向仍覆盖整个画面", grid.sample(0.1f, 0.5f) > 0.85f)
    }

    @Test
    fun invertedLinearGradientSelectsOutsideTheBand() {
        // 1.6.59：反选 = 选中当前蒙版覆盖范围之外所有区域（与 Lightroom 一致）。
        // 线性渐变的「覆盖范围」是两端点之间的条带，因此反选后条带外变为选中，
        // 条带内与未反选互补。反选在 prepare 的 eraser 之后执行。
        val layer = LocalLayer(
            "l",
            mask = LocalMask.LinearGradient(0.5f, 0.4f, 0.5f, 0.6f, 0.4f, invert = true),
            params = LocalParams(brightness = 1f)
        )
        val grid = LocalMaskRenderer.prepare(LocalAdjustments(listOf(layer)), 100, 100, null)[0].grid
        assertTrue("invert 后条带外（start 端外）应被选中", grid.sample(0.5f, 0.15f) > 0.9f)
        assertTrue("invert 后条带外（end 端外）应被选中", grid.sample(0.5f, 0.9f) > 0.9f)
        assertTrue("invert 后条带内与未反选互补", grid.sample(0.5f, 0.42f) > 0.5f)
    }

    // ---------------------------------------------------------- requirement 2

    @Test
    fun radialRotationTurnsTheEllipseInTheRaster() {
        val flat = LocalMask.RadialGradient(
            x = 0.5f, y = 0.5f, radiusX = 0.4f, radiusY = 0.1f, feather = 0.3f
        )
        val horizontal = LocalMaskRenderer.buildGrid(flat, 100, 100)
        assertTrue("未旋转：水平方向在椭圆内", horizontal.sample(0.7f, 0.5f) > 0.7f)
        assertTrue("未旋转：竖直方向在椭圆外", horizontal.sample(0.5f, 0.7f) < 0.1f)

        val turned = LocalMaskRenderer.buildGrid(flat.copy(rotationDeg = 90f), 100, 100)
        assertTrue("旋转 90°：竖直方向进入椭圆", turned.sample(0.5f, 0.7f) > 0.7f)
        assertTrue("旋转 90°：水平方向移出椭圆", turned.sample(0.7f, 0.5f) < 0.1f)
    }

    // ---------------------------------------------------------- requirement 3

    @Test
    fun brushOffsetAndRotationMoveThePaintedPixels() {
        val base = LocalMask.Brush(
            strokes = listOf(BrushStroke(listOf(0.2f, 0.5f, 0.8f, 0.5f))),
            radius = 0.05f, feather = 0.3f
        )
        val plain = LocalMaskRenderer.buildGrid(base, 100, 100)
        assertTrue("默认笔迹在中线", plain.sample(0.5f, 0.5f) > 0.3f)

        val moved = LocalMaskRenderer.buildGrid(base.copy(offsetY = 0.3f), 100, 100)
        assertTrue("整体拖动后新位置有笔迹", moved.sample(0.5f, 0.8f) > 0.3f)
        assertTrue("整体拖动后原位置为空", moved.sample(0.5f, 0.5f) < 0.1f)

        val spun = LocalMaskRenderer.buildGrid(base.copy(rotationDeg = 90f), 100, 100)
        assertTrue("旋转 90° 后竖直方向有笔迹", spun.sample(0.5f, 0.7f) > 0.3f)
        assertTrue("旋转 90° 后水平方向为空", spun.sample(0.7f, 0.5f) < 0.1f)
    }

    // -------------------------------------------------------------- pure maths

    @Test
    fun brushMapAndUnmapRoundTripUnderRotationAndOffset() {
        val mask = LocalMask.Brush(
            strokes = listOf(BrushStroke(listOf(0.2f, 0.5f, 0.8f, 0.5f))),
            offsetX = 0.1f, offsetY = -0.05f, rotationDeg = 37f
        )
        val p = mask.mapPoint(0.3f, 0.4f, 1f)
        val back = mask.unmapPoint(p.first, p.second, 1f)
        assertEquals(0.3f, back.first, 0.001f)
        assertEquals(0.4f, back.second, 0.001f)
        // The pinned centre maps to centre + offset regardless of rotation.
        val (cx, cy) = mask.centre()
        val c = mask.mapPoint(cx, cy, 1f)
        assertEquals(cx + 0.1f, c.first, 0.001f)
        assertEquals(cy - 0.05f, c.second, 0.001f)
        // Appending a second stroke must NOT move the rotation centre.
        val appended = appendBrushStroke(mask, listOf(0.1f, 0.1f, 0.9f, 0.9f), 1f) as LocalMask.Brush
        assertEquals(cx, appended.centre().first, 0.001f)
        assertEquals(cy, appended.centre().second, 0.001f)
    }

    @Test
    fun appendedBrushStrokeRendersExactlyWhereTheFingerWas() {
        val mask = LocalMask.Brush(
            strokes = listOf(BrushStroke(listOf(0.2f, 0.5f, 0.8f, 0.5f))),
            offsetX = 0.1f, offsetY = 0.05f, rotationDeg = 40f
        )
        val appended = appendBrushStroke(
            mask, listOf(0.5f, 0.5f, 0.6f, 0.5f), 1f
        ) as LocalMask.Brush
        val pts = appended.strokes.last().points
        val a = appended.mapPoint(pts[0], pts[1], 1f)
        assertEquals(0.5f, a.first, 0.001f)
        assertEquals(0.5f, a.second, 0.001f)
        val b = appended.mapPoint(pts[2], pts[3], 1f)
        assertEquals(0.6f, b.first, 0.001f)
        assertEquals(0.5f, b.second, 0.001f)
    }

    @Test
    fun rotateAndPanMasksUpdateTheirTransformFields() {
        val radial = rotateMaskBy(LocalMask.RadialGradient(), 90f, 1f) as LocalMask.RadialGradient
        assertEquals(90f, radial.rotationDeg, 0.001f)
        val brush = rotateMaskBy(LocalMask.Brush(), 45f, 1f) as LocalMask.Brush
        assertEquals(45f, brush.rotationDeg, 0.001f)
        val pannedBrush = panMask(LocalMask.Brush(), 0.1f, 0.2f) as LocalMask.Brush
        assertEquals(0.1f, pannedBrush.offsetX, 0.001f)
        assertEquals(0.2f, pannedBrush.offsetY, 0.001f)
        val point = panMask(LocalMask.ControlPoint(), 0.1f, 0.2f) as LocalMask.ControlPoint
        assertEquals(0.6f, point.x, 0.001f)
        assertEquals(0.7f, point.y, 0.001f)
    }

    @Test
    fun twoFingerRotationAlsoTurnsRadialAndBrush() {
        val r = transformMask(LocalMask.RadialGradient(), 1f, 30f, 1f) as LocalMask.RadialGradient
        assertEquals(30f, r.rotationDeg, 0.001f)
        val b = transformMask(LocalMask.Brush(), 1f, 30f, 1f) as LocalMask.Brush
        assertEquals(30f, b.rotationDeg, 0.001f)
    }

    @Test
    fun rotationHandleExistsOnlyForRadialAndBrushAndTracksAngle() {
        val rect = fitImageRect(200f, 200f, 100, 100)
        assertNull(maskRotationHandle(LocalMask.ControlPoint(), rect, 1f))
        assertNull(maskRotationHandle(LocalMask.LinearGradient(), rect, 1f))
        val h = maskRotationHandle(
            LocalMask.RadialGradient(rotationDeg = 90f), rect, 1f
        )!!
        // Screen "up" spun 90° clockwise lands to the RIGHT of the centre.
        assertTrue("旋转 90° 后手柄应在中心右侧: $h", h.x > rect.width * 0.5f)
        assertTrue(
            "手柄应落在中心水平线上: $h",
            kotlin.math.abs(h.y - rect.height * 0.5f) < 1f
        )
        assertNotNull(maskRotationHandle(LocalMask.Brush(), rect, 1f))
    }

    @Test
    fun hitBrushStrokeFollowsTheTransformedStrokes() {
        val mask = LocalMask.Brush(
            strokes = listOf(BrushStroke(listOf(0.2f, 0.5f, 0.8f, 0.5f)))
        )
        assertTrue(hitBrushStroke(mask, 0.5f, 0.5f, 1f, 0.03f))
        assertFalse(hitBrushStroke(mask, 0.5f, 0.9f, 1f, 0.03f))
        val moved = mask.copy(offsetY = 0.3f)
        assertTrue("拖动后命中位置跟着移动", hitBrushStroke(moved, 0.5f, 0.8f, 1f, 0.03f))
        assertFalse(hitBrushStroke(moved, 0.5f, 0.5f, 1f, 0.03f))
    }

    // ----------------------------------------------------------- persistence

    @Test
    fun transformFieldsSurviveJsonRoundTripAndDefaultForLegacyJson() {
        val radial = LocalMask.RadialGradient(rotationDeg = 123f)
        assertEquals(
            123f,
            (LocalMask.fromJson(radial.toJson()) as LocalMask.RadialGradient).rotationDeg,
            0.001f
        )
        val brush = LocalMask.Brush(offsetX = 0.12f, offsetY = -0.08f, rotationDeg = 77f)
        val back = LocalMask.fromJson(brush.toJson()) as LocalMask.Brush
        assertEquals(0.12f, back.offsetX, 0.001f)
        assertEquals(-0.08f, back.offsetY, 0.001f)
        assertEquals(77f, back.rotationDeg, 0.001f)
        // Old presets / history entries have none of the new keys.
        val legacy = LocalMask.fromJson(JSONObject("{\"type\":\"brush\"}")) as LocalMask.Brush
        assertEquals(0f, legacy.rotationDeg, 0.001f)
        assertEquals(0f, legacy.offsetX, 0.001f)
        assertEquals(0f, legacy.offsetY, 0.001f)
    }
}
