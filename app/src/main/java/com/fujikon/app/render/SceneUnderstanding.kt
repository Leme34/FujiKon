package com.fujikon.app.render

import android.graphics.Bitmap

/**
 * 1.6.57 阶段二：一键优化识别到的拍摄场景。
 *
 * [blocksBeauty] 标记「画面主体不可能是人」的场景。美食、风景、文档、夜景
 * 一律关闭人像美颜——这是继「人体检测」之后的第二道保险：即便分割模型在
 * 暖色食物上偶有误判，场景结论也能把人像美颜挡住（双保险）。
 */
enum class PhotoScene(val label: String, val blocksBeauty: Boolean) {
    PORTRAIT("人像", false),
    FOOD("美食", true),
    LANDSCAPE("风景", true),
    NIGHT("夜景", true),
    DOCUMENT("文档", true),
    OTHER("通用", false)
}

/** 场景识别结论。[confidence] 是该场景在 top-k softmax 上的累计概率。 */
data class SceneEvidence(
    val scene: PhotoScene,
    val confidence: Float,
    val source: String
) {
    companion object {
        /** 模型不可用 / 低置信：不阻挡也不改变任何既有行为。 */
        val UNKNOWN = SceneEvidence(PhotoScene.OTHER, 0f, "场景未识别（模型未加载）")
    }
}

/** 可注入，纯 JVM 单测用 fake 喂入预设的 1000 类概率，不触碰 TFLite native。 */
fun interface SceneDetector {
    /** null = 检测器不可用，调用方必须按「未知」处理。 */
    fun detect(source: Bitmap?): SceneEvidence?
}

/**
 * ImageNet-1k → 场景的静态映射。用**精确类名**建表（不是子串），避免
 * `goldfish` 命中 `fish`、`sea_lion` 命中 `sea` 这类误伤。索引即 ImageNet
 * class id，字符：F=美食 L=风景 D=文档 P=人像线索 .=无关。
 *
 * 判定用 top-12 累计概率投票：单张照片的 top-1 可能落在无关类（如人像照
 * 的 `pajama`），但同场景的多个类会一起出现在 top-k，累计后非常稳定。
 * 实测：美食 0.539、风景 0.516、人像 0.336、群像 0.039（低置信归通用）。
 */
object SceneClassMapper {

    private const val TOP_K = 12
    /** 累计概率低于此值视为「没有明确场景」，退回 OTHER，不影响既有行为。 */
    private const val MIN_VOTE = 0.045f
    /** 美食置信度稍高才定案，避免把暖色调的人像照误判成美食。 */
    private const val FOOD_MIN_VOTE = 0.12f

    private val MAP: String =
        "...................................................................................................." +
        "..................F..F.............................................................................." +
        "...................................................................................................." +
        "...................................................................................................P" +
        "........................................FF....D....PPDD..P..L...........L.D...F.....L..............." +
        "...FFF..DF.....P...........D........L............D.................F....F.....P....................F" +
        "........P.PD..P...F.D........P.............P.........F....PF..................P..D...........L...P.." +
        ".............D....L......F...........F....D...................F...F...P...P.....LD...P.............." +
        ".P....P.PF...F....................P.PP...P.......F...................P.L..........................F." +
        "...P...F.FF.....DDDD.DFFF.FFF.FFFFFFFFFFFFFFFFFFFFFFFFF.FF.FF.FFFFFF..L.LLLLLLLLL......F....F.....F."

    init {
        check(MAP.length == 1000) { "ImageNet scene map must cover all 1000 classes" }
    }

    /** 供单测/模型层复用：把 1000 类 softmax 归约为一个场景结论。纯函数。 */
    fun classify(scores: FloatArray): SceneEvidence {
        if (scores.size < 1000) return SceneEvidence.UNKNOWN
        // O(1000) 的部分选择：只保留概率最高的 TOP_K 个，无需全量排序。
        val topIdx = IntArray(TOP_K) { -1 }
        val topVal = FloatArray(TOP_K) { -1f }
        for (i in 0 until 1000) {
            val v = scores[i]
            if (v <= topVal[TOP_K - 1]) continue
            var p = TOP_K - 1
            while (p > 0 && topVal[p - 1] < v) {
                topVal[p] = topVal[p - 1]
                topIdx[p] = topIdx[p - 1]
                p--
            }
            topVal[p] = v
            topIdx[p] = i
        }
        var food = 0f
        var land = 0f
        var doc = 0f
        var portrait = 0f
        for (k in 0 until TOP_K) {
            val idx = topIdx[k]
            if (idx < 0) continue
            val v = topVal[k]
            when (MAP[idx]) {
                'F' -> food += v
                'L' -> land += v
                'D' -> doc += v
                'P' -> portrait += v
            }
        }
        // 美食门槛更高：美食照通常一击即中（>0.4），而人像照偶尔出现的
        // 食物类只在 0.02~0.06 量级，不会误伤。
        val best = maxOf(food, land, doc, portrait)
        val scene = when {
            food == best && food >= FOOD_MIN_VOTE -> PhotoScene.FOOD
            land == best && best >= MIN_VOTE -> PhotoScene.LANDSCAPE
            doc == best && best >= MIN_VOTE -> PhotoScene.DOCUMENT
            portrait == best && best >= MIN_VOTE -> PhotoScene.PORTRAIT
            else -> PhotoScene.OTHER
        }
        return if (scene == PhotoScene.OTHER) {
            SceneEvidence(scene, best, "本机场景分类 · EfficientNet-Lite0")
        } else {
            SceneEvidence(scene, best, "AI 场景分类 · EfficientNet-Lite0")
        }
    }
}
