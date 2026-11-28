package com.example.bilidl

import android.app.PictureInPictureParams
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.Gravity
import android.view.View
import android.widget.PopupMenu
import android.widget.Button
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import androidx.activity.ComponentActivity
import java.util.Locale

/** Lightweight built-in video/audio playback for resources downloaded by BiliDl. */
class LocalPlaybackActivity : ComponentActivity() {

    private var mediaPlayer: MediaPlayer? = null
    private var videoView: VideoView? = null
    private var progressBar: SeekBar? = null
    private var playButton: Button? = null
    private var durationText: TextView? = null
    private var isPrepared = false
    private var pipAspectRatio = Rational(16, 9)
    private var playbackSpeed = 1f
    private var speedLabel: TextView? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var resumeAfterTransientFocusLoss = false

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        val player = mediaPlayer
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                player?.setVolume(1f, 1f)
                if (resumeAfterTransientFocusLoss && isPrepared) {
                    runCatching { player?.start() }
                    playButton?.text = "暂停"
                    handler.removeCallbacks(progressUpdater)
                    handler.post(progressUpdater)
                }
                resumeAfterTransientFocusLoss = false
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                resumeAfterTransientFocusLoss = player?.isPlaying == true
                if (resumeAfterTransientFocusLoss) {
                    runCatching { player?.pause() }
                    playButton?.text = "继续播放"
                    handler.removeCallbacks(progressUpdater)
                } else {
                    player?.setVolume(0.2f, 0.2f)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeAfterTransientFocusLoss = false
                if (player?.isPlaying == true) runCatching { player.pause() }
                playButton?.text = "播放"
                handler.removeCallbacks(progressUpdater)
                abandonAudioFocus()
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val progressUpdater = object : Runnable {
        override fun run() {
            val player = mediaPlayer
            if (player != null && isPrepared && player.isPlaying) {
                progressBar?.progress = player.currentPosition
                durationText?.text = formatTime(player.currentPosition) + " / " + formatTime(player.duration)
                handler.postDelayed(this, 500)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)
        if (uri == null) {
            finish()
            return
        }
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "BiliDl 播放器" }
        val mime = intent.getStringExtra(EXTRA_MIME).orEmpty()
        setContentView(if (mime.startsWith("audio/")) buildAudioPlayer(title, uri) else buildVideoPlayer(title, uri))
    }

    private fun buildVideoPlayer(title: String, uri: Uri): android.view.View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        root.addView(buildToolbar(title, dark = true))
        val player = VideoView(this)
        videoView = player
        root.addView(player, LinearLayout.LayoutParams(-1, 0, 1f))
        val controller = MediaController(this)
        controller.setAnchorView(player)
        player.setMediaController(controller)
        player.setVideoURI(uri)
        player.setOnPreparedListener { prepared ->
            prepared.isLooping = false
            if (prepared.videoWidth > 0 && prepared.videoHeight > 0) {
                val ratio = prepared.videoWidth.toDouble() / prepared.videoHeight.toDouble()
                val safeRatio = ratio.coerceIn(1.0 / 2.39, 2.39)
                pipAspectRatio = Rational((safeRatio * 1000).toInt(), 1000)
            }
            applyPlaybackSpeed()
            player.start()
        }
        player.setOnErrorListener { _, _, _ ->
            android.widget.Toast.makeText(this, "此文件暂时无法播放", android.widget.Toast.LENGTH_SHORT).show()
            true
        }
        return root
    }

    private fun buildAudioPlayer(title: String, uri: Uri): android.view.View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(247, 248, 250))
        }
        root.addView(buildToolbar(title, dark = false))
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        body.addView(TextView(this).apply {
            text = "♫"
            textSize = 84f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(226, 69, 117))
            background = rounded(Color.WHITE, 28)
        }, LinearLayout.LayoutParams(dp(180), dp(180)).apply {
            bottomMargin = dp(26)
        })
        body.addView(TextView(this).apply {
            text = title
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(37, 42, 51))
            gravity = Gravity.CENTER
            maxLines = 2
        }, LinearLayout.LayoutParams(-1, -2))
        progressBar = SeekBar(this)
        durationText = TextView(this).apply {
            text = "00:00 / 00:00"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(112, 120, 133))
        }
        body.addView(progressBar, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(24)
        })
        body.addView(durationText, LinearLayout.LayoutParams(-1, -2))
        playButton = Button(this).apply {
            text = "正在加载…"
            isEnabled = false
            setOnClickListener { toggleAudioPlayback() }
        }
        body.addView(playButton, LinearLayout.LayoutParams(dp(160), dp(48)).apply {
            topMargin = dp(20)
        })
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        createAudioPlayer(uri)
        return root
    }

    private fun buildToolbar(title: String, dark: Boolean): android.view.View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(6))
            setBackgroundColor(if (dark) Color.rgb(18, 20, 24) else Color.WHITE)
            addView(TextView(this@LocalPlaybackActivity).apply {
                text = "‹"
                textSize = 32f
                gravity = Gravity.CENTER
                setTextColor(if (dark) Color.WHITE else Color.rgb(38, 43, 52))
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(dp(42), dp(48)))
            addView(TextView(this@LocalPlaybackActivity).apply {
                text = title
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (dark) Color.WHITE else Color.rgb(38, 43, 52))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
            speedLabel = TextView(this@LocalPlaybackActivity).apply {
                text = PlaybackSpeedPolicy.label(playbackSpeed)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(if (dark) Color.WHITE else Color.rgb(38, 43, 52))
                setPadding(dp(10), dp(8), dp(4), dp(8))
                setOnClickListener { showPlaybackSpeedMenu(this) }
                contentDescription = "播放速度"
            }
            addView(speedLabel, LinearLayout.LayoutParams(dp(54), dp(44)))
        }

    private fun createAudioPlayer(uri: Uri) {
        val player = MediaPlayer()
        mediaPlayer = player
        try {
            player.setDataSource(this, uri)
            player.setOnPreparedListener {
                isPrepared = true
                applyPlaybackSpeed()
                progressBar?.max = it.duration.coerceAtLeast(1)
                durationText?.text = "00:00 / " + formatTime(it.duration)
                playButton?.text = "播放"
                playButton?.isEnabled = true
            }
            player.setOnCompletionListener {
                playButton?.text = "播放"
                progressBar?.progress = 0
                handler.removeCallbacks(progressUpdater)
                abandonAudioFocus()
            }
            player.setOnErrorListener { _, _, _ ->
                playButton?.text = "无法播放"
                playButton?.isEnabled = false
                true
            }
            progressBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && isPrepared) {
                        durationText?.text = formatTime(progress) + " / " + formatTime(player.duration)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    if (isPrepared) player.seekTo(seekBar?.progress ?: 0)
                }
            })
            player.prepareAsync()
        } catch (_: Exception) {
            playButton?.text = "无法打开音频"
        }
    }

    private fun toggleAudioPlayback() {
        val player = mediaPlayer ?: return
        if (!isPrepared) return
        if (player.isPlaying) {
            player.pause()
            playButton?.text = "继续播放"
            handler.removeCallbacks(progressUpdater)
            abandonAudioFocus()
        } else {
            if (!requestAudioFocus()) {
                android.widget.Toast.makeText(this, "当前无法获取音频焦点", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            runCatching { player.start() }.onFailure {
                android.widget.Toast.makeText(this, "音频暂时无法播放", android.widget.Toast.LENGTH_SHORT).show()
                abandonAudioFocus()
                return
            }
            applyPlaybackSpeed()
            playButton?.text = "暂停"
            handler.removeCallbacks(progressUpdater)
            handler.post(progressUpdater)
        }
    }

    private fun showPlaybackSpeedMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            PlaybackSpeedPolicy.SUPPORTED.forEach { speed ->
                menu.add(PlaybackSpeedPolicy.label(speed)).setOnMenuItemClickListener {
                    setPlaybackSpeed(speed)
                    true
                }
            }
            show()
        }
    }

    private fun setPlaybackSpeed(speed: Float) {
        playbackSpeed = speed
        speedLabel?.text = PlaybackSpeedPolicy.label(speed)
        applyPlaybackSpeed()
    }

    private fun applyPlaybackSpeed() {
        if (!isPrepared && mediaPlayer != null) return
        runCatching {
            videoView?.setPlaybackParams(videoView?.playbackParams?.setSpeed(playbackSpeed) ?: return)
        }
        if (isPrepared) {
            runCatching {
                mediaPlayer?.setPlaybackParams(
                    mediaPlayer?.playbackParams?.setSpeed(playbackSpeed) ?: return
                )
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        val manager = getSystemService(AudioManager::class.java) ?: return true
        audioManager = manager
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(audioFocusListener, handler)
            .setWillPauseWhenDucked(true)
            .build()
        audioFocusRequest = request
        return manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        val manager = audioManager ?: return
        val request = audioFocusRequest ?: return
        runCatching { manager.abandonAudioFocusRequest(request) }
        audioFocusRequest = null
        resumeAfterTransientFocusLoss = false
    }

    private fun rounded(color: Int, radius: Int): android.graphics.drawable.Drawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun formatTime(milliseconds: Int): String {
        val seconds = (milliseconds.coerceAtLeast(0) / 1000)
        return String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val player = videoView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && player?.isPlaying == true) {
            runCatching {
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder().setAspectRatio(pipAspectRatio).build()
                )
            }
        }
    }

    override fun onStop() {
        val inPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode
        if (!inPip) videoView?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressUpdater)
        videoView?.stopPlayback()
        abandonAudioFocus()
        mediaPlayer?.release()
        mediaPlayer = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URI = "com.example.bilidl.extra.PLAYER_URI"
        const val EXTRA_TITLE = "com.example.bilidl.extra.PLAYER_TITLE"
        const val EXTRA_MIME = "com.example.bilidl.extra.PLAYER_MIME"

        fun intent(uri: Uri, title: String, mimeType: String): android.content.Intent =
            android.content.Intent().apply {
                putExtra(EXTRA_URI, uri.toString())
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_MIME, mimeType)
            }
    }
}
