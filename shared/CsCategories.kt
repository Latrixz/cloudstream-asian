package com.csasian.shared

import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.newSearchResponseList
import kotlinx.coroutines.runBlocking

/**
 * Categories the app shows itself: SkyStream (dev builds whose engine sets the system property
 * `cs3engine.categories`) draws a row of category chips on home and its own category screen, with filters,
 * a sort, a search within the category and endless pages.
 *
 * The engine finds two public, non-suspending methods on the source's MainAPI by name:
 *
 *     fun csCategories(): String = CsCategories.json(listOf(CsCategories.Category(...)))
 *     fun csCategoryPage(categoryId: String, filtersJson: String, query: String, page: Int): SearchResponseList =
 *         CsCategories.page { val chosen = CsCategories.chosen(filtersJson); ...; items to hasNext }
 *
 * csCategories() is JSON: [{id, name, filters: [{key, name, options: [{value, label}]}], sorts: [{value, label}]}].
 * Options leave out "all" (an unset filter means all); the first sort is the default.
 * csCategoryPage gets the chosen options as a JSON object, {"<filter key>": "<value>", "sort": "<value>"}, unset
 * ones left out; query searches within the category ("" for none); page starts at 1. The engine maps its titles
 * as it maps search results, posters and headers alike.
 *
 * Adult categories and options are never listed (see [ADULT]).
 */
object CsCategories {
    /** The system property a host that shows categories itself sets to "true". */
    const val PROPERTY = "cs3engine.categories"

    /** The key the chosen sort travels under in filtersJson. */
    const val SORT = "sort"

    /** True where the app shows the categories itself: leave out the source's own ways into them there. */
    val hostShowsCategories: Boolean get() = System.getProperty(PROPERTY) == "true"

    data class Option(val value: String, val label: String = value)
    data class Filter(val key: String, val name: String, val options: List<Option>)
    data class Category(val id: String, val name: String, val filters: List<Filter> = emptyList(), val sorts: List<Option> = emptyList())

    private val mapper = ObjectMapper()

    private val ADULT = Regex(
        "成人|伦理|倫理|福利|情色|色情|三级|三級|里番|裏番|写真|寫真|\\bav\\b|18\\+|r18|r-18|adult|porn|erotic|hentai|xxx|nsfw|nude|naked|uncensored",
        RegexOption.IGNORE_CASE,
    )

    /** Whether a category, filter or option [label] names adult content (the broad list). */
    fun isAdult(label: String): Boolean = ADULT.containsMatchIn(label)

    /**
     * Whether a TITLE is explicit (never a synopsis). Only clearly explicit words: the broad category
     * words (伦理 "ethics", 写真 "photo shoot", "Young Adult"…) would hide ordinary titles.
     */
    fun isAdultTitle(title: String): Boolean = ADULT_TITLE.containsMatchIn(title)

    private val ADULT_TITLE = Regex(
        "情色|色情|三级片|三級片|里番|裏番|\\b18\\+|\\bporn|\\berotic|\\bhentai\\b|\\bxxx\\b|\\bnsfw\\b|\\bnude\\b|\\bnaked\\b|\\bbdsm\\b|\\buncensored\\b|\\[[a-z]{2,6}-\\d{3,5}\\]",
        RegexOption.IGNORE_CASE,
    )

    /** csCategories()'s JSON for [categories], without anything adult. */
    fun json(categories: List<Category>): String = mapper.writeValueAsString(
        categories.filterNot { isAdult(it.name) }.map { category ->
            mapOf(
                "id" to category.id,
                "name" to category.name,
                "filters" to category.filters.filterNot { isAdult(it.name) }.map { filter ->
                    mapOf("key" to filter.key, "name" to filter.name, "options" to options(filter.options))
                }.filter { (it["options"] as List<*>).isNotEmpty() },
                "sorts" to options(category.sorts),
            )
        },
    )

    private fun options(options: List<Option>) =
        options.filterNot { isAdult(it.label) }.map { mapOf("value" to it.value, "label" to it.label) }

    /** filtersJson as a map: the chosen option per filter key, and the sort under [SORT]. Blank ones are left out. */
    fun chosen(filtersJson: String): Map<String, String> = try {
        val root = mapper.readTree(filtersJson.ifBlank { "{}" })
        root.fields().asSequence()
            .filter { it.value.isValueNode && !it.value.isNull }
            .map { it.key to it.value.asText().trim() }
            .filter { it.second.isNotEmpty() }
            .toMap()
    } catch (_: Exception) {
        emptyMap()
    }

    /**
     * Runs a page's suspending code for csCategoryPage, which the engine calls off the main thread: [block]
     * returns the titles and whether another page follows. Duplicates (by URL) are dropped.
     */
    fun page(block: suspend () -> Pair<List<SearchResponse>, Boolean>): SearchResponseList = runBlocking {
        val (items, hasNext) = block()
        newSearchResponseList(items.distinctBy { it.url }, hasNext)
    }
}
