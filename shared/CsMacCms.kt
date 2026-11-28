package com.csasian.shared

import java.net.URLEncoder

/**
 * Category browsing (shared/CsBrowse.kt) for MacCMS sites: sections, filters and list URLs from a
 * small description of the site's own filter page.
 *
 *     private val site = CsMacCms.Site(
 *         CsMacCms.Path("/show/", suffix = ""),          // or CsMacCms.Dash("/vodshow/")
 *         types = listOf(CsMacCms.Type("dianying", "电影", classes = listOf("动作", "喜剧"), subtypes = ...)),
 *         areas = listOf("大陆", "香港"), langs = listOf("国语", "粤语"), years = CsMacCms.years(),
 *         sorts = listOf("time" to "最新", "hits" to "最热", "score" to "评分"),   // newest first
 *     )
 *     private val browse = CsBrowse(this, "华人影院 Huarw", site.sections) { id, filters, query, page ->
 *         val document = getPage(site.url(mainUrl, id, filters, query, page))
 *         cardsOf(document) to hasNextPage(document)
 *     }
 *
 * The filter keys are MacCMS's own: [TYPE] (a sub-category, which replaces the section's type id),
 * [CLASS] (剧情 / 类型), [AREA], [LANG], [YEAR] and [LETTER]; the sort is MacCMS's "by".
 * Adult types and options are left out by CsBrowse.
 */
object CsMacCms {
    const val TYPE = "type"
    const val CLASS = "class"
    const val AREA = "area"
    const val LANG = "lang"
    const val YEAR = "year"
    const val LETTER = "letter"

    /** How a site writes its vod/show URLs. */
    interface Style {
        /** The list URL path for [params] (MacCMS names: id, area, by, class, lang, letter, page, year, wd). */
        fun path(params: Map<String, String>): String
    }

    /**
     * `{prefix}{id}-{area}-{by}-{class}-{lang}-{letter}-{level}-{order}-{page}-{state}-{tag}-{year}{suffix}`,
     * e.g. /vodshow/2---喜剧--------2025.html. A keyword goes in as `/wd/{q}` before the suffix, as
     * MacCMS's own search pages do. [pageOne]: whether page 1 is written out (false leaves it blank).
     */
    class Dash(
        private val prefix: String,
        private val suffix: String = ".html",
        private val pageOne: Boolean = true,
    ) : Style {
        override fun path(params: Map<String, String>): String {
            val fields = DASH.map { key ->
                val v = params[key].orEmpty()
                if (key == "page" && v == "1" && !pageOne) "" else enc(v)
            }
            val wd = params["wd"]?.takeIf { it.isNotBlank() }?.let { "/wd/${enc(it)}" }.orEmpty()
            return prefix + fields.joinToString("-") + wd + suffix
        }
    }

    /**
     * `{prefix}{id}/area/{a}/by/{b}/class/{c}/lang/{l}/page/{p}/wd/{q}/year/{y}{suffix}`: key/value pairs
     * in alphabetical order, as MacCMS writes them. [idInPath] false puts the type id among the pairs
     * (`.../by/time/id/2/year/2024.html`) instead of right after the prefix.
     */
    class Path(
        private val prefix: String,
        private val suffix: String = ".html",
        private val idInPath: Boolean = true,
        private val pageOne: Boolean = true,
    ) : Style {
        override fun path(params: Map<String, String>): String {
            val pairs = params.filter { (k, v) ->
                v.isNotBlank() && (k != "id" || !idInPath) && !(k == "page" && v == "1" && !pageOne)
            }.toSortedMap()
            val head = if (idInPath) prefix + enc(params["id"].orEmpty()) else prefix.trimEnd('/')
            val tail = pairs.entries.joinToString("") { (k, v) -> "/$k/${enc(v)}" }
            return (head + tail).let { if (!idInPath && tail.isEmpty()) "$it/" else it } + suffix
        }
    }

    /** A site style written by hand: [build] gets the params and returns the path. */
    class Custom(private val build: (Map<String, String>) -> String) : Style {
        override fun path(params: Map<String, String>): String = build(params)
    }

    /**
     * A top-level type (a section). [classes] are its 剧情/类型 labels, [subtypes] its sub-categories
     * (type id to label). [areas], [langs] and [years] override the site's lists for this type
     * (an empty list: no such filter here).
     */
    data class Type(
        val id: String,
        val name: String,
        val classes: List<String> = emptyList(),
        val subtypes: List<Pair<String, String>> = emptyList(),
        val areas: List<String>? = null,
        val langs: List<String>? = null,
        val years: List<String>? = null,
        val english: String? = null,
        val glyph: String? = null,
    )

    /**
     * A site: its URL [style], [types] and the filters every type shares; [sorts] are "by" values to
     * labels, newest first (the first is the default). [names] relabels the filter rows.
     */
    class Site(
        val style: Style,
        val types: List<Type>,
        val areas: List<String> = emptyList(),
        val langs: List<String> = emptyList(),
        val years: List<String> = emptyList(),
        val sorts: List<Pair<String, String>> = listOf("time" to "最新", "hits" to "最热", "score" to "评分"),
        val letters: Boolean = false,
        private val names: Map<String, String> = emptyMap(),
    ) {
        private fun name(key: String, default: String) = names[key] ?: default

        val sections: List<CsBrowse.Section> = types.map { t ->
            fun filter(key: String, label: String, values: List<String>) =
                CsCategories.Filter(key, name(key, label), values.map { CsCategories.Option(it) })
            val filters = listOfNotNull(
                CsCategories.Filter(TYPE, name(TYPE, "分类"), t.subtypes.map { (id, label) -> CsCategories.Option(id, label) }),
                filter(CLASS, "类型", t.classes),
                filter(AREA, "地区", t.areas ?: areas),
                filter(LANG, "语言", t.langs ?: langs),
                filter(YEAR, "年份", t.years ?: years),
                if (letters) filter(LETTER, "字母", ('A'..'Z').map { it.toString() } + "0~9") else null,
            ).filter { it.options.isNotEmpty() }
            CsBrowse.Section(
                id = t.id,
                name = t.name,
                filters = filters,
                sorts = sorts.map { (v, label) -> CsCategories.Option(v, label) },
                english = t.english ?: CsBrowse.englishFor(t.name),
                glyph = t.glyph ?: CsBrowse.glyphFor(t.name),
                slug = t.id,
            )
        }

        /** The MacCMS params for one page: type id (a chosen sub-type wins), filters, by, page and keyword. */
        fun params(sectionId: String, filters: Map<String, String>, query: String = "", page: Int = 1): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            out["id"] = filters[TYPE]?.takeIf { it.isNotBlank() } ?: sectionId
            listOf(AREA, CLASS, LANG, LETTER, YEAR).forEach { k -> filters[k]?.takeIf { it.isNotBlank() }?.let { out[k] = it } }
            filters[CsCategories.SORT]?.takeIf { it.isNotBlank() }?.let { out["by"] = it }
            out["page"] = page.coerceAtLeast(1).toString()
            if (query.isNotBlank()) out["wd"] = query.trim()
            return out
        }

        /** The full list URL on [mainUrl]. */
        fun url(mainUrl: String, sectionId: String, filters: Map<String, String>, query: String = "", page: Int = 1): String =
            mainUrl.trimEnd('/') + style.path(params(sectionId, filters, query, page))
    }

    /** Year labels: this year back [count] years. */
    fun years(count: Int = 15): List<String> {
        val now = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        return (now downTo now - count + 1).map { it.toString() }
    }

    private val DASH = listOf("id", "area", "by", "class", "lang", "letter", "level", "order", "page", "state", "tag", "year")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
