package com.suikan

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SuikanPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SuikanProvider())
        com.csasian.shared.CsSupport.maybeShow()
        // The ⚙ button next to this home source opens the source switcher.
        openSettings = { ctx -> com.csasian.shared.CsSwitcher.show(ctx) }
    }
}
