package com.fujikon.app.render

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import kotlin.random.Random

/**
 * 1.6.82 画质无损防线：把闪光/颗粒/暗角合并成一趟后，必须与原来三次独立
 * 调用逐像素一致。每一步都在中间做一次取整+钳制，正好复刻 pack() 的行为。
 */
class FlashFinishEquivalenceTest {

    @Test
    fun fusedFinishingMatchesThreeSeparatePasses() {
        val w = 64
        val h = 48
        val base = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            (0xFF shl 24) or (x * 4 shl 16) or (y * 5 shl 8) or ((x * 7 + y * 11) and 0xFF)
        }
        val combos = listOf(
            Triple(72f, 14f, 10f),   // 直闪人像预设
            Triple(100f, 0f, 0f),
            Triple(0f, 80f, 0f),
            Triple(0f, 0f, -100f),
            Triple(0f, 0f, 0f),
            Triple(35f, 25f, -40f)
        )
        combos.forEach { (flash, grain, vignette) ->
            val sequential = base.copyOf()
            val fused = base.copyOf()
            QualityOperators.applyFlash(sequential, w, h, flash, null)
            QualityOperators.applyGrain(sequential, w, h, grain, null)
            QualityOperators.applyVignette(sequential, w, h, vignette, null)
            QualityOperators.applyFinish(fused, w, h, flash, grain, vignette, null)
            assertArrayEquals(
                "flash=$flash grain=$grain vignette=$vignette 合并 pass 必须与独立 pass 逐像素一致",
                sequential, fused
            )
        }
    }

    @Test
    fun fusedFinishingIsIdempotentForZeroAmounts() {
        val w = 32
        val h = 32
        val pixels = IntArray(w * h) { Random(42).nextInt() or -0x1000000 }
        val before = pixels.copyOf()
        QualityOperators.applyFinish(pixels, w, h, 0f, 0f, 0f, null)
        assertArrayEquals("全零早退不应改变任何像素", before, pixels)
    }
}
