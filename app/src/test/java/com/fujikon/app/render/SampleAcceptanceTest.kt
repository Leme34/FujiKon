package com.fujikon.app.render

import android.app.Application
import android.graphics.Bitmap
import com.fujikon.app.data.EditAdjustments
import com.fujikon.app.data.Preset
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer

/**
 * 样张验收流水线：把任意真实照片跑成 before/after，供人工看图验收。
 *
 * 1) 先用 /tmp/decode_samples.py 把照片解码成 raw/<stem>.bin（宽/高 + RGBA）。
 * 2) 本测试读取每个 .bin，重建 Bitmap，经真实 ImageRenderer 渲染前后两张图，
 *    保存到 /tmp/fujikon-samples/<stem>_before.png / _after.png，并打印指标。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SampleAcceptanceTest {

    private val rawDir = File("/tmp/fujikon-samples/raw")
    private val outDir = File("/tmp/fujikon-samples").also { it.mkdirs() }

    @Test
    fun renderSamplesBeforeAfter() = runBlocking {
        if (!rawDir.exists()) return@runBlocking
        val bins = rawDir.listFiles { _, n -> n.endsWith(".bin") } ?: emptyArray()
        if (bins.isEmpty()) {
            println("SAMPLE_ACCEPTANCE_SKIP: no samples in $rawDir")
            return@runBlocking
        }
        val renderer = ImageRenderer()
        val neutral = Preset.none()
        for (bin in bins.sortedBy { it.name }) {
            val (w, h, pixels) = readRaw(bin)
            val source = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
            val stats = AutoEnhance.analyse(source)!!
            val result = AutoEnhance.suggest(stats)
            val before = renderer.render(source, neutral, forExport = false)
            val after = renderer.render(source, neutral.copy(adjustments = result.adjustments), forExport = false)
            save(before, File(outDir, bin.nameWithoutExtension + "_before.png"))
            save(after, File(outDir, bin.nameWithoutExtension + "_after.png"))
            val afterStats = AutoEnhance.analyse(after)!!
            println("SAMPLE ${bin.nameWithoutExtension} changed=${result.changed} summary=${result.summary}")
            println(
                "  before mean=${"%.3f".format(stats.meanLuma)} warmth=${"%.3f".format(stats.warmth)} " +
                    "sat=${"%.3f".format(stats.meanSaturation)} quality=${"%.3f".format(stats.toneQuality)}"
            )
            println(
                "  after  mean=${"%.3f".format(afterStats.meanLuma)} warmth=${"%.3f".format(afterStats.warmth)} " +
                    "sat=${"%.3f".format(afterStats.meanSaturation)} quality=${"%.3f".format(afterStats.toneQuality)}"
            )
            println("  adjustments=${result.adjustments.toJson()}")
        }
    }

    private fun readRaw(f: File): Triple<Int, Int, IntArray> {
        val bytes = f.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val w = bb.int
        val h = bb.int
        val n = w * h
        val pixels = IntArray(n)
        for (i in 0 until n) {
            val r = bb.get().toInt() and 0xFF
            val g = bb.get().toInt() and 0xFF
            val b = bb.get().toInt() and 0xFF
            val a = bb.get().toInt() and 0xFF
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Triple(w, h, pixels)
    }

    private fun save(bm: Bitmap, f: File) {
        f.outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
