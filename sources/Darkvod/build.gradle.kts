// Use an integer for version numbers
version = 3

cloudstream {
    iconUrl = "https://raw.githubusercontent.com/Latrixz/cloudstream-asian/code/branding/icons/Darkvod.png"
    description = "黑夜影院 Darkvod：电影、连续剧、综艺、动漫和爽文短剧，多条线路，自动去除插播广告 / Chinese movies, dramas, variety shows, anime and short dramas from several servers, with spliced-in ads removed"
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
    // Provided by the CloudStream app at runtime; needed here for async() and Semaphore.
    add("compileOnly", "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
}
