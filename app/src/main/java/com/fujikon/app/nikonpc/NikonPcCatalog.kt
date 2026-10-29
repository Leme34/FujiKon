package com.fujikon.app.nikonpc

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
