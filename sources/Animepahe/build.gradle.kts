// Use an integer for version numbers
version = 1

// MegaPlay (some episodes' player) is shared with the other anime sources (sources/_anime-common).
extensions.getByName<com.android.build.gradle.BaseExtension>("android")
    .sourceSets.getByName("main").java.srcDir("../_anime-common")

cloudstream {
    iconUrl = "https://raw.githubusercontent.com/Latrixz/cloudstream-asian/code/branding/icons/Animepahe.png"
    description = "Animepahe (animepahe.ch): anime series and movies with English sub and some English dubs"
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
