package com.fujikon.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditHistoryTest {

    private fun snapshot(
        photoId: Long = 1L,
        presetId: String? = "p1",
        exposure: Float = 0f
    ) = EditSnapshot(
        photoId = photoId,
        presetId = presetId,
        toneCurve = null,
        adjustments = EditAdjustments(exposure = exposure)
    )

    @Test
    fun resetCreatesAnInitialStepWithoutUndo() {
        val history = EditHistory()
        history.reset(snapshot(), "打开照片", now = 1_000L)

        assertEquals(1, history.entries.size)
        assertEquals(0, history.index)
        assertEquals("打开照片", history.current?.label)
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
        assertNull(history.undo())
    }

    @Test
    fun recordThenUndoAndRedo() {
        val history = EditHistory()
        history.reset(snapshot(), "打开照片", now = 1_000L)
        history.record("套用预设：NC 400", snapshot(presetId = "p2"), now = 2_000L)
        history.record("调节 · 曝光度", snapshot(presetId = "p2", exposure = 1f), now = 3_000L)

        assertEquals(3, history.entries.size)
        assertTrue(history.canUndo)

        val undo1 = history.undo()
        assertEquals("套用预设：NC 400", undo1?.label)
        assertEquals(1, history.index)
        assertTrue(history.canRedo)

        val undo2 = history.undo()
        assertEquals("打开照片", undo2?.label)
        assertEquals(0, history.index)
        assertFalse(history.canUndo)

        val redo = history.redo()
        assertEquals("套用预设：NC 400", redo?.label)
        assertEquals(1, history.index)
    }

    @Test
    fun newEditAfterUndoTruncatesRedoBranch() {
        val history = EditHistory()
        history.reset(snapshot(), "打开照片", now = 1_000L)
        history.record("调节 · 曝光度", snapshot(exposure = 1f), now = 2_000L)
        history.record("调节 · 对比度", snapshot(exposure = 1f), now = 3_000L)
        history.undo()

        history.record("曲线调整", snapshot(exposure = 2f), now = 4_000L)

        assertEquals(3, history.entries.size)
        assertEquals("曲线调整", history.current?.label)
        assertFalse(history.canRedo)
    }

    @Test
    fun sameSliderCoalescesButDifferentSliderDoesNot() {
        val history = EditHistory()
        history.reset(snapshot(), "打开照片", now = 1_000L)

        history.record("调节 · 曝光度", snapshot(exposure = 0.5f), "adjust:曝光度", now = 2_000L)
        history.record("调节 · 曝光度", snapshot(exposure = 1.0f), "adjust:曝光度", now = 2_500L)
        assertEquals(2, history.entries.size)
        assertEquals(1f, history.current?.snapshot?.adjustments?.exposure ?: 0f, 0.001f)

        history.record("调节 · 对比度", snapshot(exposure = 1.0f), "adjust:对比度", now = 3_000L)
        assertEquals(3, history.entries.size)
    }

    @Test
    fun sameSliderAfterTheWindowStartsANewStep() {
        val history = EditHistory(coalesceWindowMs = 1_000L)
        history.reset(snapshot(), "打开照片", now = 0L)
        history.record("调节 · 曝光度", snapshot(exposure = 0.5f), "adjust:曝光度", now = 500L)
        history.record("调节 · 曝光度", snapshot(exposure = 1.0f), "adjust:曝光度", now = 2_000L)

        assertEquals(3, history.entries.size)
    }

    @Test
    fun jumpToRestoresAnyStep() {
        val history = EditHistory()
        history.reset(snapshot(), "打开照片", now = 1_000L)
        history.record("调节 · 曝光度", snapshot(exposure = 1f), now = 2_000L)
        history.record("曲线调整", snapshot(exposure = 1f, presetId = "p3"), now = 3_000L)

        val target = history.jumpTo(0)
        assertEquals("打开照片", target?.label)
        assertEquals(0, history.index)
        assertTrue(history.canRedo)

        assertNull(history.jumpTo(9))
        assertEquals(0, history.index)
    }

    @Test
    fun historyIsCappedAndKeepsTheNewestStep() {
        val history = EditHistory(maxEntries = 4)
        history.reset(snapshot(), "开始", now = 0L)
        repeat(10) { i ->
            history.record("步骤 $i", snapshot(exposure = i.toFloat()), now = i * 1_000L + 1)
        }

        assertEquals(4, history.entries.size)
        assertEquals("步骤 9", history.current?.label)
        assertEquals(3, history.index)
    }
}
