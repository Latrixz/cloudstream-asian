// Use an integer for version numbers
version = 2

cloudstream {
    iconUrl = "https://raw.githubusercontent.com/Latrixz/cloudstream-asian/code/branding/icons/Kankanya.png"
    description = "看看鸭 Kankanya：电影、剧集、动漫和综艺，多条播放线路 / Chinese and international movies, dramas, anime and variety shows, with several servers per title"
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

dependencies {
    // Provided by the CloudStream app at runtime; needed here for async() and Semaphore.
    add("compileOnly", "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
}
