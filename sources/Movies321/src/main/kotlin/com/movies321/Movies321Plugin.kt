package com.movies321

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Movies321Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Movies321Provider())
        com.csasian.shared.CsSupport.maybeShow()
        // The ⚙ button next to this home source opens the source switcher.
        // SkyStream (desktop) can't switch its home source from a plugin, so no ⚙ there.
        if (!com.csasian.shared.CsLogos.onSkyStream) openSettings = { ctx -> com.csasian.shared.CsSwitcher.show(ctx) }
    }
}
