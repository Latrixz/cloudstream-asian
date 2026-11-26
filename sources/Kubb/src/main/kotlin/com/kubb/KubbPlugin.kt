package com.kubb

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KubbPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(KubbProvider())
        com.csasian.shared.CsSupport.maybeShow()
    }
}
