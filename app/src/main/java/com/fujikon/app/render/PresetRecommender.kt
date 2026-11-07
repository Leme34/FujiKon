package com.fujikon.app.render

import com.fujikon.app.data.Preset
import kotlin.math.abs

data class PresetRecommendation(
    val presetId: String,
    val presetName: String,
    /** 0..1, higher is a better match for the measured frame. */
    val score: Float,
    /** Human-readable, explainable reason — no black box. */
    val reason: String
)

/**
 * Offline, explainable preset recommendation.
 *
 * Uses only the already measured [ImageStats] and each preset's own published
 * parameters — no cloud, no model, no extra pass over the photo. It is the
 * "AI 推荐预设" capability's offline equivalent: deterministic and private.
 */
object PresetRecommender {

    fun rank(stats: ImageStats, presets: List<Preset>, limit: Int = 3): List<PresetRecommendation> {
        val dark = stats.meanLuma < 0.42f
        val bright = stats.meanLuma > 0.62f
        val clipped = stats.highlightClip > 0.012f
        val crushed = stats.shadowClip > 0.012f
        val cold = stats.warmth < -0.03f
        val warmCast = stats.warmth > 0.05f
        val green = stats.greenCoverage > 0.08f
        val skin = stats.skinCoverage > 0.06f
        val flat = stats.spread < 0.48f
        val overSaturated = stats.meanSaturation > 0.55f

        return presets.asSequence()
            .filter { !it.isNone }
            .map { preset ->
                var score = 0.30f
                val reasons = ArrayList<String>(3)

                val brightness = preset.brightness / 100f
                val contrast = preset.contrast / 100f
                val saturation = preset.saturation / 100f
                val highlights = preset.highlights / 100f
                val shadows = preset.shadows / 100f

                if (dark && (brightness > 0.02f || shadows > 0.03f)) {
                    score += 0.20f; reasons += "偏暗画面，该预设提亮暗部"
                }
                if (bright && (brightness < -0.02f || highlights < -0.03f)) {
                    score += 0.18f; reasons += "偏亮画面，该预设压住高光"
                }
                if (clipped && highlights < -0.02f) {
                    score += 0.14f; reasons += "高光有剪切，该预设回收亮部"
                }
                if (crushed && shadows > 0.02f) {
                    score += 0.12f; reasons += "暗部死黑，该预设提阴影"
                }
                if (flat && contrast > 0.03f) {
                    score += 0.12f; reasons += "画面偏平，该预设加强反差"
                }
                if (overSaturated && saturation < -0.02f) {
                    score += 0.10f; reasons += "色彩偏艳，该预设降饱和"
                }

                val text = "${preset.name} ${preset.baseProfileName} ${preset.description ?: ""} ${preset.note}"
                when {
                    green && (text.contains("风景") || text.contains("风光") || text.contains("Landscape")) -> {
                        score += 0.16f; reasons += "绿色背景较多，匹配风光类"
                    }
                    skin && (text.contains("人像") || text.contains("Portrait") || text.contains("皮肤")) -> {
                        score += 0.16f; reasons += "检测到肤色主体，匹配人像类"
                    }
                    cold && (text.contains("暖") || text.contains("Warm")) -> {
                        score += 0.10f; reasons += "画面偏冷，匹配暖调预设"
                    }
                    warmCast && (text.contains("冷") || text.contains("Cool") || text.contains("青")) -> {
                        score += 0.08f; reasons += "画面偏暖，匹配冷调预设"
                    }
                }

                if (preset.favorite) score += 0.06f
                // Prefer presets whose curve actually carries a look.
                if (preset.hasCurve) score += 0.04f
                // A near-neutral preset is a weak recommendation.
                if (abs(brightness) < 0.01f && abs(contrast) < 0.01f &&
                    abs(saturation) < 0.01f && !preset.hasCurve
                ) score -= 0.10f

                PresetRecommendation(
                    presetId = preset.id,
                    presetName = preset.name,
                    score = score.coerceIn(0f, 1f),
                    reason = if (reasons.isEmpty()) "综合参数与当前画面较协调" else reasons.take(2).joinToString("；")
                )
            }
            .sortedByDescending { it.score }
            .take(limit.coerceIn(1, 10))
            .toList()
    }
}
