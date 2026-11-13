package com.fujikon.app.render

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.6.57 阶段一 + 阶段二回归：用**真实照片跑真实 TFLite 模型导出的输出
 * 快照**（BlazeFace 高置信 anchor、EfficientNet-Lite0 top-12 概率）在纯 JVM
 * 里重放判定逻辑，锁死「专用模型 + 场景门控」对误判的修复。
 *
 * 快照来源：用户反馈的美食照、Unsplash 正脸人像照、背影群像照、4K 风景
 * 壁纸，均用随 APK 打包的两个 .tflite 实跑得到。这里不加载 native，仅重放
 * 输出，因此测试在任何桌面 JVM 上确定性通过。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnDeviceUnderstandingTest {

    // --------------------------- 真实照片输出快照 ---------------------------

    private data class Shot(
        val name: String,
        val sceneTop: List<Pair<Int, Float>>,
        val faceHiIdx: List<Int>,
        val faceMax: Float
    )

    private val food = Shot(
        "美食（用户反馈照）",
        listOf(962 to 0.2422f, 926 to 0.0508f, 766 to 0.0430f, 923 to 0.0391f,
            933 to 0.0391f, 567 to 0.0312f, 118 to 0.0312f, 930 to 0.0273f,
            415 to 0.0234f, 964 to 0.0195f, 960 to 0.0156f, 891 to 0.0117f),
        emptyList(), 0.3909f
    )
    private val portrait = Shot(
        "正脸人像",
        listOf(697 to 0.1055f, 614 to 0.0664f, 785 to 0.0547f, 869 to 0.0508f,
            837 to 0.0195f, 836 to 0.0195f, 841 to 0.0195f, 823 to 0.0156f,
            542 to 0.0156f, 566 to 0.0156f, 824 to 0.0156f, 845 to 0.0117f),
        listOf(175, 207, 209, 239, 241, 674, 680), 0.936f
    )
    private val landscape = Shot(
        "4K 风景壁纸",
        listOf(978 to 0.1094f, 975 to 0.0898f, 693 to 0.0742f, 977 to 0.0586f,
            979 to 0.0508f, 472 to 0.0508f, 972 to 0.0391f, 976 to 0.0234f,
            970 to 0.0195f, 801 to 0.0195f, 150 to 0.0156f, 814 to 0.0117f),
        emptyList(), 0.4338f
    )
    private val group = Shot(
        "背影群像（有人体但无正脸）",
        listOf(456 to 0.1484f, 780 to 0.0391f, 758 to 0.0391f, 701 to 0.0234f,
            608 to 0.0195f, 832 to 0.0195f, 628 to 0.0156f, 733 to 0.0156f,
            684 to 0.0117f, 557 to 0.0117f, 862 to 0.0117f, 887 to 0.0078f),
        emptyList(), 0.3547f
    )

    private fun sceneScores(shot: Shot): FloatArray {
        val a = FloatArray(1000)
        for ((i, p) in shot.sceneTop) a[i] = p
        return a
    }

    /** 把「高置信 anchor 索引」还原成一组 logits：命中项给高分，其余给负分。 */
    private fun faceLogits(shot: Shot): FloatArray {
        val a = FloatArray(BlazeFaceVerdict.ANCHOR_COUNT) { -6f }
        for (i in shot.faceHiIdx) a[i] = 3.2f // sigmoid ≈ 0.961
        if (shot.faceHiIdx.isEmpty()) {
            // 无高置信 anchor 时，用 faceMax 反推一个全局最高 logit
            val p = shot.faceMax.coerceIn(0.001f, 0.999f)
            a[0] = kotlin.math.ln(p / (1f - p))
        }
        return a
    }

    // ------------------------------ 阶段一 ---------------------------------

    @Test
    fun blazeFaceDetectsFrontalPortraitOnly() {
        val face = BlazeFaceVerdict.fromLogits(faceLogits(portrait))
        assertTrue("正脸人像必须检出人脸", face.detected)
        assertEquals(7, face.anchors)
        assertTrue(face.source.contains("BlazeFace"))
    }

    @Test
    fun blazeFaceRejectsFoodAndSceneryAndBackViewGroup() {
        for (shot in listOf(food, landscape, group)) {
            val face = BlazeFaceVerdict.fromLogits(faceLogits(shot))
            assertFalse("${shot.name} 不应检出人脸", face.detected)
        }
    }

    @Test
    fun blazeFaceRejectsScatteredNoiseAnchors() {
        // 4 个彼此远离的高置信 anchor（模拟散落噪声），不能构成连贯人脸。
        val a = FloatArray(BlazeFaceVerdict.ANCHOR_COUNT) { -6f }
        for (i in listOf(0, 300, 600, 890)) a[i] = 3.2f
        assertFalse("散落高置信 anchor 不能判定为人脸", BlazeFaceVerdict.fromLogits(a).detected)
    }

    // ------------------------------ 阶段二 ---------------------------------

    @Test
    fun sceneClassifierIdentifiesFoodLandscapePortrait() {
        assertEquals(PhotoScene.FOOD, SceneClassMapper.classify(sceneScores(food)).scene)
        assertEquals(PhotoScene.LANDSCAPE, SceneClassMapper.classify(sceneScores(landscape)).scene)
        assertEquals(PhotoScene.PORTRAIT, SceneClassMapper.classify(sceneScores(portrait)).scene)
        assertEquals(PhotoScene.OTHER, SceneClassMapper.classify(sceneScores(group)).scene)
    }

    @Test
    fun foodLandscapeDocumentNightAlwaysBlockBeauty() {
        for (s in listOf(PhotoScene.FOOD, PhotoScene.LANDSCAPE, PhotoScene.DOCUMENT, PhotoScene.NIGHT)) {
            assertTrue("$s 必须关闭人像美颜", s.blocksBeauty)
        }
        for (s in listOf(PhotoScene.PORTRAIT, PhotoScene.OTHER)) {
            assertFalse("$s 不应阻挡人像美颜", s.blocksBeauty)
        }
    }

    // --------------------------- 三路证据合成门控 ---------------------------

    private fun understanding(
        scene: PhotoScene,
        faceDetected: Boolean,
        personDetected: Boolean
    ): PhotoUnderstanding = PhotoUnderstanding(
        person = PersonEvidence(personDetected, 0.35f, "测试"),
        scene = SceneEvidence(scene, 0.5f, "测试"),
        face = FaceEvidence(faceDetected, 7, 0.93f, "测试")
    )

    @Test
    fun beautyIsBlockedForFoodEvenIfPersonDetectorSaysPresent() {
        // 双保险核心：即便人体检测（分割噪声）误判为有人体，美食场景
        // 也必须把人像美颜挡住——这正是 1.6.55/1.6.56 反复漏掉的场景。
        val u = understanding(PhotoScene.FOOD, faceDetected = false, personDetected = true)
        assertFalse("美食照无论人体检测结论如何都不得美颜", u.beautyAllowed)
    }

    @Test
    fun beautyIsAllowedWhenPortraitSceneAndHumanDetected() {
        assertTrue(understanding(PhotoScene.PORTRAIT, true, true).beautyAllowed)
        // 背影群像：场景通用、无人脸，但 person 概率图确证人体 → 允许美颜
        assertTrue(understanding(PhotoScene.OTHER, false, true).beautyAllowed)
    }

    @Test
    fun beautyIsBlockedWhenNoHumanEvenInNeutralScene() {
        assertFalse(understanding(PhotoScene.OTHER, false, false).beautyAllowed)
        assertFalse(PhotoUnderstanding.UNKNOWN.beautyAllowed)
    }

    @Test
    fun engineCombinesRealFaceEvidenceWithPersonFallback() {
        val dummy = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val oldFace = PhotoUnderstandingEngine.faceDetector
        val oldScene = PhotoUnderstandingEngine.sceneDetector
        try {
            // 用真实快照驱动 fake：正脸人像 → 检出；美食/风景 → 未检出
            val faceForPortrait = BlazeFaceVerdict.fromLogits(faceLogits(portrait))
            PhotoUnderstandingEngine.faceDetector = FaceDetector { faceForPortrait }
            PhotoUnderstandingEngine.sceneDetector = SceneDetector { SceneClassMapper.classify(sceneScores(portrait)) }
            val u = understandPhoto(dummy)
            assertTrue("正脸人像 + 人像场景应确证人体", u.beautyAllowed)
            assertNotNull(u.person)
            assertTrue(u.person!!.detected)
        } finally {
            PhotoUnderstandingEngine.faceDetector = oldFace
            PhotoUnderstandingEngine.sceneDetector = oldScene
        }
    }
}
