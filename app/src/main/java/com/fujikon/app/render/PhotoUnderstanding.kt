package com.fujikon.app.render

import android.graphics.Bitmap

/**
 * 1.6.57：一次推理得到「照片里到底有什么」的完整结论。
 *
 * 三路证据互补，缺一不可：
 *  - [face]：专用人脸检测，正脸的决定性证据（美食/风景照恒为 0 命中）；
 *  - [scene]：场景分类，美食/风景/文档/夜景**强制关闭**人像美颜，
 *    这是继人体检测之后的第二道独立保险；
 *  - [person]：既有 VOC/ADE20K person 概率图，负责背影、侧脸、远景小人
 *    这类「有人体但不露正脸」的画面（背影群像实测覆盖率 0.40）。
 *
 * UI 只对每张照片调用 [understand] 一次，提示与一键优化按钮共用同一结论，
 * 不会出现「提示说没人体、按钮却加了美颜」的不一致。
 */
data class PhotoUnderstanding(
    val person: PersonEvidence?,
    val scene: SceneEvidence,
    val face: FaceEvidence?
) {
    /**
     * 是否允许一键优化写入人像美颜参数。
     *
     * 严格 AND：
     *  1. 场景不是「主体不可能是人」的类别（或场景未知，交给人体检测）；
     *  2. 确证画面里有人体——正脸人脸检测 OR person 概率图/启发式。
     *
     * 任一不成立就不美颜。刻意偏向假阴性：宁可漏掉极小的远景人，也绝不
     * 把美食/风景照误当人像强加磨皮美白。
     */
    val beautyAllowed: Boolean
        get() = !scene.scene.blocksBeauty && person?.detected == true

    /** 人像美颜强度依据：优先用人体覆盖率，人脸检测命中时给一个保底下限。 */
    val beautyCoverage: Float
        get() = maxOf(person?.coverage ?: 0f, if (face?.detected == true) 0.12f else 0f)

    /** 给 UI 的可解释标签（诚实标注真实使用的引擎）。 */
    val sourceLabel: String
        get() = buildString {
            append(scene.source)
            if (face?.detected == true) append(" · 人脸")
            else if (person?.detected == true) append(" · 人体")
        }

    companion object {
        /** 模型全部不可用时的保守结论：不阻挡、也不启用美颜。 */
        val UNKNOWN = PhotoUnderstanding(null, SceneEvidence.UNKNOWN, null)
    }
}

/** 可注入，纯 JVM 单测用 fake 组合，无需任何 native。 */
object PhotoUnderstandingEngine {
    @Volatile var faceDetector: FaceDetector = TfliteFaceDetector
    @Volatile var sceneDetector: SceneDetector = TfliteSceneDetector

    /**
     * 综合三路证据。所有模型不可用时退化为既有的严格启发式人体检测，
     * 保证老设备 / 纯 JVM 环境行为不劣化（但绝不会凭肤色占比乱猜）。
     */
    fun understand(source: Bitmap?): PhotoUnderstanding {
        if (source == null) return PhotoUnderstanding.UNKNOWN
        val scene = sceneDetector.detect(source) ?: SceneEvidence.UNKNOWN
        val face = faceDetector.detect(source)

        // 人脸检测可用且明确检出 → 决定性证据，直接确证人体。
        // 人脸检测可用但未检出 → 仍可能是背影/侧脸，交给 person 概率图，
        //   绝不因为「没检测到脸」就武断判定无人体（背影群像会漏）。
        val person = if (face?.detected == true) {
            PersonEvidence.present(maxOf(0.12f, face.confidence * 0.4f), "AI 人脸检测 · BlazeFace")
        } else {
            detectPerson(source)
        }
        return PhotoUnderstanding(person, scene, face)
    }
}

/** 单一入口，UI 只调它一次。 */
fun understandPhoto(source: Bitmap?): PhotoUnderstanding = PhotoUnderstandingEngine.understand(source)
