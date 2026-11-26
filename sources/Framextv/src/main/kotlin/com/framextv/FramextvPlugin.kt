package com.framextv

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FramextvPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FramextvProvider())
        com.csasian.shared.CsSupport.maybeShow()
    }
}
