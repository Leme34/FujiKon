package com.fujikon.app.nikonpc

/**
 * nikonpc.com has one known malformed output: presets whose metadata contains
 * `picCtrl: false` are written with a 34-byte record #1 even though the record
 * header still says 36 bytes. The two missing bytes are the zero padding before
 * the adjustment fields. This narrow repair restores the documented NCP layout
 * without touching normal files.
 */
object NikonPcNcpRepair {

    fun repair(bytes: ByteArray): ByteArray {
        if (bytes.size < 56) return bytes
        if (bytes[0] != 'N'.code.toByte() || bytes[1] != 'C'.code.toByte() || bytes[2] != 'P'.code.toByte()) {
            return bytes
        }
        if (readU32(bytes, 4) != 1 || readU32(bytes, 8) != 36) return bytes
        // A valid file has record #2 at 48. The malformed file has it at 46.
        if (readU32(bytes, 46) != 2 || readU32(bytes, 48) == 2) return bytes
        val recordSize = readU32(bytes, 50)
        if (recordSize <= 0 || recordSize > bytes.size - 54) return bytes

        val repaired = ByteArray(bytes.size + 2)
        val missingPadding = 12 + 26
        bytes.copyInto(repaired, 0, 0, missingPadding)
        // Keep the zero padding that the generator omitted after the base profile.
        bytes.copyInto(repaired, missingPadding + 2, missingPadding, bytes.size)
        return repaired
    }

    private fun readU32(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }
}
