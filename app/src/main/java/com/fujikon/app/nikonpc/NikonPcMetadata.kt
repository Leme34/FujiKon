package com.fujikon.app.nikonpc

import kotlin.math.roundToInt

/**
 * The small subset of nikonpc.com's `loadNpc` response that is needed to ask
 * the site to generate an NCP file again. The response is a JavaScript object
 * literal rather than JSON, so it is read defensively line by line.
 */
data class NikonPcMetadata(
    val fileName: String,
    val name: String,
    val picCtrl: String,
    val reserved02: String,
    val customCurve: Boolean,
    val sharpening: String?,
    val contrast: String?,
    val brightness: String?,
    val saturation: String?,
    val hue: String?,
    val filterEffect: String,
    val toning: String?,
    val toningType: String,
    val gamma: String,
    val minInput: String,
    val maxInput: String,
    val minOutput: String,
    val maxOutput: String,
    val points: List<Pair<Int, Int>>
)

object NikonPcMetadataParser {

    private val pointsRegex = Regex(
        """'points'\s*:\s*\[(.*?)]\s*,\s*'gamma'""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )
    private val numberRegex = Regex("""-?\d+(?:\.\d+)?""")

    fun parse(body: String): NikonPcMetadata {
        val name = quotedField(body, "name")
            ?: throw NikonPcParseException("nikonpc 返回的预设详情缺少名称")
        val points = parsePoints(body)
        if (points.size < 2) {
            throw NikonPcParseException("nikonpc 返回的预设曲线无效")
        }

        return NikonPcMetadata(
            fileName = quotedField(body, "filename").orEmpty(),
            name = name,
            picCtrl = field(body, "picCtrl").orEmpty().ifBlank { "false" },
            reserved02 = field(body, "reserved02").orEmpty().ifBlank { "0" },
            customCurve = field(body, "customCurveFlag") == "1",
            sharpening = field(body, "sharpening"),
            contrast = field(body, "contrast"),
            brightness = field(body, "brightness"),
            saturation = field(body, "saturation"),
            hue = field(body, "hue"),
            filterEffect = field(body, "filterEffect").orEmpty().ifBlank { "80" },
            toning = field(body, "toning"),
            toningType = field(body, "toningType").orEmpty().ifBlank { "80" },
            gamma = field(body, "gamma").orEmpty().ifBlank { "1.00" },
            minInput = field(body, "minInput").orEmpty().ifBlank { "0" },
            maxInput = field(body, "maxInput").orEmpty().ifBlank { "255" },
            minOutput = field(body, "minOutput").orEmpty().ifBlank { "0" },
            maxOutput = field(body, "maxOutput").orEmpty().ifBlank { "255" },
            points = points
        )
    }

    private fun parsePoints(body: String): List<Pair<Int, Int>> {
        val match = pointsRegex.find(body) ?: return emptyList()
        val values = numberRegex.findAll(match.groupValues[1])
            .map { it.value.toFloat().roundToInt() }
            .toList()
        if (values.size < 4 || values.size % 2 != 0) return emptyList()
        return values.chunked(2)
            .map { Pair(it[0], it[1]) }
            .sortedBy { it.first }
    }

    /**
     * Reads quoted values. Greedy matching keeps a name such as
     * `Photographio's_Cineflat` intact.
     */
    private fun quotedField(body: String, key: String): String? {
        val regex = Regex(
            """(?m)^[ \t]*'${Regex.escape(key)}'[ \t]*:[ \t]*'(.*)'[ \t]*[,}\]]*[ \t]*\r?$"""
        )
        return regex.find(body)?.groupValues?.getOrNull(1)
    }

    /** Reads unquoted values such as `false`, `null` and numbers. */
    private fun field(body: String, key: String): String? {
        quotedField(body, key)?.let { return it }
        val regex = Regex(
            """(?m)^[ \t]*'${Regex.escape(key)}'[ \t]*:[ \t]*([^'\r\n]+?)[ \t]*[,}\]]*[ \t]*\r?$"""
        )
        return regex.find(body)?.groupValues?.getOrNull(1)?.trim()
    }
}

class NikonPcParseException(message: String) : Exception(message)
