// Use an integer for version numbers
version = 5

cloudstream {
    iconUrl = "https://raw.githubusercontent.com/Latrixz/cloudstream-asian/code/branding/icons/Ncat.png"
    description = "网飞猫 Ncat：电影、连续剧、动漫、综艺纪录和短剧，自有 1080P 超清线路优先，自动去除插播广告 / Movies, dramas, anime, variety shows and short dramas from ncat30.com, with the site's own 1080p server first and spliced-in ads removed"
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
