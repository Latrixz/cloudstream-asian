// Use an integer for version numbers
version = 4

// The site logic is shared with the other site on the same backend (sources/_anime-common).
extensions.getByName<com.android.build.gradle.BaseExtension>("android")
    .sourceSets.getByName("main").java.srcDir("../_anime-common")

cloudstream {
    iconUrl = "https://raw.githubusercontent.com/Latrixz/cloudstream-asian/code/branding/icons/AnimeSuge.png"
    description = "AnimeSuge (animesuge.cz): anime and anime movies with English sub and dub, plus subtitles in several languages"
    authors = listOf("cloudstream-asian")

    /**
    * Status int as one of the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta-only
    **/
    status = 1

    tvTypes = listOf("Anime", "AnimeMovie", "OVA")

    language = "en"
}
