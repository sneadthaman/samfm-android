package com.samfm.radio

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val client = OkHttpClient()

    private lateinit var artView: ImageView
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var btnPlay: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        artView = findViewById(R.id.art)
        titleView = findViewById(R.id.title)
        artistView = findViewById(R.id.artist)
        btnPlay = findViewById(R.id.btnPlay)

        btnPlay.setOnClickListener {
            ContextCompat.startForegroundService(
                this,
                Intent(this, PlayerService::class.java).apply { action = PlayerService.ACTION_TOGGLE }
            )
        }

        scope.launch { pollNowPlayingUI() }
    }

    private suspend fun pollNowPlayingUI() {
        val req = Request.Builder().url(Constants.NOWPLAYING_URL).build()
        while (isActive) {
            try {
                val res = withContext(Dispatchers.IO) { client.newCall(req).execute() }
                val body = res.body?.string().orEmpty()
                val root = JSONObject(body)
                val np = root.optJSONObject("now_playing") ?: JSONObject()
                val song = np.optJSONObject("song") ?: JSONObject()

                val title = song.optString("title", "Live")
                val artist = song.optString("artist", "SAM FM")
                val artUrl = song.optString("art", "")

                titleView.text = title
                artistView.text = artist

                if (artUrl.isNotEmpty()) {
                    val imgReq = Request.Builder().url(artUrl).build()
                    val imgRes = withContext(Dispatchers.IO) { client.newCall(imgReq).execute() }
                    imgRes.body?.byteStream()?.use {
                        val bmp = BitmapFactory.decodeStream(it)
                        if (bmp != null) artView.setImageBitmap(bmp)
                    }
                }
            } catch (_: Throwable) { }
            delay(5000)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
