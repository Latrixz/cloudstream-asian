// Use an integer for version numbers
version = 5

cloudstream {
    iconUrl = "https://raw.githubusercontent.com/Latrixz/cloudstream-asian/code/branding/icons/Csxyy.png"
    description = "CN影院 Csxyy：电影、电视剧、综艺、动漫和短剧，自动去除插播广告 / Chinese movies, dramas, variety shows, anime and short dramas, with spliced-in ads removed"
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
