package com.fujikon.app.nikonpc

import com.fujikon.app.ncp.PictureControl

/**
 * One Picture Control offered by nikonpc.com.
 *
 * [path] is the value of the option on the site, for example
 * `Agfa/PICCON01.NCP`. It is used as the key for import state and is sent back
 * to the site when the preset is downloaded.
 */
data class NikonPcPreset(
    val path: String,
    val name: String,
    val slot: String,
    val category: String
) {
    val key: String get() = path

    val categoryLabel: String
        get() = if (category == "NikonPC") {
            "NikonPC"
        } else {
            category.replace('_', ' ').replace('-', ' ').trim()
        }

    val numberOfDigits: String
        get() = path.substringAfterLast('/').substringBeforeLast('.').filter { it.isDigit() }.take(2)
}

/** Parses the `<select>` options embedded in nikonpc.com's home page. */
object NikonPcCatalogParser {

    private val optionRegex = Regex("""<option\b([^>]*)>([\s\S]*?)</option>""", RegexOption.IGNORE_CASE)
    private val attributeRegex = Regex(
        """([A-Za-z_:][-A-Za-z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)')"""
    )
    private val supportedExtension = Regex("""\.(ncp|np2|np3)$""", RegexOption.IGNORE_CASE)
    private val slotRegex = Regex("""^\s*\[([^\]]+)]""")
    private val entityRegex = Regex("""&(#x?[0-9A-Fa-f]+|[A-Za-z]+);""")

    /**
     * Reads the photo the site feeds into its `cLutImage` preview canvas.
     * `inpImageUrl` holds the absolute URL; `imgPreview` is its proxy form.
     */
    fun parsePreviewImageUrl(html: String): String? {
        val input = Regex("""<input\b[^>]*id\s*=\s*"inpImageUrl"[^>]*>""", RegexOption.IGNORE_CASE)
            .find(html)?.value?.let(::parseAttributes)
        input?.get("value")?.trim()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?.let { return it }

        val image = Regex("""<img\b[^>]*id\s*=\s*"imgPreview"[^>]*>""", RegexOption.IGNORE_CASE)
            .find(html)?.value?.let(::parseAttributes)
        return image?.get("src")?.trim()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    fun parse(html: String): List<NikonPcPreset> {
        val result = mutableListOf<NikonPcPreset>()
        optionRegex.findAll(html).forEach { match ->
            val attributes = parseAttributes(match.groupValues[1])
            val path = decodeHtml(attributes["value"].orEmpty()).trim()
            if (!supportedExtension.containsMatchIn(path)) return@forEach

            val optionText = decodeHtml(match.groupValues[2]).trim()
            val title = decodeHtml(attributes["title"].orEmpty()).trim()
            val titleName = title.substringBefore(';').trim()
            val textName = optionText.replace(slotRegex, "").trim()
            val name = titleName.ifBlank { textName }
                .ifBlank { path.substringAfterLast('/').substringBeforeLast('.') }

            val slot = slotRegex.find(optionText)?.groupValues?.getOrNull(1)?.trim()
                ?: path.substringAfterLast('/').substringBeforeLast('.').filter { it.isDigit() }.take(2)
            val category = path.substringBeforeLast('/', "").ifBlank { "NikonPC" }

            result += NikonPcPreset(
                path = path,
                name = name,
                slot = slot.ifBlank { "NCP" },
                category = category
            )
        }
        return result.distinctBy { it.path }
    }

    private fun parseAttributes(raw: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        attributeRegex.findAll(raw).forEach { match ->
            val key = match.groupValues[1].lowercase()
            val value = match.groups[2]?.value ?: match.groups[3]?.value.orEmpty()
            result[key] = value
        }
        return result
    }

    private fun decodeHtml(value: String): String {
        if (!value.contains('&')) return value
        return entityRegex.replace(value) { match ->
            when (val entity = match.groupValues[1]) {
                "amp" -> "&"
                "quot" -> "\""
                "apos" -> "'"
                "lt" -> "<"
                "gt" -> ">"
                "nbsp" -> " "
                else -> {
                    val codePoint = when {
                        entity.startsWith("#x", ignoreCase = true) ->
                            entity.drop(2).toIntOrNull(16)
                        entity.startsWith('#') ->
                            entity.drop(1).toIntOrNull()
                        else -> null
                    }
                    codePoint?.takeIf { Character.isValidCodePoint(it) }
                        ?.let { String(Character.toChars(it)) }
                        ?: match.value
                }
            }
        }
    }
}

/**
 * A short, list-friendly description of the look encoded in [control].
 *
 * nikonpc.com itself only publishes the binary preset, so the download list
 * derives the summary from the decoded parameters instead. Files that embed a
 * comment (NP3) use that text verbatim.
 */
internal fun presetSummary(control: PictureControl): String {
    val embedded = control.description.orEmpty()
        .replace(Regex("\\s+"), " ")
        .trim()
    if (embedded.isNotEmpty()) {
        return if (embedded.length <= SUMMARY_LIMIT) embedded else embedded.take(SUMMARY_LIMIT) + "…"
    }

    val parts = buildList {
        add("基于 ${control.baseProfileName}")
        if (control.hasCurve) add("自定义曲线")
        signed(control.sharpening)?.let { add("锐化 $it") }
        signed(control.clarity)?.let { add("清晰度 $it") }
        signed(control.midRangeSharpening)?.let { add("中灰锐化 $it") }
        control.contrast.takeIf { it != 0 }?.let { add("对比 ${sign(it)}") }
        control.brightness.takeIf { it != 0 }?.let { add("亮度 ${sign(it)}") }
        control.saturation.takeIf { it != 0 }?.let { add("饱和 ${sign(it)}") }
        control.hue.takeIf { it != 0 }?.let { add("色相 ${sign(it)}") }
        control.highlights.takeIf { it != 0 }?.let { add("高光 ${sign(it)}") }
        control.shadows.takeIf { it != 0 }?.let { add("阴影 ${sign(it)}") }
        control.whites.takeIf { it != 0 }?.let { add("白色 ${sign(it)}") }
        control.blacks.takeIf { it != 0 }?.let { add("黑色 ${sign(it)}") }
        control.filter.takeIf { it != 0 }?.let { add("滤镜 ${sign(it)}") }
        control.toning.takeIf { it != 0 }?.let { add("色调 ${sign(it)}") }
        if (control.hasColorBlender) add("色彩混合")
        if (control.hasColorGrading) add("色彩分级")
    }
    return parts.joinToString(" · ")
}

/** Same summary, built from the site's `loadNpc` metadata only (no NCP download). */
internal fun presetSummary(metadata: NikonPcMetadata): String {
    val parts = buildList {
        add("基于 ${baseProfileLabel(metadata.picCtrl)}")
        if (metadata.customCurve) add("自定义曲线")
        numeric(metadata.sharpening)?.let { add("锐化 ${signedText(it)}") }
        numeric(metadata.contrast)?.let { add("对比 ${signedText(it)}") }
        numeric(metadata.brightness)?.let { add("亮度 ${signedText(it)}") }
        numeric(metadata.saturation)?.let { add("饱和 ${signedText(it)}") }
        numeric(metadata.hue)?.let { add("色相 ${signedText(it)}") }
        if (metadata.filterEffect.isNotBlank() && metadata.filterEffect != "80") {
            add("单色滤镜")
        }
        metadata.toning?.toIntOrNull()?.takeIf { it != 0 }?.let { add("色调") }
    }
    return parts.joinToString(" · ")
}

private fun baseProfileLabel(picCtrl: String): String = when (picCtrl.trim().uppercase()) {
    "STANDARD" -> "标准 Standard"
    "VIVID" -> "鲜艳 Vivid"
    "NEUTRAL" -> "自然 Neutral"
    "PORTRAIT" -> "人像 Portrait"
    "LANDSCAPE" -> "风景 Landscape"
    "MONOCHROME" -> "单色 Monochrome"
    else -> "Flexible Color"
}

private fun numeric(raw: String?): Float? = raw?.trim()?.toFloatOrNull()

private fun signedText(value: Float): String = signed(value) ?: "0"

private const val SUMMARY_LIMIT = 120

private fun sign(value: Int): String = if (value > 0) "+$value" else value.toString()

private fun signed(value: Float): String? {
    if (value == 0f) return null
    val text = if (value % 1f == 0f) {
        value.toInt().toString()
    } else {
        String.format(java.util.Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
    }
    return if (value > 0) "+$text" else text
}
