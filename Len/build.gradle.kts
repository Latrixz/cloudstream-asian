// Use an integer for version numbers
version = 2

cloudstream {
    iconUrl = "https://www.google.com/s2/favicons?domain=len.tv&sz=%size%"
    description = "冷映 Len：电影、电视剧、国产剧、欧美剧、韩剧、日剧、港台剧、泰剧和动漫"
    authors = listOf("cloudstream-asian")

    /**
    * Status int as one of the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta-only
    **/
    status = 1

    tvTypes = listOf("Movie", "TvSeries", "Anime", "AnimeMovie")

    language = "zh"
}
