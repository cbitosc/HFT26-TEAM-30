package com.slackers.emergencymesh.background

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.slackers.emergencymesh.MainActivity
import com.slackers.emergencymesh.core.ChatMessage
import com.slackers.emergencymesh.core.PacketType

/** Picks out messages that arrive after the first call, so stored history stays quiet. */
internal class NewMessages {
    private var known: MutableSet<String>? = null

    fun pick(messages: List<ChatMessage>): List<ChatMessage> {
        val seen = known
        if (seen == null) {
            known = messages.mapTo(HashSet()) { it.messageId }
            return emptyList()
        }
        return messages.filter { seen.add(it.messageId) }
    }
}

/**
 * Tells the owner of a locked phone that something arrived. An SOS always alerts and shows its
 * text, because it is meant for anyone nearby. A direct message is private, so its alert never
 * contains the text, and it stays silent while the app is on screen.
 */
internal class MeshAlerts(private val context: Context) {
    private val notifications = context.getSystemService(NotificationManager::class.java)

    init {
        notifications.createNotificationChannel(
            NotificationChannel(SOS_CHANNEL, "SOS alerts", NotificationManager.IMPORTANCE_HIGH)
                .apply { enableVibration(true) }
        )
        notifications.createNotificationChannel(
            NotificationChannel(MESSAGE_CHANNEL, "Messages", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun show(message: ChatMessage, senderName: String?) {
        val sos = message.type == PacketType.SOS
        val official = message.type == PacketType.AREA_ALERT
        if (!sos && !official && appOnScreen()) return
        val sender = senderName?.takeIf { it.isNotBlank() } ?: "a nearby phone"
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, if (sos || official) SOS_CHANNEL else MESSAGE_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentIntent(open)
            .setAutoCancel(true)
        if (official) {
            builder.setContentTitle("Official alert for your area")
                .setContentText(message.body.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message.body.text))
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        } else if (sos) {
            builder.setContentTitle("SOS from $sender")
                .setContentText(message.body.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message.body.text))
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        } else {
            builder.setContentTitle("Message from $sender")
                .setContentText("Open EmergencyMesh to read it.")
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        }
        notifications.notify(message.messageId, ALERT_ID, builder.build())
    }

    fun showCallOut(callerName: String?, stopRinging: PendingIntent) {
        val caller = callerName?.takeIf { it.isNotBlank() } ?: "A nearby phone"
        notifications.notify(
            CALL_OUT_TAG,
            ALERT_ID,
            NotificationCompat.Builder(context, SOS_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("A rescuer is calling this phone")
                .setContentText("$caller is trying to find you. Make noise if you can.")
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setTimeoutAfter(Ringer.RING_MILLIS)
                .addAction(0, "Stop ringing", stopRinging)
                .build(),
        )
    }

    private fun appOnScreen(): Boolean {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        return state.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private companion object {
        const val SOS_CHANNEL = "sos_alerts"
        const val MESSAGE_CHANNEL = "messages"
        const val CALL_OUT_TAG = "call_out"
        const val ALERT_ID = 2
    }
}
