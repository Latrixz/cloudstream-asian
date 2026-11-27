// Use an integer for version numbers
version = 11

cloudstream {
    iconUrl = "https://raw.githubusercontent.com/Latrixz/cloudstream-asian/code/branding/logo-512.png"
    description = "One home screen with your favourite rows from all your sources, search across them, and a source status check"
    authors = listOf("cloudstream-asian")

    /**
    * Status int as one of the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta-only
    **/
    status = 1

    tvTypes = listOf("Movie", "TvSeries", "Anime", "AsianDrama", "Live")

    language = "zh"
}

dependencies {
    // Provided by the CloudStream app at runtime; needed here only to compile withTimeoutOrNull.
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
}
