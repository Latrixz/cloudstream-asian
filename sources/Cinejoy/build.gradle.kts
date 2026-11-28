// Use an integer for version numbers
version = 5

cloudstream {
    iconUrl = "https://raw.githubusercontent.com/Latrixz/cloudstream-asian/code/branding/icons/Cinejoy.png"
    description = "Cinejoy (cinejoy.pk): English movies and TV series from the TMDB catalogue, streamed in up to 4K HDR, with curated lists and subtitles in dozens of languages"
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
