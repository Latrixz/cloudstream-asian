// Use an integer for version numbers
version = 11

cloudstream {
    iconUrl = "https://raw.githubusercontent.com/Latrixz/cloudstream-asian/code/branding/icons/Olevod.png"
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
