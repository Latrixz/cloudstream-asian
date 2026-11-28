package com.kubb

import com.csasian.shared.CsBrowse
import com.csasian.shared.CsCategories

/** One section of the site (cid "0,1,<id>") with the filters its own menus offer. */
data class KubbCategory(
    val id: String,
    val name: String,
    val english: String,
    val slug: String,
    val glyph: String,
    val colors: Pair<Int, Int>,
    /** 类型 from the site's menu (assets/menu/mainmenu_fix.json): genre id to label. */
    val genres: List<Pair<String, String>>,
)

/**
 * Kubb's sections and filters for the shared category browser (shared/CsBrowse.kt): the six
 * sections, each with the site's 类型 (per section), 地区 and 年份, and its three orders, newest first.
 */
object KubbCategories {
    const val DEFAULT_ORDER = "0"
    val ORDERS = listOf("0" to "最新添加", "1" to "最近更新", "2" to "热播")
    const val POPULAR_ORDER = "2"

    /** The drawn tiles' hosted copies, used only if drawing one fails. */
    const val HOSTED_TILES = "https://raw.githubusercontent.com/Latrixz/cloudstream-asian/code/branding/kubb-categories"

    /** 地区 from the site's own search filters (v3 list/GetSearchCondition). */
    val REGIONS = listOf("大陆", "香港", "台湾", "日本", "韩国", "欧美", "英国", "泰国", "其它")

    /** 年份: recent years, then the site's own decade labels. */
    val YEARS: List<String> by lazy {
        val now = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        (now downTo now - 11).map { it.toString() } + listOf("90年代", "80年代", "怀旧")
    }

    val ALL = listOf(
        KubbCategory(
            "3", "电影", "Movies", "movie", "🎬", 0xFFE53950.toInt() to 0xFF6A1B9A.toInt(),
            listOf(
                "19" to "喜剧", "20" to "爱情", "21" to "动作", "22" to "犯罪", "23" to "科幻", "24" to "奇幻",
                "25" to "冒险", "26" to "灾难", "123" to "恐怖", "27" to "惊悚", "28" to "剧情", "29" to "战争",
                "30" to "歌舞", "31" to "经典", "32" to "悬疑", "113" to "动画", "124" to "同性", "125" to "网络电影",
            ),
        ),
        KubbCategory(
            "4", "电视剧", "TV Series", "drama", "📺", 0xFF1E88E5.toInt() to 0xFF283593.toInt(),
            listOf(
                "129" to "偶像", "127" to "言情", "146" to "爱情", "126" to "古装", "141" to "历史", "142" to "玄幻",
                "136" to "谍战", "132" to "都市", "143" to "历险", "144" to "科幻", "135" to "军旅", "133" to "喜剧",
                "128" to "武侠", "145" to "江湖", "138" to "罪案", "131" to "青春", "130" to "家庭", "134" to "战争",
                "137" to "悬疑", "139" to "穿越", "140" to "宫廷", "147" to "神话", "148" to "商战", "149" to "警匪",
                "150" to "动作", "151" to "惊悚", "152" to "剧情", "153" to "同性", "154" to "奇幻",
            ),
        ),
        KubbCategory(
            "5", "综艺", "Variety", "variety", "🎤", 0xFFFB8C00.toInt() to 0xFFC2185B.toInt(),
            listOf(
                "39" to "真人秀", "38" to "选秀", "94" to "网综", "43" to "脱口秀", "40" to "搞笑", "91" to "竞技",
                "33" to "情感", "34" to "访谈", "44" to "演唱会", "92" to "晚会", "45" to "其它",
            ),
        ),
        KubbCategory(
            "6", "动漫", "Anime", "anime", "🐉", 0xFF8E24AA.toInt() to 0xFF3949AB.toInt(),
            listOf(
                "46" to "热血", "47" to "格斗", "48" to "机战", "49" to "少女", "51" to "竞技", "52" to "科幻",
                "53" to "魔幻", "54" to "爆笑", "55" to "推理", "121" to "冒险", "120" to "恋爱", "119" to "校园",
                "118" to "治愈", "117" to "泡面", "116" to "穿越", "56" to "灵异", "122" to "耽美", "57" to "剧场版",
                "58" to "其它",
            ),
        ),
        KubbCategory(
            "7", "纪录片", "Documentary", "documentary", "🌏", 0xFF00897B.toInt() to 0xFF1B5E20.toInt(),
            listOf(
                "50" to "文化", "59" to "探索", "60" to "军事", "61" to "解密", "62" to "科技", "63" to "历史",
                "64" to "人物", "66" to "自然", "67" to "其它",
            ),
        ),
        KubbCategory(
            "8", "短剧", "Short Dramas", "short", "📱", 0xFFF06292.toInt() to 0xFF7B1FA2.toInt(),
            listOf(
                "68" to "偶像", "69" to "爱情", "72" to "言情", "70" to "古装", "141" to "历史", "73" to "玄幻",
                "74" to "谍战", "158" to "都市", "159" to "历险", "160" to "科幻", "161" to "军旅", "162" to "喜剧",
                "163" to "武侠", "164" to "江湖", "165" to "罪案", "166" to "青春", "167" to "家庭", "168" to "战争",
                "169" to "悬疑", "170" to "穿越", "171" to "宫廷", "172" to "神话", "175" to "商战", "176" to "警匪",
                "177" to "动作", "178" to "惊悚", "179" to "剧情", "180" to "同性", "181" to "奇幻",
            ),
        ),
    )

    fun byId(id: String): KubbCategory? = ALL.firstOrNull { it.id == id }

    /** The sections as the shared browser and SkyStream Dev's category chips see them. */
    val sections: List<CsBrowse.Section> by lazy {
        ALL.map { category ->
            CsBrowse.Section(
                id = category.id,
                name = category.name,
                filters = listOf(
                    CsCategories.Filter("genre", "类型", category.genres.map { (id, label) -> CsCategories.Option(id, label) }),
                    CsCategories.Filter("region", "地区", REGIONS.map { CsCategories.Option(it) }),
                    CsCategories.Filter("year", "年份", YEARS.map { CsCategories.Option(it) }),
                ),
                sorts = ORDERS.map { (order, label) -> CsCategories.Option(order, label) },
                english = category.english,
                glyph = category.glyph,
                colors = category.colors,
                slug = category.slug,
            )
        }
    }
}
