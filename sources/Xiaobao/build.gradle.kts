// Use an integer for version numbers
version = 1

cloudstream {
    iconUrl = "https://www.google.com/s2/favicons?domain=xiaobaotv.com&sz=%size%"
    description = "小宝影院 Xiaobao：电影、电视剧、综艺、动漫、短剧和纪录片"
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
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
}
