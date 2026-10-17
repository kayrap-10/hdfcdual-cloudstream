package com.hdfcdual

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HDFilmCehennemiPlugin : Plugin() {
    override fun load(context: Context) {
        // Ayarlar (SharedPreferences) için Context gerekir; sağlayıcı bunları loadLinks'te okur.
        SubtitleSettings.init(context.applicationContext)

        registerMainAPI(HDFilmCehennemi())

        // Cloudstream > Ayarlar > Eklentiler listesinde bu eklentinin yanında dişli simgesi çıkar.
        // Tıklanınca aşağıdaki lambda çalışır ve altyazı ayar penceresi açılır.
        openSettings = { ctx -> SubtitleSettings.showDialog(ctx) }
    }
}
