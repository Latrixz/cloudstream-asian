package com.nbyy

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NbyyPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(NbyyProvider())
        com.csasian.shared.CsSupport.maybeShow()
    }
}
