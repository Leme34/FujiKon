package com.fujikon.app.nic

/**
 * 在线「尼康官方云创滤镜」的导入准入策略 —— 单一真值源。
 *
 * 背景：在线目录（`bff/recipe/release`）是动态拉取的，包含大量官方色彩方案，
 * 但公开接口只返回简化参数、把影调字段返回 "gamma"，并不公开 256 点色调曲线。
 * App 早期用统计拟合假 LUT + 合成 S 曲线补全，套用后与官方效果图相差甚远
 * （用户反馈「天壤之别」）。
 *
 * 1.6.43 起，资料库附件「云创色彩预设.zip」中的真实 NP3 被逐一落地：只有
 * [OfficialCloudLooks.BY_RECIPE_ID] 里的 recipe 拥有官方真实 NP3 数据（曲线 /
 * ±100 参数 / 8 分区 Color Blender / 三分区 Color Grading 全为文件原值），由
 * [CloudLookCalibration.apply] 在生成预设时替换掉假曲线。其余在线 recipe 仍只能
 * 得到拟合假 LUT 或合成 S 曲线，观感不可信。
 *
 * 因此 1.6.45 起：**仅命中官方真实 NP3 数据表的 recipe 允许导入**，其余一律
 * 显示「暂不支持导入」，并同时在 [com.fujikon.app.ui.viewmodel.NikonCloudViewModel]
 * 的导入入口做防御性拦截，避免用户得到与官方样张不符的假滤镜。官方云创目录
 * 后续新增、且附件中没有对应真实 NP3 的方案会自动落入「暂不支持」，无需维护
 * 黑名单。
 */
object CloudImportSupport {

    const val UNSUPPORTED_LABEL = "暂不支持导入"
    const val UNSUPPORTED_HINT =
        "该色彩方案暂无官方真实色彩数据，暂不支持导入；如需使用，请从尼康云创官方下载 NP3 文件后通过「导入预设」加入。"

    /**
     * 是否已根据附件「云创色彩预设.zip」落地实现官方真实数据。
     * 判定与 [CloudLookCalibration.apply] 使用真实数据的条件严格一致。
     */
    fun isSupported(recipeId: String): Boolean =
        recipeId.isNotBlank() && recipeId in OfficialCloudLooks.BY_RECIPE_ID

    /** 当前支持导入的在线 recipe id 集合。 */
    val supportedIds: Set<String> get() = OfficialCloudLooks.BY_RECIPE_ID.keys
}
