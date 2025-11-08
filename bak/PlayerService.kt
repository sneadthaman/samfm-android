package com.samfm.radio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession

class PlayerService : LifecycleService() {
    private lateinit var exo: ExoPlayer
    private lateinit var mediaSession: MediaSession

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Radio Playback", NotificationManager.IMPORTANCE_LOW)
            )
        }

        exo = ExoPlayer.Builder(this).build()
        exo.setMediaItem(MediaItem.fromUri(Uri.parse(Constants.STREAM_URL)))
        exo.prepare()

        mediaSession = MediaSession.Builder(this, exo).build()

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("SAM FM")
            .setContentText("Ready")
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TOGGLE) {
            if (exo.isPlaying) exo.pause() else exo.play()
        }
        return Service.START_STICKY
    }

    override fun onDestroy() {
        mediaSession.release()
        exo.release()
        super.onDestroy()
    }

    companion object {
        const val ACTION_TOGGLE = "com.samfm.radio.TOGGLE"
        private const val CHANNEL_ID = "radio"
        private const val NOTIF_ID = 1
    }
}
