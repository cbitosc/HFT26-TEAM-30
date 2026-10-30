package com.slackers.emergencymesh.ui

import android.content.Context
import com.slackers.emergencymesh.core.Priority

/** Local scan session only: ending it does not retract an already stored SOS packet. */
internal class SosScanSessionStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("sos_scan_session", Context.MODE_PRIVATE)

    fun readUntil(): Long = preferences.getLong("active_until_millis", 0L)

    fun begin(nowMillis: Long): Long {
        val until = nowMillis + Priority.P0_SOS.retentionSeconds * 1_000L
        preferences.edit().putLong("active_until_millis", until).apply()
        return until
    }

    fun end() {
        preferences.edit().remove("active_until_millis").apply()
    }
}
