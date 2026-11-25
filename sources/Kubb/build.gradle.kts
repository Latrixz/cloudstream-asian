// Use an integer for version numbers
version = 4

cloudstream {
    iconUrl = "https://raw.githubusercontent.com/Latrixz/cloudstream-asian/code/branding/icons/Kubb.png"
    description = "酷播 Kubb：电影、电视剧、综艺、动漫、纪录片和短剧"
    authors = listOf("cloudstream-asian")

    /**
    * Status int as one of the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta-only
    **/
    status = 1

    tvTypes = listOf("Movie", "TvSeries", "Anime", "Documentary")

    language = "zh"
}
