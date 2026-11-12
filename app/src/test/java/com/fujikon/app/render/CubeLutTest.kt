package com.fujikon.app.render

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** 云创 .cube 3D LUT 解码、三线性插值与亮度锚定相对变换的纯 JVM 回归测试。 */
class CubeLutTest {

    /** 构造一个恒等 LUT：lookup(x,y,z) = (x,y,z)。 */
    private fun identity(size: Int = 2): CubeLut {
        val data = FloatArray(size * size * size * 3)
        for (r in 0 until size) {
            for (g in 0 until size) {
                for (b in 0 until size) {
                    val i = ((r * size + g) * size + b) * 3
                    data[i] = r / (size - 1f)
                    data[i + 1] = g / (size - 1f)
                    data[i + 2] = b / (size - 1f)
                }
            }
        }
        return CubeLut(size, data)
    }

    /** 构造一个「整体抬亮 + 偏红」LUT，用于验证亮度锚定确实能压住过曝。 */
    private fun brightWarm(size: Int = 2): CubeLut {
        val data = FloatArray(size * size * size * 3)
        for (r in 0 until size) {
            for (g in 0 until size) {
                for (b in 0 until size) {
                    val i = ((r * size + g) * size + b) * 3
                    data[i] = (r / (size - 1f) * 0.9f + 0.1f).coerceIn(0f, 1f)
                    data[i + 1] = (g / (size - 1f) * 0.8f).coerceIn(0f, 1f)
                    data[i + 2] = (b / (size - 1f) * 0.8f).coerceIn(0f, 1f)
                }
            }
        }
        return CubeLut(size, data)
    }

    @Test
    fun identityLookupReturnsInput() {
        val lut = identity(4)
        val out = FloatArray(3)
        lut.lookup(0.25f, 0.5f, 0.75f, out)
        assertEquals(0.25f, out[0], 1e-5f)
        assertEquals(0.5f, out[1], 1e-5f)
        assertEquals(0.75f, out[2], 1e-5f)
    }

    @Test
    fun lookupClampsOutOfRangeInput() {
        val lut = identity()
        val out = FloatArray(3)
        lut.lookup(-1f, 2f, 0.5f, out)
        assertEquals(0f, out[0], 1e-5f)
        assertEquals(1f, out[1], 1e-5f)
        assertEquals(0.5f, out[2], 1e-5f)
    }

    @Test
    fun neutralGrayIsExactlyUnchangedForAnyLut() {
        // 亮度锚定的核心保证：任意 LUT 下中性灰都不提亮、不发灰、不偏色。
        for (lut in listOf(brightWarm(), identity(), identity(33))) {
            for (v in intArrayOf(0, 32, 64, 128, 192, 255)) {
                val out = IntArray(3)
                lut.applyRelative(v, v, v, out)
                assertArrayEquals(
                    "中性灰 $v 必须严格恒等",
                    intArrayOf(v, v, v),
                    out
                )
            }
        }
    }

    @Test
    fun brightnessIsAnchoredForColorPixels() {
        val lut = brightWarm()
        val out = IntArray(3)
        lut.applyRelative(200, 120, 60, out)
        val lin = 0.299f * 200 + 0.587f * 120 + 0.114f * 60
        val lout = 0.299f * out[0] + 0.587f * out[1] + 0.114f * out[2]
        // 允许 0..255 截断与 roundToInt 带来的小幅偏差，但绝不允许整体过曝。
        assertTrue(
            "输出亮度应被锚定到输入亮度（±3），实际 $lout vs $lin",
            kotlin.math.abs(lout - lin) <= 3f
        )
        assertTrue(out.all { it in 0..255 })
    }

    @Test
    fun decodeRoundTripsFkl1Binary() {
        val size = 2
        val count = size * size * size * 3
        val bytes = ByteArray(12 + count * 2)
        ByteBuffer.wrap(bytes).putInt(0x464B4C31).putInt(size).putInt(0)
        var o = 12
        for (i in 0 until count) {
            val u = (i * 2731) % 65536
            bytes[o] = (u ushr 8).toByte()
            bytes[o + 1] = u.toByte()
            o += 2
        }
        val lut = CubeLut.decode(bytes)
        assertNotNull("合法 FKL1 应能解码", lut)
        assertEquals(size, lut!!.size)
        assertEquals(count, lut.data.size)
        assertEquals(0f, lut.data[0], 1e-6f)
    }

    @Test
    fun decodeRejectsMalformedData() {
        assertNull(CubeLut.decode(ByteArray(4)))
        val wrongMagic = ByteBuffer.allocate(12).putInt(0).putInt(2).putInt(0).array()
        assertNull(CubeLut.decode(wrongMagic))
        // magic + size 合法但数据不足 -> 静默降级为不套用
        val truncated = ByteBuffer.allocate(12 + 4).putInt(0x464B4C31).putInt(2).putInt(0).array()
        assertNull(CubeLut.decode(truncated))
    }

    @Test
    fun providerCachesByAssetAndCanBeCleared() {
        CubeLutProvider.clearCache()
        assertNull("未安装 loader 且未注册时返回 null", CubeLutProvider.get("missing.bin"))
        val lut = identity()
        CubeLutProvider.register("lut.bin", lut)
        assertTrue(CubeLutProvider.get("lut.bin") === lut)
        CubeLutProvider.clearCache()
        assertNull(CubeLutProvider.get("lut.bin"))
    }

    @Test
    fun providerDecodesThroughInstalledLoader() {
        val size = 2
        val count = size * size * size * 3
        val bytes = ByteArray(12 + count * 2)
        ByteBuffer.wrap(bytes).putInt(0x464B4C31).putInt(size).putInt(0)
        CubeLutProvider.install { asset -> if (asset == "ok.bin") bytes else null }
        try {
            assertNotNull(CubeLutProvider.get("ok.bin"))
            assertEquals(size, CubeLutProvider.get("ok.bin")!!.size)
            assertNull(CubeLutProvider.get("bad.bin"))
        } finally {
            CubeLutProvider.clearCache()
            CubeLutProvider.install { _: String -> null }
        }
    }
}
