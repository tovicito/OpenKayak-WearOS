package com.openkayak.app.tracking

import android.content.Context

/** Preferences that can be edited on the phone and delivered by Wear OS Data Layer. */
object AppSettingsStore {
    private const val PREFS = "openkayak_app_settings"
    fun gpsAccuracyLimit(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("gps_accuracy_limit_m", 35f).coerceIn(10f, 65f)
    fun mapZoom(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("map_zoom", 16).coerceIn(14, 17)
    fun save(context: Context, weightKg: Double, ageYears: Int, mapZoom: Int, gpsAccuracyLimitM: Float) {
        AthleteProfileStore.save(context, weightKg, ageYears)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("map_zoom", mapZoom.coerceIn(14, 17))
            .putFloat("gps_accuracy_limit_m", gpsAccuracyLimitM.coerceIn(10f, 65f)).apply()
    }
}
