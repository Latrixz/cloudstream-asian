package com.huarw

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HuarwPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(HuarwProvider())
        com.csasian.shared.CsSupport.maybeShow()
    }
}
