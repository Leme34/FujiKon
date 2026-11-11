package com.fujikon.app.nic

import com.fujikon.app.data.Preset
import com.fujikon.app.ncp.BaseProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * nikonpc.com 官方 7 款 NCP 预置的真实参数与色彩学不变量。
 *
 * 这些条目是「真实参数复刻」（数据来自站点公开的 NCP 解析值），不是统计拟合：
 * 锁死 ① 数量与名称精确匹配；② 每条曲线 256 点、值域 0..32767、单调；
 * ③ format=NCP、档位参数为站点原值；④ contentHash 稳定且互不重复；
 * ⑤ JSON 往返不丢曲线与参数。
 *
 * 用 Robolectric 提供 Android 的 org.json（toJson 依赖链式 put 返回数组）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NikonPcOfficialPresetsTest {

    private val presets = builtInNikonPcPresets()

    private val expectedNames = listOf(
        "Canon 5d M2",
        "Fuji Provia",
        "Fuji SReala Skin",
        "Fuji Velvia Neutral",
        "Kodak Portra 160NC",
        "PT mode2",
        "st_g1.47"
    )

    @Test
    fun exposesExactlySevenOfficialPresetsInOrder() {
        assertEquals(7, presets.size)
        assertEquals(expectedNames, presets.map { it.name })
        assertTrue(presets.all { it.sourceName == NikonPcOfficialPresets.SOURCE_NAME })
    }

    @Test
    fun eachPresetHasA256PointMonotonicCurveInRange() {
        for (p in presets) {
            val t = p.curve ?: throw AssertionError("${p.name} 缺少曲线")
            assertEquals("${p.name} 必须有 256 点曲线", 256, t.size)
            for (i in 1 until t.size) {
                assertTrue("${p.name} 曲线必须单调不减 @$i", t[i] >= t[i - 1])
            }
            assertTrue("${p.name} 曲线值域必须 0..32767", t.all { it in 0..32767 })
        }
    }

    @Test
    fun presetsUseRealNcpParameters() {
        // format=NCP 让渲染器用 scale=3 解释锐化/饱和/色相档位（与 NCP 二进制一致）。
        assertTrue(presets.all { it.format == "NCP" })
        // 站点原值抽样锁定，防止被误改成拟合值。
        val byName = presets.associateBy { it.name }
        assertEquals(BaseProfiles.STANDARD, byName["Canon 5d M2"]!!.baseProfile)
        assertEquals(7f, byName["Canon 5d M2"]!!.sharpening, 0.0001f)
        assertEquals(1, byName["Canon 5d M2"]!!.saturation)
        assertEquals(BaseProfiles.NEUTRAL, byName["Kodak Portra 160NC"]!!.baseProfile)
        assertEquals(5f, byName["Kodak Portra 160NC"]!!.sharpening, 0.0001f)
        assertEquals(-1, byName["Kodak Portra 160NC"]!!.saturation)
        assertEquals(BaseProfiles.VIVID, byName["PT mode2"]!!.baseProfile)
        assertEquals(-2, byName["PT mode2"]!!.hue)
        assertEquals(2, byName["Fuji Velvia Neutral"]!!.saturation)
        // 曲线已含影调，五项分区参数必须恒等（避免重复施加）。
        assertTrue(presets.all { it.contrast == 0 && it.brightness == 0 &&
            it.highlights == 0 && it.shadows == 0 && it.whites == 0 && it.blacks == 0 })
    }

    @Test
    fun contentHashesAreStableUniqueAndNamespaced() {
        val hashes = presets.map { it.contentHash }
        assertEquals(7, hashes.toSet().size)
        assertTrue(hashes.all { it.startsWith("nikonpc:") })
        assertEquals(
            listOf(
                "nikonpc:canon_5d_m2",
                "nikonpc:fuji_provia",
                "nikonpc:fuji_sreala_skin",
                "nikonpc:fuji_velvia_neutral",
                "nikonpc:kodak_portra_160nc",
                "nikonpc:pt_mode2",
                "nikonpc:st_g1_47"
            ),
            hashes
        )
        // 不与其他命名空间冲突
        assertTrue(hashes.none { it.startsWith("nic:") || it.startsWith("local:") })
    }

    @Test
    fun jsonRoundTripKeepsCurveAndParameters() {
        for (p in presets) {
            val restored = Preset.fromJson(p.toJson())
            assertEquals(p.name, restored.name)
            assertEquals("NCP", restored.format)
            assertEquals(p.contentHash, restored.contentHash)
            assertTrue("${p.name} 反序列化后曲线不能丢失", restored.curve != null)
            assertEquals(p.curve!!.toList(), restored.curve!!.toList())
            assertEquals(p.sharpening, restored.sharpening, 0.0001f)
            assertEquals(p.saturation, restored.saturation)
            assertEquals(p.hue, restored.hue)
            assertEquals(p.baseProfile, restored.baseProfile)
        }
    }

    @Test
    fun builtInCloudPresetsIncludesAllSevenAlongsideExisting() {
        val all = builtInCloudPresets()
        val names = all.map { it.name }
        for (n in expectedNames) {
            assertTrue("内置列表应包含 $n", names.contains(n))
        }
        // 与云创 / 社区近似 / 通透胶片共存，内容哈希互不重复
        assertEquals(all.size, all.map { it.contentHash }.toSet().size)
    }
}
