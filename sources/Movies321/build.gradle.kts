// Use an integer for version numbers
version = 4

cloudstream {
    iconUrl = "https://raw.githubusercontent.com/Latrixz/cloudstream-asian/code/branding/icons/Movies321.png"
    description = "321Movies (321movies.xyz): movies, TV series, K-dramas and anime from the TMDB catalogue, streamed from the site's vidsrc.sh player with 40+ subtitle languages. Plays in CloudStream; in SkyStream it only plays when the site's own servers are up"
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
    // Provided by the CloudStream app at runtime; needed here only to compile the Mutex.
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
}
