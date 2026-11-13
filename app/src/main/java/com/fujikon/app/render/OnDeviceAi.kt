package com.fujikon.app.render

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 1.6.57：两个新增的**纯标准算子** TFLite 模型（不依赖 MediaPipe 自定义
 * 算子，因此项目现有的裸 `tensorflow-lite:2.16.1` Interpreter 可直接运行）。
 *
 *  - `face_blazeface.tflite` — BlazeFace short-range，230KB，float32
 *    [1,128,128,3] → 896 个 anchor logits。专为人脸训练，美食 / 风景照
 *    实测高置信 anchor 恒为 0。
 *  - `scene_efficientnet_lite0.tflite` — EfficientNet-Lite0 int8，5.4MB，
 *    uint8 [1,224,224,3] → ImageNet 1000 类 softmax，映射到拍摄场景。
 *
 * 全部 lazy + 缓存 + 严格守卫：未 [attach]（纯 JVM 单测 / 模型缺失 /
 * 不支持的 ABI）时一律返回 null，绝不加载 native、绝不崩溃、绝不猜测。
 */
object OnDeviceModels {

    private const val FACE_SIDE = 128
    private const val SCENE_SIDE = 224
    private const val SCENE_CLASSES = 1000

    @Volatile private var appContext: Context? = null
    @Volatile private var face: Interpreter? = null
    @Volatile private var scene: Interpreter? = null
    @Volatile private var loadFailed = false
    @Volatile private var errorText: String? = null
    private val attached = AtomicBoolean(false)

    val faceAvailable: Boolean get() = face != null
    val sceneAvailable: Boolean get() = scene != null
    val lastError: String? get() = errorText

    fun attach(context: Context) {
        appContext = context.applicationContext
        attached.set(true)
    }

    /** 首次推理前的后台预热，避免一键优化首次点击卡顿。 */
    fun warmUp() {
        runCatching { if (attached.get()) ensureLoaded() }
    }

    private fun load(name: String): Interpreter? = runCatching {
        val ctx = appContext ?: return null
        val bytes = ctx.assets.open(name).use { it.readBytes() }
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buffer.put(bytes)
        buffer.rewind()
        Interpreter(buffer, Interpreter.Options().setNumThreads(2))
    }.getOrElse { t ->
        errorText = t.javaClass.simpleName
        null
    }

    private fun ensureLoaded() {
        if (loadFailed) return
        if (face != null && scene != null) return
        synchronized(this) {
            if (loadFailed) return
            if (face == null) face = load("face_blazeface.tflite")
            if (scene == null) scene = load("scene_efficientnet_lite0.tflite")
            if (face == null || scene == null) {
                face?.close(); scene?.close()
                face = null; scene = null
                loadFailed = true
            }
        }
    }

    /** 把 Bitmap 采样成 side×side 的 RGB 字节（0..255），保持比例用最近邻。 */
    private fun sampleRgb(bmp: Bitmap, side: Int): ByteArray? {
        val w = bmp.width
        val h = bmp.height
        if (bmp.isRecycled || w < 2 || h < 2) return null
        val out = ByteArray(side * side * 3)
        val row = IntArray(w)
        for (y in 0 until side) {
            val sy = (y * h / side).coerceIn(0, h - 1)
            bmp.getPixels(row, 0, w, 0, sy, w, 1)
            for (x in 0 until side) {
                val c = row[(x * w / side).coerceIn(0, w - 1)]
                val o = (y * side + x) * 3
                out[o] = ((c shr 16) and 0xFF).toByte()
                out[o + 1] = ((c shr 8) and 0xFF).toByte()
                out[o + 2] = (c and 0xFF).toByte()
            }
        }
        return out
    }

    private data class Entry(val key: String, val faceLogits: FloatArray?, val sceneScores: FloatArray?)
    private val cache = AtomicReference<Entry?>(null)

    /** 24 点归一化指纹，分辨率无关：预览与导出命中同一份推理结果。 */
    private fun fingerprint(rgb: ByteArray, side: Int): String {
        val sb = StringBuilder(72)
        for (k in 0 until 24) {
            val u = ((k * 7 + 3) % 24) / 24f
            val v = ((k * 13 + 5) % 24) / 24f
            val o = ((v * (side - 1)).toInt() * side + (u * (side - 1)).toInt()) * 3
            sb.append(rgb[o].toInt() and 0xF0)
                .append(rgb[o + 1].toInt() and 0xF0)
                .append(rgb[o + 2].toInt() and 0xF0)
        }
        return sb.toString()
    }

    /** 一次跑完两个模型，复用同一份缓存；任一模型缺失时对应字段为 null。 */
    internal fun infer(bmp: Bitmap?): Pair<FloatArray?, FloatArray?> {
        val src = bmp ?: return null to null
        if (!attached.get()) return null to null
        ensureLoaded()
        val f = face
        val s = scene
        if (f == null && s == null) return null to null
        val faceRgb = sampleRgb(src, FACE_SIDE) ?: return null to null
        val sceneRgb = sampleRgb(src, SCENE_SIDE) ?: return null to null
        val key = fingerprint(faceRgb, FACE_SIDE) + ":" + fingerprint(sceneRgb, SCENE_SIDE)
        cache.get()?.let { if (it.key == key) return it.faceLogits to it.sceneScores }
        return synchronized(this) {
            cache.get()?.let { if (it.key == key) return it.faceLogits to it.sceneScores }
            val fl = f?.let { runFace(it, faceRgb) }
            val ss = s?.let { runScene(it, sceneRgb) }
            cache.set(Entry(key, fl, ss))
            fl to ss
        }
    }

    private fun runFace(model: Interpreter, rgb: ByteArray): FloatArray? = runCatching {
        val input = ByteBuffer.allocateDirect(FACE_SIDE * FACE_SIDE * 3 * 4)
            .order(ByteOrder.nativeOrder())
        val floats = input.asFloatBuffer()
        for (i in 0 until FACE_SIDE * FACE_SIDE * 3) {
            // 与训练一致：RGB 0..255 → -1..1
            floats.put(((rgb[i].toInt() and 0xFF) / 127.5f) - 1f)
        }
        input.rewind()
        // BlazeFace 有 regressors [1,896,16] 与 classificators [1,896,1]
        // 两个输出，必须用 runForMultipleInputsOutputs。按 shape 最后一维
        // 定位：=1 的是 classificators，=16 的是 regressors（不依赖张量名，
        // 也不依赖输出顺序）。
        val outputs = HashMap<Int, Any>()
        var clsIndex = -1
        for (i in 0 until model.outputTensorCount) {
            val shape = model.getOutputTensor(i).shape()
            val last = shape.lastOrNull() ?: 0
            if (last == 1) {
                outputs[i] = Array(1) { Array(BlazeFaceVerdict.ANCHOR_COUNT) { FloatArray(1) } }
                clsIndex = i
            } else {
                outputs[i] = Array(1) { Array(BlazeFaceVerdict.ANCHOR_COUNT) { FloatArray(last) } }
            }
        }
        if (clsIndex < 0) return@runCatching null
        model.runForMultipleInputsOutputs(arrayOf(input), outputs)
        @Suppress("UNCHECKED_CAST")
        val cls = outputs[clsIndex] as Array<Array<FloatArray>>
        FloatArray(BlazeFaceVerdict.ANCHOR_COUNT) { cls[0][it][0] }
    }.getOrElse { t -> errorText = t.javaClass.simpleName; null }

    private fun runScene(model: Interpreter, rgb: ByteArray): FloatArray? = runCatching {
        val input = ByteBuffer.allocateDirect(SCENE_SIDE * SCENE_SIDE * 3)
            .order(ByteOrder.nativeOrder())
        input.put(rgb)
        input.rewind()
        val out = ByteBuffer.allocateDirect(SCENE_CLASSES).order(ByteOrder.nativeOrder())
        model.run(input, out)
        out.rewind()
        val b = ByteArray(SCENE_CLASSES)
        out.get(b)
        // 输出量化 scale = 1/256，zero_point = 0
        FloatArray(SCENE_CLASSES) { (b[it].toInt() and 0xFF) / 256f }
    }.getOrElse { t -> errorText = t.javaClass.simpleName; null }
}

/** 生产环境的人脸检测器：未加载模型时返回 null（未知），绝不猜「有人脸」。 */
object TfliteFaceDetector : FaceDetector {
    override fun detect(source: Bitmap?): FaceEvidence? {
        val logits = OnDeviceModels.infer(source).first ?: return null
        return BlazeFaceVerdict.fromLogits(logits)
    }
}

/** 生产环境的场景检测器：未加载模型时返回 null（未知），不改变既有行为。 */
object TfliteSceneDetector : SceneDetector {
    override fun detect(source: Bitmap?): SceneEvidence? {
        val scores = OnDeviceModels.infer(source).second ?: return null
        return SceneClassMapper.classify(scores)
    }
}
