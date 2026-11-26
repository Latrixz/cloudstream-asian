// Use an integer for version numbers
version = 4

cloudstream {
    iconUrl = "https://raw.githubusercontent.com/Latrixz/cloudstream-asian/code/branding/icons/Rivo.png"
    description = "Rivo (rivo.st): movies and TV series from the TMDB catalogue, streamed from the site's own HLS servers with subtitles"
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
