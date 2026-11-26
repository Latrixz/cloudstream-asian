// Use an integer for version numbers
version = 1

cloudstream {
    iconUrl = "https://raw.githubusercontent.com/Latrixz/cloudstream-asian/code/branding/icons/FourKHDHub.png"
    description = "4KHDHub (4khdhub.one): Hollywood, Bollywood, Korean and anime movies and series with Hindi/English audio. Plays the site's HubCloud files directly; normal 1080p/720p files are listed first, 4K/Dolby Vision/remux last"
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
    // Provided by the CloudStream app at runtime; needed here only to compile async().
    add("compileOnly", "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
}
