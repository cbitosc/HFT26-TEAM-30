package com.slackers.emergencymesh.security

import android.content.Context
import com.slackers.emergencymesh.core.MessageSecurity

/** Returns null until Member 4's provider lands, so release builds can't send plaintext. */
object SecurityProvider {
    fun create(context: Context): MessageSecurity? = null
}
