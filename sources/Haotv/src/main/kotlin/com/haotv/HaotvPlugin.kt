package com.haotv

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HaotvPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(HaotvProvider())
        com.csasian.shared.CsSupport.maybeShow()
    }
}
