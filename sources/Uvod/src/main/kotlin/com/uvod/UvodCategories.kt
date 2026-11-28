package com.uvod

import com.csasian.shared.CsBrowse
import com.csasian.shared.CsCategories

/**
 * UVOD's sections and filters for the shared category browser (shared/CsBrowse.kt), from the
 * site's own /video/category and /video/filter. Sorts are the site's four; 热度 (popular) comes
 * first here, unlike our other sources: titles added since about 2024 only offer the VIP tiers
 * we don't play, so a newest-first list would be all unplayable titles.
 */
object UvodCategories {
    val SORTS = listOf("hits" to "热度", "score" to "评分", "create_time" to "最新添加", "update_time" to "最近更新")

    val REGIONS = listOf("大陆", "香港", "台湾", "日本", "韩国", "欧美", "新马泰", "其他")
    val LANGUAGES = listOf("国语", "粤语", "闽南语", "英语", "日语", "韩语", "泰语", "法语", "德语", "西班牙语", "印地语")
    val YEARS = listOf("2026", "2025", "2024", "2023", "2022", "2021", "2020", "2019", "2018", "2017", "2016", "2015",
        "2014", "2013", "2012", "2011", "2010", "更早")

    private class Section(val id: Int, val name: String, val english: String, val genres: List<Pair<Int, String>>)

    private val ALL = listOf(
        Section(100, "电影", "Movies", listOf(109 to "喜剧", 110 to "爱情", 111 to "动作", 112 to "犯罪", 113 to "科幻",
            114 to "奇幻", 115 to "冒险", 116 to "灾难", 117 to "惊悚", 118 to "剧情", 119 to "战争", 120 to "经典",
            210 to "悬疑", 211 to "历史")),
        Section(101, "电视剧", "TV Series", listOf(123 to "国产剧", 125 to "港台剧", 126 to "日韩剧", 124 to "欧美剧",
            127 to "新马泰", 207 to "短剧", 128 to "其它剧")),
        Section(102, "综艺", "Variety", listOf(129 to "搞笑", 130 to "情感", 131 to "选秀", 132 to "访谈", 133 to "时尚",
            134 to "真人秀", 135 to "脱口秀", 136 to "演唱会")),
        Section(103, "动漫", "Anime", listOf(151 to "热血", 137 to "冒险", 138 to "格斗", 139 to "科幻", 140 to "恋爱",
            141 to "校园", 143 to "异界", 144 to "美食", 145 to "歌舞", 146 to "运动", 147 to "竞技", 148 to "魔幻",
            149 to "奇幻", 152 to "历史", 153 to "战争", 154 to "机战", 155 to "爆笑", 156 to "治愈", 157 to "励志",
            158 to "悬疑", 159 to "少女", 160 to "推理", 161 to "恐怖", 162 to "神鬼", 208 to "日常", 209 to "搞笑")),
        Section(105, "纪录片", "Documentary", listOf(168 to "文化", 169 to "科技", 170 to "历史", 171 to "军事",
            172 to "人物", 173 to "解密", 174 to "自然")),
        Section(106, "粤台专区", "Cantonese & Taiwanese", listOf(175 to "电影", 176 to "电视剧-国产", 177 to "电视剧-外产",
            178 to "综艺", 179 to "动画")),
        Section(107, "儿童", "Kids", listOf(187 to "儿歌", 188 to "故事", 189 to "学英语", 190 to "动作", 191 to "百科",
            192 to "国学", 193 to "手工", 194 to "识字", 195 to "数学", 196 to "美术", 197 to "舞蹈", 198 to "音乐",
            199 to "诗词", 200 to "运动", 201 to "口才", 202 to "益智", 203 to "玩具", 204 to "游戏", 205 to "母婴", 206 to "识物")),
    )

    val sections: List<CsBrowse.Section> by lazy {
        ALL.map { s ->
            CsBrowse.Section(
                id = s.id.toString(),
                name = s.name,
                filters = listOf(
                    CsCategories.Filter("genre", "类型", s.genres.map { (id, label) -> CsCategories.Option(id.toString(), label) }),
                    CsCategories.Filter("region", "地区", REGIONS.map { CsCategories.Option(it) }),
                    CsCategories.Filter("language", "语言", LANGUAGES.map { CsCategories.Option(it) }),
                    CsCategories.Filter("year", "年份", YEARS.map { CsCategories.Option(it) }),
                ),
                sorts = SORTS.map { (value, label) -> CsCategories.Option(value, label) },
                english = s.english,
            )
        }
    }
}
