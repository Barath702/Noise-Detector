package com.example.noisedetector.util

import android.content.Context

class AppPrefs(context: Context) {

    private val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var controllerHost: String
        get() = p.getString(KEY_HOST, "") ?: ""
        set(value) = p.edit().putString(KEY_HOST, value.trim()).apply()

    /** Last 4-digit code used on controller (for discovery). */
    var lastPairCode: String
        get() = p.getString(KEY_PAIR_CODE, "") ?: ""
        set(value) {
            val n = PairingCodes.normalize(value)
            if (n == null) p.edit().remove(KEY_PAIR_CODE).apply()
            else p.edit().putString(KEY_PAIR_CODE, n).apply()
        }

    var listenerPairCode: String
        get() = p.getString(KEY_LISTENER_PAIR, "") ?: ""
        set(value) {
            val n = PairingCodes.normalize(value) ?: return
            p.edit().putString(KEY_LISTENER_PAIR, n).apply()
        }

    var thresholdMarginDb: Float
        get() = p.getFloat(KEY_MARGIN, DEFAULT_MARGIN_DB).coerceIn(MARGIN_MIN_DB, MARGIN_MAX_DB)
        set(value) = p.edit().putFloat(KEY_MARGIN, value.coerceIn(MARGIN_MIN_DB, MARGIN_MAX_DB)).apply()

    var alertsEnabled: Boolean
        get() = p.getBoolean(KEY_ALERTS, true)
        set(value) = p.edit().putBoolean(KEY_ALERTS, value).apply()

    var noiseFloorDb: Float
        get() = p.getFloat(KEY_FLOOR, Float.NaN)
        set(value) = p.edit().putFloat(KEY_FLOOR, value).apply()

    fun clearNoiseFloor() {
        p.edit().remove(KEY_FLOOR).apply()
    }

    companion object {
        private const val PREFS = "noise_detector"
        private const val KEY_HOST = "controller_host"
        private const val KEY_PAIR_CODE = "last_pair_code"
        private const val KEY_LISTENER_PAIR = "listener_pair_code"
        private const val KEY_MARGIN = "threshold_margin_db"
        private const val KEY_ALERTS = "alerts_enabled"
        private const val KEY_FLOOR = "noise_floor_db"
        const val DEFAULT_MARGIN_DB = 12f
        const val MARGIN_MIN_DB = 4f
        const val MARGIN_MAX_DB = 60f
    }
}
