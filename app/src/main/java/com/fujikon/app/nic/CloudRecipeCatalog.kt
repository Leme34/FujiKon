package com.fujikon.app.nic

/**
 * 云创色彩预设库（资料库附件「云创色彩预设.zip」）中 107 个真实 NP3 的逐一对照目录。
 *
 * 数据来源：用户提供的官方云创色彩方案导出文件，非任何统计拟合或合成曲线。
 * 每个 NP3 都是完整的尼康 Picture Control（NP3 / 0310 容器），由
 * [com.fujikon.app.ncp.PictureControlParser] 在运行时零拟合解析：
 *  - 31 个文件含官方 256 点真实色调曲线（TLV record 2，值域 0..32767）；
 *  - 全部 107 个含官方真实参数（contrast / highlight / shadow / white / black /
 *    saturation 等，±100 口径）、8 分区 Color Blender 与三分区 Color Grading；
 *  - 基准 Picture Control 均为 Flexible Color。
 *
 * 这取代了此前 [CloudLookCalibration] 的统计拟合 LUT 与
 * CloudPictureControl.buildToneCurve 的合成 S 曲线 —— 后者正是 App 早期「云创滤镜
 * 套用后与官方效果图存在天壤之别」的根因：官方接口只公开简化参数、把影调字段返回
 * "gamma"，App 曾用假曲线补全。现在直接用官方 NP3 原始二进制，观感由真实数据决定。
 *
 * 文件内 name 字段（record 0x0200，20 字节）普遍被截断，故保留原始文件名做展示名，
 * 并生成含参数摘要与分类标签的中文说明，供「我的预设 / 选择预设」按名称或说明搜索。
 */
data class CloudRecipeSpec(
    val id: String,
    val asset: String,
    val name: String,
    val description: String,
    val category: String,
    val hasCurve: Boolean
)

object CloudRecipeCatalog {
    const val SOURCE_NAME = "尼康云创官方 · 真实 NP3"
    const val HASH_PREFIX = "cloudrecipe:"

    val ALL: List<CloudRecipeSpec> = listOf(
        CloudRecipeSpec(
            id = "recipe_001",
            asset = "cloud_recipes/cloud_recipe_001.np3",
            name = "BLEACH PASS 01A",
            description = "尼康云创官方色彩方案「BLEACH PASS 01A」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 40、高光 -20、阴影 -55、黑色 -10、饱和 -50 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：官方 Flexible Color 系列 / 漂白旁路。随 1.6.43 云创色彩预设库逐一落地。",
            category = "官方 Flexible Color 系列 / 漂白旁路",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_002",
            asset = "cloud_recipes/cloud_recipe_002.np3",
            name = "CLA GN 01B",
            description = "尼康云创官方色彩方案「CLA GN 01B」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 80、高光 0、阴影 0、黑色 -10、饱和 30 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：官方 Flexible Color 系列 / 清透 / 绿调。随 1.6.43 云创色彩预设库逐一落地。",
            category = "官方 Flexible Color 系列 / 清透",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_003",
            asset = "cloud_recipes/cloud_recipe_003.np3",
            name = "CLR BU 01A",
            description = "尼康云创官方色彩方案「CLR BU 01A」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 15、高光 25、阴影 -5、黑色 -10、饱和 0 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：官方 Flexible Color 系列 / 清透 / 青蓝 / 人像。随 1.6.43 云创色彩预设库逐一落地。",
            category = "官方 Flexible Color 系列 / 清透",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_004",
            asset = "cloud_recipes/cloud_recipe_004.np3",
            name = "CLR BU 01B",
            description = "尼康云创官方色彩方案「CLR BU 01B」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 15、高光 25、阴影 -5、黑色 -10、饱和 0 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：官方 Flexible Color 系列 / 清透 / 青蓝 / 人像。随 1.6.43 云创色彩预设库逐一落地。",
            category = "官方 Flexible Color 系列 / 清透",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_005",
            asset = "cloud_recipes/cloud_recipe_005.np3",
            name = "CLR GN 01A",
            description = "尼康云创官方色彩方案「CLR GN 01A」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 80、高光 0、阴影 0、黑色 -10、饱和 30 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：官方 Flexible Color 系列 / 清透 / 绿调 / 人像。随 1.6.43 云创色彩预设库逐一落地。",
            category = "官方 Flexible Color 系列 / 清透",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_006",
            asset = "cloud_recipes/cloud_recipe_006.np3",
            name = "CLR TEALOR 01A",
            description = "尼康云创官方色彩方案「CLR TEALOR 01A」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 80、高光 0、阴影 0、黑色 -12、饱和 0 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：官方 Flexible Color 系列 / 清透 / 青蓝 / 人像。随 1.6.43 云创色彩预设库逐一落地。",
            category = "官方 Flexible Color 系列 / 清透",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_007",
            asset = "cloud_recipes/cloud_recipe_007.np3",
            name = "CLR TEALOR 01B",
            description = "尼康云创官方色彩方案「CLR TEALOR 01B」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 80、高光 0、阴影 0、黑色 -12、饱和 0 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：官方 Flexible Color 系列 / 清透 / 青蓝 / 人像。随 1.6.43 云创色彩预设库逐一落地。",
            category = "官方 Flexible Color 系列 / 清透",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_008",
            asset = "cloud_recipes/cloud_recipe_008.np3",
            name = "CLR YE 01A",
            description = "尼康云创官方色彩方案「CLR YE 01A」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 60、高光 0、阴影 -8、黑色 -13、饱和 0 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：官方 Flexible Color 系列 / 清透 / 暖黄 / 人像。随 1.6.43 云创色彩预设库逐一落地。",
            category = "官方 Flexible Color 系列 / 清透",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_009",
            asset = "cloud_recipes/cloud_recipe_009.np3",
            name = "CLR YE01B",
            description = "尼康云创官方色彩方案「CLR YE01B」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 60、高光 0、阴影 -8、黑色 -13、饱和 0 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：官方 Flexible Color 系列 / 清透 / 暖黄 / 人像。随 1.6.43 云创色彩预设库逐一落地。",
            category = "官方 Flexible Color 系列 / 清透",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_010",
            asset = "cloud_recipes/cloud_recipe_010.np3",
            name = "FLEXIBLECOLOR-07",
            description = "尼康云创官方色彩方案「FLEXIBLECOLOR-07」的真实 NP3 导出，基准 Flexible Color。含官方 256 点真实色调曲线（642→30031）；对比度 0、高光 0、阴影 0、黑色 0、饱和 20 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：官方 Flexible Color 系列 / 鲜艳。随 1.6.43 云创色彩预设库逐一落地。",
            category = "官方 Flexible Color 系列 / 鲜艳",
            hasCurve = true
        ),
        CloudRecipeSpec(
            id = "recipe_011",
            asset = "cloud_recipes/cloud_recipe_011.np3",
            name = "GLOW LANHARLAND",
            description = "尼康云创官方色彩方案「GLOW LANHARLAND」的真实 NP3 导出，基准 Flexible Color。含官方 256 点真实色调曲线（257→32767）；对比度 0、高光 0、阴影 0、黑色 0、饱和 0 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 暖调日落。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 暖调日落",
            hasCurve = true
        ),
        CloudRecipeSpec(
            id = "recipe_012",
            asset = "cloud_recipes/cloud_recipe_012.np3",
            name = "KOTA CREEN TAKA",
            description = "尼康云创官方色彩方案「KOTA CREEN TAKA」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 -12、高光 -25、阴影 26、黑色 -4、饱和 9 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_013",
            asset = "cloud_recipes/cloud_recipe_013.np3",
            name = "MSLT-Portra400-V1",
            description = "尼康云创官方色彩方案「MSLT-Portra400-V1」的真实 NP3 导出，基准 Flexible Color。含官方 256 点真实色调曲线（1083→32767）；对比度 0、高光 0、阴影 0、黑色 0、饱和 0 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：官方 Flexible Color 系列 / 胶片感。随 1.6.43 云创色彩预设库逐一落地。",
            category = "官方 Flexible Color 系列 / 胶片感",
            hasCurve = true
        ),
        CloudRecipeSpec(
            id = "recipe_014",
            asset = "cloud_recipes/cloud_recipe_014.np3",
            name = "Mattebluegray",
            description = "尼康云创官方色彩方案「Mattebluegray」的真实 NP3 导出，基准 Flexible Color。含官方 256 点真实色调曲线（5163→31097）；对比度 0、高光 0、阴影 0、黑色 0、饱和 -6 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 青蓝冷调。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 青蓝冷调",
            hasCurve = true
        ),
        CloudRecipeSpec(
            id = "recipe_015",
            asset = "cloud_recipes/cloud_recipe_015.np3",
            name = "NEG YECY 01A",
            description = "尼康云创官方色彩方案「NEG YECY 01A」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 -20、高光 -5、阴影 0、黑色 -40、饱和 -15 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：官方 Flexible Color 系列 / 负片 / 暖黄 / 青蓝。随 1.6.43 云创色彩预设库逐一落地。",
            category = "官方 Flexible Color 系列 / 负片",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_016",
            asset = "cloud_recipes/cloud_recipe_016.np3",
            name = "NEGA BU 01A",
            description = "尼康云创官方色彩方案「NEGA BU 01A」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 0、高光 -30、阴影 -5、黑色 -15、饱和 10 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：官方 Flexible Color 系列 / 负片 / 青蓝。随 1.6.43 云创色彩预设库逐一落地。",
            category = "官方 Flexible Color 系列 / 负片",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_017",
            asset = "cloud_recipes/cloud_recipe_017.np3",
            name = "NEGA BU 01B",
            description = "尼康云创官方色彩方案「NEGA BU 01B」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 40、高光 -20、阴影 -20、黑色 -20、饱和 30 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：官方 Flexible Color 系列 / 负片 / 青蓝。随 1.6.43 云创色彩预设库逐一落地。",
            category = "官方 Flexible Color 系列 / 负片",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_018",
            asset = "cloud_recipes/cloud_recipe_018.np3",
            name = "NEGA CY 01A",
            description = "尼康云创官方色彩方案「NEGA CY 01A」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 0、高光 0、阴影 -70、黑色 -15、饱和 0 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：官方 Flexible Color 系列 / 负片 / 青蓝。随 1.6.43 云创色彩预设库逐一落地。",
            category = "官方 Flexible Color 系列 / 负片",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_019",
            asset = "cloud_recipes/cloud_recipe_019.np3",
            name = "NEGA CYBU 01A",
            description = "尼康云创官方色彩方案「NEGA CYBU 01A」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 0、高光 0、阴影 -5、黑色 -15、饱和 20 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：官方 Flexible Color 系列 / 负片 / 青蓝。随 1.6.43 云创色彩预设库逐一落地。",
            category = "官方 Flexible Color 系列 / 负片",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_020",
            asset = "cloud_recipes/cloud_recipe_020.np3",
            name = "NEGA CYBU 01B",
            description = "尼康云创官方色彩方案「NEGA CYBU 01B」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 40、高光 20、阴影 -20、黑色 -20、饱和 20 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：官方 Flexible Color 系列 / 负片 / 青蓝。随 1.6.43 云创色彩预设库逐一落地。",
            category = "官方 Flexible Color 系列 / 负片",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_021",
            asset = "cloud_recipes/cloud_recipe_021.np3",
            name = "NEGA GNCY 01A",
            description = "尼康云创官方色彩方案「NEGA GNCY 01A」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 0、高光 0、阴影 -5、黑色 -15、饱和 10 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：官方 Flexible Color 系列 / 负片 / 青蓝 / 绿调。随 1.6.43 云创色彩预设库逐一落地。",
            category = "官方 Flexible Color 系列 / 负片",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_022",
            asset = "cloud_recipes/cloud_recipe_022.np3",
            name = "NEGA GNCY 01B",
            description = "尼康云创官方色彩方案「NEGA GNCY 01B」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 40、高光 20、阴影 -20、黑色 -20、饱和 30 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：官方 Flexible Color 系列 / 负片 / 青蓝 / 绿调。随 1.6.43 云创色彩预设库逐一落地。",
            category = "官方 Flexible Color 系列 / 负片",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_023",
            asset = "cloud_recipes/cloud_recipe_023.np3",
            name = "NEGA RDCY 01B",
            description = "尼康云创官方色彩方案「NEGA RDCY 01B」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 40、高光 20、阴影 -20、黑色 -20、饱和 30 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：官方 Flexible Color 系列 / 负片 / 青蓝 / 红调。随 1.6.43 云创色彩预设库逐一落地。",
            category = "官方 Flexible Color 系列 / 负片",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_024",
            asset = "cloud_recipes/cloud_recipe_024.np3",
            name = "NEGA RDCY01A",
            description = "尼康云创官方色彩方案「NEGA RDCY01A」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 0、高光 0、阴影 0、黑色 -15、饱和 10 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：官方 Flexible Color 系列 / 负片 / 青蓝 / 红调。随 1.6.43 云创色彩预设库逐一落地。",
            category = "官方 Flexible Color 系列 / 负片",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_025",
            asset = "cloud_recipes/cloud_recipe_025.np3",
            name = "NEGA T 01B",
            description = "尼康云创官方色彩方案「NEGA T 01B」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 0、高光 10、阴影 -40、黑色 -5、饱和 30 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：官方 Flexible Color 系列 / 负片。随 1.6.43 云创色彩预设库逐一落地。",
            category = "官方 Flexible Color 系列 / 负片",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_026",
            asset = "cloud_recipes/cloud_recipe_026.np3",
            name = "NEGA T01A",
            description = "尼康云创官方色彩方案「NEGA T01A」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 -25、高光 -10、阴影 -30、黑色 0、饱和 20 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：官方 Flexible Color 系列 / 负片。随 1.6.43 云创色彩预设库逐一落地。",
            category = "官方 Flexible Color 系列 / 负片",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_027",
            asset = "cloud_recipes/cloud_recipe_027.np3",
            name = "NEGA YE 01A",
            description = "尼康云创官方色彩方案「NEGA YE 01A」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 0、高光 0、阴影 -5、黑色 -15、饱和 10 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：官方 Flexible Color 系列 / 负片 / 暖黄。随 1.6.43 云创色彩预设库逐一落地。",
            category = "官方 Flexible Color 系列 / 负片",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_028",
            asset = "cloud_recipes/cloud_recipe_028.np3",
            name = "NEGA YE 01B",
            description = "尼康云创官方色彩方案「NEGA YE 01B」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 40、高光 20、阴影 -20、黑色 -20、饱和 30 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：官方 Flexible Color 系列 / 负片 / 暖黄。随 1.6.43 云创色彩预设库逐一落地。",
            category = "官方 Flexible Color 系列 / 负片",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_029",
            asset = "cloud_recipes/cloud_recipe_029.np3",
            name = "NEGA YE 02A",
            description = "尼康云创官方色彩方案「NEGA YE 02A」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 0、高光 -10、阴影 -5、黑色 -15、饱和 10 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：官方 Flexible Color 系列 / 负片 / 暖黄。随 1.6.43 云创色彩预设库逐一落地。",
            category = "官方 Flexible Color 系列 / 负片",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_030",
            asset = "cloud_recipes/cloud_recipe_030.np3",
            name = "NEGA YE 02B",
            description = "尼康云创官方色彩方案「NEGA YE 02B」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 40、高光 20、阴影 -20、黑色 -20、饱和 30 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：官方 Flexible Color 系列 / 负片 / 暖黄。随 1.6.43 云创色彩预设库逐一落地。",
            category = "官方 Flexible Color 系列 / 负片",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_031",
            asset = "cloud_recipes/cloud_recipe_031.np3",
            name = "NEGA YE 03A",
            description = "尼康云创官方色彩方案「NEGA YE 03A」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 -15、高光 -10、阴影 -15、黑色 -15、饱和 10 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：官方 Flexible Color 系列 / 负片 / 暖黄。随 1.6.43 云创色彩预设库逐一落地。",
            category = "官方 Flexible Color 系列 / 负片",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_032",
            asset = "cloud_recipes/cloud_recipe_032.np3",
            name = "NEGA YE 03B",
            description = "尼康云创官方色彩方案「NEGA YE 03B」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 25、高光 10、阴影 -20、黑色 -25、饱和 30 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：官方 Flexible Color 系列 / 负片 / 暖黄。随 1.6.43 云创色彩预设库逐一落地。",
            category = "官方 Flexible Color 系列 / 负片",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_033",
            asset = "cloud_recipes/cloud_recipe_033.np3",
            name = "NEGA YECY 02A",
            description = "尼康云创官方色彩方案「NEGA YECY 02A」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 0、高光 -8、阴影 -12、黑色 -40、饱和 -15 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：官方 Flexible Color 系列 / 负片 / 暖黄 / 青蓝。随 1.6.43 云创色彩预设库逐一落地。",
            category = "官方 Flexible Color 系列 / 负片",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_034",
            asset = "cloud_recipes/cloud_recipe_034.np3",
            name = "NEGA YEGN 01A",
            description = "尼康云创官方色彩方案「NEGA YEGN 01A」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 5、高光 0、阴影 0、黑色 -10、饱和 10 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：官方 Flexible Color 系列 / 负片 / 暖黄 / 绿调。随 1.6.43 云创色彩预设库逐一落地。",
            category = "官方 Flexible Color 系列 / 负片",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_035",
            asset = "cloud_recipes/cloud_recipe_035.np3",
            name = "NEGA YEGN 01B",
            description = "尼康云创官方色彩方案「NEGA YEGN 01B」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 40、高光 20、阴影 -10、黑色 -10、饱和 20 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：官方 Flexible Color 系列 / 负片 / 暖黄 / 绿调。随 1.6.43 云创色彩预设库逐一落地。",
            category = "官方 Flexible Color 系列 / 负片",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_036",
            asset = "cloud_recipes/cloud_recipe_036.np3",
            name = "NEGATIVE CY BU",
            description = "尼康云创官方色彩方案「NEGATIVE CY BU」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 0、高光 0、阴影 -5、黑色 -15、饱和 20 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：官方 Flexible Color 系列 / 负片 / 青蓝。随 1.6.43 云创色彩预设库逐一落地。",
            category = "官方 Flexible Color 系列 / 负片",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_037",
            asset = "cloud_recipes/cloud_recipe_037.np3",
            name = "Negative Cyan01a",
            description = "尼康云创官方色彩方案「Negative Cyan01a」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 0、高光 0、阴影 -70、黑色 -15、饱和 0 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：官方 Flexible Color 系列 / 负片 / 青蓝 / 青蓝冷调。随 1.6.43 云创色彩预设库逐一落地。",
            category = "官方 Flexible Color 系列 / 负片",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_038",
            asset = "cloud_recipes/cloud_recipe_038.np3",
            name = "Ari Green Gunji",
            description = "尼康云创官方色彩方案「Ari Green Gunji」的真实 NP3 导出，基准 Flexible Color。含官方 256 点真实色调曲线（108→32767）；对比度 0、高光 0、阴影 0、黑色 0、饱和 75 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 自然风景。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 自然风景",
            hasCurve = true
        ),
        CloudRecipeSpec(
            id = "recipe_039",
            asset = "cloud_recipes/cloud_recipe_039.np3",
            name = "At Dusk Woo",
            description = "尼康云创官方色彩方案「At Dusk Woo」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 34、高光 -50、阴影 -40、黑色 -7、饱和 19 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 暖调日落 / 夜景暗调。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 暖调日落",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_040",
            asset = "cloud_recipes/cloud_recipe_040.np3",
            name = "Bluehour Luna",
            description = "尼康云创官方色彩方案「Bluehour Luna」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 10、高光 -10、阴影 -20、黑色 0、饱和 20 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 青蓝冷调 / 夜景暗调。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 青蓝冷调",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_041",
            asset = "cloud_recipes/cloud_recipe_041.np3",
            name = "Bluem Luizclas",
            description = "尼康云创官方色彩方案「Bluem Luizclas」的真实 NP3 导出，基准 Flexible Color。含官方 256 点真实色调曲线（2730→32767）；对比度 0、高光 0、阴影 0、黑色 0、饱和 0 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 青蓝冷调。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 青蓝冷调",
            hasCurve = true
        ),
        CloudRecipeSpec(
            id = "recipe_042",
            asset = "cloud_recipes/cloud_recipe_042.np3",
            name = "Britfilm Bw",
            description = "尼康云创官方色彩方案「Britfilm Bw」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 3、高光 -20、阴影 31、黑色 -10、饱和 -98 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 胶片感 / 黑白。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 胶片感",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_043",
            asset = "cloud_recipes/cloud_recipe_043.np3",
            name = "Calllt Love Faloo M",
            description = "尼康云创官方色彩方案「Calllt Love Faloo M」的真实 NP3 导出，基准 Flexible Color。含官方 256 点真实色调曲线（267→32767）；对比度 0、高光 0、阴影 0、黑色 0、饱和 30 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案",
            hasCurve = true
        ),
        CloudRecipeSpec(
            id = "recipe_044",
            asset = "cloud_recipes/cloud_recipe_044.np3",
            name = "Calmbreeze Dond",
            description = "尼康云创官方色彩方案「Calmbreeze Dond」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 18、高光 31、阴影 -20、黑色 5、饱和 7 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_045",
            asset = "cloud_recipes/cloud_recipe_045.np3",
            name = "Cineblue Brandon",
            description = "尼康云创官方色彩方案「Cineblue Brandon」的真实 NP3 导出，基准 Flexible Color。含官方 256 点真实色调曲线（1670→29298）；对比度 0、高光 0、阴影 0、黑色 0、饱和 -5 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 胶片感 / 青蓝冷调。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 胶片感",
            hasCurve = true
        ),
        CloudRecipeSpec(
            id = "recipe_046",
            asset = "cloud_recipes/cloud_recipe_046.np3",
            name = "Cinem Luizclas",
            description = "尼康云创官方色彩方案「Cinem Luizclas」的真实 NP3 导出，基准 Flexible Color。含官方 256 点真实色调曲线（2490→32767）；对比度 0、高光 0、阴影 0、黑色 0、饱和 0 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 胶片感。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 胶片感",
            hasCurve = true
        ),
        CloudRecipeSpec(
            id = "recipe_047",
            asset = "cloud_recipes/cloud_recipe_047.np3",
            name = "Cloudy Forest Y2",
            description = "尼康云创官方色彩方案「Cloudy Forest Y2」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 28、高光 38、阴影 -15、黑色 8、饱和 11 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 自然风景。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 自然风景",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_048",
            asset = "cloud_recipes/cloud_recipe_048.np3",
            name = "clrportrait tsakai",
            description = "尼康云创官方色彩方案「clrportrait tsakai」的真实 NP3 导出，基准 Flexible Color。含官方 256 点真实色调曲线（443→32382）；对比度 0、高光 0、阴影 0、黑色 0、饱和 13 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：官方 Flexible Color 系列 / 清透 / 胶片感 / 人像。随 1.6.43 云创色彩预设库逐一落地。",
            category = "官方 Flexible Color 系列 / 清透",
            hasCurve = true
        ),
        CloudRecipeSpec(
            id = "recipe_049",
            asset = "cloud_recipes/cloud_recipe_049.np3",
            name = "Coastal Glow Nm",
            description = "尼康云创官方色彩方案「Coastal Glow Nm」的真实 NP3 导出，基准 Flexible Color。含官方 256 点真实色调曲线（101→32767）；对比度 0、高光 0、阴影 0、黑色 0、饱和 8 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 自然风景 / 暖调日落。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 自然风景",
            hasCurve = true
        ),
        CloudRecipeSpec(
            id = "recipe_050",
            asset = "cloud_recipes/cloud_recipe_050.np3",
            name = "Colorsplash Gilmar",
            description = "尼康云创官方色彩方案「Colorsplash Gilmar」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 50、高光 25、阴影 -20、黑色 10、饱和 25 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 鲜艳。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 鲜艳",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_051",
            asset = "cloud_recipes/cloud_recipe_051.np3",
            name = "Cozy Autumn Leno",
            description = "尼康云创官方色彩方案「Cozy Autumn Leno」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 -15、高光 -48、阴影 48、黑色 -17、饱和 -2 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 秋日暖意。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 秋日暖意",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_052",
            asset = "cloud_recipes/cloud_recipe_052.np3",
            name = "Dawnhues Leno",
            description = "尼康云创官方色彩方案「Dawnhues Leno」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 -23、高光 -29、阴影 19、黑色 -17、饱和 8 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 暖调日落。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 暖调日落",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_053",
            asset = "cloud_recipes/cloud_recipe_053.np3",
            name = "Dgfilmlook Danny",
            description = "尼康云创官方色彩方案「Dgfilmlook Danny」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 0、高光 -50、阴影 -57、黑色 8、饱和 -5 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 胶片感。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 胶片感",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_054",
            asset = "cloud_recipes/cloud_recipe_054.np3",
            name = "Fern Lanharland",
            description = "尼康云创官方色彩方案「Fern Lanharland」的真实 NP3 导出，基准 Flexible Color。含官方 256 点真实色调曲线（36→30840）；对比度 0、高光 0、阴影 0、黑色 0、饱和 0 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 自然风景。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 自然风景",
            hasCurve = true
        ),
        CloudRecipeSpec(
            id = "recipe_055",
            asset = "cloud_recipes/cloud_recipe_055.np3",
            name = "Filmic Fabio",
            description = "尼康云创官方色彩方案「Filmic Fabio」的真实 NP3 导出，基准 Flexible Color。含官方 256 点真实色调曲线（642→29910）；对比度 0、高光 0、阴影 0、黑色 0、饱和 20 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 胶片感。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 胶片感",
            hasCurve = true
        ),
        CloudRecipeSpec(
            id = "recipe_056",
            asset = "cloud_recipes/cloud_recipe_056.np3",
            name = "Filmpop Leno",
            description = "尼康云创官方色彩方案「Filmpop Leno」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 -46、高光 -34、阴影 45、黑色 18、饱和 12 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 胶片感 / 鲜艳。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 胶片感",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_057",
            asset = "cloud_recipes/cloud_recipe_057.np3",
            name = "glow lan harland",
            description = "尼康云创官方色彩方案「glow lan harland」的真实 NP3 导出，基准 Flexible Color。含官方 256 点真实色调曲线（257→32767）；对比度 0、高光 0、阴影 0、黑色 0、饱和 0 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 暖调日落。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 暖调日落",
            hasCurve = true
        ),
        CloudRecipeSpec(
            id = "recipe_058",
            asset = "cloud_recipes/cloud_recipe_058.np3",
            name = "glowup preston",
            description = "尼康云创官方色彩方案「glowup preston」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 50、高光 -26、阴影 22、黑色 -17、饱和 39 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 人像 / 暖调日落。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 人像",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_059",
            asset = "cloud_recipes/cloud_recipe_059.np3",
            name = "Goldendusk Emili",
            description = "尼康云创官方色彩方案「Goldendusk Emili」的真实 NP3 导出，基准 Flexible Color。含官方 256 点真实色调曲线（121→32767）；对比度 0、高光 0、阴影 0、黑色 0、饱和 12 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 暖调日落 / 夜景暗调。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 暖调日落",
            hasCurve = true
        ),
        CloudRecipeSpec(
            id = "recipe_060",
            asset = "cloud_recipes/cloud_recipe_060.np3",
            name = "Hazy Memories Ju",
            description = "尼康云创官方色彩方案「Hazy Memories Ju」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 -10、高光 -35、阴影 -79、黑色 -18、饱和 0 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 夜景暗调。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 夜景暗调",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_061",
            asset = "cloud_recipes/cloud_recipe_061.np3",
            name = "Heartwarming Yoh",
            description = "尼康云创官方色彩方案「Heartwarming Yoh」的真实 NP3 导出，基准 Flexible Color。含官方 256 点真实色调曲线（2719→28655）；对比度 0、高光 0、阴影 0、黑色 0、饱和 25 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 暖调日落 / 秋日暖意。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 暖调日落",
            hasCurve = true
        ),
        CloudRecipeSpec(
            id = "recipe_062",
            asset = "cloud_recipes/cloud_recipe_062.np3",
            name = "Hidanaei Hanuka",
            description = "尼康云创官方色彩方案「Hidanaei Hanuka」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 50、高光 -5、阴影 90、黑色 30、饱和 10 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_063",
            asset = "cloud_recipes/cloud_recipe_063.np3",
            name = "Hk Reminisce Hiu",
            description = "尼康云创官方色彩方案「Hk Reminisce Hiu」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 64、高光 0、阴影 36、黑色 -18、饱和 45 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_064",
            asset = "cloud_recipes/cloud_recipe_064.np3",
            name = "Honey Lan Harland",
            description = "尼康云创官方色彩方案「Honey Lan Harland」的真实 NP3 导出，基准 Flexible Color。含官方 256 点真实色调曲线（385→30100）；对比度 0、高光 0、阴影 0、黑色 0、饱和 0 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 暖调日落 / 秋日暖意。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 暖调日落",
            hasCurve = true
        ),
        CloudRecipeSpec(
            id = "recipe_065",
            asset = "cloud_recipes/cloud_recipe_065.np3",
            name = "Ht Harmony Hana",
            description = "尼康云创官方色彩方案「Ht Harmony Hana」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 8、高光 -27、阴影 13、黑色 -10、饱和 10 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_066",
            asset = "cloud_recipes/cloud_recipe_066.np3",
            name = "Indoorharmony Ju",
            description = "尼康云创官方色彩方案「Indoorharmony Ju」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 -30、高光 -44、阴影 -43、黑色 17、饱和 0 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_067",
            asset = "cloud_recipes/cloud_recipe_067.np3",
            name = "Japanesque Aras",
            description = "尼康云创官方色彩方案「Japanesque Aras」的真实 NP3 导出，基准 Flexible Color。含官方 256 点真实色调曲线（2823→32767）；对比度 0、高光 0、阴影 0、黑色 0、饱和 20 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 自然风景。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 自然风景",
            hasCurve = true
        ),
        CloudRecipeSpec(
            id = "recipe_068",
            asset = "cloud_recipes/cloud_recipe_068.np3",
            name = "kota green taka",
            description = "尼康云创官方色彩方案「kota green taka」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 -12、高光 -25、阴影 26、黑色 -4、饱和 9 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 自然风景。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 自然风景",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_069",
            asset = "cloud_recipes/cloud_recipe_069.np3",
            name = "Mninght Blush",
            description = "尼康云创官方色彩方案「Mninght Blush」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 -74、高光 11、阴影 -30、黑色 -60、饱和 1 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 青蓝冷调。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 青蓝冷调",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_070",
            asset = "cloud_recipes/cloud_recipe_070.np3",
            name = "Moody Gr Brittanyk",
            description = "尼康云创官方色彩方案「Moody Gr Brittanyk」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 6、高光 9、阴影 12、黑色 -17、饱和 0 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 夜景暗调。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 夜景暗调",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_071",
            asset = "cloud_recipes/cloud_recipe_071.np3",
            name = "Moodyshadows G",
            description = "尼康云创官方色彩方案「Moodyshadows G」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 50、高光 -30、阴影 -30、黑色 10、饱和 -5 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 夜景暗调。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 夜景暗调",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_072",
            asset = "cloud_recipes/cloud_recipe_072.np3",
            name = "Moodyshadows",
            description = "尼康云创官方色彩方案「Moodyshadows」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 50、高光 -30、阴影 -30、黑色 10、饱和 -5 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 夜景暗调。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 夜景暗调",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_073",
            asset = "cloud_recipes/cloud_recipe_073.np3",
            name = "Mv Green Orange",
            description = "尼康云创官方色彩方案「Mv Green Orange」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 30、高光 52、阴影 0、黑色 0、饱和 21 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 自然风景。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 自然风景",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_074",
            asset = "cloud_recipes/cloud_recipe_074.np3",
            name = "Nature Links",
            description = "尼康云创官方色彩方案「Nature Links」的真实 NP3 导出，基准 Flexible Color。含官方 256 点真实色调曲线（829→32767）；对比度 0、高光 0、阴影 0、黑色 0、饱和 18 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 自然风景。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 自然风景",
            hasCurve = true
        ),
        CloudRecipeSpec(
            id = "recipe_075",
            asset = "cloud_recipes/cloud_recipe_075.np3",
            name = "negative cybu 01a",
            description = "尼康云创官方色彩方案「negative cybu 01a」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 0、高光 0、阴影 -5、黑色 -15、饱和 20 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：官方 Flexible Color 系列 / 负片 / 青蓝。随 1.6.43 云创色彩预设库逐一落地。",
            category = "官方 Flexible Color 系列 / 负片",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_076",
            asset = "cloud_recipes/cloud_recipe_076.np3",
            name = "Neon Glow Brando",
            description = "尼康云创官方色彩方案「Neon Glow Brando」的真实 NP3 导出，基准 Flexible Color。含官方 256 点真实色调曲线（0→32767）；对比度 0、高光 0、阴影 0、黑色 0、饱和 8 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 青蓝冷调 / 暖调日落 / 夜景暗调。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 青蓝冷调",
            hasCurve = true
        ),
        CloudRecipeSpec(
            id = "recipe_077",
            asset = "cloud_recipes/cloud_recipe_077.np3",
            name = "Nighta Like Dondre",
            description = "尼康云创官方色彩方案「Nighta Like Dondre」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 67、高光 -15、阴影 20、黑色 -52、饱和 17 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 青蓝冷调 / 夜景暗调。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 青蓝冷调",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_078",
            asset = "cloud_recipes/cloud_recipe_078.np3",
            name = "Painterly Light Da",
            description = "尼康云创官方色彩方案「Painterly Light Da」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 51、高光 40、阴影 12、黑色 0、饱和 21 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_079",
            asset = "cloud_recipes/cloud_recipe_079.np3",
            name = "Pastelpink Yuki",
            description = "尼康云创官方色彩方案「Pastelpink Yuki」的真实 NP3 导出，基准 Flexible Color。含官方 256 点真实色调曲线（1717→31739）；对比度 0、高光 0、阴影 0、黑色 0、饱和 5 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 人像。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 人像",
            hasCurve = true
        ),
        CloudRecipeSpec(
            id = "recipe_080",
            asset = "cloud_recipes/cloud_recipe_080.np3",
            name = "Primarypop Gilmar",
            description = "尼康云创官方色彩方案「Primarypop Gilmar」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 50、高光 40、阴影 -50、黑色 15、饱和 -10 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 鲜艳。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 鲜艳",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_081",
            asset = "cloud_recipes/cloud_recipe_081.np3",
            name = "Redm Luizclas",
            description = "尼康云创官方色彩方案「Redm Luizclas」的真实 NP3 导出，基准 Flexible Color。含官方 256 点真实色调曲线（2441→32767）；对比度 0、高光 0、阴影 0、黑色 0、饱和 0 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案",
            hasCurve = true
        ),
        CloudRecipeSpec(
            id = "recipe_082",
            asset = "cloud_recipes/cloud_recipe_082.np3",
            name = "Rose Old Film Hina",
            description = "尼康云创官方色彩方案「Rose Old Film Hina」的真实 NP3 导出，基准 Flexible Color。含官方 256 点真实色调曲线（1871→29812）；对比度 0、高光 0、阴影 0、黑色 0、饱和 -5 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 胶片感。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 胶片感",
            hasCurve = true
        ),
        CloudRecipeSpec(
            id = "recipe_083",
            asset = "cloud_recipes/cloud_recipe_083.np3",
            name = "Setouchiblue Noci",
            description = "尼康云创官方色彩方案「Setouchiblue Noci」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 0、高光 -35、阴影 0、黑色 0、饱和 -13 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 青蓝冷调。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 青蓝冷调",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_084",
            asset = "cloud_recipes/cloud_recipe_084.np3",
            name = "Shadefall Brittany",
            description = "尼康云创官方色彩方案「Shadefall Brittany」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 0、高光 0、阴影 0、黑色 0、饱和 0 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_085",
            asset = "cloud_recipes/cloud_recipe_085.np3",
            name = "Sky Mistfilm Hinano",
            description = "尼康云创官方色彩方案「Sky Mistfilm Hinano」的真实 NP3 导出，基准 Flexible Color。含官方 256 点真实色调曲线（2867→32253）；对比度 0、高光 0、阴影 0、黑色 0、饱和 5 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 胶片感 / 青蓝冷调。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 胶片感",
            hasCurve = true
        ),
        CloudRecipeSpec(
            id = "recipe_086",
            asset = "cloud_recipes/cloud_recipe_086.np3",
            name = "Soft Vivid Vijay",
            description = "尼康云创官方色彩方案「Soft Vivid Vijay」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 98、高光 25、阴影 75、黑色 10、饱和 10 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 鲜艳。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 鲜艳",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_087",
            asset = "cloud_recipes/cloud_recipe_087.np3",
            name = "Softfilm Leno",
            description = "尼康云创官方色彩方案「Softfilm Leno」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 -38、高光 -64、阴影 52、黑色 -40、饱和 -10 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 胶片感。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 胶片感",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_088",
            asset = "cloud_recipes/cloud_recipe_088.np3",
            name = "Softglow Harpreet",
            description = "尼康云创官方色彩方案「Softglow Harpreet」的真实 NP3 导出，基准 Flexible Color。含官方 256 点真实色调曲线（110→32767）；对比度 0、高光 0、阴影 0、黑色 0、饱和 3 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 人像 / 暖调日落。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 人像",
            hasCurve = true
        ),
        CloudRecipeSpec(
            id = "recipe_089",
            asset = "cloud_recipes/cloud_recipe_089.np3",
            name = "Summershine Do",
            description = "尼康云创官方色彩方案「Summershine Do」的真实 NP3 导出，基准 Flexible Color。含官方 256 点真实色调曲线（1285→27499）；对比度 0、高光 0、阴影 0、黑色 0、饱和 0 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 暖调日落。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 暖调日落",
            hasCurve = true
        ),
        CloudRecipeSpec(
            id = "recipe_090",
            asset = "cloud_recipes/cloud_recipe_090.np3",
            name = "Sunkissedcool",
            description = "尼康云创官方色彩方案「Sunkissedcool」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 -10、高光 -76、阴影 -79、黑色 17、饱和 0 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 暖调日落。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 暖调日落",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_091",
            asset = "cloud_recipes/cloud_recipe_091.np3",
            name = "Sunkisssedwarm",
            description = "尼康云创官方色彩方案「Sunkisssedwarm」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 -10、高光 -76、阴影 -79、黑色 -18、饱和 0 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 暖调日落。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 暖调日落",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_092",
            asset = "cloud_recipes/cloud_recipe_092.np3",
            name = "Sunlight Happy Da",
            description = "尼康云创官方色彩方案「Sunlight Happy Da」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 32、高光 24、阴影 15、黑色 -20、饱和 17 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 暖调日落。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 暖调日落",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_093",
            asset = "cloud_recipes/cloud_recipe_093.np3",
            name = "Sunsetfilm Jkboy",
            description = "尼康云创官方色彩方案「Sunsetfilm Jkboy」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 50、高光 0、阴影 45、黑色 -20、饱和 -25 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 胶片感 / 暖调日落。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 胶片感",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_094",
            asset = "cloud_recipes/cloud_recipe_094.np3",
            name = "Sunsetglow Mikan",
            description = "尼康云创官方色彩方案「Sunsetglow Mikan」的真实 NP3 导出，基准 Flexible Color。含官方 256 点真实色调曲线（1955→32767）；对比度 0、高光 0、阴影 0、黑色 0、饱和 0 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 暖调日落。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 暖调日落",
            hasCurve = true
        ),
        CloudRecipeSpec(
            id = "recipe_095",
            asset = "cloud_recipes/cloud_recipe_095.np3",
            name = "Sunsetreaet Baek",
            description = "尼康云创官方色彩方案「Sunsetreaet Baek」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 15、高光 0、阴影 10、黑色 -25、饱和 0 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 暖调日落。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 暖调日落",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_096",
            asset = "cloud_recipes/cloud_recipe_096.np3",
            name = "T&o Brandonw",
            description = "尼康云创官方色彩方案「T&o Brandonw」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 25、高光 10、阴影 -25、黑色 0、饱和 0 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_097",
            asset = "cloud_recipes/cloud_recipe_097.np3",
            name = "Tenderclera",
            description = "尼康云创官方色彩方案「Tenderclera」的真实 NP3 导出，基准 Flexible Color。含官方 256 点真实色调曲线（1028→31354）；对比度 0、高光 0、阴影 0、黑色 0、饱和 15 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 人像。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 人像",
            hasCurve = true
        ),
        CloudRecipeSpec(
            id = "recipe_098",
            asset = "cloud_recipes/cloud_recipe_098.np3",
            name = "Textured Docmntry",
            description = "尼康云创官方色彩方案「Textured Docmntry」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 100、高光 54、阴影 33、黑色 -6、饱和 0 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_099",
            asset = "cloud_recipes/cloud_recipe_099.np3",
            name = "Turquoise Blue Yu",
            description = "尼康云创官方色彩方案「Turquoise Blue Yu」的真实 NP3 导出，基准 Flexible Color。含官方 256 点真实色调曲线（1927→32767）；对比度 0、高光 0、阴影 0、黑色 0、饱和 5 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 青蓝冷调。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 青蓝冷调",
            hasCurve = true
        ),
        CloudRecipeSpec(
            id = "recipe_100",
            asset = "cloud_recipes/cloud_recipe_100.np3",
            name = "Vintage A Brittanyk",
            description = "尼康云创官方色彩方案「Vintage A Brittanyk」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 -9、高光 0、阴影 0、黑色 0、饱和 0 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 胶片感。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 胶片感",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_101",
            asset = "cloud_recipes/cloud_recipe_101.np3",
            name = "Vintagem Tsakai",
            description = "尼康云创官方色彩方案「Vintagem Tsakai」的真实 NP3 导出，基准 Flexible Color。含官方 256 点真实色调曲线（1825→30069）；对比度 0、高光 0、阴影 0、黑色 0、饱和 13 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 胶片感。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 胶片感",
            hasCurve = true
        ),
        CloudRecipeSpec(
            id = "recipe_102",
            asset = "cloud_recipes/cloud_recipe_102.np3",
            name = "Vintagevibe Prest",
            description = "尼康云创官方色彩方案「Vintagevibe Prest」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 50、高光 -21、阴影 -15、黑色 -7、饱和 20 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 胶片感。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 胶片感",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_103",
            asset = "cloud_recipes/cloud_recipe_103.np3",
            name = "Vitality Film Pman",
            description = "尼康云创官方色彩方案「Vitality Film Pman」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 -4、高光 54、阴影 -17、黑色 -3、饱和 0 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 胶片感 / 鲜艳。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 胶片感",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_104",
            asset = "cloud_recipes/cloud_recipe_104.np3",
            name = "Warm Street Ben",
            description = "尼康云创官方色彩方案「Warm Street Ben」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 36、高光 -51、阴影 64、黑色 -10、饱和 7 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 暖调日落。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 暖调日落",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_105",
            asset = "cloud_recipes/cloud_recipe_105.np3",
            name = "Warmdimlight Lu",
            description = "尼康云创官方色彩方案「Warmdimlight Lu」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 5、高光 30、阴影 10、黑色 0、饱和 15 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 暖调日落。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 暖调日落",
            hasCurve = false
        ),
        CloudRecipeSpec(
            id = "recipe_106",
            asset = "cloud_recipes/cloud_recipe_106.np3",
            name = "Warmportrait Y2",
            description = "尼康云创官方色彩方案「Warmportrait Y2」的真实 NP3 导出，基准 Flexible Color。含官方 256 点真实色调曲线（103→30711）；对比度 0、高光 0、阴影 0、黑色 0、饱和 8 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 胶片感 / 人像 / 暖调日落。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 胶片感",
            hasCurve = true
        ),
        CloudRecipeSpec(
            id = "recipe_107",
            asset = "cloud_recipes/cloud_recipe_107.np3",
            name = "Warmtoned Ptt",
            description = "尼康云创官方色彩方案「Warmtoned Ptt」的真实 NP3 导出，基准 Flexible Color。官方以参数+分区调色定义影调，无独立曲线；对比度 29、高光 -22、阴影 14、黑色 0、饱和 20 均为官方原值；8 分区色彩混合与高光/中间调/阴影三分区调色为文件内真实数据，零拟合。分类：云创创作者色彩方案 / 暖调日落 / 秋日暖意。随 1.6.43 云创色彩预设库逐一落地。",
            category = "云创创作者色彩方案 / 暖调日落",
            hasCurve = false
        )
    )
}
