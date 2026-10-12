package com.openkayak.app.tracking

import android.content.Context

/** Preferences that can be edited on the phone and delivered by Wear OS Data Layer. */
object AppSettingsStore {
    const val PREFS_NAME = "openkayak_app_settings"
    const val ROUTE_COLOR_KEY = "route_color_hex"
    const val DEFAULT_ROUTE_COLOR_HEX = "FFFF8500"
    private const val PREFS = PREFS_NAME
    private val routeColorPalette = setOf(DEFAULT_ROUTE_COLOR_HEX, "FF42D9FF", "FFFF4FA3")
    fun gpsAccuracyLimit(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("gps_accuracy_limit_m", 35f).coerceIn(10f, 65f)
    fun mapZoom(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("map_zoom", 16).coerceIn(14, 17)
    fun routeColorHex(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(ROUTE_COLOR_KEY, DEFAULT_ROUTE_COLOR_HEX)
            ?.takeIf { it in routeColorPalette } ?: DEFAULT_ROUTE_COLOR_HEX
    fun setRouteColorHex(context: Context, hex: String) {
        val safeHex = hex.uppercase().takeIf { it in routeColorPalette } ?: DEFAULT_ROUTE_COLOR_HEX
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(ROUTE_COLOR_KEY, safeHex).apply()
    }
    fun save(context: Context, weightKg: Double, ageYears: Int, mapZoom: Int, gpsAccuracyLimitM: Float) {
        AthleteProfileStore.save(context, weightKg, ageYears)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("map_zoom", mapZoom.coerceIn(14, 17))
            .putFloat("gps_accuracy_limit_m", gpsAccuracyLimitM.coerceIn(10f, 65f)).apply()
    }
}
