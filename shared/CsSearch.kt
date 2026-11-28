package com.csasian.shared

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Search in any language: "The Untamed" finds 陈情令 on a Chinese source, 琅琊榜 finds
 * "Nirvana in Fire" on an English one.
 *
 * The query is looked up on TMDB (through the keyless db.wecollege.net proxy) as typed, in movies
 * and TV. The best matches (an exact title match first, then the most popular) are then asked for
 * their title in the source's language, and those titles are searched on the source as well.
 *
 * In a provider's search:
 *   `val extra = async { CsSearch.extra(query, lang) { q -> ownSearch(q) } }` next to the normal search,
 *   then the source's own results first, followed by `extra.await()`, without duplicates
 *   ([withExtra] does exactly that for a search page).
 *
 * Nothing here can break or hold up a search: every failure gives no extra titles, the TMDB
 * lookup and the extra searches together are capped at five seconds, and answers are remembered
 * for half an hour.
 */
object CsSearch {
    private const val TMDB = "https://db.wecollege.net/3"
    // The proxy refuses some default user agents; okhttp's own is accepted.
    private val headers = mapOf("User-Agent" to "okhttp/4.12.0", "Accept" to "application/json")
    // TMDB lookup cap, and the cap for everything (lookup plus the source's searches) together.
    private const val LOOKUP_MS = 3_500L
    private const val TOTAL_MS = 5_000L
    private const val CACHE_MS = 30 * 60 * 1000L
    private const val CANDIDATES = 8
    private const val DETAILS = 2
    private const val MAX_QUERIES = 5
    private val PUNCT = Regex("[\\p{Punct}：；，。、·・「」『』（）《》〈〉【】！？\\s]+")
    private const val CACHE_SIZE = 300

    private val mapper = ObjectMapper()
    private val cache = ConcurrentHashMap<String, Pair<Long, List<String>>>()
    /** Searches added only as a series' name ("饥饿游戏" for "饥饿游戏：日出收割"), not as a title of their own. */
    /** Per search (the cache key), the searches added only as a series' name. */
    private val seriesOnly = ConcurrentHashMap<String, Set<String>>()

    private fun key(query: String, targetLang: String) = "$targetLang|${query.trim().lowercase()}"

    /**
     * Up to three other titles to search for [query] in [targetLang] ("zh", "en", "ko", ...).
     * Empty when the query is already in the source's script, is very short, or nothing matched.
     */
    suspend fun translations(query: String, targetLang: String): List<String> {
        val q = query.trim()
        if (!worthTranslating(q, targetLang)) return emptyList()
        val key = key(q, targetLang)
        cache[key]?.let { (time, names) -> if (System.currentTimeMillis() - time < CACHE_MS) return names }
        val names = try {
            withTimeoutOrNull(LOOKUP_MS) { lookup(q, targetLang) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        } ?: return emptyList() // Timeouts and errors aren't cached, so the next search tries again.
        val now = System.currentTimeMillis()
        if (cache.size >= CACHE_SIZE) cache.entries.removeIf { now - it.value.first >= CACHE_MS }
        if (cache.size >= CACHE_SIZE) cache.clear()
        cache[key] = now to names
        return names
    }

    /**
     * The source's results for the translated titles of [query], in order and without duplicates.
     * [search] is the source's own search for one query; its failures are ignored.
     */
    suspend fun extra(
        query: String,
        targetLang: String,
        search: suspend (String) -> List<SearchResponse>,
    ): List<SearchResponse> {
        if (!worthTranslating(query.trim(), targetLang)) return emptyList()
        return try {
            withTimeoutOrNull(TOTAL_MS) {
                val queries = translations(query, targetLang)
                // Per translated title, the source's exact title first, then titles starting with it
                // ("琅琊榜" before "琅琊榜之风起长林" and "琅琊榜网络版").
                queries.amap { q ->
                    val want = norm(q)
                    val belongs = belongsTo(q, queries, seriesOnly[key(query, targetLang)].orEmpty())
                    runCatching { search(q) }.getOrDefault(emptyList()).filter(belongs).sortedWith(
                        compareByDescending<SearchResponse> { norm(it.name) == want }
                            .thenByDescending { norm(it.name).startsWith(want) },
                    )
                }.flatten().distinctBy { it.url }.filterNot { CsCategories.isAdultTitle(it.name) }
            }.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emptyList()
        }
    }

    /**
     * Which results of the search for [q] to keep. A full title keeps everything. A series name
     * tried for a longer title ("饥饿游戏" for "饥饿游戏：日出收割") keeps only results that share a
     * piece of what the longer title adds ("日出", "收割"), so the series' other films don't show up as
     * the title; when that addition is only a season or "movie" marker ("第二季", "剧场版"), the
     * series' own page is the right result and everything is kept.
     */
    private fun belongsTo(q: String, queries: List<String>, seriesOnly: Set<String>): (SearchResponse) -> Boolean {
        val base = norm(q)
        if (base !in seriesOnly) return { true }
        val longer = queries.map { norm(it) }.filter { it.length > base.length && it.startsWith(base) }
        if (longer.isEmpty()) return { true }
        val extras = longer.map { it.removePrefix(base).replace(MARKERS, "") }
        if (extras.any { it.length < 2 }) return { true }
        val pieces = extras.flatMap { e -> e.windowed(2) }.toSet()
        return { item ->
            val name = norm(item.name)
            longer.any { name.startsWith(it) } || pieces.any { name.contains(it) }
        }
    }

    /**
     * A source's search page with the translated titles added on page 1: [own] (the source's search for
     * a query and page) runs at the same time as the translation lookup; its results come first, then
     * the extra titles without duplicates. The next-page flag is the source's own.
     *
     *   `private suspend fun searchPage(query: String, page: Int) = CsSearch.withExtra(query, page, lang) { q, p -> searchOwn(q, p) }`
     */
    suspend fun withExtra(
        query: String,
        page: Int,
        targetLang: String,
        own: suspend (String, Int) -> Pair<List<SearchResponse>, Boolean>,
    ): Pair<List<SearchResponse>, Boolean> = coroutineScope {
        if (query.isBlank()) return@coroutineScope emptyList<SearchResponse>() to false
        val translated = if (page <= 1) async { extra(query, targetLang) { own(it, 1).first } } else null
        val (items, hasNext) = own(query, page)
        // The shared adult filter, by title only.
        (items + translated?.await().orEmpty()).distinctBy { it.url }.filterNot { CsCategories.isAdultTitle(it.name) } to hasNext
    }

    private suspend fun lookup(query: String, targetLang: String): List<String> = coroutineScope {
        val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val tv = async { candidates("tv", "search/tv?query=$encoded&language=en-US&include_adult=false") }
        val movie = async { candidates("movie", "search/movie?query=$encoded&language=en-US&include_adult=false") }
        val wanted = norm(query)
        // Exact title matches first, then the most popular titles that start with the query (so a
        // partial "hidden" also tries "Hidden Love"), then anything else by popularity.
        val ranked = (tv.await() + movie.await())
            .sortedWith(
                compareByDescending<Candidate> { it.matches(wanted) }
                    .thenByDescending { it.startsWith(wanted) }
                    .thenByDescending { it.popularity },
            )
            .let { all ->
                // Exact matches and titles starting with the query ("Demon Slayer" exactly matches an
                // obscure film; the anime is "Demon Slayer: Kimetsu no Yaiba"). Only without an exact
                // match, TMDB's most popular hits too: they match on alternative titles ("Battle Through
                // the Heavens" is listed as "Fights Break Sphere", 斗破苍穹). With one, they would be a
                // film's sequels and prequels, shown as if they were the title.
                val exact = all.filter { it.matches(wanted) }.take(DETAILS)
                val longer = all.filter { !it.matches(wanted) && it.startsWith(wanted) }.take(DETAILS)
                if (exact.isNotEmpty()) exact + longer
                else longer + all.sortedByDescending { it.popularity }.take(DETAILS)
            }
            .distinctBy { it.kind + it.id }
            .take(DETAILS * 2)
        val tmdbLang = tmdbLanguage(targetLang)
        ranked.amap { candidate ->
            val local = if (tmdbLang == "en-US") candidate.name
            else runCatching {
                json("${candidate.kind}/${candidate.id}?language=$tmdbLang")?.let { it.text("name") ?: it.text("title") }
            }.getOrNull()
            listOfNotNull(local, candidate.original)
        }.flatten()
            .map { it.trim() }
            .filter { it.isNotEmpty() && inScript(it, targetLang) && norm(it) != wanted }
            .let { names ->
                // Sites write titles without punctuation ("斗破苍穹剧场版双帝之战"), and the shared start
                // of the names ("斗破苍穹") finds the whole series, so try those too, the shared start first.
                val cjk = tmdbLang.substringBefore('-') in setOf("zh", "ja", "ko")
                val cleaned = if (cjk) names.map { it.replace(PUNCT, "") }.filter { it.isNotEmpty() } else names
                val core = if (cjk) commonPrefix(cleaned).takeIf { cleaned.size > 1 && it.length >= 2 } else null
                // A movie, special or season of a series also tries the series' own name
                // (斗破苍穹剧场版：双帝之战 → 斗破苍穹), after the full names.
                val bases = if (cjk) names.mapNotNull { baseTitle(it) } else emptyList()
                val titles = (listOfNotNull(core) + cleaned).map { norm(it) }.toSet()
                if (seriesOnly.size >= CACHE_SIZE) seriesOnly.clear()
                seriesOnly[key(query, targetLang)] = bases.map { norm(it) }.filter { it !in titles }.toSet()
                listOfNotNull(core) + cleaned + bases
            }
            .distinctBy { norm(it) }
            .take(MAX_QUERIES)
    }

    private suspend fun candidates(kind: String, path: String): List<Candidate> =
        json(path)?.get("results")?.take(CANDIDATES)?.mapNotNull { item ->
            val id = item.get("id")?.asInt() ?: return@mapNotNull null
            if (item.get("adult")?.asBoolean() == true) return@mapNotNull null
            Candidate(
                kind = kind,
                id = id,
                name = item.text("name") ?: item.text("title"),
                original = item.text("original_name") ?: item.text("original_title"),
                popularity = item.get("popularity")?.asDouble() ?: 0.0,
            )
        }.orEmpty()

    private suspend fun json(path: String): JsonNode? {
        val response = app.get("$TMDB/$path", headers = headers, timeout = 5L)
        if (!response.isSuccessful) return null
        return mapper.readTree(response.text)
    }

    private fun JsonNode.text(field: String): String? = get(field)?.asText()?.takeIf { it.isNotBlank() }

    private data class Candidate(
        val kind: String,
        val id: Int,
        val name: String?,
        val original: String?,
        val popularity: Double,
    ) {
        fun matches(wanted: String) = norm(name.orEmpty()) == wanted || norm(original.orEmpty()) == wanted
        fun startsWith(wanted: String) = norm(name.orEmpty()).startsWith(wanted) || norm(original.orEmpty()).startsWith(wanted)
    }

    private fun worthTranslating(query: String, targetLang: String): Boolean {
        val letters = query.count { it.isLetterOrDigit() }
        val latinOnly = query.filter { it.isLetter() }.all { script(it) == Character.UnicodeScript.LATIN }
        if (letters < if (latinOnly) 3 else 2) return false
        return !inScript(query, targetLang)
    }

    /** Whether [text] is written the way titles on a [lang] source are (Chinese: Han characters, no kana or hangul). */
    private fun inScript(text: String, lang: String): Boolean {
        val scripts = text.filter { it.isLetter() }.map { script(it) }.toSet()
        if (scripts.isEmpty()) return false
        val kana = Character.UnicodeScript.HIRAGANA in scripts || Character.UnicodeScript.KATAKANA in scripts
        val hangul = Character.UnicodeScript.HANGUL in scripts
        return when (lang.substringBefore('-').lowercase()) {
            "zh" -> Character.UnicodeScript.HAN in scripts && !kana && !hangul
            "ja" -> kana || (Character.UnicodeScript.HAN in scripts && !hangul)
            "ko" -> hangul
            "th" -> Character.UnicodeScript.THAI in scripts
            else -> scripts.all { it == Character.UnicodeScript.LATIN }
        }
    }

    private fun script(c: Char): Character.UnicodeScript = Character.UnicodeScript.of(c.code)

    private fun tmdbLanguage(lang: String): String = when (lang.substringBefore('-').lowercase()) {
        "zh" -> "zh-CN"
        "en" -> "en-US"
        "ko" -> "ko-KR"
        "ja" -> "ja-JP"
        "th" -> "th-TH"
        "vi" -> "vi-VN"
        "id" -> "id-ID"
        else -> lang
    }

    /** What a season, special or film of a series adds to its name without naming anything: "第二季", "剧场版", "2". */
    private val MARKERS = Regex("剧场版|电影版|特别篇|番外|年番|完整版|未删减|第[一二三四五六七八九十0-9]+[季部集]|[0-9一二三四五六七八九十]|之|季|部")

    private val SERIES_PART = Regex("剧场版|电影版|特别篇|番外|年番|第[一二三四五六七八九十0-9]+[季部集]|[：:（(·・]|之")

    /** "斗破苍穹剧场版：双帝之战" → "斗破苍穹": the name before a movie/special/season marker, or null. */
    private fun baseTitle(name: String): String? {
        val cut = SERIES_PART.find(name)?.range?.first ?: return null
        return name.substring(0, cut).replace(PUNCT, "").trimEnd { it.isDigit() }.takeIf { it.length >= 2 }
    }

    /** The start that all [names] share (whole characters only). */
    private fun commonPrefix(names: List<String>): String {
        if (names.isEmpty()) return ""
        var prefix = names.first()
        for (n in names.drop(1)) {
            var i = 0
            while (i < prefix.length && i < n.length && prefix[i] == n[i]) i++
            prefix = prefix.substring(0, i)
        }
        return prefix.trimEnd { it.isDigit() || it == '第' || it == '之' }
    }

    private fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }
}
