package com.dramanice

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DramanicePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DramaniceProvider())
        com.csasian.shared.CsSupport.maybeShow()
        // The ⚙ button next to this home source opens the source switcher (not on SkyStream).
        if (!com.csasian.shared.CsLogos.onSkyStream) openSettings = { ctx -> com.csasian.shared.CsSwitcher.show(ctx) }
    }
}
