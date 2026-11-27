// Use an integer for version numbers
version = 2

cloudstream {
    iconUrl = "https://raw.githubusercontent.com/Latrixz/cloudstream-asian/code/branding/icons/Dm233.png"
    description = "233动漫 Dm233：日漫、国漫、剧场版、美漫和特摄，中文字幕，精品线路 1080P 直连 / Japanese, Chinese and American anime, anime movies and tokusatsu with Chinese subtitles, including the site's own 1080p server"
    authors = listOf("cloudstream-asian")

    /**
    * Status int as one of the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta-only
    **/
    status = 1

    tvTypes = listOf("Anime", "AnimeMovie")

    language = "zh"
}

dependencies {
    // Provided by the CloudStream app at runtime; needed here for async().
    add("compileOnly", "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
}
