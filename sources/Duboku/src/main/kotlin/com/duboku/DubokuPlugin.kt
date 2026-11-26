package com.duboku

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DubokuPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DubokuProvider())
        com.csasian.shared.CsSupport.maybeShow()
    }
}
