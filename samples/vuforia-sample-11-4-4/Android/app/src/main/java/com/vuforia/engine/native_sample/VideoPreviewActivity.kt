/*===============================================================================
Copyright (c) 2024 PTC Inc. and/or Its Subsidiary Companies. All Rights Reserved.

Vuforia is a trademark of PTC Inc., registered in the United States and other
countries.
===============================================================================*/

package com.vuforia.engine.native_sample

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.vuforia.engine.native_sample.notifications.GlimpAppForeground
import androidx.core.content.FileProvider
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import java.io.File
import java.io.FileInputStream

/**
 * Modern preview activity for recorded AR video.
 * Uses ExoPlayer with a custom overlay and gallery integration.
 */
class VideoPreviewActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "VideoPreviewActivity"
        const val EXTRA_VIDEO_PATH = "extra_video_path"
    }

    private var exoPlayer: ExoPlayer? = null
    private var videoPath: String? = null
    private lateinit var btnPlayPause: ImageButton
    private lateinit var loadingIndicator: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_preview)

        videoPath = intent.getStringExtra(EXTRA_VIDEO_PATH)
        if (videoPath.isNullOrBlank() || !File(videoPath!!).exists()) {
            Toast.makeText(this, "Video not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val playerView = findViewById<com.google.android.exoplayer2.ui.PlayerView>(R.id.player_view)
        btnPlayPause = findViewById(R.id.btn_play_pause)
        loadingIndicator = findViewById(R.id.loading_indicator)

        exoPlayer = ExoPlayer.Builder(this).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(videoPath!!))))
            prepare()
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = false // Start paused to match iOS experience
        }
        playerView.player = exoPlayer

        val toggleAction = View.OnClickListener { togglePlayPause() }
        btnPlayPause.setOnClickListener(toggleAction)
        findViewById<View>(R.id.controls_overlay).setOnClickListener(toggleAction)

        exoPlayer?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                loadingIndicator.visibility = when (state) {
                    Player.STATE_IDLE, Player.STATE_BUFFERING -> View.VISIBLE
                    else -> View.GONE
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayPauseIcon(isPlaying)
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "ExoPlayer error: ${error.message}", error)
                Toast.makeText(
                    this@VideoPreviewActivity,
                    "Video playback error: ${error.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        })

        findViewById<View>(R.id.btn_close).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_save).setOnClickListener { saveToGallery() }
        findViewById<View>(R.id.btn_share).setOnClickListener { shareVideo() }
        
        // Initial state - show play button since we start paused
        updatePlayPauseIcon(false)
    }

    private fun togglePlayPause() {
        exoPlayer?.let {
            if (it.isPlaying) it.pause() else it.play()
        }
    }

    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        if (isPlaying) {
            btnPlayPause.animate()
                .alpha(0f)
                .scaleX(0.8f)
                .scaleY(0.8f)
                .setDuration(300)
                .withEndAction {
                    if (exoPlayer?.isPlaying == true) {
                        btnPlayPause.visibility = View.GONE
                        btnPlayPause.setImageResource(R.drawable.ic_play)
                    }
                }
                .start()
        } else {
            btnPlayPause.setImageResource(R.drawable.ic_play)
            btnPlayPause.visibility = View.VISIBLE
            btnPlayPause.scaleX = 0.8f
            btnPlayPause.scaleY = 0.8f
            btnPlayPause.alpha = 0f
            btnPlayPause.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(400)
                .setInterpolator(OvershootInterpolator(1.5f))
                .start()
        }
    }

    override fun onStart() {
        super.onStart()
        GlimpAppForeground.onActivityStarted()
    }

    override fun onStop() {
        GlimpAppForeground.onActivityStopped()
        super.onStop()
    }

    override fun onPause() {
        super.onPause()
        exoPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        exoPlayer = null
    }

    private fun saveToGallery() {
        val sourceFile = File(videoPath!!)
        var success = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "AR_${System.currentTimeMillis()}.mp4")
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/AR_Recordings")
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let {
                try {
                    contentResolver.openOutputStream(it)?.use { out ->
                        FileInputStream(sourceFile).use { input -> input.copyTo(out) }
                    }
                    success = true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save video", e)
                }
            }
        } else {
            try {
                val dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                val destFile = File(dcimDir, "AR_Recordings/${sourceFile.name}")
                destFile.parentFile?.mkdirs()
                sourceFile.copyTo(destFile, overwrite = true)
                sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(destFile)))
                success = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save video", e)
            }
        }

        if (success) {
            showSavedAndDismiss()
        } else {
            Toast.makeText(this, R.string.video_save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSavedAndDismiss() {
        val savedLabel = findViewById<TextView>(R.id.saved_label)
        savedLabel.visibility = View.VISIBLE
        savedLabel.alpha = 0f
        savedLabel.scaleX = 0.8f
        savedLabel.scaleY = 0.8f
        savedLabel.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(400)
            .withEndAction {
                savedLabel.animate()
                    .alpha(0f)
                    .setDuration(400)
                    .setStartDelay(700)
                    .withEndAction { finish() }
                    .start()
            }
            .start()
    }

    private fun shareVideo() {
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", File(videoPath!!))
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share AR Video"))
        } catch (e: Exception) {
            Toast.makeText(this, "Share failed", Toast.LENGTH_SHORT).show()
        }
    }
}
