// Use an integer for version numbers
version = 1

cloudstream {
    iconUrl = "https://raw.githubusercontent.com/Latrixz/cloudstream-asian/code/branding/icons/Cilixiong.png"
    description = "磁力熊 Cilixiong：豆瓣高分电影和剧集，电影可在线播放，并附磁力链接（剧集仅第一集在线预览，其余用磁力下载播放） / Highly rated movies and series: movies stream online, plus magnet links (series stream episode 1 only; whole seasons as magnets)"
    authors = listOf("cloudstream-asian")

    /**
    * Status int as one of the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta-only
    **/
    status = 1

    tvTypes = listOf("Movie", "TvSeries")

    language = "zh"
}

dependencies {
    // Provided by the CloudStream app at runtime; needed here for async().
    add("compileOnly", "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
}
