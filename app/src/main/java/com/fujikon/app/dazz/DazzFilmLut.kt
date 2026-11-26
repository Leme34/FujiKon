package com.fujikon.app.dazz

import java.util.concurrent.atomic.AtomicLongArray
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * 富士/Dazz 胶片模拟的 3D 色彩查找表（sRGB 域，33³，三线性插值）。
 *
 * 为什么用 3D LUT：1D 逐通道曲线在数学上无法表达「阴影青绿 / 高光洋红」这种
 * 随亮度耦合的色相分离，而这是富士胶片模拟的灵魂，也是三款 look 拉不开差距的
 * 根因。3D LUT 把「影调 S 曲线 + 随亮度的色相分离 + 全局色温 + 饱和度 + 肤色
 * 保护」一次性烘进 RGB→RGB 的映射，逐像素只剩固定次数的查表插值。
 *
 * 肤色保护是清透人像的关键：用 YCbCr 椭圆 + 暖色启发式识别肤色，对肤色区域
 * 几乎不加胶片色偏，只做「提亮 / 去黄 / 增粉嫩」；同时对高光低饱和区（白衣、
 * 天空、白墙）保护，避免泛粉泛黄。差异因此体现在环境色（背景、花、衣、木墙），
 * 而肤色三款都保持干净通透。
 *
 * LUT 由 [build] 按 look 生成一次并缓存；3 款常驻约 1.3MB，属一次性开销。
 */
object DazzFilmLut {
    const val N = 33
    private const val SIZE = N * N * N * 3

    const val LOOK_NONE = 0
    const val LOOK_FXN_R = 1
    const val LOOK_CLASSIC_U = 2
    const val LOOK_NOSTALGIC_NEG = 3
    /** Dazz 最火的理光负片 OFM R：低饱和、褪色抬黑、中性偏暖浅黄（1.6.69）。 */
    const val LOOK_OFM_R = 4
    /** 胶片直闪人像：径向闪光 + 清透浓郁富士色彩（青绿阴影/干净高光/高饱和）、肤色通透不泛红（1.6.73，1.6.80 清晰度修正）。 */
    const val LOOK_FLASH_PORTRAIT = 5

    private val cache = arrayOfNulls<FloatArray>(LOOK_FLASH_PORTRAIT + 1)
    private val samplers = arrayOfNulls<Sampler>(LOOK_FLASH_PORTRAIT + 1)

    /** 返回对应 look 的 LUT；look 非法或无 LUT 时返回 null。线程安全（读多写少的幂等缓存）。 */
    @Synchronized
    fun get(look: Int): FloatArray? {
        if (look <= LOOK_NONE || look > LOOK_FLASH_PORTRAIT) return null
        return cache[look] ?: build(look).also { cache[look] = it }
    }

    /**
     * 返回该 look 的共享采样器。采样器内是一个「24bit RGB → 采样结果」的直接
     * 映射缓存：同一张照片里大量像素色值重复（纯色背景、平坦肤色、压缩后
     * 的 JPEG 色块），命中后一次原子读即可取回结果，省掉整段三线性插值。
     * 缓存按 look 全局共享，跨预览/导出持续累积命中；用单个 long 原子写入
     * key+value，保证渲染线程并行时不可能读到错配的色值与结果。
     */
    @Synchronized
    fun sampler(look: Int): Sampler? {
        if (look <= LOOK_NONE || look > LOOK_FLASH_PORTRAIT) return null
        val lut = get(look) ?: return null
        return samplers[look] ?: Sampler(lut).also { samplers[look] = it }
    }

    /**
     * 线程安全的 3D LUT 采样器。缓存条目高 32 位是 key+1（0 表示空槽），
     * 低 32 位是打包结果；单次原子读写避免并行渲染时出现「新 key + 旧值」。
     * 缓存只做记忆化，命中与未命中得到的像素结果完全相同（画质无损）。
     */
    class Sampler internal constructor(private val lut: FloatArray) {
        private val entries = AtomicLongArray(CACHE_SIZE)

        fun sample(r: Int, g: Int, b: Int): Int {
            val key = (r shl 16) or (g shl 8) or b
            val slot = key and (CACHE_SIZE - 1)
            val cached = entries.get(slot)
            if (((cached ushr 32).toInt() - 1) == key) return cached.toInt()
            val value = sampleCore(lut, r, g, b)
            entries.set(slot, ((key + 1).toLong() shl 32) or (value.toLong() and 0xFFFFFFFFL))
            return value
        }
    }

    private const val CACHE_SIZE = 1 shl 18

    /** 0..255 输入对应的格点下标与插值权重，只依赖 N，全局算一次。 */
    private val SAMPLE_INDEX = IntArray(256)
    private val SAMPLE_FRAC = FloatArray(256)

    init {
        for (i in 0 until 256) {
            val f = i * (N - 1) / 255f
            SAMPLE_INDEX[i] = f.toInt()
            SAMPLE_FRAC[i] = f - SAMPLE_INDEX[i]
        }
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** pts: [x0,y0, x1,y1, ...] 单调递增，线性插值（对应 Python np.interp）。 */
    private fun toneMap(x: Float, pts: FloatArray): Float {
        if (x <= pts[0]) return pts[1]
        val n = pts.size / 2
        for (i in 1 until n) {
            val xi = pts[i * 2]
            if (x <= xi) {
                val x0 = pts[(i - 1) * 2]
                val y0 = pts[(i - 1) * 2 + 1]
                val y1 = pts[i * 2 + 1]
                return y0 + (y1 - y0) * ((x - x0) / (xi - x0))
            }
        }
        return pts[pts.size - 1]
    }

    private class Spec(
        val tone: FloatArray,
        val s0: Float, val s1: Float, val h0: Float, val h1: Float,
        val shadow: FloatArray, val mid: FloatArray, val high: FloatArray,
        val skinProtect: Float, val skinLift: Float,
        val sat: Float, val skinSatDelta: Float, val hprot: Float,
        val gcast: FloatArray, val redBoost: Float,
        /**
         * 肤色提亮 [skinLift] 在 R/G/B 三通道上的分配权重。默认走「增粉嫩」
         * 配比 (0.7,-0.35,0.5)；直闪人像为去橙红改用中性配比（少加红、不压绿、
         * 补蓝），避免肤色与其它暖色区域整体泛红。
         */
        val liftRGB: FloatArray = floatArrayOf(0.7f, -0.35f, 0.5f),
        /**
         * 白布中性化强度（0..1，默认 0 关闭）。高亮（luma 0.78→0.90）且低饱和
         * （sat 0.16→0.06）的白色/米白布料（白裙、白衣、白床单）会被肤色逻辑
         * 与保色影调保留暖粉偏色，与同画面中性白物（栏杆/白墙）不一致而显得
         * 「颜色失真」。开启后把这类白布往其亮度灰度拉近，消除暖粉；肤色
         * （sat>0.16）与彩色碎花不受影响。仅直闪人像启用。
         */
        val whiteNeutral: Float = 0f
    )

    /**
     * FXN R 经典负片（1.6.83 量化拟合重做）：
     * 初版参数（tone 高光 0.975 偏暖、sat 0.88、gcast 仅 -0.030）套出来偏亮、
     * 偏暖、青蓝影调不足，与 Dazz 样张「压暗、低饱和、青灰影调、高光去暖」的
     * 指纹差距 3~4 倍甚至方向相反。本版用原图—Dazz 成片配对做色彩指纹拟合，
     * 让变换量逼近实测目标：lum -0.045 / sat -0.066 / RB -0.122 / 阴影B-R +0.120 /
     * 高光R-B -0.093。同时保留椭圆肤色保护，环境去红青蓝不污染人脸清透度。
     */
    private val FXN_R = Spec(
        tone = floatArrayOf(0f, 0.006f, 0.10f, 0.055f, 0.25f, 0.190f, 0.50f, 0.420f, 0.75f, 0.790f, 0.90f, 0.930f, 1f, 0.965f),
        s0 = 0.18f, s1 = 0.55f, h0 = 0.50f, h1 = 0.95f,
        shadow = floatArrayOf(-0.048f, 0.028f, 0.105f),
        mid = floatArrayOf(-0.010f, 0.014f, 0.018f),
        high = floatArrayOf(-0.012f, 0.006f, 0.048f),
        skinProtect = 0.94f, skinLift = 0.052f, sat = 0.72f, skinSatDelta = 0.05f,
        hprot = 0.88f, gcast = floatArrayOf(-0.058f, 0.014f, 0.078f), redBoost = 0f,
        // 拟合脚本的肤色提亮配比（少加红、微压绿、少补蓝），避免通用配比
        // (0.7,-0.35,0.5) 在去红青蓝影调里把肤色推冷发灰。
        liftRGB = floatArrayOf(0.85f, -0.35f, 0.18f)
    )

    private val CLASSIC_U = Spec(
        tone = floatArrayOf(0f, 0.015f, 0.12f, 0.100f, 0.30f, 0.285f, 0.55f, 0.555f, 0.78f, 0.805f, 1f, 0.955f),
        s0 = 0.20f, s1 = 0.58f, h0 = 0.50f, h1 = 0.95f,
        shadow = floatArrayOf(-0.045f, 0.020f, 0.080f),
        mid = floatArrayOf(-0.012f, 0.006f, 0.022f),
        high = floatArrayOf(-0.015f, 0.010f, 0.028f),
        skinProtect = 0.90f, skinLift = 0.044f, sat = 1.05f, skinSatDelta = 0f,
        hprot = 0.85f, gcast = floatArrayOf(-0.040f, 0.021f, 0.063f), redBoost = 0.75f
    )

    private val NOSTALGIC_NEG = Spec(
        tone = floatArrayOf(0f, 0.022f, 0.10f, 0.100f, 0.28f, 0.275f, 0.50f, 0.490f, 0.72f, 0.745f, 0.90f, 0.915f, 1f, 0.952f),
        s0 = 0.18f, s1 = 0.54f, h0 = 0.46f, h1 = 0.94f,
        shadow = floatArrayOf(-0.020f, -0.010f, 0.070f),
        mid = floatArrayOf(0.012f, 0.002f, 0f),
        high = floatArrayOf(0.075f, 0.030f, -0.045f),
        skinProtect = 0.88f, skinLift = 0.060f, sat = 1.06f, skinSatDelta = 0.02f,
        hprot = 0.88f, gcast = floatArrayOf(0.060f, 0.024f, -0.040f), redBoost = 0f
    )

    /**
     * 理光负片 OFM R：和三款富士是两种审美——三款富士「浓郁 + 影调分离」，
     * OFM R 是「寡淡 + 褪色」。指纹来自 Dazz 官方样张与多篇实测评测的一致描述：
     *  - 低对比 + 抬黑（faded/matte）：黑位整体抬起、白位略压，动态范围收窄，
     *    形成温柔的「空气感 / 回忆感」，阴天废片也能救回。用 tone 曲线直接实现。
     *  - 低饱和：sat 显著低于三款富士，画面寡淡。
     *  - 中性偏暖浅黄 + 黄蓝分离：阴影带一点青蓝、高光带浅黄，但幅度都很轻，
     *    不像 Nostalgic Neg 那样琥珀/深蓝对撞。
     *  - 肤色：理光负片自带柔和磨皮、肤色干净；继续复用椭圆肤色保护，避免发黄发脏。
     */
    private val OFM_R = Spec(
        // 1.6.84 按 10 张 Dazz OFM R 成片的色彩锚点量化重做：
        // 抬黑 + 压白更强（黑位 0→0.052，白位 1→0.930），褪色感更足；
        // 中段压低对比，形成理光负片的柔和空气感。
        tone = floatArrayOf(0f, 0.052f, 0.12f, 0.140f, 0.28f, 0.278f, 0.50f, 0.475f, 0.72f, 0.690f, 0.90f, 0.875f, 1f, 0.930f),
        s0 = 0.18f, s1 = 0.62f, h0 = 0.50f, h1 = 0.95f,
        // 阴影去青：旧版 +0.030 蓝偏青蓝，成片阴影近中性微暖，改为近零。
        shadow = floatArrayOf(0.006f, 0.004f, -0.008f),
        mid = floatArrayOf(0f, 0f, -0.004f),
        high = floatArrayOf(0.028f, 0.022f, -0.024f),
        // 饱和度 0.78→0.60：成片 sat≈0.218，旧版只降到 0.305 明显不够。
        skinProtect = 0.93f, skinLift = 0.048f, sat = 0.60f, skinSatDelta = 0.15f,
        hprot = 0.88f, gcast = floatArrayOf(0.012f, 0.010f, -0.018f), redBoost = 0f,
        // 与 Python 拟合脚本一致的肤色提亮配比，保证肤色清透不发红。
        liftRGB = floatArrayOf(0.85f, -0.35f, 0.18f)
    )

    /**
     * 胶片直闪人像（1.6.73，1.6.76 影调优化，1.6.77 去橙红，1.6.79 胶片氛围增强）：
     * 复刻「机顶直闪」的胶片人像风格，指纹来自该类滤镜的公认视觉语言与多篇实拍/
     * 后期教程：
     *  - 保留直闪反差、但暗部不死黑、高光不死白：中段仍下沉让主体从背景里
     *    「跳」出来；同时抬黑位、提亮暗部，把高光/白位回收到近白。
     *  - 真实闪光照明由预设的 [flash] 径向算子完成（中心提亮、边缘衰减），本
     *    LUT 只负责色彩与影调指纹；暗角/颗粒作为闪光氛围的点缀。
     *  - 富士/理光胶片氛围（1.6.79 增强）：整体非肤色区施加青蓝色温（gcast），
     *    配合更强的青绿阴影，营造富士经典负片式的冷调影调分离；同时以抬黑+
     *    降饱和的 matte 处理带出理光负片的「空气感/褪色感」。氛围只作用于非
     *    肤色区，肤色被椭圆保护不受影响。
     *  - 肤色清透不泛红（1.6.77 修正并保持）：肤色提亮用中性三通道配比（少加
     *    红、不压绿、补蓝），skinSatDelta 很低；全局色温走青蓝而非暖红，避免
     *    肤色与其它暖色区域整体泛红。肤色保持健康通透。
     */
    private val FLASH_PORTRAIT = Spec(
        // 1.6.80 清透富士影调：撤回 1.6.79 的「抬黑 matte + 收高光 + 全局青蓝」，
        // 那套把白位压到 242、抬黑 0.042 拉平对比，导致画面灰蒙蒙、不通透。
        // 现在黑位回收到 0.016（对比清爽）、白位放回 0.968（白墙 230/纯白 247 通透），
        // 中段仅保留极轻下沉维持直闪主体反差。
        tone = floatArrayOf(
            0f, 0.016f,
            0.12f, 0.098f, 0.28f, 0.250f, 0.50f, 0.470f,
            0.72f, 0.730f, 0.88f, 0.930f, 1f, 0.968f
        ),
        s0 = 0.22f, s1 = 0.62f, h0 = 0.48f, h1 = 0.92f,
        // 青绿阴影保留（富士经典影调分离）：暗环境/背景泛青绿，主体更突出。
        shadow = floatArrayOf(-0.038f, 0.048f, 0.086f),
        mid = floatArrayOf(-0.006f, 0.002f, 0.010f),
        high = floatArrayOf(0.045f, 0.036f, 0.010f),
        // 提高整体饱和恢复浓郁富士色彩（sat 0.87→0.99，草绿/蓝天明显更艳）；
        // skinSatDelta 取负值，让肤色饱和单独特调、避免环境提艳时肤色跟着泛红。
        skinProtect = 0.94f, skinLift = 0.045f, sat = 0.99f, skinSatDelta = -0.06f,
        hprot = 0.76f,
        // 去掉全局青蓝色温（gcast 归零）：白色/中性区保持干净通透、不发青发灰。
        // 色彩感改由「青绿阴影 + 高饱和 + 干净高光」的色调分离提供，而非全局染色。
        gcast = floatArrayOf(0f, 0f, 0f), redBoost = 0f,
        liftRGB = floatArrayOf(0.25f, 0.10f, 0.55f),
        // 1.6.81 白布中性化：白裙/白衣等暖白布料消除暖粉偏色（肤色/碎花不受影响）。
        whiteNeutral = 0.75f
    )

    private fun specOf(look: Int): Spec = when (look) {
        LOOK_FXN_R -> FXN_R
        LOOK_CLASSIC_U -> CLASSIC_U
        LOOK_NOSTALGIC_NEG -> NOSTALGIC_NEG
        LOOK_OFM_R -> OFM_R
        LOOK_FLASH_PORTRAIT -> FLASH_PORTRAIT
        else -> FXN_R
    }

    /** 肤色权重：YCbCr 椭圆（覆盖偏白亚洲肤色）+ 暖色启发式，取二者较大。 */
    private fun skinWeight(r: Float, g: Float, b: Float, luma: Float): Float {
        val cb = -0.169f * r - 0.331f * g + 0.500f * b + 0.5f
        val cr = 0.500f * r - 0.419f * g - 0.081f * b + 0.5f
        val dc = ((cb - 0.44f) / 0.11f).let { it * it } + ((cr - 0.54f) / 0.075f).let { it * it }
        val ell = exp(-dc * 0.6f)
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val sat = if (mx > 0f) (mx - mn) / mx else 0f
        val warm = smoothstep(0.015f, 0.10f, r - b) * smoothstep(0f, 0.03f, g - b)
        val hsv = warm * smoothstep(0.22f, 0.45f, luma) * (1f - smoothstep(0.55f, 0.85f, sat))
        return max(ell, hsv).coerceIn(0f, 1f)
    }

    private fun build(look: Int): FloatArray {
        val s = specOf(look)
        val out = FloatArray(SIZE)
        val step = 1f / (N - 1)
        for (bi in 0 until N) {
            val b0 = bi * step
            for (gi in 0 until N) {
                val g0 = gi * step
                for (ri in 0 until N) {
                    val r0 = ri * step
                    val luma = 0.299f * r0 + 0.587f * g0 + 0.114f * b0

                    // 1) 保色影调曲线
                    val nl = toneMap(luma, s.tone)
                    val scale = if (luma > 0.015f) nl / luma else 1f
                    var r = r0 * scale
                    var g = g0 * scale
                    var b = b0 * scale

                    // 2) 随亮度的色相分离
                    val sw = 1f - smoothstep(s.s0, s.s1, luma)
                    val hw = smoothstep(s.h0, s.h1, luma)
                    val mw = (1f - sw - hw).coerceAtLeast(0f)
                    var dr = sw * s.shadow[0] + mw * s.mid[0] + hw * s.high[0]
                    var dg = sw * s.shadow[1] + mw * s.mid[1] + hw * s.high[1]
                    var db = sw * s.shadow[2] + mw * s.mid[2] + hw * s.high[2]

                    // 3) 肤色保护：去色偏 + 提亮去黄增粉嫩
                    val sk = skinWeight(r0, g0, b0, luma)
                    val prot = 1f - sk * s.skinProtect
                    dr *= prot; dg *= prot; db *= prot
                    val lift = sk * s.skinLift
                    dr += lift * s.liftRGB[0]
                    dg += lift * s.liftRGB[1]
                    db += lift * s.liftRGB[2]

                    // 4) 高光低饱和保护：白衣 / 天空 / 白墙保持干净
                    val mx0 = max(r0, max(g0, b0))
                    val mn0 = min(r0, min(g0, b0))
                    val sat0 = if (mx0 > 0f) (mx0 - mn0) / mx0 else 0f
                    val hprot = smoothstep(0.66f, 0.90f, luma) * (1f - smoothstep(0.10f, 0.32f, sat0)) * s.hprot
                    val hp = 1f - hprot
                    dr *= hp; dg *= hp; db *= hp

                    // 4.5) 全局色温（非肤色区域）——三款差异的主要来源
                    val nonSkin = (1f - sk) * (1f - hprot * 0.55f)
                    dr += s.gcast[0] * nonSkin
                    dg += s.gcast[1] * nonSkin
                    db += s.gcast[2] * nonSkin

                    // 5) 红色敏感（Classic U）
                    if (s.redBoost > 0f) {
                        val redness = smoothstep(0.03f, 0.18f, r0 - max(g0, b0)) * (1f - sk)
                        r += redness * s.redBoost * 0.10f
                        g -= redness * s.redBoost * 0.02f
                    }

                    r += dr; g += dg; b += db

                    // 6) 饱和度（肤色保护）
                    val l2 = 0.299f * r + 0.587f * g + 0.114f * b
                    val sat = s.sat + sk * s.skinSatDelta
                    r = l2 + (r - l2) * sat
                    g = l2 + (g - l2) * sat
                    b = l2 + (b - l2) * sat

                    // 7) 白布中性化（仅直闪人像）：高亮低饱和的白裙/白衣往亮度灰度
                    //    拉近，消除被肤色逻辑与保色曲线保留的暖粉偏色；肤色(sat>0.16)
                    //    与彩色碎花不受影响。
                    if (s.whiteNeutral > 0f) {
                        val wg = smoothstep(0.78f, 0.90f, luma) * (1f - smoothstep(0.06f, 0.16f, sat0))
                        val amt = s.whiteNeutral * wg
                        if (amt > 0.001f) {
                            val lf = 0.299f * r + 0.587f * g + 0.114f * b
                            r += (lf - r) * amt
                            g += (lf - g) * amt
                            b += (lf - b) * amt
                        }
                    }

                    val idx = ((ri * N + gi) * N + bi) * 3
                    out[idx] = r.coerceIn(0f, 1f)
                    out[idx + 1] = g.coerceIn(0f, 1f)
                    out[idx + 2] = b.coerceIn(0f, 1f)
                }
            }
        }
        return out
    }

    /**
     * 三线性插值采样。r/g/b 为 0..255，返回打包的 0xRRGGBB。
     * 索引布局与 [build] 一致：idx = ((ri*N + gi)*N + bi)*3。
     */
    fun sample(lut: FloatArray, r: Int, g: Int, b: Int): Int = sampleCore(lut, r, g, b)

    /** 与 [sample] 完全等价的三线性插值，供采样器未命中时复用。 */
    private fun sampleCore(lut: FloatArray, r: Int, g: Int, b: Int): Int {
        val r0 = SAMPLE_INDEX[r]; val g0 = SAMPLE_INDEX[g]; val b0 = SAMPLE_INDEX[b]
        val r1 = if (r0 >= N - 1) N - 1 else r0 + 1
        val g1 = if (g0 >= N - 1) N - 1 else g0 + 1
        val b1 = if (b0 >= N - 1) N - 1 else b0 + 1
        val tr = SAMPLE_FRAC[r]; val tg = SAMPLE_FRAC[g]; val tb = SAMPLE_FRAC[b]

        // 8 个格点线性索引
        val i000 = ((r0 * N + g0) * N + b0) * 3
        val i100 = ((r1 * N + g0) * N + b0) * 3
        val i010 = ((r0 * N + g1) * N + b0) * 3
        val i110 = ((r1 * N + g1) * N + b0) * 3
        val i001 = ((r0 * N + g0) * N + b1) * 3
        val i101 = ((r1 * N + g0) * N + b1) * 3
        val i011 = ((r0 * N + g1) * N + b1) * 3
        val i111 = ((r1 * N + g1) * N + b1) * 3

        // 沿 r 插值
        var c00r = lut[i000] + (lut[i100] - lut[i000]) * tr
        var c00g = lut[i000 + 1] + (lut[i100 + 1] - lut[i000 + 1]) * tr
        var c00b = lut[i000 + 2] + (lut[i100 + 2] - lut[i000 + 2]) * tr
        var c10r = lut[i010] + (lut[i110] - lut[i010]) * tr
        var c10g = lut[i010 + 1] + (lut[i110 + 1] - lut[i010 + 1]) * tr
        var c10b = lut[i010 + 2] + (lut[i110 + 2] - lut[i010 + 2]) * tr
        var c01r = lut[i001] + (lut[i101] - lut[i001]) * tr
        var c01g = lut[i001 + 1] + (lut[i101 + 1] - lut[i001 + 1]) * tr
        var c01b = lut[i001 + 2] + (lut[i101 + 2] - lut[i001 + 2]) * tr
        var c11r = lut[i011] + (lut[i111] - lut[i011]) * tr
        var c11g = lut[i011 + 1] + (lut[i111 + 1] - lut[i011 + 1]) * tr
        var c11b = lut[i011 + 2] + (lut[i111 + 2] - lut[i011 + 2]) * tr
        // 沿 g
        val c0r = c00r + (c10r - c00r) * tg
        val c0g = c00g + (c10g - c00g) * tg
        val c0b = c00b + (c10b - c00b) * tg
        val c1r = c01r + (c11r - c01r) * tg
        val c1g = c01g + (c11g - c01g) * tg
        val c1b = c01b + (c11b - c01b) * tg
        // 沿 b
        val rr = (c0r + (c1r - c0r) * tb) * 255f
        val gg = (c0g + (c1g - c0g) * tb) * 255f
        val bb = (c0b + (c1b - c0b) * tb) * 255f
        return (rr.toInt().coerceIn(0, 255) shl 16) or
            (gg.toInt().coerceIn(0, 255) shl 8) or
            bb.toInt().coerceIn(0, 255)
    }
}
