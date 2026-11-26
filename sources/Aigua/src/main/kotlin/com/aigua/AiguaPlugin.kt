package com.aigua

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AiguaPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AiguaProvider())
        com.csasian.shared.CsSupport.maybeShow()
    }
}
