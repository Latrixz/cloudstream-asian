// Use an integer for version numbers
version = 6

cloudstream {
    iconUrl = "https://raw.githubusercontent.com/Latrixz/cloudstream-asian/code/branding/icons/Netflixgc.png"
    description = "奈飞工厂 Netflixgc：Netflix 及海外电影、剧集、漫剧、综艺和纪录片，自带蓝光线路，插播广告自动去除 / Netflix and international movies, series, anime, variety shows and documentaries, with the site's own Blu-ray server and spliced-in ads removed"
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

dependencies {
    // Provided by the CloudStream app at runtime; needed here for async() and Semaphore.
    add("compileOnly", "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
}
