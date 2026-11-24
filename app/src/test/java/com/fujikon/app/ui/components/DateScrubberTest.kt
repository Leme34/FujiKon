package com.fujikon.app.ui.components

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

/**
 * 日期快速定位轴的纯逻辑回归：按「某一天」分组、标签、拖动比例→照片下标映射。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DateScrubberTest {

    private fun photo(id: Long, dateMillis: Long) = com.fujikon.app.data.PhotoItem(
        id = id,
        uri = Uri.parse("content://media/external/images/media/$id"),
        displayName = "IMG_$id.jpg",
        dateTaken = dateMillis,
        dateAdded = dateMillis / 1000L,
        bucketId = "camera",
        bucketName = "Camera",
        width = 1200,
        height = 800,
        mimeType = "image/jpeg",
        size = 1024L
    )

    private fun millis(year: Int, month: Int, day: Int, hour: Int = 12): Long {
        val cal = Calendar.getInstance()
        cal.clear()
        cal.set(year, month - 1, day, hour, 0, 0)
        return cal.timeInMillis
    }

    @Test
    fun groupsPhotosByDayInDescendingOrder() {
        // 5/20 两张 + 5/15 两张 + 4/28 一张 + 3/11 一张（已按日期倒序）。
        val photos = listOf(
            photo(1, millis(2024, 5, 20, 18)),
            photo(2, millis(2024, 5, 20, 9)),
            photo(3, millis(2024, 5, 15, 20)),
            photo(4, millis(2024, 5, 15, 8)),
            photo(5, millis(2024, 4, 28, 12)),
            photo(6, millis(2024, 3, 11, 12))
        )
        val ticks = buildDateTicks(photos, nowMillis = millis(2024, 5, 25))
        assertEquals(4, ticks.size)
        // 每天的第一张位置：5/20=0，5/15=2，4/28=4，3/11=5。
        assertEquals(listOf(0, 2, 4, 5), ticks.map { it.startIndex })
        assertEquals(listOf("5/20", "5/15", "4/28", "3/11"), ticks.map { it.label })
    }

    @Test
    fun emptyAndSingleDayCases() {
        assertTrue(buildDateTicks(emptyList(), 0L).isEmpty())
        val oneDay = listOf(
            photo(1, millis(2024, 5, 20, 18)),
            photo(2, millis(2024, 5, 20, 9))
        )
        assertEquals(1, buildDateTicks(oneDay, millis(2024, 5, 25)).size)
    }

    @Test
    fun labelShowsConcreteDayWithYearForOlderDates() {
        val thisYear = formatDateTickLabel(millis(2024, 5, 1), millis(2024, 5, 25))
        val lastYear = formatDateTickLabel(millis(2023, 12, 1), millis(2024, 5, 25))
        assertEquals("5/1", thisYear)
        assertEquals("23/12/1", lastYear)
    }

    @Test
    fun fractionMapsToPhotoIndexLinearly() {
        assertEquals(0, fractionToPhotoIndex(0f, 100))
        assertEquals(99, fractionToPhotoIndex(1f, 100))
        assertEquals(50, fractionToPhotoIndex(0.5f, 101))
        // 越界钳制。
        assertEquals(0, fractionToPhotoIndex(-0.5f, 100))
        assertEquals(99, fractionToPhotoIndex(2f, 100))
        // 空列表安全。
        assertEquals(0, fractionToPhotoIndex(0.5f, 0))
    }

    @Test
    fun tickForIndexReturnsContainingDay() {
        val ticks = listOf(
            DateTick(0, "5/20", 0L),
            DateTick(2, "5/15", 0L),
            DateTick(4, "4/28", 0L),
            DateTick(5, "3/11", 0L)
        )
        assertEquals(0, tickForPhotoIndex(ticks, 0)!!.startIndex)
        assertEquals(0, tickForPhotoIndex(ticks, 1)!!.startIndex)
        assertEquals(2, tickForPhotoIndex(ticks, 2)!!.startIndex)
        assertEquals(2, tickForPhotoIndex(ticks, 3)!!.startIndex)
        assertEquals(4, tickForPhotoIndex(ticks, 4)!!.startIndex)
        assertEquals(5, tickForPhotoIndex(ticks, 5)!!.startIndex)
        assertEquals(null, tickForPhotoIndex(emptyList(), 0))
    }

    @Test
    fun fallsBackToDateAddedWhenTakenInvalid() {
        // PhotoItem.dateAdded 已是毫秒（Repository 读取时 ×1000），此处直接传毫秒。
        val p = photo(1, 0L).copy(dateTaken = 0L, dateAdded = millis(2024, 6, 1))
        val ticks = buildDateTicks(listOf(p), nowMillis = millis(2024, 6, 10))
        assertEquals(1, ticks.size)
        assertEquals("6/1", ticks.first().label)
    }
}
