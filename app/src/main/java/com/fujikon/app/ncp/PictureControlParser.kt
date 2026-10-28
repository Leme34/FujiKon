package com.fujikon.app.ncp

import java.io.ByteArrayOutputStream

/**
 * Pure-JVM reader for Nikon NCP (legacy 0100) and NP3 (0300/0310) files.
 *
 * The container is a simple big-endian TLV stream:
 *
 *     "NCP\0"  [id:u32][size:u32][body...] ... [0x00000000]
 *
 * NCP keeps the recipe in record #1 (36 bytes) and the tone LUT in record #2.
 * NP3 uses tagged records (0x0100 version, 0x0200 name, 0x0300 base, ...).
 */
object PictureControlParser {

    class ParseException(message: String) : Exception(message)

    private const val RECORD_TONE_CURVE = 2
    private const val LUT_OFFSET = 66
    private const val LUT_SIZE = 256

    fun parse(bytes: ByteArray, fileName: String = ""): PictureControl {
        if (bytes.size < 12) throw ParseException("文件过短，不是有效的尼康预设文件")
        val signature = String(bytes, 0, 3, Charsets.US_ASCII)
        if (signature != "NCP" && signature != "NP2" && signature != "NP3") {
            throw ParseException("文件头不是 NCP，无法识别此预设")
        }
        if (bytes[3].toInt() != 0) throw ParseException("文件头格式异常")

        val records = readRecords(bytes)
        val version = records[0x0100]?.let { ascii(it, 0, 4) } ?: ""
        val np3 = signature == "NP3" ||
            (signature == "NCP" && (version.startsWith("03") || version == "0310" || version == "0300"))
        val np2 = signature == "NP2" || (signature == "NCP" && version.startsWith("02"))

        return when {
            np3 -> parseNp3(records, version, bytes)
            np2 -> parseNp2(records, version)
            else -> parseNcp(records, version)
        }
    }

    // ---------------------------------------------------------------- NCP

    private fun parseNcp(records: Map<Int, ByteArray>, versionHint: String): PictureControl {
        val body = records[1] ?: throw ParseException("NCP 缺少设置记录")
        if (body.size < 36) throw ParseException("NCP 设置记录不完整")

        val version = ascii(body, 0, 4).ifBlank { versionHint.ifBlank { "0100" } }
        val name = ascii(body, 4, 20).trim().ifBlank { "未命名预设" }
        val base = u16(body, 24)
        val curve = readCurve(records)

        val sharpening = decodeStored(body[28]).toFloat()
        val contrast = decodeStored(body[29])
        val brightness = decodeStored(body[30])
        val saturation = decodeStored(body[31])
        val hue = decodeStored(body[32])
        val filter = decodeStored(body[33])
        val toning = decodeStored(body[34])
        val toningStrength = decodeStored(body[35])

        return PictureControl(
            format = PictureControlFormat.NCP,
            version = version,
            name = name,
            baseProfile = base,
            baseProfileName = BaseProfiles.nameOf(base),
            curve = curve,
            sharpening = sharpening,
            contrast = contrast,
            brightness = brightness,
            saturation = saturation,
            hue = hue,
            filter = filter,
            toning = toning,
            toningStrength = toningStrength,
            description = null
        )
    }

    // ---------------------------------------------------------------- NP2
    // NP2 shares the NCP record layout for the fields we care about; only the
    // tag says "0200". Many NP2 files are exported by newer cameras and still
    // contain a record #1, so a best-effort NCP read is useful.

    private fun parseNp2(records: Map<Int, ByteArray>, versionHint: String): PictureControl {
        val npc = runCatching { parseNcp(records, versionHint) }.getOrNull()
            ?: throw ParseException("NP2 预设结构暂不支持，请改用 NCP 或 NP3")
        return npc.copy(format = PictureControlFormat.NP2)
    }

    // ---------------------------------------------------------------- NP3

    private fun parseNp3(records: Map<Int, ByteArray>, version: String, bytes: ByteArray): PictureControl {
        val name = records[0x0200]?.let { utf8Trim(it) }?.ifBlank { "未命名预设" } ?: "未命名预设"
        val baseBody = records[0x0300] ?: throw ParseException("NP3 缺少基准 Picture Control")
        val base = if (baseBody.size >= 2) u16(baseBody, 0) else BaseProfiles.FLEXIBLE_COLOR

        val sharpening = scaled4(records[0x0600])
        val clarity = scaled4(records[0x0700])
        val midRange = scaled4(records[0x1600])

        val contrast = signed(records[0x1900])
        val highlights = signed(records[0x1A00])
        val shadows = signed(records[0x1B00])
        val whites = signed(records[0x1C00])
        val blacks = signed(records[0x1D00])
        val saturation = signed(records[0x1E00])

        val filter = signed(records[0x0C00])
        val toning = signed(records[0x0D00])
        val toningStrength = signed(records[0x0E00])

        val blender = parseColorBlender(records[0x1F00])
        val grading = parseColorGrading(records[0x2000])
        val curve = readCurve(records)
        val description = parseDescription(bytes)

        return PictureControl(
            format = PictureControlFormat.NP3,
            version = version.ifBlank { "0310" },
            name = name,
            baseProfile = base,
            baseProfileName = BaseProfiles.nameOf(base),
            curve = curve,
            sharpening = sharpening,
            clarity = clarity,
            midRangeSharpening = midRange,
            contrast = contrast,
            brightness = 0,
            saturation = saturation,
            hue = 0,
            highlights = highlights,
            shadows = shadows,
            whites = whites,
            blacks = blacks,
            filter = filter,
            toning = toning,
            toningStrength = toningStrength,
            colorBlender = blender,
            colorGrading = grading,
            description = description
        )
    }

    private fun parseColorBlender(body: ByteArray?): List<ColorBlendValue> {
        if (body == null || body.size < 24) return emptyList()
        return (0 until 8).map { i ->
            val o = i * 3
            ColorBlendValue(
                hue = signedByte(body[o]),
                chroma = signedByte(body[o + 1]),
                brightness = signedByte(body[o + 2])
            )
        }
    }

    private fun parseColorGrading(body: ByteArray?): ColorGrading {
        if (body == null || body.size < 16) return ColorGrading()
        fun zone(o: Int): ColorGradingZone {
            if (o + 3 >= body.size) return ColorGradingZone()
            val hue = ((body[o].toInt() and 0x0F) shl 8) or (body[o + 1].toInt() and 0xFF)
            return ColorGradingZone(
                hue = hue,
                chroma = signedByte(body[o + 2]),
                brightness = signedByte(body[o + 3])
            )
        }
        val blending = if (body.size > 16) signedByte(body[16]) else 50
        val balance = if (body.size > 18) signedByte(body[18]) else 0
        return ColorGrading(
            highlights = zone(0),
            midtones = zone(4),
            shadows = zone(8),
            blending = blending.coerceIn(0, 100),
            balance = balance
        )
    }

    private fun parseDescription(bytes: ByteArray): String? {
        // The optional UTF-8 comment lives after the 392-byte base layout.
        if (bytes.size < 400) return null
        if (bytes[0x185].toInt() != 1 || bytes[0x186].toInt() != 1) return null
        val length = readU32(bytes, 392)
        if (length <= 0 || length > 1024 || 396 + length > bytes.size) return null
        return runCatching {
            String(bytes, 396, length, Charsets.UTF_8).trimEnd('\u0000').trim()
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun readCurve(records: Map<Int, ByteArray>): IntArray? {
        val body = records[RECORD_TONE_CURVE] ?: return null
        if (body.size < LUT_OFFSET + LUT_SIZE * 2) return null
        val lut = IntArray(LUT_SIZE)
        for (i in 0 until LUT_SIZE) {
            lut[i] = u16(body, LUT_OFFSET + i * 2).coerceIn(0, 32767)
        }
        // A completely flat LUT carries no look; treat it as absent.
        if (lut.all { it == lut[0] }) return null
        return lut
    }

    // ---------------------------------------------------------------- util

    private fun readRecords(bytes: ByteArray): Map<Int, ByteArray> {
        val result = LinkedHashMap<Int, ByteArray>()
        var offset = 4
        var terminated = false
        while (offset + 4 <= bytes.size) {
            val id = readU32(bytes, offset)
            offset += 4
            if (id == 0) {
                terminated = true
                break
            }
            if (offset + 4 > bytes.size) throw ParseException("预设记录被截断")
            val size = readU32(bytes, offset)
            offset += 4
            if (size < 0 || size > bytes.size - offset) throw ParseException("预设记录长度异常")
            if (result.size > 128) throw ParseException("预设记录过多")
            result[id] = bytes.copyOfRange(offset, offset + size)
            offset += size
        }
        if (!terminated && result.isEmpty()) throw ParseException("未找到预设记录")
        return result
    }

    private fun readU32(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun u16(bytes: ByteArray, offset: Int): Int {
        if (offset + 1 >= bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String {
        val end = (offset + length).coerceAtMost(bytes.size)
        val builder = StringBuilder()
        var i = offset
        while (i < end) {
            val value = bytes[i].toInt() and 0xFF
            if (value == 0) break
            builder.append(if (value in 32..126) value.toChar() else ' ')
            i++
        }
        return builder.toString()
    }

    private fun utf8Trim(bytes: ByteArray): String {
        var end = bytes.size
        for (i in bytes.indices) {
            if (bytes[i].toInt() == 0) {
                end = i
                break
            }
        }
        return String(bytes, 0, end, Charsets.UTF_8).trim()
    }

    /**
     * NCP/NP3 parameter byte: 0x80 is zero, 0x00/0x01/0xFF are special modes
     * (auto / controlled by curve / not applicable) and decode to zero.
     */
    private fun decodeStored(byte: Byte): Int {
        val value = byte.toInt() and 0xFF
        return if (value == 0x00 || value == 0x01 || value == 0xFF) 0 else value - 0x80
    }

    private fun signedByte(byte: Byte): Int {
        val value = byte.toInt() and 0xFF
        return if (value == 0x00 || value == 0x01 || value == 0xFF) 0 else value - 0x80
    }

    private fun signed(body: ByteArray?): Int =
        if (body == null || body.isEmpty()) 0 else signedByte(body[0])

    private fun scaled4(body: ByteArray?): Float =
        if (body == null || body.size < 1) 0f else signedByte(body[0]) / 4f

    @Suppress("unused")
    private fun dump(bytes: ByteArray): String {
        val out = ByteArrayOutputStream()
        out.write(bytes)
        return out.toString()
    }
}
