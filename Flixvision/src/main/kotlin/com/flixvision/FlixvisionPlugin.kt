package com.flixvision

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FlixvisionPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FlixvisionProvider())
    }
}
