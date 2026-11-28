// Use an integer for version numbers
version = 5

cloudstream {
    iconUrl = "https://raw.githubusercontent.com/Latrixz/cloudstream-asian/code/branding/icons/Qwfilm.png"
    description = "七味网 Qwfilm（试用版，Cloudflare 验证）：电影、剧集、综艺、动漫和短剧 / Trial: Chinese movies, dramas, variety, anime and short dramas"
    authors = listOf("cloudstream-asian")

    /**
    * Status int as one of the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta-only
    **/
    status = 1

    tvTypes = listOf("Movie", "TvSeries", "Anime", "AsianDrama")

    language = "zh"
}

dependencies {
    // Provided by the CloudStream app at runtime; needed here for async().
    add("compileOnly", "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
}
