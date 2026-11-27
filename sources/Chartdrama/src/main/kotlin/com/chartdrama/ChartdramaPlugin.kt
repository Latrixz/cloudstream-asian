package com.chartdrama

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ChartdramaPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(ChartdramaProvider())
        com.csasian.shared.CsSupport.maybeShow()
        // The ⚙ button next to this home source opens the source switcher.
        // SkyStream (desktop) can't switch its home source from a plugin, so no ⚙ there.
        if (!com.csasian.shared.CsLogos.onSkyStream) openSettings = { ctx -> com.csasian.shared.CsSwitcher.show(ctx) }
    }
}
