package com.crackle

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CracklePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CrackleProvider())
    }
}
