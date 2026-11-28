// Use an integer for version numbers
version = 2

cloudstream {
    iconUrl = "https://raw.githubusercontent.com/Latrixz/cloudstream-asian/code/branding/icons/Zxcstream.png"
    description = "ZXCStream (zxcstream.com): movies, TV series, K-dramas and anime from the TMDB catalogue. Plays from several servers without a WebView: 1080p multi-audio HLS, 4K on many movies, and dubbed audio (Hindi, Tagalog, Indonesian, Tamil, Telugu and more), with subtitles"
    authors = listOf("cloudstream-asian")

    /**
    * Status int as one of the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta-only
    **/
    status = 1

    tvTypes = listOf("Movie", "TvSeries", "AsianDrama", "Anime")

    language = "en"
}

dependencies {
    // Provided by the CloudStream app at runtime; needed here only to compile the coroutine helpers.
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
}
