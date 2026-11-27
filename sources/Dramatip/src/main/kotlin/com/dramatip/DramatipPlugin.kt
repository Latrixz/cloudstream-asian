package com.dramatip

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DramatipPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DramatipProvider())
        com.csasian.shared.CsSupport.maybeShow()
        // SkyStream (desktop) can't switch its home source from a plugin, so no ⚙ there.
        if (!com.csasian.shared.CsLogos.onSkyStream) openSettings = { ctx -> com.csasian.shared.CsSwitcher.show(ctx) }
    }
}
