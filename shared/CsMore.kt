package com.csasian.shared

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newHomePageResponse
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * "更多 More ›" for SkyStream, whose engine never asks a plugin for a home row's second page.
 *
 * On SkyStream, a row that has more pages gets one extra card at its end. Opening it shows a details
 * page whose "recommended" list is the row's next page, which again ends with a "More ›" card, so a
 * whole row can be walked through. CloudStream pages rows itself, so nothing changes there.
 *
 * In a provider:
 *   getMainPage: `return CsMore.wrap(this, request, page, newHomePageResponse(request.name, items, hasNext))`
 *   load:        `CsMore.load(this, url) ?: loadPage(url).also { CsBackup.remember(this, it) }`
 */
object CsMore {
    private const val MARK = "#csmore"

    /** Adds the "More ›" card to [response] when running on SkyStream and the row has another page. */
    fun wrap(api: MainAPI, request: MainPageRequest, page: Int, response: HomePageResponse): HomePageResponse {
        if (!CsLogos.onSkyStream || !response.hasNext) return response
        val lists = response.items.map { list ->
            HomePageList(list.name, list.list + card(api, request, page + 1), list.isHorizontalImages)
        }
        return newHomePageResponse(lists, response.hasNext)
    }

    private fun card(api: MainAPI, request: MainPageRequest, nextPage: Int): SearchResponse {
        val url = "${api.mainUrl.trimEnd('/')}/$MARK?n=${enc(request.name)}&d=${enc(request.data)}" +
            "&h=${request.horizontalImages}&p=$nextPage"
        return api.newMovieSearchResponse("更多 More › ${request.name}", url, TvType.Others) {
            posterUrl = CsLogos.REPO_LOGO
        }
    }

    /** The details page for a "More ›" card (the row's next page as its list), or null for any other URL. */
    suspend fun load(api: MainAPI, url: String): LoadResponse? {
        if (!url.contains("/$MARK?")) return null
        val params = url.substringAfter("/$MARK?").split('&')
            .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }
        val name = params["n"].orEmpty()
        val page = params["p"]?.toIntOrNull() ?: 2
        val request = MainPageRequest(name, params["d"].orEmpty(), params["h"] == "true")
        val items = api.getMainPage(page, request)?.items?.flatMap { it.list }.orEmpty()
        return api.newMovieLoadResponse("$name · 第 $page 页 Page $page", url, TvType.Others, url) {
            posterUrl = CsLogos.REPO_LOGO
            plot = if (items.isEmpty()) "没有更多了 · Nothing more in this row."
            else "下面是这一栏的第 $page 页，最后一张「更多 More ›」是下一页。\n" +
                "Below is page $page of this row; the last card, \"More ›\", opens the next page."
            recommendations = items
        }
    }

    /** True for a "More ›" page's own play data, so a source can refuse it quickly. */
    fun isMore(data: String): Boolean = data.contains("/$MARK?")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
