// Use an integer for version numbers
version = 3

cloudstream {
    iconUrl = "https://raw.githubusercontent.com/Latrixz/cloudstream-asian/code/branding/icons/Mvffm.png"
    description = "Movieffm 電影線上看：電影、連續劇、綜藝、動漫和紀錄片"
    authors = listOf("cloudstream-asian")

    /**
    * Status int as one of the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta-only
    **/
    status = 1

    tvTypes = listOf("Movie", "TvSeries", "Anime", "Documentary")

    language = "zh"
}
