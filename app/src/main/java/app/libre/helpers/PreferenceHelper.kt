package app.libre.helpers

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import app.libre.helpers.LocaleHelper.getDetectedCountry

object PreferenceHelper {
    /**
     * SharedPreferences instance
     */
    lateinit var settings: SharedPreferences

    fun initialize(context: Context) {
        settings = getDefaultSharedPreferences(context)
    }

    fun migrate() {
        // No migrations needed for Libre
    }

    fun putString(key: String, value: String) {
        settings.edit(commit = true) { putString(key, value) }
    }

    fun putBoolean(key: String, value: Boolean) {
        settings.edit(commit = true) { putBoolean(key, value) }
    }

    fun putInt(key: String, value: Int) {
        settings.edit(commit = true) { putInt(key, value) }
    }

    fun putLong(key: String, value: Long) {
        settings.edit(commit = true) { putLong(key, value) }
    }

    fun putStringSet(key: String, value: Set<String>) {
        settings.edit(commit = true) { putStringSet(key, value) }
    }

    fun remove(key: String) {
        settings.edit(commit = true) { remove(key) }
    }

    fun getString(key: String?, defValue: String): String {
        return settings.getString(key, defValue) ?: defValue
    }

    fun getBoolean(key: String?, defValue: Boolean): Boolean {
        return settings.getBoolean(key, defValue)
    }

    fun getInt(key: String?, defValue: Int): Int {
        return runCatching {
            settings.getInt(key, defValue)
        }.getOrElse { settings.getLong(key, defValue.toLong()).toInt() }
    }

    fun getLong(key: String?, defValue: Long): Long {
        return settings.getLong(key, defValue)
    }

    fun getStringSet(key: String?, defValue: Set<String>): Set<String> {
        return settings.getStringSet(key, defValue).orEmpty()
    }

    fun clearPreferences() {
        settings.edit { clear() }
    }

    fun saveErrorLog(log: String) {
        putString("error_log", log)
    }

    fun getErrorLog(): String {
        return getString("error_log", "")
    }

    fun getTrendingRegion(context: Context): String {
        val regionPref = PreferenceHelper.getString("region", "sys")

        // get the system default country if auto region selected
        return if (regionPref == "sys") {
            getDetectedCountry(context).uppercase()
        } else {
            regionPref
        }
    }

    private fun getDefaultSharedPreferences(context: Context): SharedPreferences {
        return PreferenceManager.getDefaultSharedPreferences(context)
    }
}
