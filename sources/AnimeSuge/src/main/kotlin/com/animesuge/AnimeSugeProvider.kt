package com.animesuge

import com.csasian.anime.AnimeSiteProvider

/** https://animesuge.cz — all site logic lives in AnimeSiteProvider (sources/_anime-common). */
class AnimeSugeProvider : AnimeSiteProvider() {
    override var mainUrl = "https://animesuge.cz"
    override var name = "AnimeSuge CS"
    override val siteLogo = "https://animesuge.cz/animesuge/images/logo.png"
}
