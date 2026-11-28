// Use an integer for version numbers
version = 5

cloudstream {
    iconUrl = "https://raw.githubusercontent.com/Latrixz/cloudstream-asian/code/branding/icons/Nangua.png"
    description = "南瓜影视 Nangua：电影、电视剧、综艺、动漫、短剧和纪录片，多条线路，插播广告自动去除 / Chinese movies, dramas, variety shows, anime, short dramas and documentaries, with several servers and spliced-in ads removed"
    authors = listOf("cloudstream-asian")

    /**
    * Status int as one of the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta-only
    **/
    status = 1

    tvTypes = listOf("Movie", "TvSeries", "Anime", "AsianDrama", "Documentary")

    language = "zh"
}

dependencies {
    // Provided by the CloudStream app at runtime; needed here for async().
    add("compileOnly", "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
}
