package com.samfm.radio;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Metadata;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.LibraryResult;
import androidx.media3.session.MediaLibraryService;
import androidx.media3.session.MediaLibraryService.MediaLibrarySession;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaStyleNotificationHelper;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

public class PlayerService extends MediaLibraryService {
    public static final String ACTION_TOGGLE = "com.samfm.radio.TOGGLE";
    private static final String CHANNEL_ID = "radio";
    private static final int NOTIF_ID = 1;
    private static final String ROOT_ID = "root";
    private static final String MEDIA_ID_LIVE = "live";

    private ExoPlayer exo;
    private MediaLibrarySession mediaLibrarySession;
    private Handler mainHandler;
    private volatile String lastArtUrl = "";

    private final OkHttpClient http = new OkHttpClient.Builder()
        .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(false)
        .build();

    private ScheduledExecutorService scheduler;

    @Override public void onCreate() {
        super.onCreate();

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "Radio Playback", NotificationManager.IMPORTANCE_LOW));
        }

        mainHandler = new Handler(Looper.getMainLooper());
        exo = new ExoPlayer.Builder(this).build();

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                .build();
        exo.setAudioAttributes(attrs, true);
        exo.setHandleAudioBecomingNoisy(true);

        MediaItem item = buildLiveItem("SAM FM", "Live", "");
        exo.setMediaItem(item);
        exo.prepare();

        mediaLibrarySession = new MediaLibrarySession.Builder(this, exo, new SessionCb())
                .setSessionActivity(piContent())
                .build();

        NotificationCompat.Builder nb = new NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("SAM FM")
        .setContentText("Starting…")
        .setContentIntent(piContent())
        .setOngoing(true)
        .addAction(new NotificationCompat.Action(0, "Play/Pause", piToggle()))
        .setStyle(new MediaStyleNotificationHelper.MediaStyle(mediaLibrarySession).setShowActionsInCompactView(0));

        startForeground(NOTIF_ID, nb.build());

        exo.play();

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
                // Use lastArtUrl as fallback since ICY metadata carries no artwork
                applyMetadata(title, artist, lastArtUrl);
            }
        });

        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::updateNowPlayingFromApi, 0, 5, TimeUnit.SECONDS);
    }

    private MediaItem buildLiveItem(String title, String artist, String art) {
        MediaMetadata.Builder mb = new MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist);
        if (art != null && !art.isEmpty()) {
            mb.setArtworkUri(Uri.parse(art));
        }
        return new MediaItem.Builder()
                .setMediaId(MEDIA_ID_LIVE)
                .setUri(Uri.parse(Constants.STREAM_URL))
                .setMediaMetadata(mb.build())
                .build();
    }

    private void updateNowPlayingFromApi() {
        try {
            String body = fetchJsonHandlingRedirect(Constants.NOWPLAYING_URL);
            if (body == null || body.isEmpty()) return;

            NowPlaying np = parseNowPlaying(body);
            lastArtUrl = np.artUrl;
            applyMetadata(np.title, np.artist, np.artUrl);
        } catch (Throwable ignored) {}
    }

    // Updates the current MediaItem metadata so that Bluetooth AVRCP (Tesla, etc.) picks up
    // the track title and artist. setPlaylistMetadata() does NOT propagate to AVRCP;
    // replaceMediaItem() with the same URI updates metadata in-place without rebuffering
    // (Media3 1.2+). Must run on the main thread because ExoPlayer is not thread-safe.
    private void applyMetadata(String title, String artist, String artUrl) {
        mainHandler.post(() -> {
            if (exo == null || mediaLibrarySession == null) return;

            MediaMetadata.Builder mb = new MediaMetadata.Builder()
                    .setTitle(title.isEmpty() ? "Live" : title)
                    .setArtist(artist.isEmpty() ? "SAM FM" : artist);
            if (artUrl != null && !artUrl.isEmpty()) mb.setArtworkUri(Uri.parse(artUrl));

            MediaItem updatedItem = new MediaItem.Builder()
                    .setMediaId(MEDIA_ID_LIVE)
                    .setUri(Uri.parse(Constants.STREAM_URL))
                    .setMediaMetadata(mb.build())
                    .build();
            exo.replaceMediaItem(0, updatedItem);

            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationCompat.Builder nb = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(title.isEmpty() ? "SAM FM" : title)
                    .setContentText(artist.isEmpty() ? "Live" : artist)
                    .setContentIntent(piContent())
                    .setOngoing(true)
                    .addAction(new NotificationCompat.Action(0, "Play/Pause", piToggle()))
                    .setStyle(new MediaStyleNotificationHelper.MediaStyle(mediaLibrarySession)
                            .setShowActionsInCompactView(0));
            nm.notify(NOTIF_ID, nb.build());
        });
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

    private PendingIntent piToggle() {
        Intent i = new Intent(this, PlayerService.class).setAction(ACTION_TOGGLE);
        return PendingIntent.getService(this, 1, i,
            android.os.Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        }

    private PendingIntent piContent() {
        Intent open = new Intent(this, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 2, open,
            android.os.Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
    }

    @Override public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent != null && ACTION_TOGGLE.equals(intent.getAction())) {
            if (exo.isPlaying()) exo.pause(); else exo.play();
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        if (scheduler != null) scheduler.shutdownNow();
        try { mediaLibrarySession.release(); } catch (Throwable ignored) {}
        try { exo.release(); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Nullable @Override public MediaLibrarySession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return mediaLibrarySession;
    }

    private class SessionCb implements MediaLibrarySession.Callback {
        @NonNull @Override
        public ListenableFuture<LibraryResult<MediaItem>> onGetLibraryRoot(@NonNull MediaLibrarySession session, @NonNull MediaSession.ControllerInfo controller, @Nullable MediaLibraryService.LibraryParams params) {
            MediaItem root = new MediaItem.Builder()
                    .setMediaId(ROOT_ID)
                    .setMediaMetadata(new MediaMetadata.Builder().setTitle("SAM FM").build())
                    .build();
            return Futures.immediateFuture(LibraryResult.ofItem(root, params));
        }

        @NonNull @Override
        public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetChildren(@NonNull MediaLibrarySession session, @NonNull MediaSession.ControllerInfo controller, @NonNull String parentId, int page, int pageSize, @Nullable MediaLibraryService.LibraryParams params) {
            if (!ROOT_ID.equals(parentId)) {
                return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE));
            }
            List<MediaItem> children = new ArrayList<>();
            MediaItem live = new MediaItem.Builder()
                    .setMediaId(MEDIA_ID_LIVE)
                    .setMediaMetadata(new MediaMetadata.Builder().setTitle("SAM FM Live").setArtist("24/7").build())
                    .setUri(Uri.parse(Constants.STREAM_URL))
                    .build();
            children.add(live);
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(children), params));
        }

        @NonNull @Override
        public ListenableFuture<List<MediaItem>> onAddMediaItems(@NonNull MediaSession session, @NonNull MediaSession.ControllerInfo controller, @NonNull List<MediaItem> mediaItems) {
            List<MediaItem> resolved = new ArrayList<>();
            for (MediaItem mi : mediaItems) {
                if (MEDIA_ID_LIVE.equals(mi.mediaId) || mi.requestMetadata.mediaUri != null) {
                    resolved.add(new MediaItem.Builder()
                            .setMediaId(MEDIA_ID_LIVE)
                            .setUri(Uri.parse(Constants.STREAM_URL))
                            .setMediaMetadata(new MediaMetadata.Builder().setTitle("SAM FM Live").setArtist("24/7").build())
                            .build());
                }
            }
            return Futures.immediateFuture(ImmutableList.copyOf(resolved));
        }
    }

    private static class NowPlaying {
        final String title, artist, artUrl;
        NowPlaying(String t, String a, String art) { this.title = t; this.artist = a; this.artUrl = art; }
    }
}
