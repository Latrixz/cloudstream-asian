// Use an integer for version numbers
version = 2

cloudstream {
    iconUrl = "https://www.google.com/s2/favicons?domain=dbku.tv&sz=%size%"
    description = "独播库 Duboku：电影、连续剧（陆剧、日韩剧、台泰剧）、短剧、综艺和动漫"
    authors = listOf("cloudstream-asian")

    /**
    * Status int as one of the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta-only
    **/
    status = 1

    tvTypes = listOf("Movie", "TvSeries", "Anime")

    language = "zh"
}
