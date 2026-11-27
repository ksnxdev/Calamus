package uz.ksnxdev.calamus

import android.app.Activity
import android.content.SharedPreferences
import org.telegram.messenger.ApplicationLoader
import uz.unnarsx.cherrygram.preferences.boolean
import uz.unnarsx.cherrygram.preferences.int

object CalamusConfig {
    private val prefs: SharedPreferences =
        ApplicationLoader.applicationContext.getSharedPreferences("calamus_config", Activity.MODE_PRIVATE)

    var useSystemColor by prefs.boolean("use_system_color", true)
    var appColor by prefs.int("app_color", 0xff677e6d.toInt())
    var switchCheckmarks by prefs.boolean("switch_checkmarks", false)
    var material3 by prefs.boolean("material3", true)
    var translucentElements by prefs.boolean("translucent_elements", true)
}
