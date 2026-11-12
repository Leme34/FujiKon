package com.fujikon.app.nic

import com.fujikon.app.ncp.ColorBlendValue
import com.fujikon.app.ncp.ColorGrading
import com.fujikon.app.ncp.ColorGradingZone

/**
 * 官方云创真实 NP3 数据表 —— 由「云创色彩预设.zip」中对应文件逐一解析生成，
 * 不是任何统计拟合或合成 S 曲线。
 *
 * 供 [CloudLookCalibration] 替换早期「在线接口只返回 gamma 字段 -> 拟合假 LUT」
 * 的失真实现：这些 recipe 的 256 点色调曲线、参数（±100 口径）、8 分区 Color
 * Blender、三分区 Color Grading、锐化档位全部为官方 NP3 原值。
 *
 * 无独立曲线的文件（392 字节）是官方「以参数 + 分区调色定义影调」的方案，
 * curve 保持 null，渲染器按官方参数施加，绝不用合成曲线顶替。
 */
internal data class OfficialLook(
    val curve: IntArray?,
    val saturation: Int,
    val hue: Int,
    val contrast: Int,
    val highlights: Int,
    val shadows: Int,
    val whites: Int,
    val blacks: Int,
    val sharpening: Float,
    val clarity: Float,
    val midRangeSharpening: Float,
    val blender: List<ColorBlendValue>,
    val grading: ColorGrading
)

internal object OfficialCloudLooks {
    /** recipeId -> 官方真实 NP3 数据。未命中（如 Catgrain）走原有兜底路径。 */
    val BY_RECIPE_ID: Map<String, OfficialLook> = mapOf(
        // Hidamari Color <- hidanaei hanuka.NP3（官方以参数+分区定义影调，无独立曲线）
        "01JD1PCXH2HKADD09AZZ80CN30" to OfficialLook(
            curve = null,
            saturation = 10,
            hue = 0,
            contrast = 50,
            highlights = -5,
            shadows = 90,
            whites = -5,
            blacks = 30,
            sharpening = 2.0f,
            clarity = 0.5f,
            midRangeSharpening = 1.0f,
            blender = listOf(ColorBlendValue(15, -10, 0), ColorBlendValue(-10, 15, 0), ColorBlendValue(-10, -15, 0), ColorBlendValue(45, -10, 0), ColorBlendValue(5, 0, 0), ColorBlendValue(-55, 10, 0), ColorBlendValue(-15, 0, 0), ColorBlendValue(25, 10, 0)),
            grading = ColorGrading(highlights = ColorGradingZone(229, 4, -20), midtones = ColorGradingZone(352, 0, 100), shadows = ColorGradingZone(26, 14, 6), blending = 50, balance = 0)
        ),
        // Warm Portrait <- warmportrait y2.NP3（含官方 256 点真实曲线）
        "01J6VDR8901GMQMP1JA12XZ2SX" to OfficialLook(
            curve = intArrayOf(
            103, 207, 310, 414, 518, 621, 725, 829,
            933, 1038, 1142, 1247, 1352, 1457, 1563, 1668,
            1775, 1881, 1988, 2095, 2203, 2311, 2419, 2528,
            2637, 2747, 2857, 2968, 3080, 3192, 3304, 3418,
            3532, 3646, 3761, 3877, 3994, 4111, 4229, 4348,
            4468, 4588, 4709, 4831, 4954, 5078, 5203, 5329,
            5455, 5583, 5712, 5841, 5972, 6104, 6236, 6370,
            6505, 6641, 6778, 6917, 7056, 7197, 7339, 7482,
            7626, 7770, 7916, 8063, 8211, 8360, 8509, 8660,
            8811, 8963, 9116, 9270, 9425, 9580, 9736, 9892,
            10049, 10207, 10366, 10524, 10684, 10844, 11004, 11165,
            11327, 11489, 11651, 11813, 11976, 12139, 12303, 12466,
            12630, 12794, 12959, 13123, 13288, 13453, 13617, 13782,
            13947, 14112, 14277, 14441, 14606, 14771, 14935, 15100,
            15264, 15428, 15591, 15755, 15918, 16081, 16244, 16406,
            16568, 16729, 16890, 17051, 17211, 17371, 17530, 17689,
            17847, 18005, 18162, 18319, 18475, 18631, 18786, 18941,
            19095, 19248, 19401, 19553, 19705, 19856, 20006, 20156,
            20305, 20453, 20601, 20747, 20893, 21039, 21183, 21327,
            21470, 21612, 21754, 21894, 22034, 22173, 22311, 22448,
            22584, 22719, 22853, 22987, 23119, 23251, 23382, 23511,
            23640, 23767, 23894, 24019, 24144, 24267, 24390, 24511,
            24631, 24750, 24868, 24985, 25100, 25215, 25328, 25440,
            25551, 25661, 25769, 25876, 25982, 26087, 26190, 26293,
            26393, 26493, 26591, 26688, 26783, 26877, 26970, 27061,
            27151, 27240, 27328, 27414, 27499, 27583, 27665, 27747,
            27827, 27906, 27984, 28061, 28137, 28212, 28286, 28359,
            28431, 28502, 28573, 28642, 28711, 28778, 28845, 28911,
            28977, 29041, 29105, 29169, 29231, 29294, 29355, 29416,
            29476, 29536, 29595, 29654, 29712, 29770, 29828, 29885,
            29941, 29998, 30054, 30110, 30165, 30221, 30276, 30330,
            30385, 30440, 30494, 30549, 30603, 30657, 30711, 30711
        ),
            saturation = 8,
            hue = 0,
            contrast = 0,
            highlights = 0,
            shadows = 0,
            whites = 0,
            blacks = 0,
            sharpening = 2.75f,
            clarity = -0.25f,
            midRangeSharpening = 2.0f,
            blender = listOf(ColorBlendValue(0, 0, 0), ColorBlendValue(0, 0, 0), ColorBlendValue(0, 0, 0), ColorBlendValue(5, -10, 0), ColorBlendValue(0, 0, 0), ColorBlendValue(0, 0, 0), ColorBlendValue(0, 0, 0), ColorBlendValue(0, 0, 16)),
            grading = ColorGrading(highlights = ColorGradingZone(221, 0, -33), midtones = ColorGradingZone(44, 4, 7), shadows = ColorGradingZone(63, 9, 4), blending = 50, balance = 0)
        ),
        // Filmic <- filmic fabio.NP3（含官方 256 点真实曲线）
        "01JSBM2ZVF6SX4VSEK76K1VGJQ" to OfficialLook(
            curve = intArrayOf(
            642, 642, 642, 642, 642, 642, 642, 642,
            642, 642, 642, 642, 754, 845, 928, 1005,
            1078, 1149, 1219, 1286, 1353, 1419, 1484, 1550,
            1615, 1681, 1747, 1813, 1880, 1949, 2018, 2088,
            2160, 2233, 2308, 2384, 2463, 2543, 2625, 2710,
            2797, 2886, 2978, 3072, 3170, 3270, 3372, 3477,
            3585, 3695, 3808, 3922, 4040, 4159, 4281, 4404,
            4530, 4658, 4788, 4919, 5053, 5188, 5325, 5464,
            5604, 5746, 5889, 6034, 6180, 6328, 6476, 6626,
            6777, 6930, 7083, 7238, 7393, 7550, 7708, 7867,
            8027, 8187, 8349, 8512, 8675, 8840, 9005, 9171,
            9338, 9506, 9674, 9844, 10013, 10184, 10355, 10527,
            10700, 10873, 11046, 11220, 11395, 11570, 11746, 11922,
            12098, 12275, 12452, 12630, 12807, 12986, 13164, 13342,
            13521, 13700, 13879, 14058, 14237, 14417, 14596, 14775,
            14955, 15134, 15313, 15492, 15671, 15850, 16029, 16208,
            16386, 16564, 16742, 16919, 17097, 17274, 17450, 17626,
            17802, 17977, 18152, 18326, 18499, 18673, 18845, 19017,
            19188, 19359, 19529, 19698, 19866, 20034, 20201, 20367,
            20532, 20697, 20860, 21023, 21184, 21345, 21504, 21663,
            21820, 21977, 22132, 22286, 22439, 22591, 22741, 22891,
            23039, 23185, 23331, 23475, 23618, 23759, 23899, 24037,
            24174, 24309, 24443, 24575, 24706, 24835, 24963, 25088,
            25212, 25335, 25455, 25574, 25691, 25806, 25919, 26031,
            26140, 26248, 26354, 26457, 26559, 26658, 26756, 26851,
            26944, 27036, 27125, 27213, 27298, 27382, 27464, 27544,
            27623, 27700, 27775, 27848, 27920, 27990, 28059, 28127,
            28193, 28257, 28321, 28383, 28443, 28503, 28561, 28618,
            28674, 28729, 28783, 28836, 28888, 28939, 28989, 29038,
            29087, 29134, 29181, 29227, 29273, 29318, 29362, 29406,
            29449, 29492, 29534, 29576, 29618, 29659, 29700, 29741,
            29782, 29822, 29863, 29903, 29910, 29910, 29910, 29910
        ),
            saturation = 20,
            hue = 0,
            contrast = 0,
            highlights = 0,
            shadows = 0,
            whites = 0,
            blacks = 0,
            sharpening = 0.75f,
            clarity = -0.75f,
            midRangeSharpening = 1.0f,
            blender = listOf(ColorBlendValue(10, -2, 14), ColorBlendValue(10, -5, 15), ColorBlendValue(-27, 5, 30), ColorBlendValue(-15, -40, 0), ColorBlendValue(-4, -50, 10), ColorBlendValue(-45, -30, -25), ColorBlendValue(0, -40, 15), ColorBlendValue(20, -35, 5)),
            grading = ColorGrading(highlights = ColorGradingZone(194, 10, -50), midtones = ColorGradingZone(18, 5, 65), shadows = ColorGradingZone(52, 18, -30), blending = 50, balance = 0)
        ),
        // Textured Documentary <- textured docmntry.NP3（官方以参数+分区定义影调，无独立曲线）
        "01JAWAKHQ8B1J3TXXQSNTQGC5H" to OfficialLook(
            curve = null,
            saturation = 0,
            hue = 0,
            contrast = 100,
            highlights = 54,
            shadows = 33,
            whites = 0,
            blacks = -6,
            sharpening = 2.0f,
            clarity = 0.5f,
            midRangeSharpening = 1.0f,
            blender = listOf(ColorBlendValue(0, 63, -57), ColorBlendValue(0, 0, 0), ColorBlendValue(0, 0, 0), ColorBlendValue(0, 0, 0), ColorBlendValue(0, 23, 0), ColorBlendValue(0, 15, -25), ColorBlendValue(0, 0, 0), ColorBlendValue(0, 0, 0)),
            grading = ColorGrading(highlights = ColorGradingZone(0, 0, 0), midtones = ColorGradingZone(0, 0, 0), shadows = ColorGradingZone(340, 1, -34), blending = 50, balance = 0)
        ),
        // Nature <- nature links.NP3（含官方 256 点真实曲线）
        "01JAWAKHJ0DG7KCBY6BNAW4271" to OfficialLook(
            curve = intArrayOf(
            829, 888, 947, 1006, 1065, 1125, 1186, 1247,
            1309, 1371, 1435, 1500, 1565, 1633, 1701, 1771,
            1842, 1915, 1990, 2066, 2144, 2225, 2307, 2392,
            2479, 2568, 2659, 2754, 2850, 2950, 3052, 3158,
            3266, 3378, 3492, 3610, 3730, 3854, 3980, 4110,
            4242, 4376, 4514, 4654, 4796, 4941, 5088, 5238,
            5390, 5545, 5702, 5860, 6021, 6185, 6350, 6517,
            6686, 6856, 7029, 7203, 7379, 7557, 7736, 7917,
            8100, 8283, 8468, 8655, 8842, 9031, 9221, 9413,
            9605, 9798, 9992, 10187, 10383, 10580, 10778, 10976,
            11174, 11374, 11574, 11774, 11975, 12176, 12377, 12579,
            12781, 12983, 13185, 13387, 13589, 13791, 13993, 14195,
            14396, 14598, 14799, 14999, 15199, 15399, 15598, 15796,
            15994, 16191, 16388, 16583, 16778, 16972, 17164, 17356,
            17547, 17736, 17925, 18112, 18297, 18482, 18665, 18847,
            19027, 19205, 19382, 19557, 19731, 19903, 20073, 20241,
            20408, 20574, 20737, 20899, 21060, 21219, 21376, 21532,
            21686, 21839, 21990, 22140, 22288, 22435, 22580, 22724,
            22866, 23008, 23147, 23285, 23422, 23558, 23692, 23825,
            23956, 24086, 24215, 24343, 24469, 24594, 24717, 24840,
            24961, 25081, 25200, 25318, 25434, 25550, 25664, 25777,
            25889, 25999, 26109, 26218, 26325, 26432, 26537, 26641,
            26745, 26847, 26949, 27049, 27148, 27247, 27344, 27441,
            27536, 27631, 27725, 27818, 27910, 28001, 28091, 28181,
            28270, 28358, 28445, 28531, 28617, 28701, 28786, 28869,
            28951, 29033, 29115, 29195, 29275, 29354, 29433, 29511,
            29588, 29665, 29741, 29817, 29892, 29966, 30040, 30114,
            30187, 30259, 30331, 30402, 30473, 30544, 30614, 30684,
            30753, 30822, 30890, 30958, 31026, 31094, 31161, 31227,
            31294, 31360, 31426, 31491, 31557, 31622, 31687, 31752,
            31816, 31880, 31944, 32008, 32072, 32136, 32199, 32263,
            32326, 32389, 32452, 32515, 32578, 32641, 32704, 32767
        ),
            saturation = 18,
            hue = 0,
            contrast = 0,
            highlights = 0,
            shadows = 0,
            whites = 0,
            blacks = 0,
            sharpening = 2.0f,
            clarity = 0.5f,
            midRangeSharpening = 1.0f,
            blender = listOf(ColorBlendValue(0, 0, 0), ColorBlendValue(0, 0, 0), ColorBlendValue(0, 0, 0), ColorBlendValue(0, 0, 0), ColorBlendValue(0, 0, 0), ColorBlendValue(-12, 19, 0), ColorBlendValue(0, 0, 0), ColorBlendValue(0, 0, 0)),
            grading = ColorGrading(highlights = ColorGradingZone(0, 0, 0), midtones = ColorGradingZone(0, 0, 0), shadows = ColorGradingZone(0, 0, 0), blending = 50, balance = 0)
        ),
        // Vitality Film <- vitality film pman.NP3（官方以参数+分区定义影调，无独立曲线）
        "01JSBM30XWQ6GG4XK0KSZ3FQEK" to OfficialLook(
            curve = null,
            saturation = 0,
            hue = 0,
            contrast = -4,
            highlights = 54,
            shadows = -17,
            whites = 0,
            blacks = -3,
            sharpening = 3.5f,
            clarity = -1.0f,
            midRangeSharpening = 1.5f,
            blender = listOf(ColorBlendValue(-8, 8, -6), ColorBlendValue(5, 9, 2), ColorBlendValue(-13, -22, -9), ColorBlendValue(85, -44, -53), ColorBlendValue(49, 37, -21), ColorBlendValue(-30, 43, -39), ColorBlendValue(-4, 14, 0), ColorBlendValue(7, 15, -11)),
            grading = ColorGrading(highlights = ColorGradingZone(184, 7, -50), midtones = ColorGradingZone(81, 6, 50), shadows = ColorGradingZone(348, 2, -13), blending = 42, balance = 0)
        )
    )
}
