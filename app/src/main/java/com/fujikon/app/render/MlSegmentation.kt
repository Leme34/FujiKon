package com.fujikon.app.render

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.exp
import kotlin.math.max

/**
 * Soft semantic probabilities produced by the on-device TFLite models, on a
 * square normalised grid. [subject] is "this pixel belongs to an object"
 * (PASCAL-VOC foreground), [sky] is "this pixel is sky" (ADE20K sky class).
 *
 * A square grid is used on purpose: both networks take a square input, so
 * (u, v) in [0,1] maps straight onto the photo regardless of its aspect
 * ratio. The consumer bilinearly resamples it onto its own analysis grid.
 */
class MlMaps(val side: Int, val subject: FloatArray, val sky: FloatArray) {

    /** Bilinear sample of one field at normalised (u, v). */
    fun sample(field: FloatArray, u: Float, v: Float): Float {
        val fx = (u.coerceIn(0f, 1f) * (side - 1))
        val fy = (v.coerceIn(0f, 1f) * (side - 1))
        val x0 = fx.toInt().coerceIn(0, side - 1)
        val y0 = fy.toInt().coerceIn(0, side - 1)
        val x1 = (x0 + 1).coerceAtMost(side - 1)
        val y1 = (y0 + 1).coerceAtMost(side - 1)
        val tx = fx - x0
        val ty = fy - y0
        val a = field[y0 * side + x0] + (field[y0 * side + x1] - field[y0 * side + x0]) * tx
        val b = field[y1 * side + x0] + (field[y1 * side + x1] - field[y1 * side + x0]) * tx
        return (a + (b - a) * ty).coerceIn(0f, 1f)
    }

    /** True when there is usable signal (so callers can show an honest status). */
    fun hasSubject(): Boolean = subject.any { it > 0.35f }
    fun hasSky(): Boolean = sky.any { it > 0.35f }
}

/**
 * Pluggable segmentation source. Keeping this an interface is what lets the
 * pure-JVM unit tests inject a deterministic fake: the real implementation
 * loads native TFLite libraries that do not exist on a desktop JVM.
 */
fun interface SelectionAiProvider {
    /** Returns null when no model is available (the caller falls back to CV). */
    fun segment(pixels: IntArray, sw: Int, sh: Int, side: Int): MlMaps?
}

/**
 * On-device AI semantic segmentation, mirroring the "Select Subject" /
 * "Select Sky" engines in Lightroom and Meitu.
 *
 * Two stock-op TFLite graphs ship in assets (no MediaPipe custom ops, no
 * network, no runtime download):
 *
 *  - `seg_subject_voc.tflite` — DeepLabV3 / MobileNetV2 trained on PASCAL VOC.
 *    Input float32 [1,257,257,3] in -1..1, output 21 per-pixel logits. Class 0
 *    is background and ALL other 20 classes are objects, so
 *    `1 - softmax(background)` is a clean "subject" probability. This is what
 *    makes arbitrary subjects (people, pets, cars, bicycles, furniture …)
 *    selectable instead of just a guessed centre blob.
 *  - `seg_sky_ade20k.tflite` — quantised DeepLabV3 / MobileNetV2 trained on
 *    the ADE20K 31-class scene subset. Input uint8 [1,512,512,3] (the model's
 *    own quantisation maps raw RGB bytes into -1..1), output int32 argmax
 *    [1,512,512]. Class 3 = sky, class 13 = person. Recognising sky as a
 *    SEMANTIC class — instead of "blue pixels near the top" — is what finally
 *    handles sunsets, overcast skies, snow scenes and backlit clouds.
 *
 * Everything is lazy, cached by a cheap photo fingerprint and fully guarded:
 * a missing model, an unsupported ABI or a native failure degrades to the
 * classic computer-vision pipeline rather than crashing or showing a blank
 * selection. Interpreter instances are never thread-safe, so inference is
 * serialised; the result is a plain FloatArray and is safe to share.
 */
object MlSegmentation : SelectionAiProvider {

    private const val ADE_SIDE = 512
    private const val VOC_SIDE = 257
    private const val VOC_CLASSES = 21
    private const val ADE_SKY = 3
    private const val ADE_PERSON = 13

    @Volatile private var appContext: Context? = null
    @Volatile private var adeModel: Interpreter? = null
    @Volatile private var vocModel: Interpreter? = null
    @Volatile private var loadFailed = false
    @Volatile private var errorText: String? = null
    private val attached = AtomicBoolean(false)

    /** True once both graphs are loaded. Used by the UI for an honest status. */
    val available: Boolean get() = adeModel != null && vocModel != null
    val lastError: String? get() = errorText

    private data class Entry(val key: String, val maps: MlMaps)
    private val cache = AtomicReference<Entry?>(null)

    /** Called once from the app container; models load on first use off-thread. */
    fun attach(context: Context) {
        appContext = context.applicationContext
        attached.set(true)
    }

    /** Eager warm-up on an IO dispatcher so the first selection isn't janky. */
    fun warmUp() {
        // Loading TFLite touches a native library that does not exist on a
        // desktop JVM (Robolectric / unit tests). Swallow *any* Throwable —
        // including UnsatisfiedLinkError — so a test run never dies here.
        runCatching { if (attached.get()) ensureLoaded() }
    }

    /** Drops cached inference so "重新识别" really re-runs the networks. */
    fun invalidate() {
        cache.set(null)
        loadFailed = false
        errorText = null
    }

    private fun load(name: String, threads: Int): Interpreter? = runCatching {
        val ctx = appContext ?: return null
        val bytes = ctx.assets.open(name).use { it.readBytes() }
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buffer.put(bytes)
        buffer.rewind()
        Interpreter(buffer, Interpreter.Options().setNumThreads(threads))
    }.getOrElse { t ->
        errorText = t.javaClass.simpleName
        null
    }

    private fun ensureLoaded() {
        if (available || loadFailed) return
        synchronized(this) {
            if (available || loadFailed) return
            val ade = load("seg_sky_ade20k.tflite", 4)
            val voc = load("seg_subject_voc.tflite", 4)
            if (ade == null || voc == null) {
                ade?.close(); voc?.close()
                loadFailed = true
                if (errorText == null) errorText = "model unavailable"
            } else {
                adeModel = ade
                vocModel = voc
                errorText = null
            }
        }
    }

    /**
     * Resolution-independent content fingerprint: 24 samples taken at FIXED
     * NORMALISED positions, quantised to 4 bits. The UI passes a downscaled
     * bitmap while the renderer passes the full-resolution pixel array; using
     * relative coordinates makes both hit the same cached inference, so the
     * preview and the export see byte-identical AI probabilities.
     */
    private fun fingerprint(pixels: IntArray, sw: Int, sh: Int): String {
        val sb = StringBuilder(48)
        for (k in 0 until 24) {
            // A scrambled but deterministic lattice over the frame.
            val u = ((k * 7 + 3) % 24) / 24f
            val v = ((k * 13 + 5) % 24) / 24f
            val x = (u * (sw - 1)).toInt().coerceIn(0, sw - 1)
            val y = (v * (sh - 1)).toInt().coerceIn(0, sh - 1)
            val c = pixels[(y * sw + x).coerceIn(0, pixels.size - 1)]
            sb.append(((c shr 16) and 0xFF) shr 4)
                .append(((c shr 8) and 0xFF) shr 4)
                .append((c and 0xFF) shr 4)
        }
        return sb.toString()
    }

    override fun segment(pixels: IntArray, sw: Int, sh: Int, side: Int): MlMaps? {
        // Hard isolation for pure-JVM tests: without an attached Android
        // Context this object NEVER touches the TFLite classes. Unit tests can
        // therefore install a fake provider and exercise the AI fusion path,
        // while the default (unattached) state stays 100% deterministic CV.
        if (!attached.get()) return null
        ensureLoaded()
        val ade = adeModel ?: return null
        val voc = vocModel ?: return null
        val key = "$side:" + fingerprint(pixels, sw, sh)
        cache.get()?.let { if (it.key == key) return it.maps }
        return synchronized(this) {
            cache.get()?.let { if (it.key == key) return it.maps }
            val built = runCatching { infer(pixels, sw, sh, side, ade, voc) }
                .getOrElse { t -> errorText = t.javaClass.simpleName; null }
            if (built != null) cache.set(Entry(key, built))
            built
        }
    }

    private fun infer(
        pixels: IntArray, sw: Int, sh: Int, side: Int,
        ade: Interpreter, voc: Interpreter
    ): MlMaps {
        val n = side * side
        val sky = FloatArray(n)
        val subject = FloatArray(n)

        // ---- ADE20K: semantic sky + a higher-resolution person prior --------
        val adeIn = ByteBuffer.allocateDirect(ADE_SIDE * ADE_SIDE * 3)
            .order(ByteOrder.nativeOrder())
        val adeOut = ByteBuffer.allocateDirect(ADE_SIDE * ADE_SIDE * 4)
            .order(ByteOrder.nativeOrder())
        for (gy in 0 until ADE_SIDE) {
            val sy = (gy * sh / ADE_SIDE).coerceIn(0, sh - 1)
            for (gx in 0 until ADE_SIDE) {
                val sx = (gx * sw / ADE_SIDE).coerceIn(0, sw - 1)
                val c = pixels[sy * sw + sx]
                adeIn.put((c shr 16).toByte())
                adeIn.put((c shr 8).toByte())
                adeIn.put(c.toByte())
            }
        }
        adeIn.rewind()
        ade.run(adeIn, adeOut)
        adeOut.rewind()
        val adeLabels = adeOut.asIntBuffer()
        val adeSky = FloatArray(n)
        val adePerson = FloatArray(n)
        val sx = ADE_SIDE.toFloat() / side
        for (y in 0 until side) {
            val ay = (y * sx).toInt().coerceIn(0, ADE_SIDE - 1)
            for (x in 0 until side) {
                val ax = (x * sx).toInt().coerceIn(0, ADE_SIDE - 1)
                val label = adeLabels.get(ay * ADE_SIDE + ax)
                val i = y * side + x
                if (label == ADE_SKY) adeSky[i] = 1f
                if (label == ADE_PERSON) adePerson[i] = 1f
            }
        }
        // Soften hard argmax edges before fusing (2 px box blur on the grid).
        boxBlur(adeSky, side, 1)
        boxBlur(adePerson, side, 1)

        // ---- VOC: object-vs-background probability --------------------------
        val vocIn = ByteBuffer.allocateDirect(VOC_SIDE * VOC_SIDE * 3 * 4)
            .order(ByteOrder.nativeOrder())
        val vocFloats = vocIn.asFloatBuffer()
        val vocOut = ByteBuffer.allocateDirect(VOC_SIDE * VOC_SIDE * VOC_CLASSES * 4)
            .order(ByteOrder.nativeOrder())
        for (gy in 0 until VOC_SIDE) {
            val sy = (gy * sh / VOC_SIDE).coerceIn(0, sh - 1)
            for (gx in 0 until VOC_SIDE) {
                val sx0 = (gx * sw / VOC_SIDE).coerceIn(0, sw - 1)
                val c = pixels[sy * sw + sx0]
                vocFloats.put(((c shr 16) and 0xFF) / 127.5f - 1f)
                vocFloats.put(((c shr 8) and 0xFF) / 127.5f - 1f)
                vocFloats.put((c and 0xFF) / 127.5f - 1f)
            }
        }
        vocIn.rewind()
        voc.run(vocIn, vocOut)
        vocOut.rewind()
        val logits = vocOut.asFloatBuffer()
        val vx = VOC_SIDE.toFloat() / side
        for (y in 0 until side) {
            val vy = (y * vx).toInt().coerceIn(0, VOC_SIDE - 1)
            for (x in 0 until side) {
                val px = (x * vx).toInt().coerceIn(0, VOC_SIDE - 1)
                val base = (vy * VOC_SIDE + px) * VOC_CLASSES
                var maxLogit = Float.NEGATIVE_INFINITY
                for (c in 0 until VOC_CLASSES) {
                    val v = logits.get(base + c)
                    if (v > maxLogit) maxLogit = v
                }
                var sum = 0f
                var obj = 0f
                for (c in 0 until VOC_CLASSES) {
                    val e = exp(logits.get(base + c) - maxLogit)
                    sum += e
                    if (c != 0) obj += e
                }
                val p = if (sum > 0f) obj / sum else 0f
                val i = y * side + x
                // The person prior from ADE20K (512 px) sharpens the VOC (257 px)
                // person boundary; VOC still covers every other object class.
                subject[i] = max(p, adePerson[i] * 0.88f)
                sky[i] = adeSky[i]
            }
        }
        boxBlur(subject, side, 1)
        boxBlur(sky, side, 1)
        return MlMaps(side, subject, sky)
    }

    private fun boxBlur(values: FloatArray, side: Int, radius: Int) {
        if (radius <= 0) return
        val temp = FloatArray(values.size)
        for (y in 0 until side) {
            for (x in 0 until side) {
                var s = 0f; var c = 0
                for (k in -radius..radius) {
                    s += values[y * side + (x + k).coerceIn(0, side - 1)]; c++
                }
                temp[y * side + x] = s / c
            }
        }
        for (x in 0 until side) {
            for (y in 0 until side) {
                var s = 0f; var c = 0
                for (k in -radius..radius) {
                    s += temp[(y + k).coerceIn(0, side - 1) * side + x]; c++
                }
                values[y * side + x] = s / c
            }
        }
    }
}
