package com.ddys

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DdysPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DdysProvider())
        com.csasian.shared.CsSupport.maybeShow()
        // The ⚙ button next to this home source opens the source switcher.
        openSettings = { ctx -> com.csasian.shared.CsSwitcher.show(ctx) }
    }
}
