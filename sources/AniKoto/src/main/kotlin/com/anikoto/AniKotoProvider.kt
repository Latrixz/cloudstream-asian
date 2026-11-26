package com.anikoto

import com.csasian.anime.AnimeSiteProvider

/** https://anikototv.bz — all site logic lives in AnimeSiteProvider (sources/_anime-common). */
class AniKotoProvider : AnimeSiteProvider() {
    override var mainUrl = "https://anikototv.bz"
    override var name = "AniKoto CS"
    override val siteLogo = "https://anikototv.bz/AnikotoTheme/assets/images/logo.png"
}
