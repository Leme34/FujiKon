package com.fujikon.app.render

import android.graphics.Bitmap
import kotlin.math.exp
import kotlin.math.hypot

/**
 * 1.6.57 阶段一：专用人脸检测结论。
 *
 * 之前用通用语义分割（VOC/ADE20K）的 person 概率猜人体，在食物 / 暖色
 * 物体上会零星撒出伪「人」概率（美食照实测 52 个高概率 cell 实为 3 团
 * 噪声）。改用专为近景人脸训练的 BlazeFace：美食 / 风景 / 背影照实测
 * 高置信 anchor 数恒为 0，正脸人像为 7 个且聚在人脸位置。
 */
data class FaceEvidence(
    val detected: Boolean,
    /** 高置信 anchor 数，用于强度映射与可解释性。 */
    val anchors: Int,
    /** 最高人脸置信度 0..1。 */
    val confidence: Float,
    val source: String
) {
    companion object {
        val NONE = FaceEvidence(false, 0, 0f, "未检测到人脸")
    }
}

/** 可注入，纯 JVM 单测用 fake 喂入 896 个 anchor logits，不触碰 TFLite native。 */
fun interface FaceDetector {
    /** null = 检测器不可用，调用方必须按「未知」处理，不得当作有人脸。 */
    fun detect(source: Bitmap?): FaceEvidence?
}

/**
 * 把 BlazeFace short-range 的 896 个 anchor logits 归约为「是否有正脸」。
 *
 * 判定刻意偏保守（宁可漏、不可误）：
 *  - 最高 sigmoid ≥ [MIN_CONFIDENCE]；
 *  - 高置信 anchor ≥ [MIN_ANCHORS]；
 *  - 这些 anchor 在归一化坐标上构成**单一占主导的连通块**（人脸是一个
 *    连续区域，而不是散落在各处的暖色噪点）。
 *
 * 896 个 anchor 的排布沿用 MediaPipe BlazeFace：前 512 个是 16×16 网格
 * 每格 2 个，后 384 个是 8×8 网格每格 6 个。用索引即可还原归一化中心，
 * 无需解码 regressors，也避开自定义算子与 NMS。
 */
object BlazeFaceVerdict {

    internal const val ANCHOR_COUNT = 896
    private const val MIN_CONFIDENCE = 0.72f
    private const val MIN_ANCHORS = 4
    private const val HIGH = 0.62f
    private const val MIN_DOMINANCE = 0.80f

    /** 纯函数，供单测直接喂 logits。 */
    fun fromLogits(logits: FloatArray): FaceEvidence {
        if (logits.size < ANCHOR_COUNT) return FaceEvidence.NONE
        var maxScore = 0f
        var highCount = 0
        val cx = FloatArray(ANCHOR_COUNT)
        val cy = FloatArray(ANCHOR_COUNT)
        val high = BooleanArray(ANCHOR_COUNT)
        for (i in 0 until ANCHOR_COUNT) {
            val s = sigmoid(logits[i])
            if (s > maxScore) maxScore = s
            if (s >= HIGH) {
                high[i] = true
                highCount++
                val (x, y) = anchorCenter(i)
                cx[i] = x
                cy[i] = y
            }
        }
        if (maxScore < MIN_CONFIDENCE || highCount < MIN_ANCHORS) return FaceEvidence.NONE
        // 单一连通簇：人脸的 anchor 在归一化坐标上彼此靠近（实测 5 个 cell
        // 全落在 0.44..0.56 × 0.34..0.47）。散落噪声不构成大簇。
        val largest = largestCluster(high, cx, cy)
        if (largest < MIN_ANCHORS || largest < highCount * MIN_DOMINANCE) {
            return FaceEvidence.NONE
        }
        return FaceEvidence(true, highCount, maxScore, "AI 人脸检测 · BlazeFace")
    }

    /** 896 anchor 的归一化中心 (0..1)，按 16×16×2 + 8×8×6 还原。 */
    private fun anchorCenter(i: Int): Pair<Float, Float> = if (i < 512) {
        val cell = i / 2
        ((cell % 16) + 0.5f) / 16f to ((cell / 16) + 0.5f) / 16f
    } else {
        val cell = (i - 512) / 6
        ((cell % 8) + 0.5f) / 8f to ((cell / 8) + 0.5f) / 8f
    }

    /** 归一化距离 < 0.16 视为同一人脸簇；返回最大连通簇大小。 */
    private fun largestCluster(high: BooleanArray, cx: FloatArray, cy: FloatArray): Int {
        val n = high.size
        val seen = BooleanArray(n)
        var best = 0
        for (start in 0 until n) {
            if (!high[start] || seen[start]) continue
            var size = 0
            val stack = mutableListOf(start)
            seen[start] = true
            while (stack.isNotEmpty()) {
                val c = stack.removeAt(stack.lastIndex)
                size++
                for (j in 0 until n) {
                    if (!high[j] || seen[j]) continue
                    if (hypot(cx[c] - cx[j], cy[c] - cy[j]) < 0.16f) {
                        seen[j] = true
                        stack.add(j)
                    }
                }
            }
            if (size > best) best = size
        }
        return best
    }

    private fun sigmoid(v: Float): Float {
        val c = v.coerceIn(-50f, 50f)
        return (1f / (1f + exp(-c))).toFloat()
            .let { if (it.isNaN()) 0f else it }
    }
}
