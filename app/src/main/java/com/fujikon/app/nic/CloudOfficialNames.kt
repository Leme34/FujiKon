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

    /**
     * 官方 recipeId -> 官方说明描述（来自 bff/recipe/release/detail 的 recipeDetail，
     * 回退 recipeOutline），随 1.6.49 落地。命中 [CloudOfficialNames] 的内置预置改用
     * 此官方说明，与在线导入共用同一内容；2 个无官方对应的文件不在此表中。
     */
    val DESCRIPTION_BY_RECIPE_ID: Map<String, String> = mapOf(
        "01HXE5FMRFHVM4S2P78B1K15AM" to "创作者的说明\nVintage Mellow色彩方案捕捉了略微褪色但丰富的色彩精髓，让人想起在复古胶片上拍摄的珍贵回忆。在增强整体质朴色调的同时，它保留了原始配色方案的真实性，使其在各种情况下都能通用。\n为了最好地展现色彩方案的颜色深度，特别推荐用于对比度强的图像或在温暖的夕阳光辉下拍摄的图像。",
        "01HXE5FN2K5ZZHMG9WYMYHNH9T" to "创作者的说明\nRed Mood色彩方案的灵感来源于香港电影美学，利用红色的基调，构建一种忧郁且浪漫的情感，展现20世纪60年代的氛围。Red Mood给人一种怀旧和复古的魅力，与电影的标志性视觉效果产生共鸣，可增强图像中的情感强度和张力。这种极具影响力的红色之美必定令人难忘。\n该色彩方案最推荐用于夜间室内人工照明下。",
        "01HXE5FND66VMWD4MGJBRC2ETV" to "创作者的说明\nCine Mood色彩方案使用广受欢迎的橙青色调，将您的普通图像转换为流行的电影质感。非常适合为任何场景营造温暖、温馨的氛围，引起观众共鸣。\n无论是在室内还是室外，这种百搭的风格都能满足您的需求。通过降低对比度，无论是在阳光下、黄金时段、夜间，还是使用人工照明，您都可以利用它。",
        "01HXE5FNHXJQ3VNP2JP2QN2GFD" to "创作者的说明\nBlue Mood色彩方案赋予一种俏皮风，增强温暖的橙色调，同时在阴影中注入一丝清爽的蓝色对比，营造出格外醒目的现代效果。它主要用于人像摄影，但它的多功能性使其能够应用于不同的场景和情形，为图像带来酷炫别致的氛围。\n无论是在室内还是室外，该色彩方案在应用于白天、日落或黄金时段拍摄的图像时，其效果最为神奇。",
        "01HXE5FNPN749Q8MNKH5GAFFJE" to "创作者的说明\nGolden Dusk色彩方案可将您的图像变成一个温暖而怀旧的世界。它旨在唤起一种让人联想到温暖的秋天日落的舒适氛围，突出鲜艳生动的黄色和橙色色相，在您的照片中注入高饱和度和低对比度，同时增强白色色调的表现力。该色彩方案非常适合营造温馨的氛围，以柔和、活力四射的光芒增添照片魅力，因而是拍摄风景、肖像和城市风光的理想选择。\n\n该色彩方案专为充满暖光的拍摄环境而设计，例如沐浴在日出或日落的金色光芒下时，或是在色彩缤纷的环境中。特别是在黄金时段，您使用该色彩方案拍出的图像简直就像被笼罩在迷人的金色光波里。",
        "01HXE5FP0GF51ZP6W7H9Y249P5" to "创作者的说明\nDG Film Look旨在为在各种情况下（如明媚的阳光下或光线不足的室内）拍摄的视频片段增强情感影响力。它的颜色和外观创意风格如同电影院中上映的影像，可唤起观众的怀旧思绪。\nDG Film Look可以在照相机内应用，为您录制的视频片段即刻赋予一种更加浓郁的电影感，让人回想起传奇而经典的电影胶片色彩。为了实现这种电影感，亮部被柔化得更加中性，保留了丰富的暗部，同时实现图像整体干净清爽的效果。拍摄虚构的人物剧以及纪录片时最适合应用这种电影感，因此，在您身处各种拍摄情况、各种天气条件下，以及讲述不同的故事时，均可获得电影般的肤色和好莱坞风格的质感，提升对观众的影响力。",
        "01HXE5FP3WYNBFPBS14AKGR9CV" to "创作者的说明\nTeal & Orange色彩方案增强了图像中的蓝色，使蓝色更饱和，同时赋予其偏青的色相。该色彩方案还可以增强橙色和红色的饱和度，从而产生非常互补的色调和色彩，使拍摄对象更加突出。总体而言，它将饱和度和对比度控制得恰到好处。\n\n该色彩方案适用于整体图像中已经具有不同蓝色和橙色的场景，因为它可对这两种不同颜色的区域进行丰富的表达。在黄金时段拍摄人像或使用互补色捕捉日落时，该色彩方案是理想的选择。Teal & Orange色彩方案在各种天气或一天中的很多时刻都能有用武之地。它可突出您的拍摄对象并增强画面色彩，因此非常适合已具有这些元素的照片。",
        "01HXE5FP854H5ZK91KQEKKFZ07" to "创作者的说明\nNeon Glow色彩方案旨在唤起您图像中的复古未来感。它具有亮粉中间色调和深蓝阴影，以及略微增强的饱和度和对比度，使其理想地展现赛博朋克梦幻效果的人像。\n\n该色彩方案适合拍摄对象已经被某种彩色光源（例如霓虹灯、彩色路灯或汽车前灯的光芒）照亮的夜间场景。它增强了鲜艳的色相和深邃的阴影。它包含饱和的粉蓝色调，当您拍摄已经被这些颜色笼罩的场景时，可达到极其逼真的效果，因此它成为捕捉街头风格人像或城市景观背景光芒的理想的选择。",
        "01HXE5FPBPHS9GGHQCP3QXCAH9" to "创作者的说明\nCinematic Blue色彩方案非常适合在蓝调时段拍摄带有环境的镜头。当您追求电影氛围的时刻，它的效果极佳。\n\nCinematic Blue色彩方案可为您的图像提供酷炫的电影效果。亮部突显暖黄色调，而中间色调和阴影则偏向蓝绿效果，让您拍摄的图像能具有电影般的画面质感。它的蓝色被赋予了适中的对比度和厚重的饱和度。Cinematic Blue适合在各种天气和一天中的很多时刻使用，尤其在您使用较广角度进行拍摄并将对焦点从拍摄对象上稍微移开一点时，效果较为显著。",
        "01HXE775PCFTQPPJAX5ZRBGDTX" to "该色彩方案产生清晰的对比度，使图像看起来像彩色负片。\n\n它给阴影添加了绿色并给亮部添加了橙色。绿色被抑制而青色被突出，从而产生了具有怀旧感的色彩。",
        "01HXE775X8CNFGMNTPMYZ0QYMD" to "该色彩方案产生庄严肃穆的色彩，让人感叹时间的流逝。\n\n它减少并加浓了阴影并使蓝色略带青色色调。因此，蓝色天空被赋予独特的色彩。",
        "01HXE7761WQ0KT24DZ547E1AQD" to "该色彩方案的灵感源自彩色负片的色阶，可少量调整亮度和色彩强度，营造一种平静的氛围。\n\n该色彩方案还可用于在拍摄后调整着色。",
        "01HXE7766TYK2PVRYGK4Q6W8G0" to "该色彩方案赋予照片一种肃穆感，让人联想到漂白旁路胶片显影技术。\n\n该色彩方案以适度减少阴影并抑制整体颜色的方式对图像进行调整。",
        "01HXE776BVJCGYZYCTEPJQP9ZR" to "该色彩方案能产生更暖的色彩。它能使傍晚的景色比肉眼所见的更加鲜艳。\n\n该色彩方案强调了\"Color Mode Yellow 01a”色彩方案的独特特征。",
        "01HXE776JB5KQ37E7T1JA78S20" to "该色彩方案能产生更暖的色彩。它能使傍晚的景色比肉眼所见的更加鲜艳。\n\n整体对比度适中，并稍微减少了阴影。该色彩方案调整了色彩并提高了饱和度，以突出黄色和橙色。",
        "01HXE776QDMM2GH3WMB80SJ378" to "该色彩方案增强了整体着色，以产生一种清爽和多彩的外观。\n\n该色彩方案强调了“Color Mode Green 01a”色彩方案的独特特征。",
        "01HXE776V93CCA109293ABN8QD" to "该色彩方案增强了整体着色，以产生一种清爽和多彩的外观。\n\n该色彩方案对图像进行调整，使蓝色和橙色更具层次感。将对比度调高，并将阴影稍微减少和加浓。",
        "01HXE7770AY1EDWX24SKDS67XF" to "该色彩方案采用高调的蓝色调色彩，能产生凉爽的外观。\n\n该色彩方案强调了“Color Mode Blue 01a”色彩方案的独特特征。",
        "01HXE7775T5BN1SAEG0WSZ6F6E" to "该色彩方案采用高调的蓝色调色彩，能产生凉爽的外观。\n\n该色彩方案将图像颜色调得更蓝并使整体更亮。",
        "01HXE777BYRGXHWVQC3405AAS9" to "该色彩方案的灵感源自“橙色和深青色”的外观，这是电影中常用的一种颜色分级技术。\n\n该色彩方案强调了“Color Mode Teal&Orange 01a”色彩方案的独特特征。",
        "01HXE777J72YMSA0W5P06A43E8" to "该色彩方案的灵感源自“橙色和深青色”的外观，这是电影中常用的一种颜色分级技术。\n\n它强调了橙色及其青色互补色，稍微增加了对比度以强调界定该外观的色彩。",
        "01HXE777QD9B1067WMV45CX6PS" to "该色彩方案的灵感源自一种钨金色彩的负片。使典型的夜景变得看似电影中的场景。\n\n请配合3200K左右的白平衡设定使用该色彩方案。\n\n该色彩方案是“Negative Film Tungsten Color 01a”色彩方案的变体，其特点是增加了对比度和饱和度。",
        "01HXE777XRC3T3FA8FNCV872H1" to "该色彩方案的灵感源自一种钨金色彩的负片。使典型的夜景变得看似电影中的场景。\n\n请配合3200K左右的白平衡设定使用该色彩方案。该色彩方案降低了整体对比度和饱和度。通过添加黄色使阴影变浓，并给亮部添加了蓝色。将红色调整成洋红色调。",
        "01HXE778320VAQMVG02C5ASZ5N" to "该色彩方案的灵感源自一种以强色调为特征的彩色负片。色彩与肉眼看到的不同，营造出一种魔幻氛围。\n\n该色彩方案是“Negative Film Yellow&Green 01a”色彩方案的变体，其特点是增加了对比度和饱和度。",
        "01HXE7788BQFWRRP7NDXC156AV" to "该色彩方案的灵感源自一种以强色调为特征的彩色负片。色彩与肉眼看到的不同，营造出一种魔幻氛围。\n\n该色彩方案降低了整体对比度和饱和度。给阴影添加了青色，给中间色调添加了略带红色的洋红并给亮部添加了绿色。此外，该色彩方案还强调了黄色。",
        "01HXE778FAMYVVJMKMQNF56YDG" to "该色彩方案的灵感源自一种以鲜艳黄色为特征的彩色负片。它给日光增添了暖色调。\n\n该色彩方案是“Negative Film Yellow 03a”色彩方案的变体，其特点是增加了对比度和饱和度。",
        "01HXE778JYGDFQY9X9AP8H4G18" to "该色彩方案的灵感源自一种以鲜艳黄色为特征的彩色负片。它给日光增添了暖色调。\n\n该色彩方案降低了整体对比度和饱和度。最终处理减少了照片中的阴影。给阴影添加了绿色并给亮部添加了蓝色。",
        "01HXE778Q9JW9N49XFQ90F5PQP" to "该色彩方案的灵感源自一种以黄色着色为特征的彩色负片。它给照片带来一种怀旧感。\n\n该色彩方案是“Negative Film Yellow 02a”色彩方案的变体，其特点是增加了对比度和饱和度。",
        "01HXE778TTF37VQ4ZE67GAX70G" to "该色彩方案的灵感源自一种以黄色着色为特征的彩色负片。它给照片带来一种怀旧感。\n\n该色彩方案给阴影添加了略带黄色的绿色并给亮部添加了洋红色。对整体图像强调了暖色调。",
        "01HXE778ZTZW1QE99J8CWXKJD8" to "该色彩方案的灵感源自一种以红色和青色着色为特征的彩色负片。当与蓝色天空一起拍摄阴影时，它能带来一种宁静感。\n\n该色彩方案是“NegativeFilm Red&Cyan 01a”色彩方案的变体，其特点是增加了对比度和饱和度。",
        "01HXE77966V8FJYVXP1R7CPCWT" to "该色彩方案的灵感源自一种以红色和青色着色为特征的彩色负片。当与蓝色天空一起拍摄阴影时，它能给图像带来一种宁静感。\n\n该色彩方案给阴影添加了略带青色的绿色并给亮部添加了略带洋红的蓝色。",
        "01HXE779AN00JPSQZFFSWFDBAM" to "该色彩方案的灵感源自一种以青色和蓝色为特征的彩色负片。它可产生一种强调光线的穿透感的通透氛围。\n\n该色彩方案是“NegativeFilm Cyan&Blue 01a”色彩方案的变体，其特点是增加了对比度和饱和度。",
        "01HXE779MS5G098EA0EH9M4KZS" to "该色彩方案的灵感源自一种以青色和蓝色为特征的彩色负片。它可产生一种强调光线的穿透感的通透氛围。\n\n该色彩方案降低了整体对比度和饱和度。给阴影添加了绿色，给亮部添加了蓝色，并对整体图像抑制了暖色调。",
        "01HXE779RR64H3G2SRMWR1FBAC" to "该色彩方案的灵感源自一种以绿色和青色着色为特征的彩色负片。透明蓝色彩营造出一种通透感。\n\n该色彩方案是“Negative Film Green&Cyan 01a”色彩方案的变体，其特点是增加了对比度和饱和度。",
        "01HXE779X9YPE9CZD2G07BDK7E" to "该色彩方案的灵感源自一种以绿色和青色着色为特征的彩色负片。透明蓝色彩营造出一种通透感。\n\n该色彩方案降低了整体对比度和饱和度。给阴影添加了绿色并给亮部添加了蓝色。",
        "01HXE77A2K6K6Z4HYFGVMV42TM" to "该色彩方案的灵感源自一种以蓝色着色为特征的彩色负片。当您想要突出天空的深蓝色时，请尝试这一色彩方案。\n\n该色彩方案是“Negative Film Blue 01a”色彩方案的变体，其特点是增加了对比度和饱和度。",
        "01HXE77A9Z5F3N9C5XV7P60ECZ" to "该色彩方案的灵感源自一种以蓝色着色为特征的彩色负片。当您想要突出天空的深蓝色时，请尝试这一色彩方案。\n\n该色彩方案降低了整体对比度和饱和度，并给亮部添加了洋红色。",
        "01HXE77AEVSPHAHFRCS43W73AR" to "该色彩方案的灵感源自一种以黄色着色为特征的彩色负片。它可生动鲜明地呈现日出和日落的景象。\n\n该色彩方案是“Negative Film Yellow 01a”色彩方案的变体，其特点是增加了对比度和饱和度。",
        "01HXE77AMQFSPRFZKZ24KZ4XDF" to "该色彩方案的灵感源自一种以黄色着色为特征的彩色负片。它可生动鲜明地呈现日出和日落的景象。\n\n该色彩方案降低了整体对比度和饱和度，并给阴影添加了蓝色，给亮部添加了紫色。",
        "01J6VDR8901GMQMP1JA12XZ2SX" to "创作者的说明\n这是一种用于人像拍摄的色彩方案。它没有强烈的特性，通用于任何户外环境，可营造出暖色调的氛围。由于考虑到也要用于视频，因此将整体对比度保持在较低水平。若您想加强视频中的氛围，请以10位录制以利用其丰富的色彩层次，并使用编辑软件增加对比度。",
        "01J6VDR8G88PECXHW5969JYHRG" to "创作者的说明\n这是一个电影风格的色彩方案，特别为阴天或森林中等户外使用时设计。它为暗淡区域增添了些许蓝色，使森林和树木的绿色脱颖而出。它也适用于人像，因此可以完好地保持人物皮肤和其他区域的颜色。此色彩方案给人带来的印象根据视频或照片亮度的不同而大有差异，所以建议您在拍摄时更改曝光，以便找到您最喜欢的氛围。",
        "01J6VDR8P897ZN0E3BJFYRKRAQ" to "创作者的说明\n“Clear Portrait”是一种旨在创建通透人像的色彩方案。它保持对比度，以免其过于微弱，并将饱和度调整到鲜艳级别。晴天时，它给人一种清爽的印象，阴天时，则呈现出一种平静的氛围。它创造出柔和的效果，与利用散景的照片完美契合，并可应用于快照和桌面摄影。将曝光调整到略高于良好值时可获得更清晰的效果。色温可以设为自动，但您也可以尝试略冷的蓝色色调。",
        "01J6VDR91SGX7PJB31RHKBA03P" to "创作者的说明\n创建此色彩方案时，我秉着主题“像濑户内*海一样平静柔和的色彩方案”，充分诠释了我的个人喜好。它呈现一种浅淡和略带怀旧的氛围，因此相比色彩缤纷的照片，更适用于颜色较少的照片。正如标题中“Setouchi Blue”所提示的那样，如果您喜欢将其与天空和大海等蓝色搭配使用，我会很高兴。在拍摄过程中稍微增加曝光将带来更丰富、更微妙的色彩。\n\n*日本最大的内海，位于日本西部。这片封闭的海洋得天独厚，气候温和宜人。",
        "01JAWAKH9G5P4EGMQ4CSJPYYFV" to "创作者的说明\n为了以艺术的手法展现色彩的和谐，我选择用绿色和红色完美互补，来描绘一种蓝色时刻。青绿色增添了艺术气息，也为您展现迪拜充满活力的氛围。\n该色彩方案最适合拍摄黄昏美景，在该场景下风景被彩色路灯或车灯照亮，呈现出蓝色调。它柔化了红色，同时增强了青绿色，以达到完美和谐且具艺术感的图像效果。",
        "01JAWAKHJ0DG7KCBY6BNAW4271" to "创作者的说明\n无论是阴天、晴天还是雾天，该色彩方案都适合拍摄绿草、蓝天、木头的质感等自然光线下的日常生活场景。它通过巧妙控制色彩饱和度与对比度以展现出柔和、自然的氛围。",
        "01JAWAKHQ8B1J3TXXQSNTQGC5H" to "创作者的说明\n该色彩方案适合在旅途中记录色彩丰富的建筑物和街景。它能突显图像的对比度和原有的色彩浓度。",
        "01JAWAKHWW7ZN6RNX3Z6E05GFN" to "创作者的说明\n“Blue Hour”色彩方案具有高对比度和高色彩饱和度，适合在晴天的海边拍摄或在蓝色时段进行色彩分级般的拍摄。",
        "01JAWAKJ0QXZDSWW9E11TH419Z" to "创作者的说明\n“Warm-toned dim lighting”（暖调暗光）适合在室内环境进行拍摄。暖色调暗淡的光线优化背景与拍摄对象之间的对比，并加强色调的柔和感与光线的变迁，由此营造出温暖平静的氛围。",
        "01JD1PCWXCJMXA8207GAAKB701" to "创作者的说明\nTurquoise Blue是一种多用途色彩方案，可以用于各种主题和场景，包括人像和快照。实现一种清新而具透明感的效果。由于对比度稍低，它在白天光线明亮的时段以及天空蓝色突出的场景中效果显著。也适用于多云和雨天。我希望您能尝试一下。",
        "01JD1PCX4339YR090MMN19NP7F" to "创作者的说明\n该色彩方案是基于我自己柔和而具透明感的摄影风格而创建的。以强调蓝色和绿色的色彩为基础，阴影略带蓝色调。它提供的色调整体更柔和，而清晰度有所降低。推荐在半背光条件下拍摄时使用该色彩方案，因为这样可以摄入更多的光线，展现出更具透明感的效果。它在拍摄植物和风景时效果较佳，同时也能为室内照片表现出柔和、宁静的氛围。 \n如果您觉得蓝色太强烈，可以调整白平衡以达到您喜欢的平衡。",
        "01JD1PCX8Y93G7AMHF0SB2KSCT" to "创作者的说明\n正如其名称所示，该色彩方案表达了一种温暖的视角，让人联想到傍晚的光线。由于它是为暖色调而创建的，因此与展示蓝色等冷色调的主题或场景不太搭配。当拍摄包含中性绿色的风景时效果最佳。请尽情感受Sunset Glow的温暖，创作出温暖又温馨的自然风光和城市景观照片。 \n使用该色彩方案拍摄人像时，建议将人物定位在光能打到人像皮肤上的位置，以展现色相的对比。",
        "01JD1PCXC831RAQFETNFMMQSDT" to "创作者的说明\n色彩柔和，并且质感呈现出哑光效果。我创建该色彩方案是为了使阴影变得柔和，并稍带蓝色调。推荐将它用于拍摄曝光不足氛围的图像，比如咖啡馆等室内或夜间城市风光摄影。 \n请使用该色彩方案享受拍摄光影照片的乐趣。",
        "01JD1PCXH2HKADD09AZZ80CN30" to "创作者的说明\n该色彩方案旨在营造出一种被柔和、温暖阳光包围的感觉。创建时，我希望让这一色彩方案可以应对各种场景，比如蓝天、阴天和日落。它特别适合用来拍摄花卉、自然风光和多彩的建筑。色彩已经过微调，让您从通过取景器查看的那一刻起，就能享受到一个独特的世界。请根据您的喜好调整曝光和白平衡，并探索您喜爱的表现方式。",
        "01JD1PCXMJ2WCCC0NNARQCNAXN" to "创作者的说明\n该色彩方案旨在为您的图像营造出柔和的色调，创作灵感来自于乡村——我居住的幸田町，那里的人们热情温暖，乡野绿意盎然。该色彩方案的低对比度非常适合用来拍摄色彩鲜艳的蔬菜等照片。正如其名称“Kota-Green”所示，希望您能享受它在绿色植物和花卉，尤其是绿色主题和场景上的表现。我认为在拍摄时稍微提高曝光，会得到更好的效果。",
        "01JD1PCXSJWWAEJWDN5WQD64Q7" to "创作者的说明\nPrimary Pop旨在使用鲜艳的原色填充图像，为每张照片提高深度和饱和度。该色彩方案的灵感来源于大胆的色彩所唤起的喜悦与活力，使用其生动的色相，为您的摄影带来一种幸福感。它引入一种微妙的哑光效果，在保护暗部的同时，保持清晰而优雅的氛围，并通过加强对比度和对原色的细致处理，创造出惊人的视觉冲击力，使每张图像都脱颖而出。使用Primary Pop，您的照片将在保留清晰亮部的同时，散发出生动而富有活力的感觉。",
        "01JD1PCXWTXA42DCNKZ6QYHV5V" to "创作者的说明\nMoody Shadows旨在为您的图像注入一种丰富、深沉的氛围，让人联想到经典画作。此预设保护并改善阴影，引入哑光效果，添加深度和质感。通过降低亮部和白点，同时提高黒点，它创造出一种柔和、丝滑、绘画般的质感。将柔和的暖色调添加至阴影、中间色调和亮部，给人一种整体协调而迷人的感觉。使用Moody Shadows，您的照片将精致并具有氛围感，通过结合对比度和微妙的暖意，营造出一种优美均衡且引发情感共鸣的美感。\n注意：当在尼康工坊而非照相机内应用Moody Shadows时，建议您将曝光补偿提高+0.50EV。此预设设定会使图像自然变暗，因此这样调整曝光补偿可确保获得均衡的最终效果。",
        "01JD1PCY1QC69XMZ4PBG7T38ND" to "创作者的说明\nColor Splash可为每种色相瞬间添加柔和而鲜艳的饱和度，使照片色彩生动且均衡。该色彩方案非常适合在不破坏场景的前提下，创作出充满光泽和亮度的图像。亮部中的一丝暖意营造出一种温馨的光晕，同时通过精心的调整保持肤色自然。使用Color Splash，您的照片不会损失自然的美感，将散发柔和而生动的魅力，每种色彩都夺目展现。",
        "01JD1PCY4MKBH4NXB97ZW5B679" to "创作者的说明\nSunlight & Happiness色彩方案旨在为您的照片注入柔和而明亮的光芒。该色彩方案通过柔化阴影和提升亮部来改善自然阳光。非常适合捕捉阳光下多姿多彩的瞬间，它使绿色更鲜亮，让黄色更具暖意，优化色彩和色调。亮部、绿色和黄色调得到提亮，同时图像中的对比度微妙提升，饱和度加强。  \n无论您是改善户外人像、风景，还是日常景象，Sunlight & Happiness都将为您的图像添加光彩和暖意。",
        "01JD1PCY7SZNNA1QQ9R1C6MP6H" to "创作者的说明\nPainterly Light色彩方案的灵感来自17世纪的荷兰大师级画家，他们用像明暗对比这样的技术彻底改变了艺术。尤其是Rembrandt，他完美地运用光影，创作出美丽且情感丰富的艺术作品。 \n该色彩方案着重于光影的运用，旨在拍摄出这些艺术大师风格的照片。它加深蓝色调和阴影，以加强明暗对比，展现经典绘画中可见的令人印象深刻的效果。还提升金色调和亮部，为画面中较亮的区域带来暖意。该色彩方案将改善使用方向光拍摄的图像，让照片更具深度并提升饱和度和对比度。",
        "01JD1PCYJCV7PJAKWQ1JBQCTN7" to "创作者的说明\nSunset Reset是一个以“冷漠数字世界中的心灵家园”为主题的色彩方案，它可为您的照片营造出一种温暖、柔和而舒缓的氛围。非常适合捕捉日常生活中的温馨时刻，如与家人共进晚餐时，或与朋友或宠物共度的宝贵时刻，向观众传达一种温暖而宁静的感受。该色彩方案在金色光线下以及室内间接照明中拍摄时同样表现出色。Sunset Reset这个名字包含了将夕阳视为重设机遇并拥抱全新开始的寓意。",
        "01JD1PCYMSJVRHS9XB7DDE1YF5" to "创作者的说明\nDawn Hues色彩方案完全是为了拍摄柔和金色的晨光而制作。它加强暖色调，并为您的照片赋予柔和的光晕，非常适合那些宁静平和的清晨时刻。无论是直接拍摄清晨的阳光，描绘清晨天空的美丽色彩，还是拍摄沐浴在柔和迷人光线中的人像，该色彩方案都能出色地充分利用黎明时分的自然光线。",
        "01JD1PCYRNCBES85KAZR2PG56G" to "创作者的说明\nCozy Autumn色彩方案是您拍摄温暖、自然秋色的首选。它突显深橙色、红色和棕色，这些色彩可让秋天变得如此温馨。它可柔和冷色调，让整体感觉温暖而迷人，最适合营造该季节的舒适感。当拍摄风景和大自然照片，以及在自然光下拍摄人像，尤其是在秋天的公园或者树林里时，该色彩方案非常出色。",
        "01JD1PCYWNRYS94FZEKD85Z38M" to "创作者的说明\nFilm Pop色彩方案可突出街头摄影大胆而充满活力的一面。它加强色彩和对比度，在保留真实胶片感的同时，使整体看起来丰富多彩且鲜明生动。它着重于使照片因色彩而充满活力，从而体现拍摄现场的热情氛围。非常适合您想突出色彩的场景（从都市和街头摄影到旅行照片、庆祝活动以及时尚摄影等），可为您的照片注入活力与激情。",
        "01JD1PCZ1NPAX794XEKESGRH2J" to "创作者的说明\nSoft Film色彩方案使用柔和的对焦、微妙的色彩触感和平缓的对比度，赋予照片一种优雅而梦幻的氛围。它在忠于照片原有色彩方案的同时，添加了一种温暖且不过时的色调。适用于广泛的拍摄场景，例如捕捉人像中的浪漫亲密瞬间，创建宁静平和的风景照，为生活照赋予微妙的色彩触感，以及为婚礼或订婚照添加怀旧气息等。",
        "01JD1PCZ7JVX56RTKPKD3MSDQ3" to "创作者的说明\nNights Like This色彩方案专为记录那些我们不愿忘记的夜间场景而制作。它在低光环境和夜景中突出图像被照亮的部分，同时使阴影区域更暗，让图像具有良好的对比度。该色彩方案可为夜景人像赋予电影感，并在夜晚背景中有彩色光源时也能产生神奇的效果。",
        "01JD1PCZCT6ZJPHZDVYQF4EG5K" to "创作者的说明\nCalm Breeze色彩方案通过其绿色和黄色调，为您的图像添加一种柔和、永恒的质感，加强那一刻的情感深度。它广泛适用于从明亮的日间到低光环境等各种光线条件。该色彩方案非常适合为您的人像打造现代、时尚的美感，完美捕捉白色背景下的拍摄对象，突出生动的绿色植物，或为夜景快照带来温暖。",
        "01JD1PCZNP54WXSHZYMFPSG0Y2" to "创作者的说明\nSummertime Shine色彩方案使用温暖的黄色和橙色调，营造出一种复古且令人难忘的夏日感觉。它为您的图像添加整体柔和、怀旧的氛围，因此非常适合白天的场景。无论是在明亮的自然光下捕捉人像，进行街头抓拍，还是拍摄旅行照片，该色彩方案都有助于将这些特别的瞬间保存为温馨的回忆。",
        "01JD1PCZVJ3EVDD5AAHH0239VB" to "创作者的说明\nIndoor Harmony色彩方案通过均衡地调整亮部和暗部色调，营造出微妙的模拟感。它非常适合各种室内拍摄，无论是静物还是人像。较为平缓的对比度和中性色调可为场景带来宁静的氛围。该色彩方案还可以使简约风格的建筑照片大放异彩，尤其适合那些在画面中以几何线条和中性色彩为特色的照片。",
        "01JD1PD000JN9FZWKG2CFFBTZV" to "创作者的说明\nHazy Memories色彩方案是对Sunkissed Warmth的重要补充，专为阴天拍摄量身定制。它可为照片添加暖意，减少数码清晰质感，带来一种充满情感和年代感的氛围。由于非常适用于平缓、阴天光线下，该色彩方案也适合拍摄城市景观和建筑，尤其是当画面内有光影对比时。",
        "01JD1PD03DGSKT94S8DZRP7AP0" to "创作者的说明\nSunkissed Cool色彩方案是Sunkissed Warmth色彩方案的一种变体。它通过将中间色调和亮部转化为蓝色调，同时保留阴影的暖色调来创造出绝妙的平衡，为您的图像添加一种复古感。非常适合在白天明亮的场景下拍摄，尤其是当您想要突显蓝天的颜色时。该色彩方案可将人像、静物或街景风貌等各种场景转化为既怀旧又让人记忆犹新的画面。",
        "01JD1PD06VNC5S3ED7EDKK1AMM" to "创作者的说明\nSunkissed Warmth是一种优美的怀旧色彩方案，它将中间色调和阴影转化为舒适的橙色调，能极好地为您的图像添加一种模拟感和温暖氛围。最适合阳光明媚的户外拍摄，可让强烈的阳光温暖而夺目，同时完美衬托天空之湛蓝。它还可加强黄昏光线浓郁的橙色调，赋予日落场景戏剧性氛围。",
        "01JMB9VCN3ZQHNTG9NFJTW0RRH" to "创作者的说明\n黒白摄影一直是我的最爱。当我使用黒白胶片拍摄时，没有通用的“万能”解决方案。不同的胶片乳剂对光线和色调的反应各不相同，因此我着手创作灵活优化校准系列色彩方案，以再现我喜欢的胶片所营造的独特氛围。该色彩方案旨在模拟经典的英国ISO 400黒白胶片乳剂在ISO 200下进行曝光以及“拉片”处理时的色调还原曲线，营造出良好的肤色和平滑的过渡效果。",
        "01JMB9VD4MYTCWB9G2G0FVAXRX" to "创作者的说明\n我创建了该色彩方案，旨在为图像捕捉那一刻轻松愉快的氛围。它整体呈现出带蓝色和绿色调的清爽色调，尽可能保持了日常场景图像的自然感，因为颜色和质感的平衡使人物看起来更健康，也推荐用于人像摄影。我认为它还非常适合拍摄包含植物、树木、水体等自然景观丰富的图像。由于图像容易稍微曝光过度，因此应用稍暗的曝光设定，降低色温，使颜色偏向绿色，能更好地发挥该色彩方案的特性。",
        "01JMB9VD7W35ZE9HC8SNVTD65B" to "创作者的说明\n该色彩方案加入了淡粉色和适量的紫色。特别适合人像摄影，因为皮肤和光线中加入了粉色调，可营造出美丽而又略带怀旧的氛围。在清晨或阳光强烈的白天等自然光下拍摄时效果非常理想。光线不足的场景下拍摄出的图像往往较暗，因此在这些情况下使用更亮的曝光设定可能会获得更好效果。",
        "01JMB9VDBRTE5H7ACV40V179F2" to "创作者的说明\n蓝色向青色偏移，黄色向橙色偏移，冷暖对比，突出反差吸引眼球。非常适合拍摄蓝色调的城市风景照片。",
        "01JMB9VDG9D326HJ9KF7RRH0SF" to "创作者的说明\n适合日落时分的拍摄，突出个性化的质感，与高饱和度相比，更强调色彩的质感和吸引眼球的效果，同时突出电影感和对比度。",
        "01JMB9VDKSX2WP766PMPVD0CY2" to "创作者的说明\n适合在室内摄影棚以及光线相对充足的户外环境中使用。整体色彩细腻，细节丰富。",
        "01JMB9VDT6N0J0KX3XMHA9XK0K" to "创作者的说明\nMoody Greens色彩方案着墨于深邃、沉稳的绿色，让图像显得丰富且具有氛围感。它加强翡翠绿、苔藓绿和森林绿的色调，同时稍微减弱其他色调。阴影变得更加深邃，亮部呈现出一种沉稳、朦胧的质感，从而给茂密森林或雾气缭绕的风景添加一种神秘感。非常适合户外场景拍摄，有助于让普通环境变得绿意盎然且具戏剧氛围。",
        "01JMB9VDXP5CZ9K4F26G4YEN6Q" to "创作者的说明\nShades of Fall色彩方案让各种场景焕发秋日的光彩，在整个图像中呈现琥珀色、金色和柔和红色等丰富的色调。它加强光线的自然暖意，使风景显得更加生动、闪耀，并将初秋的树叶调整至更接近于最佳观赏期的色彩。非常适合风景或温馨氛围的室内拍摄。",
        "01JMB9VE11JMA47FD2NC94J5A9" to "创作者的说明\nVintage Autumn色彩方案唤起秋日的怀旧感，赋予照片一种温暖、永恒的效果。它降低对比度，同时减弱绿色，来突出柔和的橙色、棕色和黄色等秋天的大地色调。非常适合营造梦幻和电影般的氛围，捕捉逝去的秋日风情。",
        "01JMB9VE4FKTTX53NZCCK26YE2" to "创作者的说明\n该色彩方案为您的照片添加一丝暖意，非常适合背光场景。通过略微降低饱和度并加强暖色调，营造出富有凝聚力的类似色的和谐感。阴影和亮部加入金色调，而白色部分则故意让其保持亮白，以此再现柔和的阳光，给人一种柔和而朦胧的效果。",
        "01JMB9VE78WVP9BBVFVCDPYT44" to "创作者的说明\nFern可为富含冷色调、自然色调的场景添加一种忧郁的电影感。通过加深绿色和强调蓝色，突显原本较为暗淡或压抑环境中的戏剧性情境。在阴影中添加对比度，加强氛围，同时柔化白色，营造出一种朦胧、空灵的效果。非常适合拍摄以冷色调为主的各种户外场景，为背阴或寒冷环境添加深度和神秘感。",
        "01JMB9VEB2P57A2AP488JM0YEG" to "创作者的说明\nGlow是一种简约风格的色彩方案，通过添加一丝暖意柔化各种场景。这种微小的调整能够营造出自然而舒适的印象，非常适合直接在照相机显示屏中向模特儿展示他们的优雅风采。该色彩方案通过加入柔和的暖意并柔化整个图像，在拍摄时即可加强照片，相当于一种可现场立即设定的理想优化校准。非常适合在不破坏场景氛围的情况下，表现出平滑而温暖的美感。",
        "01JMB9VEFB835A5N232FVEWHA3" to "创作者的说明\n这是一种复古且具有电影感的色彩方案，通过沉稳的绿色调和舒适的暖色调，自然地营造出怀旧氛围。",
        "01JMB9VEJDVDDTRBMNRQT2WQDB" to "创作者的说明\n该色彩方案明朗、鲜亮且便于使用，非常适合想要表现清晰、细腻美感的初学者使用。",
        "01JMB9VENWYEJXETAGCVZX1BRN" to "创作者的说明\n该色彩方案巧妙运用略带粉色调的深黒色，展现出忧郁而强烈的氛围，非常适合用来突出照片效果。",
        "01JSBM2Z2DWYYZMBNQDQ9HTHPZ" to "创作者的说明\n此色彩方案采用洋红色调，给人一种使用过期胶卷所拍画面的印象。我想通过照片营造出一种褪色而又令人难忘的场景。对比度较低，阴影部分得到提亮，整体亮度较为柔和。我本意是让画面整体偏暗，但亮度可以根据个人喜好进行调整。\n最终的视觉效果比预期的更加自然，因此该色彩方案不仅适合营造出独特的氛围，还能在人像拍摄中漂亮地呈现肤色。",
        "01JSBM2ZAPT0J374SWW93WKKQX" to "创作者的说明\n此胶片风格的色彩方案着重于柔和浅蓝和温柔橙色，唤起一种空气中飘浮着薄雾的感觉。虽然对比度和色度略有降低，但整体色调得以保留，呈现的效果会让人联想到35mm胶片的拍摄效果。整体呈现浅蓝色调，但加强了肤色，所以在想要让氛围彻底改变的时候很值得推荐。较亮的图像将具有柔和、清新的外观，而较暗的图像则给人以沉稳的印象，因此用户还能体会到不同曝光带来的不同成像效果。",
        "01JSBM2ZEX270CEBQA94M3MKCC" to "创作者的说明\n此色彩方案可产生类似胶片的效果，非常契合日本的自然风光拍摄。相比热门冲印店的现代质感，我更希望实现一种让人联想到街头照相馆的效果。\n因为该色彩方案温暖、琥珀色调的外观，我认为它更适用于自然元素，比如蓝天和鲜花，而不太适用于那些色彩单调的无机物。建议在户外自然光下使用。根据具体情况，它可能更适合像使用便携式胶卷照相机那样，在28mm至35mm范围内随意快拍，而不太适合用中远摄镜头抓拍模糊效果的照片。",
        "01JSBM2ZNK7MH02S5HK9XXGGHZ" to "创作者的说明\n此色彩方案的创作灵感来自于对日常生活中温暖与善意的感悟。我喜欢简单行事，所以创建了一种通用性很强的色彩方案，适用于任何拍摄对象或场景。它非常适合日常抓拍，比如在咖啡馆拍摄美食照片以及拍摄自然风景等。在拍摄人像时，它能让肤色显得柔和而美丽。\n此色彩方案在晴天和阴天都很有效，但稍微提高曝光会更接近理想的效果。它适用于任何场景，我希望用户们能够随身携带照相机，使用此色彩方案来捕捉日常生活中遇到的美好。",
        "01JSBM2ZVF6SX4VSEK76K1VGJQ" to "创作者的说明\nFilmic色彩方案从胶片摄影永恒的魅力中获取灵感，适用于您的全方位拍摄。它以温暖、如电影般的色调，将您的影像打造成引发情感共鸣且颇具艺术性的怀旧作品。通过调整白平衡，保持完美均衡且和谐的色调来适应您的场景。在具有挑战性的光线条件下，启用动态D-Lighting保留亮部，调整对比度，从而确保可对图像进行正确的曝光。Filmic色彩方案完美融合了现代的灵活性与经典美学，将平凡瞬间化作永恒的记忆。",
        "01JSBM3004K48WEHF7JRSGCX9J" to "创作者的说明\n我们每天所遇见并存留心间的一切内容，无论是天空、树木、花朵，还是珍视的人，全都充满着爱。我喜爱色调鲜明的影像，因此以这种风格为基调制作了该色彩方案。推荐将其用于晴天时的拍摄，它能很好地呈现天空之蓝、大自然之绿、还有花朵的洋红与黄色。您将收获鲜艳而生动的影像，让人百看不厌。为了获得良好效果，建议您使用“动态D-Lighting高+”。这样创建的影像将鲜艳夺目、栩栩如生且魅力无穷。",
        "01JSBM307K7JMYSFVKW44VMQTY" to "创作者的说明\nSOFT GLOW AURA色彩方案旨在为您的影像赋予宁静优雅的粉彩色调。建议将其应用于米色舒缓的背景或是柔和的色调场景中。它可确保肤色亮部与阴影之间平滑过渡，营造出一种朴实却颇具吸引力的质感，从而突显拍摄对象的自然美。",
        "01JSBM30C161CNK36288WS581P" to "创作者的说明\n该色彩方案适合夜景及室内灯光的生活场景，高饱和度的色彩配置，忧郁的蓝调灯光，加上对比感强烈的柔和暖色调，能轻易创造出港产电影的胶片色调，让影像有一种赛博朋克城市的怀旧感。这种配色可牵动情感，将观众带入梦幻的城市景观，联结共同记忆与城市经历。",
        "01JSBM30GMSQB908F9P1W09QCS" to "创作者的说明\n这种优化校准非常适合日落时分的人像拍摄，能加强暖橙色调，同时弱化不必要的色彩，并添加戏剧性色调，从而获得更加精致且颇具魅力的效果。",
        "01JSBM30N55AJV4SKKHFHGYX2H" to "创作者的说明\n此色彩方案基于我自身的摄影风格创作而成，它将让人联想到清晨海边光线的柔和淡雅色调与轻柔的对比度相融合，营造出宁静且怀旧的氛围。该滤镜着重于黄色的暖色调和柔和的蓝色调，同时添加了些许复古效果，突出在海边所能感受到的自然韵味。非常适合用来创建具有梦幻感且永不过时的唯美照片。",
        "01JSBM30T7ZBDW1M9YQ09MNN63" to "创作者的说明\n该色彩方案非常适合色彩浓郁的风景画面以及日常光影的街头影像，是一种浓郁但不明艳，具有独特氛围感的暖蓝色，意在把平淡的色彩画面变得丰富，或者为光影街头的暗部赋予一定的质感。",
        "01JSBM30XWQ6GG4XK0KSZ3FQEK" to "创作者的说明\n该色彩方案模拟了胶片的风格，对比度适中，色彩较浓郁。其加强了中暗部的青色，并且保留了人物健康温暖的肤色，可以应对不同天气和环境，是一个长期可以使用的色彩方案。",
        "01JSBM311F0ZRVVKAGZFCFK5CW" to "创作者的说明\n此“Soft Vivid”优化校准效果尤佳。旨在保持优雅和自然氛围的同时，轻微加强自然色调，温和地突出绿色，保留柔和的光线效果。",
    )

    /** 统一的 contentHash：命中官方映射用 nic:<recipeId>，否则保留原 cloudrecipe: 哈希。 */
    fun hashForSpec(specId: String, fallback: String): String {
        val identity = IDENTITY_FOR_ANY_SPEC[specId] ?: return fallback
        return HASH_PREFIX + identity.recipeId
    }

    /** 官方全称；无对应时返回 null，由调用方保留缩写名。 */
    fun officialNameForSpec(specId: String): String? = BY_SPEC_ID[specId]?.officialName

    /** 命中官方映射的 spec 返回官方说明描述；无对应返回 null，调用方保留本地说明。 */
    fun officialDescriptionForSpec(specId: String): String? {
        val identity = IDENTITY_FOR_ANY_SPEC[specId] ?: return null
        return DESCRIPTION_BY_RECIPE_ID[identity.recipeId]
    }

    /** 把缩写别名拼进说明，保证按旧缩写名仍能搜索到。 */
    fun descriptionWithAliases(specId: String, description: String): String {
        val aliases = BY_SPEC_ID[specId]?.aliases ?: return description
        val suffix = aliases.joinToString("、")
        return "$description 官方原名缩写：$suffix。"
    }
}
