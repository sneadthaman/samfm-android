package com.samfm.radio;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Metadata;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

public class PlayerService extends Service {
    public static final String ACTION_TOGGLE = "com.samfm.radio.TOGGLE";
    private static final String CHANNEL_ID = "radio";
    private static final int NOTIF_ID = 1;

    private ExoPlayer exo;
    private MediaSession mediaSession;

    private final OkHttpClient http = new OkHttpClient.Builder()
        .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(false)   // 👈 important: we'll handle redirects manually
        .build();

    private ScheduledExecutorService scheduler;

    @Override public void onCreate() {
        super.onCreate();

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "Radio Playback", NotificationManager.IMPORTANCE_LOW));
        }

        exo = new ExoPlayer.Builder(this).build();

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                .build();
        exo.setAudioAttributes(attrs, true);
        exo.setHandleAudioBecomingNoisy(true);

        // Initial metadata
        MediaMetadata initialMeta = new MediaMetadata.Builder()
                .setTitle("SAM FM")
                .setArtist("Live")
                .build();

        MediaItem item = new MediaItem.Builder()
                .setUri(Uri.parse(Constants.STREAM_URL))
                .setMediaMetadata(initialMeta)
                .build();

        exo.setMediaItem(item);
        exo.prepare();

        mediaSession = new MediaSession.Builder(this, exo).build();

        // build the media-style notification with actions
        NotificationCompat.Builder nb = new NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("SAM FM")
        .setContentText("Starting…")
        .setContentIntent(piContent())                   // tap opens app
        .setOngoing(true)
        .addAction(new NotificationCompat.Action(0, "Play/Pause", piToggle()))
        .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
            .setMediaSession(mediaSession.getSessionCompatToken())
            .setShowActionsInCompactView(0)   // show the first action compact
        );

        startForeground(NOTIF_ID, nb.build());

        exo.play();

        // ICY metadata fallback
        exo.addListener(new Player.Listener() {
            @Override public void onMetadata(Metadata metadata) {
                String title = "";
                String artist = "";
                for (int i = 0; i < metadata.length(); i++) {
                    String s = String.valueOf(metadata.get(i));
                    if (s != null && s.contains("StreamTitle")) {
                        String v = s.replace("StreamTitle='", "").replace("';", "").trim();
                        int dash = v.indexOf(" - ");
                        if (dash > 0) {
                            artist = v.substring(0, dash).trim();
                            title = v.substring(dash + 3).trim();
                        } else {
                            title = v;
                        }
                        break;
                    }
                }
                if (title.isEmpty() && artist.isEmpty()) return;

                MediaMetadata meta = new MediaMetadata.Builder()
                        .setTitle(title.isEmpty() ? "Live" : title)
                        .setArtist(artist.isEmpty() ? "SAM FM" : artist)
                        .build();

                exo.setPlaylistMetadata(meta);

                NotificationManager nm = getSystemService(NotificationManager.class);
                NotificationCompat.Builder n2 = new NotificationCompat.Builder(PlayerService.this, CHANNEL_ID)
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setContentTitle(title.isEmpty() ? "SAM FM" : title)
                        .setContentText(artist.isEmpty() ? "Live" : artist)
                        .setOngoing(true);
                nm.notify(NOTIF_ID, n2.build());
            }
        });

        // Poll AzuraCast API every 5s and push to session
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::updateNowPlayingFromApi, 0, 5, TimeUnit.SECONDS);
    }

    private void updateNowPlayingFromApi() {
        try {
            String body = fetchJsonHandlingRedirect(Constants.NOWPLAYING_URL);
            if (body == null || body.isEmpty()) return;
    
            NowPlaying np = parseNowPlaying(body);
    
            MediaMetadata.Builder mb = new MediaMetadata.Builder()
                    .setTitle(np.title.isEmpty() ? "Live" : np.title)
                    .setArtist(np.artist.isEmpty() ? "SAM FM" : np.artist);
            if (!np.artUrl.isEmpty()) mb.setArtworkUri(Uri.parse(np.artUrl));
            MediaMetadata meta = mb.build();
    
            exo.setPlaylistMetadata(meta);
    
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationCompat.Builder nb = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(np.title.isEmpty() ? "SAM FM" : np.title)
                    .setContentText(np.artist.isEmpty() ? "Live" : np.artist)
                    .setOngoing(true);
            nm.notify(NOTIF_ID, nb.build());
        } catch (Throwable ignored) {}
    }
    

    private NowPlaying parseNowPlaying(String body) {
        try {
            JSONObject root = new JSONObject(body);
            NowPlaying a = fromObjectShape(root);
            if (a != null) return a;
        } catch (Throwable ignored) {}

        try {
            JSONArray arr = new JSONArray(body);
            if (arr.length() > 0) {
                NowPlaying b = fromObjectShape(arr.getJSONObject(0));
                if (b != null) return b;
            }
        } catch (Throwable ignored) {}

        return new NowPlaying("Live", "SAM FM", "");
    }

    private String fetchJsonHandlingRedirect(String url) throws Exception {
        Request req = new Request.Builder().url(url).build();
        try (Response res = http.newCall(req).execute()) {
            int code = res.code();
            if (code >= 300 && code < 400) {
                String loc = res.header("Location", "");
                if (loc == null || loc.isEmpty()) return "";
                if (loc.startsWith("https://209.97.158.36")) {
                    loc = loc.replace("https://", "http://");
                }
                res.close();
                Request req2 = new Request.Builder().url(loc).build();
                try (Response res2 = http.newCall(req2).execute()) {
                    return res2.body() != null ? res2.body().string() : "";
                }
            } else {
                return res.body() != null ? res.body().string() : "";
            }
        }
    }    

    private NowPlaying fromObjectShape(JSONObject root) {
        try {
            JSONObject now = root.optJSONObject("now_playing");
            if (now == null) return null;
            JSONObject song = now.optJSONObject("song");

            String title = "", artist = "", art = "";
            if (song != null) {
                title = song.optString("title", "");
                artist = song.optString("artist", "");
                art = song.optString("art", "");
                if (title.isEmpty()) title = song.optString("text", "");
            }
            if (art.isEmpty()) {
                art = root.optString("art", "");
                if (art.isEmpty()) {
                    JSONObject st = root.optJSONObject("station");
                    if (st != null) art = st.optString("art", "");
                }
            }
            return new NowPlaying(title, artist, art);
        } catch (Throwable __) { return null; }
    }

    private android.app.PendingIntent piToggle() {
        Intent i = new Intent(this, PlayerService.class).setAction(ACTION_TOGGLE);
        return android.app.PendingIntent.getService(this, 1, i,
            android.os.Build.VERSION.SDK_INT >= 23 ? android.app.PendingIntent.FLAG_IMMUTABLE : 0);
    }
    
    private android.app.PendingIntent piContent() {
        Intent open = new Intent(this, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return android.app.PendingIntent.getActivity(this, 2, open,
            android.os.Build.VERSION.SDK_INT >= 23 ? android.app.PendingIntent.FLAG_IMMUTABLE : 0);
    }
    

    @Override public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent != null && ACTION_TOGGLE.equals(intent.getAction())) {
            if (exo.isPlaying()) exo.pause(); else exo.play();
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        if (scheduler != null) scheduler.shutdownNow();
        try { mediaSession.release(); } catch (Throwable ignored) {}
        try { exo.release(); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Nullable @Override public android.os.IBinder onBind(Intent intent) { return null; }

    private static class NowPlaying {
        final String title, artist, artUrl;
        NowPlaying(String t, String a, String art) { this.title = t; this.artist = a; this.artUrl = art; }
    }
}
