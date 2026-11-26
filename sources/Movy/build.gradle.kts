// Use an integer for version numbers
version = 4

cloudstream {
    iconUrl = "https://raw.githubusercontent.com/Latrixz/cloudstream-asian/code/branding/icons/Movy.png"
    description = "Movy (movy.sx): movies and TV series from the TMDB catalogue, streamed from the site's own multi-server backend"
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

    language = "en"
}

dependencies {
    // Provided by the CloudStream app at runtime; needed here only to compile the Mutex.
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
}
