package com.xiaobao

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class XiaobaoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(XiaobaoProvider())
        com.csasian.shared.CsSupport.maybeShow()
        // The ⚙ button next to this home source opens the source switcher.
        openSettings = { ctx -> com.csasian.shared.CsSwitcher.show(ctx) }
    }
}
