package com.fujikon.app.nic

/**
 * 资料库附件「云创色彩预设.zip」中 NP3 缩写文件名 ↔ 尼康云创官方 recipe 的对照表。
 *
 * 数据来源：官方目录接口 bff/recipe/release（144 条，含 picture_controll_name）
 * 与详情接口 bff/recipe/release/detail 的参数指纹（contrast / saturation / 锐化三档等）。
 * 105 个 NP3 的名称相似度与官方参数双重吻合；6 个已确认锚点参数 100% 一致。
 *
 * 用途：让内置预置使用与在线导入相同的身份（nic:<recipeId>）与官方全称，
 * 用户再导入同一官方滤镜时由 PresetRepository 按 contentHash 去重，不再出现两个。
 * 缩写名保留在 [CloudOfficialIdentity.aliases]，写入预设说明供搜索继续使用。
 */
internal data class CloudOfficialIdentity(
    val recipeId: String,
    val officialName: String,
    /** 附件中的缩写文件名（含被合并的同方案变体），供说明与搜索。 */
    val aliases: List<String>,
    /** 代表 spec.id，指向保留的真实 NP3 asset。 */
    val specId: String
)

internal object CloudOfficialNames {

    /** 与在线导入一致的内容哈希前缀。 */
    const val HASH_PREFIX = "nic:"

    /** 代表 spec.id -> 官方身份（共 99 个官方方案）。 */
    val BY_SPEC_ID: Map<String, CloudOfficialIdentity> = mapOf(
        "recipe_001" to CloudOfficialIdentity(
            recipeId = "01HXE7766TYK2PVRYGK4Q6W8G0",
            officialName = "Bleach Bypass 01a",
            aliases = listOf("BLEACH PASS 01A"),
            specId = "recipe_001"
        ),
        "recipe_002" to CloudOfficialIdentity(
            recipeId = "01HXE776QDMM2GH3WMB80SJ378",
            officialName = "Color Mode Green 01b",
            aliases = listOf("CLA GN 01B"),
            specId = "recipe_002"
        ),
        "recipe_003" to CloudOfficialIdentity(
            recipeId = "01HXE7775T5BN1SAEG0WSZ6F6E",
            officialName = "Color Mode Blue 01a",
            aliases = listOf("CLR BU 01A"),
            specId = "recipe_003"
        ),
        "recipe_004" to CloudOfficialIdentity(
            recipeId = "01HXE7770AY1EDWX24SKDS67XF",
            officialName = "Color Mode Blue 01b",
            aliases = listOf("CLR BU 01B"),
            specId = "recipe_004"
        ),
        "recipe_005" to CloudOfficialIdentity(
            recipeId = "01HXE776V93CCA109293ABN8QD",
            officialName = "Color Mode Green 01a",
            aliases = listOf("CLR GN 01A"),
            specId = "recipe_005"
        ),
        "recipe_006" to CloudOfficialIdentity(
            recipeId = "01HXE777J72YMSA0W5P06A43E8",
            officialName = "Color Mode Teal&Orange 01a",
            aliases = listOf("CLR TEALOR 01A"),
            specId = "recipe_006"
        ),
        "recipe_007" to CloudOfficialIdentity(
            recipeId = "01HXE777BYRGXHWVQC3405AAS9",
            officialName = "Color Mode Teal&Orange 01b",
            aliases = listOf("CLR TEALOR 01B"),
            specId = "recipe_007"
        ),
        "recipe_008" to CloudOfficialIdentity(
            recipeId = "01HXE776JB5KQ37E7T1JA78S20",
            officialName = "Color Mode Yellow 01a",
            aliases = listOf("CLR YE 01A"),
            specId = "recipe_008"
        ),
        "recipe_009" to CloudOfficialIdentity(
            recipeId = "01HXE776BVJCGYZYCTEPJQP9ZR",
            officialName = "Color Mode Yellow 01b",
            aliases = listOf("CLR YE01B"),
            specId = "recipe_009"
        ),
        "recipe_011" to CloudOfficialIdentity(
            recipeId = "01JMB9VEB2P57A2AP488JM0YEG",
            officialName = "Glow by Ian Harland",
            aliases = listOf("GLOW LANHARLAND", "glow lan harland"),
            specId = "recipe_011"
        ),
        "recipe_012" to CloudOfficialIdentity(
            recipeId = "01JD1PCXMJ2WCCC0NNARQCNAXN",
            officialName = "Kota-Green by TAKASU TILE",
            aliases = listOf("KOTA CREEN TAKA", "kota green taka"),
            specId = "recipe_012"
        ),
        "recipe_014" to CloudOfficialIdentity(
            recipeId = "01JD1PCXC831RAQFETNFMMQSDT",
            officialName = "Matte Blue Gray by tanco",
            aliases = listOf("Mattebluegray"),
            specId = "recipe_014"
        ),
        "recipe_015" to CloudOfficialIdentity(
            recipeId = "01HXE7761WQ0KT24DZ547E1AQD",
            officialName = "Negative Film Yellow&Cyan 01a",
            aliases = listOf("NEG YECY 01A"),
            specId = "recipe_015"
        ),
        "recipe_016" to CloudOfficialIdentity(
            recipeId = "01HXE77A9Z5F3N9C5XV7P60ECZ",
            officialName = "Negative Film Blue 01a",
            aliases = listOf("NEGA BU 01A"),
            specId = "recipe_016"
        ),
        "recipe_017" to CloudOfficialIdentity(
            recipeId = "01HXE77A2K6K6Z4HYFGVMV42TM",
            officialName = "Negative Film Blue 01b",
            aliases = listOf("NEGA BU 01B"),
            specId = "recipe_017"
        ),
        "recipe_018" to CloudOfficialIdentity(
            recipeId = "01HXE775PCFTQPPJAX5ZRBGDTX",
            officialName = "Negative Film Cyan 01a",
            aliases = listOf("NEGA CY 01A", "Negative Cyan01a"),
            specId = "recipe_018"
        ),
        "recipe_019" to CloudOfficialIdentity(
            recipeId = "01HXE779MS5G098EA0EH9M4KZS",
            officialName = "NegativeFilm Cyan&Blue 01a",
            aliases = listOf("NEGA CYBU 01A", "NEGATIVE CY BU", "negative cybu 01a"),
            specId = "recipe_019"
        ),
        "recipe_020" to CloudOfficialIdentity(
            recipeId = "01HXE779AN00JPSQZFFSWFDBAM",
            officialName = "NegativeFilm Cyan&Blue 01b",
            aliases = listOf("NEGA CYBU 01B"),
            specId = "recipe_020"
        ),
        "recipe_021" to CloudOfficialIdentity(
            recipeId = "01HXE779X9YPE9CZD2G07BDK7E",
            officialName = "Negative Film Green&Cyan 01a",
            aliases = listOf("NEGA GNCY 01A"),
            specId = "recipe_021"
        ),
        "recipe_022" to CloudOfficialIdentity(
            recipeId = "01HXE779RR64H3G2SRMWR1FBAC",
            officialName = "NegativeFilm Green&Cyan 01b",
            aliases = listOf("NEGA GNCY 01B"),
            specId = "recipe_022"
        ),
        "recipe_023" to CloudOfficialIdentity(
            recipeId = "01HXE778ZTZW1QE99J8CWXKJD8",
            officialName = "NegativeFilm Red&Cyan 01b",
            aliases = listOf("NEGA RDCY 01B"),
            specId = "recipe_023"
        ),
        "recipe_024" to CloudOfficialIdentity(
            recipeId = "01HXE77966V8FJYVXP1R7CPCWT",
            officialName = "NegativeFilm Red&Cyan 01a",
            aliases = listOf("NEGA RDCY01A"),
            specId = "recipe_024"
        ),
        "recipe_025" to CloudOfficialIdentity(
            recipeId = "01HXE777QD9B1067WMV45CX6PS",
            officialName = "Negative Film Tungsten Color 01b",
            aliases = listOf("NEGA T 01B"),
            specId = "recipe_025"
        ),
        "recipe_026" to CloudOfficialIdentity(
            recipeId = "01HXE777XRC3T3FA8FNCV872H1",
            officialName = "Negative Film Tungsten Color 01a",
            aliases = listOf("NEGA T01A"),
            specId = "recipe_026"
        ),
        "recipe_027" to CloudOfficialIdentity(
            recipeId = "01HXE77AMQFSPRFZKZ24KZ4XDF",
            officialName = "Negative Film Yellow 01a",
            aliases = listOf("NEGA YE 01A"),
            specId = "recipe_027"
        ),
        "recipe_028" to CloudOfficialIdentity(
            recipeId = "01HXE77AEVSPHAHFRCS43W73AR",
            officialName = "Negative Film Yellow 01b",
            aliases = listOf("NEGA YE 01B"),
            specId = "recipe_028"
        ),
        "recipe_029" to CloudOfficialIdentity(
            recipeId = "01HXE778TTF37VQ4ZE67GAX70G",
            officialName = "Negative Film Yellow 02a",
            aliases = listOf("NEGA YE 02A"),
            specId = "recipe_029"
        ),
        "recipe_030" to CloudOfficialIdentity(
            recipeId = "01HXE778Q9JW9N49XFQ90F5PQP",
            officialName = "Negative Film Yellow 02b",
            aliases = listOf("NEGA YE 02B"),
            specId = "recipe_030"
        ),
        "recipe_031" to CloudOfficialIdentity(
            recipeId = "01HXE778JYGDFQY9X9AP8H4G18",
            officialName = "Negative Film Yellow 03a",
            aliases = listOf("NEGA YE 03A"),
            specId = "recipe_031"
        ),
        "recipe_032" to CloudOfficialIdentity(
            recipeId = "01HXE778FAMYVVJMKMQNF56YDG",
            officialName = "Negative Film Yellow 03b",
            aliases = listOf("NEGA YE 03B"),
            specId = "recipe_032"
        ),
        "recipe_033" to CloudOfficialIdentity(
            recipeId = "01HXE775X8CNFGMNTPMYZ0QYMD",
            officialName = "Negative Film Yellow&Cyan 02a",
            aliases = listOf("NEGA YECY 02A"),
            specId = "recipe_033"
        ),
        "recipe_034" to CloudOfficialIdentity(
            recipeId = "01HXE7788BQFWRRP7NDXC156AV",
            officialName = "Negative Film Yellow&Green 01a",
            aliases = listOf("NEGA YEGN 01A"),
            specId = "recipe_034"
        ),
        "recipe_035" to CloudOfficialIdentity(
            recipeId = "01HXE778320VAQMVG02C5ASZ5N",
            officialName = "NegativeFilm Yellow&Green 01b",
            aliases = listOf("NEGA YEGN 01B"),
            specId = "recipe_035"
        ),
        "recipe_038" to CloudOfficialIdentity(
            recipeId = "01JMB9VD4MYTCWB9G2G0FVAXRX",
            officialName = "Air Green by Takumi Gunji",
            aliases = listOf("Ari Green Gunji"),
            specId = "recipe_038"
        ),
        "recipe_039" to CloudOfficialIdentity(
            recipeId = "01JMB9VDG9D326HJ9KF7RRH0SF",
            officialName = "At Dusk by Noise Woo",
            aliases = listOf("At Dusk Woo"),
            specId = "recipe_039"
        ),
        "recipe_040" to CloudOfficialIdentity(
            recipeId = "01JAWAKHWW7ZN6RNX3Z6E05GFN",
            officialName = "Blue Hour by Luna",
            aliases = listOf("Bluehour Luna"),
            specId = "recipe_040"
        ),
        "recipe_041" to CloudOfficialIdentity(
            recipeId = "01HXE5FNHXJQ3VNP2JP2QN2GFD",
            officialName = "Blue Mood by LUIZCLAS",
            aliases = listOf("Bluem Luizclas"),
            specId = "recipe_041"
        ),
        "recipe_042" to CloudOfficialIdentity(
            recipeId = "01JMB9VCN3ZQHNTG9NFJTW0RRH",
            officialName = "British Film Black and White by Vincent Versace",
            aliases = listOf("Britfilm Bw"),
            specId = "recipe_042"
        ),
        "recipe_043" to CloudOfficialIdentity(
            recipeId = "01JSBM3004K48WEHF7JRSGCX9J",
            officialName = "Call It Love by Faloo Mi",
            aliases = listOf("Calllt Love Faloo M"),
            specId = "recipe_043"
        ),
        "recipe_044" to CloudOfficialIdentity(
            recipeId = "01JD1PCZCT6ZJPHZDVYQF4EG5K",
            officialName = "Calm Breeze by Dondre Green",
            aliases = listOf("Calmbreeze Dond"),
            specId = "recipe_044"
        ),
        "recipe_045" to CloudOfficialIdentity(
            recipeId = "01HXE5FPBPHS9GGHQCP3QXCAH9",
            officialName = "Cinematic Blue by Brandon Woelfel",
            aliases = listOf("Cineblue Brandon"),
            specId = "recipe_045"
        ),
        "recipe_046" to CloudOfficialIdentity(
            recipeId = "01HXE5FND66VMWD4MGJBRC2ETV",
            officialName = "Cine Mood by LUIZCLAS",
            aliases = listOf("Cinem Luizclas"),
            specId = "recipe_046"
        ),
        "recipe_047" to CloudOfficialIdentity(
            recipeId = "01J6VDR8G88PECXHW5969JYHRG",
            officialName = "Cloudy Forest by Y2",
            aliases = listOf("Cloudy Forest Y2"),
            specId = "recipe_047"
        ),
        "recipe_048" to CloudOfficialIdentity(
            recipeId = "01J6VDR8P897ZN0E3BJFYRKRAQ",
            officialName = "Clear Portrait by Takahiro Sakai",
            aliases = listOf("clrportrait tsakai"),
            specId = "recipe_048"
        ),
        "recipe_049" to CloudOfficialIdentity(
            recipeId = "01JSBM30N55AJV4SKKHFHGYX2H",
            officialName = "Coastal Glow by Najmuddin Muif",
            aliases = listOf("Coastal Glow Nm"),
            specId = "recipe_049"
        ),
        "recipe_050" to CloudOfficialIdentity(
            recipeId = "01JD1PCY1QC69XMZ4PBG7T38ND",
            officialName = "Color Splash by Gilmar Smith",
            aliases = listOf("Colorsplash Gilmar"),
            specId = "recipe_050"
        ),
        "recipe_051" to CloudOfficialIdentity(
            recipeId = "01JD1PCYRNCBES85KAZR2PG56G",
            officialName = "Cozy Autumn by Leno Cainelli",
            aliases = listOf("Cozy Autumn Leno"),
            specId = "recipe_051"
        ),
        "recipe_052" to CloudOfficialIdentity(
            recipeId = "01JD1PCYMSJVRHS9XB7DDE1YF5",
            officialName = "Dawn Hues by Leno Cainelli",
            aliases = listOf("Dawnhues Leno"),
            specId = "recipe_052"
        ),
        "recipe_053" to CloudOfficialIdentity(
            recipeId = "01HXE5FP0GF51ZP6W7H9Y249P5",
            officialName = "DG Film Look by Danny Gevirtz",
            aliases = listOf("Dgfilmlook Danny"),
            specId = "recipe_053"
        ),
        "recipe_054" to CloudOfficialIdentity(
            recipeId = "01JMB9VE78WVP9BBVFVCDPYT44",
            officialName = "Fern by Ian Harland",
            aliases = listOf("Fern Lanharland"),
            specId = "recipe_054"
        ),
        "recipe_055" to CloudOfficialIdentity(
            recipeId = "01JSBM2ZVF6SX4VSEK76K1VGJQ",
            officialName = "Filmic by Fabio Oliveira",
            aliases = listOf("Filmic Fabio"),
            specId = "recipe_055"
        ),
        "recipe_056" to CloudOfficialIdentity(
            recipeId = "01JD1PCYWNRYS94FZEKD85Z38M",
            officialName = "Film Pop by Leno Cainelli",
            aliases = listOf("Filmpop Leno"),
            specId = "recipe_056"
        ),
        "recipe_058" to CloudOfficialIdentity(
            recipeId = "01JMB9VEJDVDDTRBMNRQT2WQDB",
            officialName = "Glow Up by Preston Luke",
            aliases = listOf("glowup preston"),
            specId = "recipe_058"
        ),
        "recipe_059" to CloudOfficialIdentity(
            recipeId = "01HXE5FNPN749Q8MNKH5GAFFJE",
            officialName = "Golden Dusk by Emilie Hill",
            aliases = listOf("Goldendusk Emili"),
            specId = "recipe_059"
        ),
        "recipe_060" to CloudOfficialIdentity(
            recipeId = "01JD1PD000JN9FZWKG2CFFBTZV",
            officialName = "Hazy Memories by Julia Nimke",
            aliases = listOf("Hazy Memories Ju"),
            specId = "recipe_060"
        ),
        "recipe_061" to CloudOfficialIdentity(
            recipeId = "01JSBM2ZNK7MH02S5HK9XXGGHZ",
            officialName = "Heartwarming by Yohei Sawamura",
            aliases = listOf("Heartwarming Yoh"),
            specId = "recipe_061"
        ),
        "recipe_062" to CloudOfficialIdentity(
            recipeId = "01JD1PCXH2HKADD09AZZ80CN30",
            officialName = "Hidamari Color by Haruka Koharu",
            aliases = listOf("Hidanaei Hanuka"),
            specId = "recipe_062"
        ),
        "recipe_063" to CloudOfficialIdentity(
            recipeId = "01JSBM30C161CNK36288WS581P",
            officialName = "HK Reminiscence by Hiuman Lam",
            aliases = listOf("Hk Reminisce Hiu"),
            specId = "recipe_063"
        ),
        "recipe_064" to CloudOfficialIdentity(
            recipeId = "01JMB9VE4FKTTX53NZCCK26YE2",
            officialName = "Honey by Ian Harland",
            aliases = listOf("Honey Lan Harland"),
            specId = "recipe_064"
        ),
        "recipe_065" to CloudOfficialIdentity(
            recipeId = "01JAWAKH9G5P4EGMQ4CSJPYYFV",
            officialName = "HT Harmony by Hanaa Turkistani",
            aliases = listOf("Ht Harmony Hana"),
            specId = "recipe_065"
        ),
        "recipe_066" to CloudOfficialIdentity(
            recipeId = "01JD1PCZVJ3EVDD5AAHH0239VB",
            officialName = "Indoor Harmony by Julia Nimke",
            aliases = listOf("Indoorharmony Ju"),
            specId = "recipe_066"
        ),
        "recipe_067" to CloudOfficialIdentity(
            recipeId = "01JSBM2ZEX270CEBQA94M3MKCC",
            officialName = "Japanesque Film by Taishi Arashida",
            aliases = listOf("Japanesque Aras"),
            specId = "recipe_067"
        ),
        "recipe_069" to CloudOfficialIdentity(
            recipeId = "01JMB9VENWYEJXETAGCVZX1BRN",
            officialName = "Midnight Blush by Preston Luke",
            aliases = listOf("Mninght Blush"),
            specId = "recipe_069"
        ),
        "recipe_070" to CloudOfficialIdentity(
            recipeId = "01JMB9VDT6N0J0KX3XMHA9XK0K",
            officialName = "Moody Greens by Brittany Eliza Kunkel",
            aliases = listOf("Moody Gr Brittanyk"),
            specId = "recipe_070"
        ),
        "recipe_071" to CloudOfficialIdentity(
            recipeId = "01JD1PCXWTXA42DCNKZ6QYHV5V",
            officialName = "Moody Shadows by Gilmar Smith",
            aliases = listOf("Moodyshadows G", "Moodyshadows"),
            specId = "recipe_071"
        ),
        "recipe_073" to CloudOfficialIdentity(
            recipeId = "01JMB9VDBRTE5H7ACV40V179F2",
            officialName = "Movie Green Orange by Noise Woo",
            aliases = listOf("Mv Green Orange"),
            specId = "recipe_073"
        ),
        "recipe_074" to CloudOfficialIdentity(
            recipeId = "01JAWAKHJ0DG7KCBY6BNAW4271",
            officialName = "Nature by Links",
            aliases = listOf("Nature Links"),
            specId = "recipe_074"
        ),
        "recipe_076" to CloudOfficialIdentity(
            recipeId = "01HXE5FP854H5ZK91KQEKKFZ07",
            officialName = "Neon Glow by Brandon Woelfel",
            aliases = listOf("Neon Glow Brando"),
            specId = "recipe_076"
        ),
        "recipe_077" to CloudOfficialIdentity(
            recipeId = "01JD1PCZ7JVX56RTKPKD3MSDQ3",
            officialName = "Nights Like This by Dondre Green",
            aliases = listOf("Nighta Like Dondre"),
            specId = "recipe_077"
        ),
        "recipe_078" to CloudOfficialIdentity(
            recipeId = "01JD1PCY7SZNNA1QQ9R1C6MP6H",
            officialName = "Painterly Light by D'Ann Boal",
            aliases = listOf("Painterly Light Da"),
            specId = "recipe_078"
        ),
        "recipe_079" to CloudOfficialIdentity(
            recipeId = "01JMB9VD7W35ZE9HC8SNVTD65B",
            officialName = "Pastelpink Portrait by Yuki Hirotani",
            aliases = listOf("Pastelpink Yuki"),
            specId = "recipe_079"
        ),
        "recipe_080" to CloudOfficialIdentity(
            recipeId = "01JD1PCXSJWWAEJWDN5WQD64Q7",
            officialName = "Primary Pop by Gilmar Smith",
            aliases = listOf("Primarypop Gilmar"),
            specId = "recipe_080"
        ),
        "recipe_081" to CloudOfficialIdentity(
            recipeId = "01HXE5FN2K5ZZHMG9WYMYHNH9T",
            officialName = "Red Mood by LUIZCLAS",
            aliases = listOf("Redm Luizclas"),
            specId = "recipe_081"
        ),
        "recipe_082" to CloudOfficialIdentity(
            recipeId = "01JSBM2Z2DWYYZMBNQDQ9HTHPZ",
            officialName = "Rose Old Film by Fujikawa hinano",
            aliases = listOf("Rose Old Film Hina"),
            specId = "recipe_082"
        ),
        "recipe_083" to CloudOfficialIdentity(
            recipeId = "01J6VDR91SGX7PJB31RHKBA03P",
            officialName = "Setouchi Blue by Noci Kosho",
            aliases = listOf("Setouchiblue Noci"),
            specId = "recipe_083"
        ),
        "recipe_084" to CloudOfficialIdentity(
            recipeId = "01JMB9VDXP5CZ9K4F26G4YEN6Q",
            officialName = "Shades of Fall by Brittany Eliza Kunkel",
            aliases = listOf("Shadefall Brittany"),
            specId = "recipe_084"
        ),
        "recipe_085" to CloudOfficialIdentity(
            recipeId = "01JSBM2ZAPT0J374SWW93WKKQX",
            officialName = "Sky Mist Film by Fujikawa hinano",
            aliases = listOf("Sky Mistfilm Hinano"),
            specId = "recipe_085"
        ),
        "recipe_086" to CloudOfficialIdentity(
            recipeId = "01JSBM311F0ZRVVKAGZFCFK5CW",
            officialName = "Soft Vivid by Vijay Eesam",
            aliases = listOf("Soft Vivid Vijay"),
            specId = "recipe_086"
        ),
        "recipe_087" to CloudOfficialIdentity(
            recipeId = "01JD1PCZ1NPAX794XEKESGRH2J",
            officialName = "Soft Film by Leno Cainelli",
            aliases = listOf("Softfilm Leno"),
            specId = "recipe_087"
        ),
        "recipe_088" to CloudOfficialIdentity(
            recipeId = "01JSBM307K7JMYSFVKW44VMQTY",
            officialName = "The SOFT GLOW AURA by Harpreet Singh",
            aliases = listOf("Softglow Harpreet"),
            specId = "recipe_088"
        ),
        "recipe_089" to CloudOfficialIdentity(
            recipeId = "01JD1PCZNP54WXSHZYMFPSG0Y2",
            officialName = "Summertime Shine by Dondre Green",
            aliases = listOf("Summershine Do"),
            specId = "recipe_089"
        ),
        "recipe_090" to CloudOfficialIdentity(
            recipeId = "01JD1PD03DGSKT94S8DZRP7AP0",
            officialName = "Sunkissed Cool by Julia Nimke",
            aliases = listOf("Sunkissedcool"),
            specId = "recipe_090"
        ),
        "recipe_091" to CloudOfficialIdentity(
            recipeId = "01JD1PD06VNC5S3ED7EDKK1AMM",
            officialName = "Sunkissed Warmth by Julia Nimke",
            aliases = listOf("Sunkisssedwarm"),
            specId = "recipe_091"
        ),
        "recipe_092" to CloudOfficialIdentity(
            recipeId = "01JD1PCY4MKBH4NXB97ZW5B679",
            officialName = "Sunlight & Happiness by D'Ann Boal",
            aliases = listOf("Sunlight Happy Da"),
            specId = "recipe_092"
        ),
        "recipe_093" to CloudOfficialIdentity(
            recipeId = "01JSBM30GMSQB908F9P1W09QCS",
            officialName = "Sunset Film Portrait by Jatenipat Ketpradit (JKBoy)",
            aliases = listOf("Sunsetfilm Jkboy"),
            specId = "recipe_093"
        ),
        "recipe_094" to CloudOfficialIdentity(
            recipeId = "01JD1PCX8Y93G7AMHF0SB2KSCT",
            officialName = "Sunset Glow by Amanatsu Mikan",
            aliases = listOf("Sunsetglow Mikan"),
            specId = "recipe_094"
        ),
        "recipe_095" to CloudOfficialIdentity(
            recipeId = "01JD1PCYJCV7PJAKWQ1JBQCTN7",
            officialName = "Sunset Reset by Baek Jaekwang",
            aliases = listOf("Sunsetreaet Baek"),
            specId = "recipe_095"
        ),
        "recipe_096" to CloudOfficialIdentity(
            recipeId = "01HXE5FP3WYNBFPBS14AKGR9CV",
            officialName = "Teal & Orange by Brandon Woelfel",
            aliases = listOf("T&o Brandonw"),
            specId = "recipe_096"
        ),
        "recipe_097" to CloudOfficialIdentity(
            recipeId = "01JD1PCX4339YR090MMN19NP7F",
            officialName = "Tender Clear by Fuka Kamata",
            aliases = listOf("Tenderclera"),
            specId = "recipe_097"
        ),
        "recipe_098" to CloudOfficialIdentity(
            recipeId = "01JAWAKHQ8B1J3TXXQSNTQGC5H",
            officialName = "Textured Documentary by MediaStorm",
            aliases = listOf("Textured Docmntry"),
            specId = "recipe_098"
        ),
        "recipe_099" to CloudOfficialIdentity(
            recipeId = "01JD1PCWXCJMXA8207GAAKB701",
            officialName = "Turquoise Blue by Yuki Hirotani",
            aliases = listOf("Turquoise Blue Yu"),
            specId = "recipe_099"
        ),
        "recipe_100" to CloudOfficialIdentity(
            recipeId = "01JMB9VE11JMA47FD2NC94J5A9",
            officialName = "Vintage Autumn by Brittany Eliza Kunkel",
            aliases = listOf("Vintage A Brittanyk"),
            specId = "recipe_100"
        ),
        "recipe_101" to CloudOfficialIdentity(
            recipeId = "01HXE5FMRFHVM4S2P78B1K15AM",
            officialName = "Vintage Mellow by Takahiro Sakai",
            aliases = listOf("Vintagem Tsakai"),
            specId = "recipe_101"
        ),
        "recipe_102" to CloudOfficialIdentity(
            recipeId = "01JMB9VEFB835A5N232FVEWHA3",
            officialName = "Vintage Vibes by Preston Luke",
            aliases = listOf("Vintagevibe Prest"),
            specId = "recipe_102"
        ),
        "recipe_103" to CloudOfficialIdentity(
            recipeId = "01JSBM30XWQ6GG4XK0KSZ3FQEK",
            officialName = "Vitality Film by Peng Mango",
            aliases = listOf("Vitality Film Pman"),
            specId = "recipe_103"
        ),
        "recipe_104" to CloudOfficialIdentity(
            recipeId = "01JSBM30T7ZBDW1M9YQ09MNN63",
            officialName = "Warm Street by Ben",
            aliases = listOf("Warm Street Ben"),
            specId = "recipe_104"
        ),
        "recipe_105" to CloudOfficialIdentity(
            recipeId = "01JAWAKJ0QXZDSWW9E11TH419Z",
            officialName = "Warm-toned dim lighting by Luna",
            aliases = listOf("Warmdimlight Lu"),
            specId = "recipe_105"
        ),
        "recipe_106" to CloudOfficialIdentity(
            recipeId = "01J6VDR8901GMQMP1JA12XZ2SX",
            officialName = "Warm Portrait by Y2",
            aliases = listOf("Warmportrait Y2"),
            specId = "recipe_106"
        ),
        "recipe_107" to CloudOfficialIdentity(
            recipeId = "01JMB9VDKSX2WP766PMPVD0CY2",
            officialName = "Warm-toned Portrait by Wazika",
            aliases = listOf("Warmtoned Ptt"),
            specId = "recipe_107"
        ),
    )

    /** 被合并的重复导出 spec.id -> 代表 spec.id（共 6 个）。 */
    val MERGED_INTO: Map<String, String> = mapOf(
        "recipe_036" to "recipe_019",
        "recipe_037" to "recipe_018",
        "recipe_057" to "recipe_011",
        "recipe_068" to "recipe_012",
        "recipe_072" to "recipe_071",
        "recipe_075" to "recipe_019",
    )

    /** 无官方对应、保持独立的 spec.id。 */
    val INDEPENDENT_IDS: Set<String> = setOf(
        "recipe_010",
        "recipe_013",
    )

    /** 任意 spec.id（含被合并的）-> 官方身份，供覆盖安装迁移旧预置。 */
    val IDENTITY_FOR_ANY_SPEC: Map<String, CloudOfficialIdentity> = mapOf(
        "recipe_001" to BY_SPEC_ID.getValue("recipe_001"),
        "recipe_002" to BY_SPEC_ID.getValue("recipe_002"),
        "recipe_003" to BY_SPEC_ID.getValue("recipe_003"),
        "recipe_004" to BY_SPEC_ID.getValue("recipe_004"),
        "recipe_005" to BY_SPEC_ID.getValue("recipe_005"),
        "recipe_006" to BY_SPEC_ID.getValue("recipe_006"),
        "recipe_007" to BY_SPEC_ID.getValue("recipe_007"),
        "recipe_008" to BY_SPEC_ID.getValue("recipe_008"),
        "recipe_009" to BY_SPEC_ID.getValue("recipe_009"),
        "recipe_011" to BY_SPEC_ID.getValue("recipe_011"),
        "recipe_012" to BY_SPEC_ID.getValue("recipe_012"),
        "recipe_014" to BY_SPEC_ID.getValue("recipe_014"),
        "recipe_015" to BY_SPEC_ID.getValue("recipe_015"),
        "recipe_016" to BY_SPEC_ID.getValue("recipe_016"),
        "recipe_017" to BY_SPEC_ID.getValue("recipe_017"),
        "recipe_018" to BY_SPEC_ID.getValue("recipe_018"),
        "recipe_019" to BY_SPEC_ID.getValue("recipe_019"),
        "recipe_020" to BY_SPEC_ID.getValue("recipe_020"),
        "recipe_021" to BY_SPEC_ID.getValue("recipe_021"),
        "recipe_022" to BY_SPEC_ID.getValue("recipe_022"),
        "recipe_023" to BY_SPEC_ID.getValue("recipe_023"),
        "recipe_024" to BY_SPEC_ID.getValue("recipe_024"),
        "recipe_025" to BY_SPEC_ID.getValue("recipe_025"),
        "recipe_026" to BY_SPEC_ID.getValue("recipe_026"),
        "recipe_027" to BY_SPEC_ID.getValue("recipe_027"),
        "recipe_028" to BY_SPEC_ID.getValue("recipe_028"),
        "recipe_029" to BY_SPEC_ID.getValue("recipe_029"),
        "recipe_030" to BY_SPEC_ID.getValue("recipe_030"),
        "recipe_031" to BY_SPEC_ID.getValue("recipe_031"),
        "recipe_032" to BY_SPEC_ID.getValue("recipe_032"),
        "recipe_033" to BY_SPEC_ID.getValue("recipe_033"),
        "recipe_034" to BY_SPEC_ID.getValue("recipe_034"),
        "recipe_035" to BY_SPEC_ID.getValue("recipe_035"),
        "recipe_036" to BY_SPEC_ID.getValue("recipe_019"),
        "recipe_037" to BY_SPEC_ID.getValue("recipe_018"),
        "recipe_038" to BY_SPEC_ID.getValue("recipe_038"),
        "recipe_039" to BY_SPEC_ID.getValue("recipe_039"),
        "recipe_040" to BY_SPEC_ID.getValue("recipe_040"),
        "recipe_041" to BY_SPEC_ID.getValue("recipe_041"),
        "recipe_042" to BY_SPEC_ID.getValue("recipe_042"),
        "recipe_043" to BY_SPEC_ID.getValue("recipe_043"),
        "recipe_044" to BY_SPEC_ID.getValue("recipe_044"),
        "recipe_045" to BY_SPEC_ID.getValue("recipe_045"),
        "recipe_046" to BY_SPEC_ID.getValue("recipe_046"),
        "recipe_047" to BY_SPEC_ID.getValue("recipe_047"),
        "recipe_048" to BY_SPEC_ID.getValue("recipe_048"),
        "recipe_049" to BY_SPEC_ID.getValue("recipe_049"),
        "recipe_050" to BY_SPEC_ID.getValue("recipe_050"),
        "recipe_051" to BY_SPEC_ID.getValue("recipe_051"),
        "recipe_052" to BY_SPEC_ID.getValue("recipe_052"),
        "recipe_053" to BY_SPEC_ID.getValue("recipe_053"),
        "recipe_054" to BY_SPEC_ID.getValue("recipe_054"),
        "recipe_055" to BY_SPEC_ID.getValue("recipe_055"),
        "recipe_056" to BY_SPEC_ID.getValue("recipe_056"),
        "recipe_057" to BY_SPEC_ID.getValue("recipe_011"),
        "recipe_058" to BY_SPEC_ID.getValue("recipe_058"),
        "recipe_059" to BY_SPEC_ID.getValue("recipe_059"),
        "recipe_060" to BY_SPEC_ID.getValue("recipe_060"),
        "recipe_061" to BY_SPEC_ID.getValue("recipe_061"),
        "recipe_062" to BY_SPEC_ID.getValue("recipe_062"),
        "recipe_063" to BY_SPEC_ID.getValue("recipe_063"),
        "recipe_064" to BY_SPEC_ID.getValue("recipe_064"),
        "recipe_065" to BY_SPEC_ID.getValue("recipe_065"),
        "recipe_066" to BY_SPEC_ID.getValue("recipe_066"),
        "recipe_067" to BY_SPEC_ID.getValue("recipe_067"),
        "recipe_068" to BY_SPEC_ID.getValue("recipe_012"),
        "recipe_069" to BY_SPEC_ID.getValue("recipe_069"),
        "recipe_070" to BY_SPEC_ID.getValue("recipe_070"),
        "recipe_071" to BY_SPEC_ID.getValue("recipe_071"),
        "recipe_072" to BY_SPEC_ID.getValue("recipe_071"),
        "recipe_073" to BY_SPEC_ID.getValue("recipe_073"),
        "recipe_074" to BY_SPEC_ID.getValue("recipe_074"),
        "recipe_075" to BY_SPEC_ID.getValue("recipe_019"),
        "recipe_076" to BY_SPEC_ID.getValue("recipe_076"),
        "recipe_077" to BY_SPEC_ID.getValue("recipe_077"),
        "recipe_078" to BY_SPEC_ID.getValue("recipe_078"),
        "recipe_079" to BY_SPEC_ID.getValue("recipe_079"),
        "recipe_080" to BY_SPEC_ID.getValue("recipe_080"),
        "recipe_081" to BY_SPEC_ID.getValue("recipe_081"),
        "recipe_082" to BY_SPEC_ID.getValue("recipe_082"),
        "recipe_083" to BY_SPEC_ID.getValue("recipe_083"),
        "recipe_084" to BY_SPEC_ID.getValue("recipe_084"),
        "recipe_085" to BY_SPEC_ID.getValue("recipe_085"),
        "recipe_086" to BY_SPEC_ID.getValue("recipe_086"),
        "recipe_087" to BY_SPEC_ID.getValue("recipe_087"),
        "recipe_088" to BY_SPEC_ID.getValue("recipe_088"),
        "recipe_089" to BY_SPEC_ID.getValue("recipe_089"),
        "recipe_090" to BY_SPEC_ID.getValue("recipe_090"),
        "recipe_091" to BY_SPEC_ID.getValue("recipe_091"),
        "recipe_092" to BY_SPEC_ID.getValue("recipe_092"),
        "recipe_093" to BY_SPEC_ID.getValue("recipe_093"),
        "recipe_094" to BY_SPEC_ID.getValue("recipe_094"),
        "recipe_095" to BY_SPEC_ID.getValue("recipe_095"),
        "recipe_096" to BY_SPEC_ID.getValue("recipe_096"),
        "recipe_097" to BY_SPEC_ID.getValue("recipe_097"),
        "recipe_098" to BY_SPEC_ID.getValue("recipe_098"),
        "recipe_099" to BY_SPEC_ID.getValue("recipe_099"),
        "recipe_100" to BY_SPEC_ID.getValue("recipe_100"),
        "recipe_101" to BY_SPEC_ID.getValue("recipe_101"),
        "recipe_102" to BY_SPEC_ID.getValue("recipe_102"),
        "recipe_103" to BY_SPEC_ID.getValue("recipe_103"),
        "recipe_104" to BY_SPEC_ID.getValue("recipe_104"),
        "recipe_105" to BY_SPEC_ID.getValue("recipe_105"),
        "recipe_106" to BY_SPEC_ID.getValue("recipe_106"),
        "recipe_107" to BY_SPEC_ID.getValue("recipe_107"),
    )

    /** 官方 recipeId -> 代表 spec.id，供升级用户此前导入的失真条目。 */
    val SPEC_BY_RECIPE_ID: Map<String, String> = mapOf(
        "01HXE5FMRFHVM4S2P78B1K15AM" to "recipe_101",
        "01HXE5FN2K5ZZHMG9WYMYHNH9T" to "recipe_081",
        "01HXE5FND66VMWD4MGJBRC2ETV" to "recipe_046",
        "01HXE5FNHXJQ3VNP2JP2QN2GFD" to "recipe_041",
        "01HXE5FNPN749Q8MNKH5GAFFJE" to "recipe_059",
        "01HXE5FP0GF51ZP6W7H9Y249P5" to "recipe_053",
        "01HXE5FP3WYNBFPBS14AKGR9CV" to "recipe_096",
        "01HXE5FP854H5ZK91KQEKKFZ07" to "recipe_076",
        "01HXE5FPBPHS9GGHQCP3QXCAH9" to "recipe_045",
        "01HXE775PCFTQPPJAX5ZRBGDTX" to "recipe_018",
        "01HXE775X8CNFGMNTPMYZ0QYMD" to "recipe_033",
        "01HXE7761WQ0KT24DZ547E1AQD" to "recipe_015",
        "01HXE7766TYK2PVRYGK4Q6W8G0" to "recipe_001",
        "01HXE776BVJCGYZYCTEPJQP9ZR" to "recipe_009",
        "01HXE776JB5KQ37E7T1JA78S20" to "recipe_008",
        "01HXE776QDMM2GH3WMB80SJ378" to "recipe_002",
        "01HXE776V93CCA109293ABN8QD" to "recipe_005",
        "01HXE7770AY1EDWX24SKDS67XF" to "recipe_004",
        "01HXE7775T5BN1SAEG0WSZ6F6E" to "recipe_003",
        "01HXE777BYRGXHWVQC3405AAS9" to "recipe_007",
        "01HXE777J72YMSA0W5P06A43E8" to "recipe_006",
        "01HXE777QD9B1067WMV45CX6PS" to "recipe_025",
        "01HXE777XRC3T3FA8FNCV872H1" to "recipe_026",
        "01HXE778320VAQMVG02C5ASZ5N" to "recipe_035",
        "01HXE7788BQFWRRP7NDXC156AV" to "recipe_034",
        "01HXE778FAMYVVJMKMQNF56YDG" to "recipe_032",
        "01HXE778JYGDFQY9X9AP8H4G18" to "recipe_031",
        "01HXE778Q9JW9N49XFQ90F5PQP" to "recipe_030",
        "01HXE778TTF37VQ4ZE67GAX70G" to "recipe_029",
        "01HXE778ZTZW1QE99J8CWXKJD8" to "recipe_023",
        "01HXE77966V8FJYVXP1R7CPCWT" to "recipe_024",
        "01HXE779AN00JPSQZFFSWFDBAM" to "recipe_020",
        "01HXE779MS5G098EA0EH9M4KZS" to "recipe_019",
        "01HXE779RR64H3G2SRMWR1FBAC" to "recipe_022",
        "01HXE779X9YPE9CZD2G07BDK7E" to "recipe_021",
        "01HXE77A2K6K6Z4HYFGVMV42TM" to "recipe_017",
        "01HXE77A9Z5F3N9C5XV7P60ECZ" to "recipe_016",
        "01HXE77AEVSPHAHFRCS43W73AR" to "recipe_028",
        "01HXE77AMQFSPRFZKZ24KZ4XDF" to "recipe_027",
        "01J6VDR8901GMQMP1JA12XZ2SX" to "recipe_106",
        "01J6VDR8G88PECXHW5969JYHRG" to "recipe_047",
        "01J6VDR8P897ZN0E3BJFYRKRAQ" to "recipe_048",
        "01J6VDR91SGX7PJB31RHKBA03P" to "recipe_083",
        "01JAWAKH9G5P4EGMQ4CSJPYYFV" to "recipe_065",
        "01JAWAKHJ0DG7KCBY6BNAW4271" to "recipe_074",
        "01JAWAKHQ8B1J3TXXQSNTQGC5H" to "recipe_098",
        "01JAWAKHWW7ZN6RNX3Z6E05GFN" to "recipe_040",
        "01JAWAKJ0QXZDSWW9E11TH419Z" to "recipe_105",
        "01JD1PCWXCJMXA8207GAAKB701" to "recipe_099",
        "01JD1PCX4339YR090MMN19NP7F" to "recipe_097",
        "01JD1PCX8Y93G7AMHF0SB2KSCT" to "recipe_094",
        "01JD1PCXC831RAQFETNFMMQSDT" to "recipe_014",
        "01JD1PCXH2HKADD09AZZ80CN30" to "recipe_062",
        "01JD1PCXMJ2WCCC0NNARQCNAXN" to "recipe_012",
        "01JD1PCXSJWWAEJWDN5WQD64Q7" to "recipe_080",
        "01JD1PCXWTXA42DCNKZ6QYHV5V" to "recipe_071",
        "01JD1PCY1QC69XMZ4PBG7T38ND" to "recipe_050",
        "01JD1PCY4MKBH4NXB97ZW5B679" to "recipe_092",
        "01JD1PCY7SZNNA1QQ9R1C6MP6H" to "recipe_078",
        "01JD1PCYJCV7PJAKWQ1JBQCTN7" to "recipe_095",
        "01JD1PCYMSJVRHS9XB7DDE1YF5" to "recipe_052",
        "01JD1PCYRNCBES85KAZR2PG56G" to "recipe_051",
        "01JD1PCYWNRYS94FZEKD85Z38M" to "recipe_056",
        "01JD1PCZ1NPAX794XEKESGRH2J" to "recipe_087",
        "01JD1PCZ7JVX56RTKPKD3MSDQ3" to "recipe_077",
        "01JD1PCZCT6ZJPHZDVYQF4EG5K" to "recipe_044",
        "01JD1PCZNP54WXSHZYMFPSG0Y2" to "recipe_089",
        "01JD1PCZVJ3EVDD5AAHH0239VB" to "recipe_066",
        "01JD1PD000JN9FZWKG2CFFBTZV" to "recipe_060",
        "01JD1PD03DGSKT94S8DZRP7AP0" to "recipe_090",
        "01JD1PD06VNC5S3ED7EDKK1AMM" to "recipe_091",
        "01JMB9VCN3ZQHNTG9NFJTW0RRH" to "recipe_042",
        "01JMB9VD4MYTCWB9G2G0FVAXRX" to "recipe_038",
        "01JMB9VD7W35ZE9HC8SNVTD65B" to "recipe_079",
        "01JMB9VDBRTE5H7ACV40V179F2" to "recipe_073",
        "01JMB9VDG9D326HJ9KF7RRH0SF" to "recipe_039",
        "01JMB9VDKSX2WP766PMPVD0CY2" to "recipe_107",
        "01JMB9VDT6N0J0KX3XMHA9XK0K" to "recipe_070",
        "01JMB9VDXP5CZ9K4F26G4YEN6Q" to "recipe_084",
        "01JMB9VE11JMA47FD2NC94J5A9" to "recipe_100",
        "01JMB9VE4FKTTX53NZCCK26YE2" to "recipe_064",
        "01JMB9VE78WVP9BBVFVCDPYT44" to "recipe_054",
        "01JMB9VEB2P57A2AP488JM0YEG" to "recipe_011",
        "01JMB9VEFB835A5N232FVEWHA3" to "recipe_102",
        "01JMB9VEJDVDDTRBMNRQT2WQDB" to "recipe_058",
        "01JMB9VENWYEJXETAGCVZX1BRN" to "recipe_069",
        "01JSBM2Z2DWYYZMBNQDQ9HTHPZ" to "recipe_082",
        "01JSBM2ZAPT0J374SWW93WKKQX" to "recipe_085",
        "01JSBM2ZEX270CEBQA94M3MKCC" to "recipe_067",
        "01JSBM2ZNK7MH02S5HK9XXGGHZ" to "recipe_061",
        "01JSBM2ZVF6SX4VSEK76K1VGJQ" to "recipe_055",
        "01JSBM3004K48WEHF7JRSGCX9J" to "recipe_043",
        "01JSBM307K7JMYSFVKW44VMQTY" to "recipe_088",
        "01JSBM30C161CNK36288WS581P" to "recipe_063",
        "01JSBM30GMSQB908F9P1W09QCS" to "recipe_093",
        "01JSBM30N55AJV4SKKHFHGYX2H" to "recipe_049",
        "01JSBM30T7ZBDW1M9YQ09MNN63" to "recipe_104",
        "01JSBM30XWQ6GG4XK0KSZ3FQEK" to "recipe_103",
        "01JSBM311F0ZRVVKAGZFCFK5CW" to "recipe_086",
    )

    /** 统一的 contentHash：命中官方映射用 nic:<recipeId>，否则保留原 cloudrecipe: 哈希。 */
    fun hashForSpec(specId: String, fallback: String): String {
        val identity = IDENTITY_FOR_ANY_SPEC[specId] ?: return fallback
        return HASH_PREFIX + identity.recipeId
    }

    /** 官方全称；无对应时返回 null，由调用方保留缩写名。 */
    fun officialNameForSpec(specId: String): String? = BY_SPEC_ID[specId]?.officialName

    /** 把缩写别名拼进说明，保证按旧缩写名仍能搜索到。 */
    fun descriptionWithAliases(specId: String, description: String): String {
        val aliases = BY_SPEC_ID[specId]?.aliases ?: return description
        val suffix = aliases.joinToString("、")
        return "$description 官方原名缩写：$suffix。"
    }
}
