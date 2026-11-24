package com.fujikon.app.dazz

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * 1.6.82 画质无损防线：3D LUT 的共享色值缓存只做记忆化，命中/未命中都必须
 * 与直接三线性插值逐位一致；并行渲染下也不允许出现 key/value 错配。
 */
class FilmSamplerEquivalenceTest {

    @Test
    fun samplerMatchesDirectSamplingForEveryLook() {
        for (look in DazzFilmLut.LOOK_FXN_R..DazzFilmLut.LOOK_FLASH_PORTRAIT) {
            val lut = DazzFilmLut.get(look) ?: continue
            val sampler = DazzFilmLut.sampler(look) ?: continue
            val rnd = Random(look * 7919L)
            repeat(20_000) {
                val r = rnd.nextInt(256)
                val g = rnd.nextInt(256)
                val b = rnd.nextInt(256)
                val expected = DazzFilmLut.sample(lut, r, g, b)
                // 重复取一次，覆盖命中缓存的分支。
                assertEquals("look=$look rgb=($r,$g,$b)", expected, sampler.sample(r, g, b))
                assertEquals("look=$look 缓存命中 rgb=($r,$g,$b)", expected, sampler.sample(r, g, b))
            }
        }
    }
}
