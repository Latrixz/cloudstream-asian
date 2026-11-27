package com.asianhome

import android.content.Context
import android.widget.Toast
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AsianHomePlugin : Plugin() {
    override fun load(context: Context) {
        val store = AsianHomeStore(context.applicationContext ?: context)
        lateinit var provider: AsianHomeProvider
        val open: (Context) -> Unit = { ctx ->
            try {
                if (com.csasian.shared.CsLogos.onSkyStream) {
                    AsianHomeSkySettings(ctx, store, provider.name) { reloadHome(ctx) }.show()
                } else {
                    AsianHomeSettings(ctx, store, provider.name) { reloadHome(ctx) }.show()
                }
            } catch (e: Throwable) {
                Toast.makeText(ctx, "CS Asian 首页: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        provider = AsianHomeProvider(store, open)
        registerMainAPI(provider)
        com.csasian.shared.CsSupport.maybeShow()
        // The ⚙ next to "CS Asian 首页" opens the source switcher like on every CS Asian source;
        // its "Edit home rows" button opens this plugin's own row settings.
        // SkyStream: ⚙ opens the row editor directly (the switcher can't change its home source).
        openSettings = if (com.csasian.shared.CsLogos.onSkyStream) open else { ctx -> com.csasian.shared.CsSwitcher.show(ctx, "✏️ 编辑首页栏目 Edit home rows" to open) }
    }

    /** Asks the app to rebuild the home screen, which reads the provider's mainPage again. */
    private fun reloadHome(ctx: Context) {
        try {
            // The home screen listens to this event and reloads the selected home source.
            MainActivity.afterPluginsLoadedEvent.invoke(true)
            Toast.makeText(ctx, "已保存 Saved", Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
            Toast.makeText(ctx, "已保存，重新打开首页即可生效 Saved: reopen the home screen", Toast.LENGTH_LONG).show()
        }
    }
}
