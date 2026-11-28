// Use an integer for version numbers
version = 5

cloudstream {
    iconUrl = "https://raw.githubusercontent.com/Latrixz/cloudstream-asian/code/branding/icons/Mujizy.png"
    description = "母鸡资源 Mujizy：直连母鸡资源站（mjzy 4K60帧线路），电影、电视剧、综艺、动漫和短剧，1080P 无广告 / Direct feed from the Mujizy (母鸡资源) collection network: Chinese movies, dramas, variety shows, anime and short dramas in clean, ad-free 1080p"
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
