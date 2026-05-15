package com.example.clockapp

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class MediaNotificationListener : NotificationListenerService() {

    companion object {
        var currentTrack: String = ""
        var currentArtist: String = ""
        var isPlaying: Boolean = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val packageName = sbn.packageName

        if (packageName == "ru.yandex.music") {
            var title = extras.getString(android.app.Notification.EXTRA_TITLE)
            val text = extras.getString(android.app.Notification.EXTRA_TEXT)

            val actions = sbn.notification.actions
            val hasPauseButton = actions?.any { action ->
                action.title?.toString()?.contains("Пауза") == true ||
                        action.title?.toString()?.contains("Pause") == true
            } ?: false

            if (title != null && title.isNotEmpty()) {
                if (title.length >= 3) {
                    title = title.substring(3)
                }
                currentTrack = title
                currentArtist = text ?: ""
                isPlaying = hasPauseButton

                Log.d("MediaListener", "$currentArtist - $currentTrack, Играет: $isPlaying")
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val packageName = sbn.packageName

        if (packageName == "ru.yandex.music") {
            currentTrack = ""
            currentArtist = ""
            isPlaying = false
            Log.d("MediaListener", "Яндекс.Музыка уведомление удалено")
        }
    }
}