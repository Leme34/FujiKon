package com.fujikon.app.render

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.6.55 regression for「当且仅当识别到人体才对识别到的人像进行美颜」。
 *
 * Locks in three things:
 *  - a generic "subject" (car / pet / furniture / warm scenery) is NEVER a
 *    person — only the dedicated person probability map counts;
 *  - the TFLite person verdict wins over the fallback heuristic, and an
 *    unavailable model degrades to a STRICT heuristic rather than guessing;
 *  - the heuristic rejects a beach / autumn-leaf / flat colour rectangle but
 *    accepts a central skin blob that actually has facial features.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PersonDetectionTest {

    @After
    fun tearDown() {
        SelectionMaskGenerator.provider = null
    }

    private fun bitmap(w: Int = 120, h: Int = 96, pixel: (Float, Float) -> Int): Bitmap {
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                px[y * w + x] = pixel(x / w.toFloat(), y / h.toFloat())
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun rgb(r: Float, g: Float, b: Float): Int = Color.rgb(
        (r * 255).toInt().coerceIn(0, 255),
        (g * 255).toInt().coerceIn(0, 255),
        (b * 255).toInt().coerceIn(0, 255)
    )

    /** Full-frame warm colour (beach / autumn leaves): NO human body. */
    private fun warmScenery() = bitmap { nx, ny ->
        val grain = ((nx * 700 + ny * 1300) % 9) / 255f
        rgb(0.72f + grain * 0.10f, 0.52f + grain * 0.07f, 0.42f + grain * 0.04f)
    }

    /** Flat skin rectangle with grey background: colour only, no facial features. */
    private fun flatSkinBlock() = bitmap { nx, ny ->
        if (nx in 0.22f..0.78f && ny in 0.16f..0.84f) rgb(0.72f, 0.52f, 0.42f)
        else rgb(0.46f, 0.46f, 0.46f)
    }

    /** Central skin blob WITH dark eyes / mouth / hairline — a recognisable face. */
    private fun faceWithFeatures() = bitmap { nx, ny ->
        val dx = (nx - 0.5f) / 0.27f
        val dy = (ny - 0.5f) / 0.34f
        val inFace = dx * dx + dy * dy <= 1f
        val leftEye = nx in 0.36f..0.44f && ny in 0.37f..0.42f
        val rightEye = nx in 0.56f..0.64f && ny in 0.37f..0.42f
        val mouth = nx in 0.43f..0.57f && ny in 0.62f..0.665f
        val hair = ny < 0.185f && nx in 0.28f..0.72f
        when {
            leftEye || rightEye || mouth || hair -> rgb(0.12f, 0.11f, 0.10f)
            inFace -> rgb(0.72f, 0.52f, 0.42f)
            else -> rgb(0.46f, 0.46f, 0.46f)
        }
    }

    private fun providerReturning(person: FloatArray?, side: Int = 16): SelectionAiProvider =
        SelectionAiProvider { _, _, _, requestedSide ->
            val s = requestedSide
            val n = s * s
            val p = person ?: FloatArray(n)
            MlMaps(
                s,
                FloatArray(n) { 1f },          // generic subject: deliberately high
                FloatArray(n),
                if (person == null) FloatArray(0) else p
            )
        }

    // ---------------------------- MlMaps person map ----------------------------

    @Test
    fun genericSubjectIsNeverMistakenForAPerson() {
        val side = 8
        val n = side * side
        val generic = MlMaps(side, FloatArray(n) { 1f }, FloatArray(n), FloatArray(n))
        assertFalse(
            "通用主体（车/宠物/家具）概率再高也不能当作人体",
            generic.hasPerson()
        )
        assertEquals(0f, generic.personCoverage(), 0.0001f)
    }

    @Test
    fun personMapCoverageAndPeakDriveHasPerson() {
        // 贴近生产网格（MlPersonDetector 用 128）：小 grid 里单个 cell 的
        // 覆盖率就会虚高，无法区分「远景小人」与「孤立噪声」。
        val side = 32
        val n = side * side
        val strong = MlMaps(side, FloatArray(n), FloatArray(n), FloatArray(n) { 0.8f })
        assertTrue("成片 person 概率应判定为有人体", strong.hasPerson())
        assertEquals(0.8f, strong.personCoverage(), 0.0001f)

        // A small but COHERENT high-confidence distant person still counts;
        // a single isolated noisy cell must not.
        val small = MlMaps(side, FloatArray(n), FloatArray(n), FloatArray(n))
        for (i in 0 until 4) small.person[i] = 0.95f
        assertTrue("远处小但成片高置信的人体也应识别", small.hasPerson())
        val noise = MlMaps(side, FloatArray(n), FloatArray(n), FloatArray(n))
        noise.person[0] = 0.99f
        assertFalse("单个孤立高置信 cell 不能判定有人体", noise.hasPerson())

        val unknown = MlMaps(side, FloatArray(n), FloatArray(n))
        assertFalse("无 person 字段（旧模型/fake）必须视为未知，不可当作有人体", unknown.hasPerson())
        assertEquals(0f, unknown.personCoverage(), 0.0001f)
    }

    /**
     * 1.6.56: 语义分割会把「人」概率零星撒在食物 / 暖色物体上，形成多个互不
     * 相连的小噪点团。只要高概率 cell 够多（旧规则 ≥3）就会被误判为人像。
     * 现在要求这些 cell 必须构成一个**单一占主导的连通块**，散落噪声被拒绝。
     */
    @Test
    fun mlPersonMapRejectsScatteredFalsePositives() {
        val side = 32
        val n = side * side
        val scattered = MlMaps(side, FloatArray(n), FloatArray(n), FloatArray(n))
        // 3 个彼此远离、互不相通的高概率小团（模拟美食照上的散落噪点）。
        val clusters = listOf(
            listOf(2 to 2, 3 to 2, 2 to 3, 3 to 3),
            listOf(20 to 5, 21 to 5, 20 to 6, 21 to 6),
            listOf(10 to 25, 11 to 25, 10 to 26, 11 to 26)
        )
        for (c in clusters) for ((x, y) in c) scattered.person[y * side + x] = 0.9f
        assertFalse(
            "多个分散的高概率团（美食/暖色噪声）不得判定为有人体",
            scattered.hasPerson()
        )

        // 对照：一个连贯的高概率小块（远处小但真实的人体）仍应识别。
        val coherent = MlMaps(side, FloatArray(n), FloatArray(n), FloatArray(n))
        for ((x, y) in listOf(15 to 15, 16 to 15, 15 to 16, 16 to 16, 15 to 17, 16 to 17)) {
            coherent.person[y * side + x] = 0.9f
        }
        assertTrue("单一连贯的高概率块应判定为有人体", coherent.hasPerson())
    }

    // --------------------------- model-based detector --------------------------

    @Test
    fun mlDetectorUsesThePersonMapNotTheSubjectMap() {
        val side = 16
        val n = side * side
        SelectionMaskGenerator.provider = providerReturning(FloatArray(n) { 0.9f })
        val present = MlPersonDetector.detect(faceWithFeatures())
        assertNotNull(present)
        assertTrue("模型检出 person 应判定有人体", present!!.detected)
        assertTrue("来源须如实标注为 AI 语义分割", present.source.contains("AI"))

        SelectionMaskGenerator.provider = providerReturning(FloatArray(n))
        val absent = MlPersonDetector.detect(faceWithFeatures())
        assertNotNull(absent)
        assertFalse("模型判定无 person 时不得启用美颜", absent!!.detected)
    }

    @Test
    fun mlDetectorReportsUnknownWhenNoPersonSemanticsOrNoProvider() {
        SelectionMaskGenerator.provider = providerReturning(null) // empty person field
        assertNull(
            "不提供 person 语义的 provider 应返回 null（未知），由严格启发式兜底",
            MlPersonDetector.detect(faceWithFeatures())
        )
        SelectionMaskGenerator.provider = null
        assertNull("无模型时 MlPersonDetector 应返回 null", MlPersonDetector.detect(faceWithFeatures()))
        assertNull("空图源不应触发检测", MlPersonDetector.detect(null))
    }

    // ------------------------------ heuristic ---------------------------------

    @Test
    fun heuristicRejectsWarmSceneryAndFlatColourBlocks() {
        val scenery = HeuristicPersonDetector.detect(warmScenery())
        assertNotNull(scenery)
        assertFalse("满屏暖色（沙滩/秋叶）不是人体", scenery!!.detected)

        val flat = HeuristicPersonDetector.detect(flatSkinBlock())
        assertNotNull(flat)
        assertFalse("纯肤色矩形没有五官，不能算识别到人体", flat!!.detected)
        assertNull("无图源时不应给出人体结论", HeuristicPersonDetector.detect(null))
    }

    /**
     * 1.6.56: 美食照里肉块等暖色碎块各自成团、互不相连。仅看占比/中心度会
     * 误判，必须要求最大连通肤色块占绝大多数，否则判为无人体。
     */
    @Test
    fun heuristicRejectsFragmentedWarmPatches() {
        val food = bitmap { nx, ny ->
            // 几块互不相连、分散在画面各处的暖色「肉块」，背景中性灰。
            val inPatch = (nx in 0.15f..0.32f && ny in 0.20f..0.38f) ||
                (nx in 0.62f..0.80f && ny in 0.30f..0.48f) ||
                (nx in 0.35f..0.52f && ny in 0.66f..0.84f)
            if (inPatch) rgb(0.74f, 0.50f, 0.40f) else rgb(0.46f, 0.46f, 0.46f)
        }
        val verdict = HeuristicPersonDetector.detect(food)
        assertNotNull(verdict)
        assertFalse("分散的暖色碎块（美食/秋叶）不是人体", verdict!!.detected)
    }

    @Test
    fun heuristicAcceptsAFaceWithFacialFeatures() {
        val face = HeuristicPersonDetector.detect(faceWithFeatures())
        assertNotNull(face)
        assertTrue(
            "中心肤色块 + 眼/嘴/发际暗特征应识别为人体（覆盖率=${face!!.coverage}）",
            face.detected
        )
        assertTrue("覆盖率应在合理区间", face.coverage in 0.04f..0.55f)
        assertTrue("启发式来源须如实标注非 AI", face.source.contains("非 AI"))
    }

    // --------------------------- combined entry point --------------------------

    @Test
    fun modelVerdictWinsOverHeuristic() {
        // The heuristic WOULD accept this face, but an explicit model "no
        // person" verdict must win — the model is the more precise engine.
        val side = 16
        val n = side * side
        SelectionMaskGenerator.provider = providerReturning(FloatArray(n))
        val verdict = detectPerson(faceWithFeatures())
        assertNotNull(verdict)
        assertFalse("模型明确判定无人体时不得被启发式翻案", verdict!!.detected)
        assertTrue("应采用模型结论", verdict.source.contains("AI"))
    }

    @Test
    fun fallsBackToStrictHeuristicWhenModelIsUnavailable() {
        SelectionMaskGenerator.provider = null
        val face = detectPerson(faceWithFeatures())
        assertNotNull(face)
        assertTrue("模型不可用时仍应能识别带五官的人像", face!!.detected)

        val scenery = detectPerson(warmScenery())
        assertNotNull(scenery)
        assertFalse("模型不可用时暖色风景也不能误判为人体", scenery!!.detected)
    }
}
