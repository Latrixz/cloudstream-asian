package com.olevod

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class OlevodPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(OlevodProvider())
        com.csasian.shared.CsSupport.maybeShow()
    }
}
