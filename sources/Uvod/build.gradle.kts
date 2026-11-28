// Use an integer for version numbers
version = 3

cloudstream {
    iconUrl = "https://raw.githubusercontent.com/Latrixz/cloudstream-asian/code/branding/icons/Uvod.png"
    description = "优视频 UVOD：电影、电视剧、综艺、动漫、纪录片、粤语和儿童节目（免费标清） / Chinese movies, dramas, variety shows, anime, documentaries, Cantonese and kids' titles (free SD tier)"
    authors = listOf("cloudstream-asian")

    /**
    * Status int as one of the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta-only
    **/
    status = 1

    tvTypes = listOf("Movie", "TvSeries", "Anime", "AsianDrama", "Documentary")

    language = "zh"
}
