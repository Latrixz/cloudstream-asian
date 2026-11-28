// Use an integer for version numbers
version = 6

cloudstream {
    iconUrl = "https://raw.githubusercontent.com/Latrixz/cloudstream-asian/code/branding/icons/Gqc.png"
    description = "共青春影院 Gqc（Cloudflare 验证）：电影、连续剧、综艺、动漫、短剧、纪录片和音乐，1080P 线路优先，自动去除插播广告 / Chinese movies, dramas, variety, anime, short dramas, documentaries and concerts; 1080p lines first, spliced-in ads removed"
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
