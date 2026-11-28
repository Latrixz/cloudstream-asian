// Use an integer for version numbers
version = 2

// MegaPlay (older episodes' player) is shared with the other anime sources (sources/_anime-common).
extensions.getByName<com.android.build.gradle.BaseExtension>("android")
    .sourceSets.getByName("main").java.srcDir("../_anime-common")

cloudstream {
    iconUrl = "https://raw.githubusercontent.com/Latrixz/cloudstream-asian/code/branding/icons/Animenosub.png"
    description = "Animenosub (animenosub.to): anime series and movies with English sub, English dub and raw, English dub listed first"
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
