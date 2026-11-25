package com.mvffm

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MvffmPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MvffmProvider())
    }
}
