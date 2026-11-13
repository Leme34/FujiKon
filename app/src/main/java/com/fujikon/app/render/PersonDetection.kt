package com.fujikon.app.render

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Evidence that the frame ACTUALLY contains a human body.
 *
 * 1.6.55: 「一键优化」的人像美颜只在本证据 [detected] 为真时启用。之前仅凭
 * ImageStats.skinCoverage > 0.07 就判定人像，沙滩 / 秋叶 / 木墙 / 暖色建筑
 * 都会被误当成脸而强加磨皮美白。现在改为先做人体识别，再由肤色 mask 的
 * 渲染器把美颜限定在皮肤上——「当且仅当识别到人体才对识别到的人像美颜」。
 */
data class PersonEvidence(
    val detected: Boolean,
    /** 0..1 share of the frame occupied by the detected person / skin block. */
    val coverage: Float,
    /** Honest engine label, so a heuristic is never presented as AI. */
    val source: String
) {
    companion object {
        val NONE = PersonEvidence(false, 0f, "未识别到人体")

        fun present(coverage: Float, source: String): PersonEvidence =
            PersonEvidence(true, coverage.coerceIn(0f, 1f), source)
    }
}

/** Pluggable so pure-JVM tests can inject a deterministic verdict. */
fun interface PersonDetector {
    /** null means "detection unavailable" — callers MUST treat it as NOT detected. */
    fun detect(source: Bitmap?): PersonEvidence?
}

/**
 * Primary detector: the on-device semantic segmentation already shipping in
 * the app. PASCAL-VOC class 15 (person) softmax + ADE20K class 13 (person)
 * produce a real body probability map, which is far more precise than a skin
 * colour threshold and also works for backs, winter coats and side profiles.
 */
object MlPersonDetector : PersonDetector {

    private const val SAMPLE_SIDE = 256
    private const val GRID_SIDE = 128

    override fun detect(source: Bitmap?): PersonEvidence? {
        val bmp = source ?: return null
        if (bmp.isRecycled || bmp.width < 2 || bmp.height < 2) return null
        // Null on desktop JVM tests / unsupported ABIs / missing model assets.
        // Falling through to the heuristic is deliberate; never crash here.
        val provider = SelectionMaskGenerator.provider ?: return null
        val (pixels, sw, sh) = sample(bmp, SAMPLE_SIDE) ?: return null
        val maps = provider.segment(pixels, sw, sh, GRID_SIDE) ?: return null
        // A provider without person semantics (older model / test fake) cannot
        // prove a body: report "unknown" instead of guessing from generic
        // "subject" probability, which also covers cars, pets and furniture.
        if (maps.person.isEmpty()) return null
        val cov = maps.personCoverage()
        return PersonEvidence(maps.hasPerson(), cov, "AI 语义分割 · person")
    }

    /** Downsample to at most [side] on the longest edge, preserving the ratio. */
    internal fun sample(bmp: Bitmap, side: Int): Triple<IntArray, Int, Int>? {
        val w = bmp.width
        val h = bmp.height
        if (w < 2 || h < 2) return null
        val scale = side.toFloat() / max(w, h)
        val sw = max(2, (w * scale).roundToInt().coerceAtMost(side))
        val sh = max(2, (h * scale).roundToInt().coerceAtMost(side))
        val pixels = IntArray(sw * sh)
        val row = IntArray(w)
        for (y in 0 until sh) {
            val sy = (y * h / sh).coerceIn(0, h - 1)
            bmp.getPixels(row, 0, w, 0, sy, w, 1)
            for (x in 0 until sw) {
                val sx = (x * w / sw).coerceIn(0, w - 1)
                pixels[y * sw + x] = row[sx]
            }
        }
        return Triple(pixels, sw, sh)
    }
}

/**
 * Strict, conservative fallback used ONLY when the TFLite models are
 * unavailable. It is not "skin coverage > threshold": a warm landscape would
 * pass that. A body is accepted only when ALL of these hold at once:
 *
 *  - skin occupies a plausible 4%..55% of the frame (a beach / wood wall is
 *    far higher, a tiny incidental warm patch far lower);
 *  - the skin pixels form ONE compact central blob (small mean distance to
 *    their own centroid) — scattered autumn leaves are rejected;
 *  - skin is concentrated in the central subject region rather than evenly
 *    spread over the frame;
 *  - there are dark facial features (eyes / brows / lips / hairline) INSIDE
 *    the skin blob's neighbourhood — a flat colour rectangle is not a face.
 *
 * Deliberately biased toward false negatives: when in doubt, no beauty.
 */
object HeuristicPersonDetector : PersonDetector {

    private const val SIDE = 64
    private const val MIN_SKIN = 0.04f
    private const val MAX_SKIN = 0.55f
    private const val MAX_SPREAD = 0.40f
    private const val MIN_CENTER_RATIO = 1.15f
    private const val MIN_FACIAL_DARK = 0.008f
    private const val MAX_FACIAL_DARK = 0.35f

    override fun detect(source: Bitmap?): PersonEvidence? {
        val bmp = source ?: return null
        if (bmp.isRecycled || bmp.width < 2 || bmp.height < 2) return null
        val (pixels, sw, sh) = MlPersonDetector.sample(bmp, SIDE) ?: return null
        val n = sw * sh
        val skin = FloatArray(n)
        val luma = FloatArray(n)

        var skinCount = 0
        var wx = 0f
        var wy = 0f
        var centerSkin = 0
        var centerTotal = 0
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                val i = y * sw + x
                val c = pixels[i]
                val r = ((c shr 16) and 0xFF).toFloat()
                val g = ((c shr 8) and 0xFF).toFloat()
                val b = (c and 0xFF).toFloat()
                val l = (r * 0.299f + g * 0.587f + b * 0.114f) / 255f
                luma[i] = l
                val chroma = (max(r, max(g, b)) - min(r, min(g, b))) / 255f
                // Same YCbCr-like rule as AutoEnhance.analyse's skinCoverage.
                val isSkin = chroma > 0.05f && r > g && g > b &&
                    r - b < 0.45f * 255f && l > 0.22f && l < 0.88f
                if (isSkin) {
                    skin[i] = 1f
                    skinCount++
                    val nx = x / (sw - 1).toFloat()
                    val ny = y / (sh - 1).toFloat()
                    wx += nx
                    wy += ny
                }
                if (abs(x / (sw - 1).toFloat() - 0.5f) < 0.22f &&
                    abs(y / (sh - 1).toFloat() - 0.5f) < 0.28f
                ) {
                    centerTotal++
                    if (isSkin) centerSkin++
                }
            }
        }
        if (skinCount == 0) return PersonEvidence.NONE
        // 1.6.56：人体是一个连贯的轮廓，不能是散落的暖色碎块。美食照里肉块 /
        // 秋叶等会把肤色像素拆成许多互不相连的小团，单看占比 / 中心度都会
        // 误判；要求最大连通肤色块占绝大多数肤色像素，否则视为无人体。
        val largest = largestSkinCluster(skin, sw, sh)
        if (largest < skinCount * 0.70f || largest < 4) {
            return PersonEvidence.NONE
        }
        val skinCov = skinCount / n.toFloat()
        val cx = wx / skinCount
        val cy = wy / skinCount
        var spread = 0f
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                if (skin[y * sw + x] > 0f) {
                    spread += hypot(
                        x / (sw - 1).toFloat() - cx,
                        y / (sh - 1).toFloat() - cy
                    )
                }
            }
        }
        spread /= skinCount
        val centerRatio = if (centerTotal > 0) {
            (centerSkin / centerTotal.toFloat()) / skinCov.coerceAtLeast(1e-4f)
        } else 0f

        // Facial features: dark pixels that sit INSIDE / beside the skin blob,
        // not dark background. A flat skin rectangle has none of these.
        var facialDark = 0
        for (y in 2 until sh - 2) {
            for (x in 2 until sw - 2) {
                if (luma[y * sw + x] >= 0.30f) continue
                var nearSkin = false
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        if (skin[(y + dy) * sw + (x + dx)] > 0f) {
                            nearSkin = true
                            break
                        }
                    }
                    if (nearSkin) break
                }
                if (nearSkin) facialDark++
            }
        }
        val facialDarkRatio = facialDark / n.toFloat()

        val detected = skinCov in MIN_SKIN..MAX_SKIN &&
            spread < MAX_SPREAD &&
            centerRatio > MIN_CENTER_RATIO &&
            facialDarkRatio in MIN_FACIAL_DARK..MAX_FACIAL_DARK
        return PersonEvidence(
            detected = detected,
            coverage = skinCov,
            source = "本机肤色/五官特征（非 AI 模型）"
        )
    }

    /** Largest 4-connected cluster of skin pixels on the [sw]×[sh] grid. */
    private fun largestSkinCluster(skin: FloatArray, sw: Int, sh: Int): Int {
        val seen = BooleanArray(sw * sh)
        var best = 0
        for (start in skin.indices) {
            if (seen[start] || skin[start] <= 0f) continue
            var size = 0
            val stack = mutableListOf(start)
            seen[start] = true
            while (stack.isNotEmpty()) {
                val c = stack.removeAt(stack.lastIndex)
                size++
                val cx = c % sw
                val cy = c / sw
                if (cx > 0) {
                    val nb = c - 1
                    if (!seen[nb] && skin[nb] > 0f) { seen[nb] = true; stack.add(nb) }
                }
                if (cx < sw - 1) {
                    val nb = c + 1
                    if (!seen[nb] && skin[nb] > 0f) { seen[nb] = true; stack.add(nb) }
                }
                if (cy > 0) {
                    val nb = c - sw
                    if (!seen[nb] && skin[nb] > 0f) { seen[nb] = true; stack.add(nb) }
                }
                if (cy < sh - 1) {
                    val nb = c + sw
                    if (!seen[nb] && skin[nb] > 0f) { seen[nb] = true; stack.add(nb) }
                }
            }
            if (size > best) best = size
        }
        return best
    }
}

/** Model first; strict heuristic only when the model cannot produce a verdict. */
object SmartPersonDetector : PersonDetector {
    override fun detect(source: Bitmap?): PersonEvidence? {
        val ml = MlPersonDetector.detect(source)
        if (ml != null) return ml
        return HeuristicPersonDetector.detect(source)
    }
}

/** Single entry point for the UI. Null / [PersonEvidence.detected] = false both
 *  mean "no human body": one-tap beauty must stay OFF. */
fun detectPerson(source: Bitmap?): PersonEvidence? = SmartPersonDetector.detect(source)
