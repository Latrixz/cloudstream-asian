// Use an integer for version numbers
version = 11

cloudstream {
    iconUrl = "https://raw.githubusercontent.com/Latrixz/cloudstream-asian/code/branding/icons/LiveTv.png"
    description = "Free, publicly available live TV channels from the iptv-org project"
    authors = listOf("cloudstream-asian")

    /**
    * Status int as one of the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta-only
    **/
    status = 1

    tvTypes = listOf("Live")

    language = "zh"
}

dependencies {
    // Provided by the CloudStream app at runtime; needed here only to compile the Mutex and async.
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
}
