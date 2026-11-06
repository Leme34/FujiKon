package com.fujikon.app.data

import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Channels offered by the tone-curve editor.
 *
 * [RGB] is the master curve, which lives in [Preset.toneCurve]. The three
 * colour channels are stored separately in [ChannelToneCurves].
 */
enum class ToneCurveChannel(val key: String, val label: String) {
    RGB("rgb", "RGB"),
    RED("red", "红"),
    GREEN("green", "绿"),
    BLUE("blue", "蓝");

    val isMaster: Boolean get() = this == RGB

    companion object {
        fun fromKey(key: String?): ToneCurveChannel? =
            values().firstOrNull { it.key == key }
    }
}

/**
 * Per-channel tone curves layered on top of the master RGB curve.
 *
 * A linear channel is a no-op, so a default instance leaves rendering exactly
 * as it was before per-channel curves existed. Presets written by older app
 * versions simply decode to [Neutral].
 */
data class ChannelToneCurves(
    val red: ToneCurve = ToneCurve.linear(),
    val green: ToneCurve = ToneCurve.linear(),
    val blue: ToneCurve = ToneCurve.linear()
) {
    /** True when every colour channel is visually indistinguishable from y = x. */
    val isNeutral: Boolean
        get() = red.isLinear() && green.isLinear() && blue.isLinear()

    /**
     * Returns the curve for one colour channel. [ToneCurveChannel.RGB] has no
     * counterpart here (it is [Preset.toneCurve]) and resolves to a linear curve.
     */
    fun curve(channel: ToneCurveChannel): ToneCurve = when (channel) {
        ToneCurveChannel.RED -> red
        ToneCurveChannel.GREEN -> green
        ToneCurveChannel.BLUE -> blue
        ToneCurveChannel.RGB -> ToneCurve.linear()
    }

    /** Returns a copy with [channel] replaced. The master channel is ignored. */
    fun withCurve(channel: ToneCurveChannel, curve: ToneCurve): ChannelToneCurves =
        when (channel) {
            ToneCurveChannel.RED -> copy(red = curve)
            ToneCurveChannel.GREEN -> copy(green = curve)
            ToneCurveChannel.BLUE -> copy(blue = curve)
            ToneCurveChannel.RGB -> this
        }

    /** Normalises all three point lists so equality checks and history are stable. */
    fun normalized(): ChannelToneCurves = ChannelToneCurves(
        red = ToneCurve(red.normalizedPoints),
        green = ToneCurve(green.normalizedPoints),
        blue = ToneCurve(blue.normalizedPoints)
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("red", red.toJson())
        put("green", green.toJson())
        put("blue", blue.toJson())
    }

    companion object {
        val Neutral = ChannelToneCurves()

        /** Missing or malformed fields fall back to a linear (no-op) curve. */
        fun fromJson(json: JSONObject?): ChannelToneCurves {
            if (json == null) return Neutral
            fun read(key: String): ToneCurve =
                json.optJSONObject(key)?.let { ToneCurve.fromJson(it) } ?: ToneCurve.linear()
            return ChannelToneCurves(read("red"), read("green"), read("blue")).normalized()
        }
    }
}

/**
 * Builds three 256-entry LUTs that layer the per-channel curves on top of the
 * master LUT. The master LUT already contains the Nikon file curve, the
 * preset's tone mapping and the user's RGB master curve, so a colour channel
 * curve maps that *output* value.
 *
 * Linear channels reuse [master] directly, keeping the no-channel path byte
 * for byte identical to the original renderer. This is a pure function so it
 * can be unit-tested on the JVM without an Android runtime.
 */
fun buildChannelLuts(master: IntArray, curves: ChannelToneCurves): Array<IntArray> {
    val sources = arrayOf(curves.red, curves.green, curves.blue)
    return Array(3) { channel ->
        val curve = sources[channel]
        if (curve.isLinear()) {
            master
        } else {
            IntArray(256) { i ->
                val value = curve.apply(master[i].coerceIn(0, 255) / 255f)
                (value.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
            }
        }
    }
}
