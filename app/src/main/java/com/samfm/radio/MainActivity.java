package com.samfm.radio;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {

    private final OkHttpClient http = new OkHttpClient.Builder()
        .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(false)   // 👈 important: we'll handle redirects manually
        .build();

    private ImageView artView;
    private TextView titleView;
    private TextView artistView;
    private ScheduledExecutorService scheduler;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        artView   = findViewById(R.id.art);
        titleView = findViewById(R.id.title);
        artistView= findViewById(R.id.artist);

        Button btn = findViewById(R.id.btnPlay);

        // Start/ensure service running
        ContextCompat.startForegroundService(this, new Intent(this, PlayerService.class));

        btn.setOnClickListener(v -> {
            Intent i = new Intent(this, PlayerService.class).setAction(PlayerService.ACTION_TOGGLE);
            ContextCompat.startForegroundService(this, i);
        });

        // Poll AzuraCast every 5s for UI (independent from service)
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::pollNowPlaying, 0, 5, TimeUnit.SECONDS);
    }

    private String fetchJsonHandlingRedirect(String url) throws Exception {
        Request req = new Request.Builder().url(url).build();
        try (Response res = http.newCall(req).execute()) {
            int code = res.code();
            if (code >= 300 && code < 400) {
                String loc = res.header("Location", "");
                if (loc == null || loc.isEmpty()) return "";
                // If redirected to https on the IP, rewrite to http (dev only)
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
    

    private void pollNowPlaying() {
        long t0 = System.currentTimeMillis();
        try {
            String body = fetchJsonHandlingRedirect(Constants.NOWPLAYING_URL);
            final String sample = body.length() > 80 ? body.substring(0, 80) + "..." : body;

            if (body.isEmpty()) {
                runOnUiThread(() -> { titleView.setText("SAM FM"); artistView.setText("NP empty"); });
                return;
            }

            NowPlaying np = parseNowPlaying(body);
            runOnUiThread(() -> {
                titleView.setText(np.title.isEmpty() ? "Live" : np.title);
                artistView.setText(np.artist.isEmpty() ? "SAM FM" : np.artist);
            });
            if (!np.artUrl.isEmpty()) {
                Bitmap bmp = downloadBitmap(np.artUrl);
                if (bmp != null) runOnUiThread(() -> artView.setImageBitmap(bmp));
            }
        } catch (Throwable t) {
            runOnUiThread(() -> {
                titleView.setText("SAM FM");
                artistView.setText("NP error");
            });
        }
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

    private Bitmap downloadBitmap(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(6000);
            c.connect();
            try (InputStream in = c.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            } finally {
                c.disconnect();
            }
        } catch (Throwable t) { return null; }
    }

    @Override protected void onDestroy() {
        if (scheduler != null) scheduler.shutdownNow();
        super.onDestroy();
    }

    private static class NowPlaying {
        final String title, artist, artUrl;
        NowPlaying(String t, String a, String art) { this.title = t; this.artist = a; this.artUrl = art; }
    }
}
