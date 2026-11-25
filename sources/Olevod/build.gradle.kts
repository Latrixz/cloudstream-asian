// Use an integer for version numbers
version = 5

cloudstream {
    iconUrl = "https://www.google.com/s2/favicons?domain=olevod.com&sz=%size%"
    // All of these properties are optional, you can safely remove any of them.

    description = "欧乐影院：电影、连续剧、综艺、动漫和短剧（公开非 VIP 播放）"
    authors = listOf("cloudstream-asian")

    /**
    * Status int as one of the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta-only
    **/
    status = 1 // Will be 3 if unspecified

    tvTypes = listOf("Movie", "TvSeries", "Anime")

    language = "zh"
}
