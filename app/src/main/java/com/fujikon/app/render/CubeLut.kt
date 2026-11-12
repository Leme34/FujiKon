package com.fujikon.app.render

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * 云创色彩预设库中 `.cube` 3D LUT 的运行时表示（纯 JVM，不依赖 Android，便于单测）。
 *
 * 数据布局遵循 cube 规范：index = (r * size + g) * size + b，即 r 变化最慢、b 最快，
 * 每点存 R/G/B 三个 0..1 浮点。`decode` 读取 App 内紧凑的 FKL1 二进制：
 *
 *     "FKL1" | size:i32 | flags:i32 | size^3 * 3 个 uint16(0..65535)
 *
 * 原始 LUT 是尼康 / RED 的「N-Log / Rec.2020 Log -> Rec.709」技术转换或电影风格
 * LUT，输入应为 Log 编码素材。把它直接套到已显影的 sRGB JPEG 会因对角线
 * `0.5 -> 0.7+` 而整体过曝。因此 [applyRelative] 采用**亮度锚定的相对变换**：
 * 只把 LUT 相对「同亮度中性灰响应」的通道偏差叠加回原像素，再把整体亮度拉回
 * 原值。中性灰像素严格恒等（不提亮、不发灰、不偏色），彩色像素获得 LUT 的
 * 色度 / 通道影调风格，且亮度被锚定 —— 既有可见风格，又不会毁掉曝光。
 */
class CubeLut(val size: Int, val data: FloatArray) {

    init {
        require(size >= 2) { "LUT 维度至少为 2" }
        require(data.size == size * size * size * 3) { "LUT 数据与维度不匹配" }
    }

    private val maxIndex = size - 1

    /** 三线性插值，输入与输出均为 0..1，结果写入 [out]（长度 3）。 */
    fun lookup(r: Float, g: Float, b: Float, out: FloatArray) {
        val rf = r.coerceIn(0f, 1f) * maxIndex
        val gf = g.coerceIn(0f, 1f) * maxIndex
        val bf = b.coerceIn(0f, 1f) * maxIndex
        val r0 = rf.toInt(); val g0 = gf.toInt(); val b0 = bf.toInt()
        val r1 = (r0 + 1).coerceAtMost(maxIndex)
        val g1 = (g0 + 1).coerceAtMost(maxIndex)
        val b1 = (b0 + 1).coerceAtMost(maxIndex)
        val dr = rf - r0; val dg = gf - g0; val db = bf - b0
        for (c in 0 until 3) {
            val c000 = at(r0, g0, b0, c); val c100 = at(r1, g0, b0, c)
            val c010 = at(r0, g1, b0, c); val c110 = at(r1, g1, b0, c)
            val c001 = at(r0, g0, b1, c); val c101 = at(r1, g0, b1, c)
            val c011 = at(r0, g1, b1, c); val c111 = at(r1, g1, b1, c)
            val x00 = c000 + (c100 - c000) * dr
            val x10 = c010 + (c110 - c010) * dr
            val x01 = c001 + (c101 - c001) * dr
            val x11 = c011 + (c111 - c011) * dr
            val y0 = x00 + (x10 - x00) * dg
            val y1 = x01 + (x11 - x01) * dg
            out[c] = (y0 + (y1 - y0) * db).coerceIn(0f, 1f)
        }
    }

    private fun at(r: Int, g: Int, b: Int, c: Int): Float =
        data[((r * size + g) * size + b) * 3 + c]

    /**
     * 亮度锚定的相对变换。输入 RGB 为 0..255，结果写入 [out]（长度 3，同为 0..255）。
     *
     * 1. 求 LUT 对该像素亮度对应的「中性灰」响应 neutral；
     * 2. 求 LUT 对实际 RGB 的响应 actual；
     * 3. 输出 = 原像素 + (actual - neutral)，即只叠加 LUT 的相对偏差；
     * 4. 把整体 luma 拉回输入 luma（保色平移），杜绝全局提亮 / 压暗。
     */
    fun applyRelative(ri: Int, gi: Int, bi: Int, out: IntArray) {
        val scratch = threadScratch.get() ?: Pair(FloatArray(3), FloatArray(3))
        val neutral = scratch.first
        val actual = scratch.second
        val r = ri / 255f; val g = gi / 255f; val b = bi / 255f
        val lin = 0.299f * r + 0.587f * g + 0.114f * b
        lookup(lin, lin, lin, neutral)
        lookup(r, g, b, actual)
        var or = r + (actual[0] - neutral[0])
        var og = g + (actual[1] - neutral[1])
        var ob = b + (actual[2] - neutral[2])
        val lout = 0.299f * or + 0.587f * og + 0.114f * ob
        val shift = lin - lout
        or += shift; og += shift; ob += shift
        out[0] = (or * 255f).roundToInt().coerceIn(0, 255)
        out[1] = (og * 255f).roundToInt().coerceIn(0, 255)
        out[2] = (ob * 255f).roundToInt().coerceIn(0, 255)
    }

    // ImageRenderer.processRows 会被多核并行调用，且共享同一个 CubeLut 实例；
    // 暂存按线程隔离，避免逐像素分配对象、也避免跨线程数据竞争。
    private val threadScratch = ThreadLocal.withInitial {
        Pair(FloatArray(3), FloatArray(3))
    }

    companion object {
        private const val MAGIC = 0x464B4C31 // "FKL1"

        /** 解码 FKL1 二进制；格式错误返回 null（渲染时静默降级为不套用）。 */
        fun decode(bytes: ByteArray): CubeLut? = runCatching {
            if (bytes.size < 12) return null
            var o = 0
            fun i32(): Int {
                val v = ((bytes[o].toInt() and 0xFF) shl 24) or
                    ((bytes[o + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[o + 2].toInt() and 0xFF) shl 8) or
                    (bytes[o + 3].toInt() and 0xFF)
                o += 4
                return v
            }
            if (i32() != MAGIC) return null
            val size = i32()
            i32() // flags，当前仅 0 = uint16 量化
            if (size < 2 || size > 128) return null
            val count = size * size * size * 3
            if (bytes.size < 12 + count * 2) return null
            val data = FloatArray(count)
            for (i in 0 until count) {
                val u = ((bytes[o].toInt() and 0xFF) shl 8) or (bytes[o + 1].toInt() and 0xFF)
                o += 2
                data[i] = u / 65535f
            }
            CubeLut(size, data)
        }.getOrNull()
    }
}

/**
 * 3D LUT 的注入式提供者。[ImageRenderer] 是纯 JVM 类（无 Context），由
 * [com.fujikon.app.AppContainer] 在启动时用 assets 装配 loader；单测可直接
 * [register] 一个内存 LUT。按 asset 路径缓存，避免逐像素重复解码。
 */
object CubeLutProvider {
    @Volatile
    private var loader: ((String) -> ByteArray?)? = null
    private val cache = ConcurrentHashMap<String, CubeLut>()

    fun install(loader: (String) -> ByteArray?) {
        this.loader = loader
        cache.clear()
    }

    /** 测试或进程内直接注册一个已解码的 LUT。 */
    fun register(asset: String, lut: CubeLut) {
        cache[asset] = lut
    }

    fun get(asset: String): CubeLut? {
        cache[asset]?.let { return it }
        val bytes = loader?.invoke(asset) ?: return null
        val lut = CubeLut.decode(bytes) ?: return null
        cache.putIfAbsent(asset, lut)
        return cache[asset]
    }

    fun clearCache() {
        cache.clear()
    }
}
