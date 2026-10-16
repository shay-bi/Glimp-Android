/*===============================================================================
Copyright (c) 2024 PTC Inc. and/or Its Subsidiary Companies. All Rights Reserved.

Vuforia is a trademark of PTC Inc., registered in the United States and other
countries.
===============================================================================*/

package com.vuforia.engine.native_sample

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.util.Log
import android.view.Surface
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.util.Util
import com.google.android.exoplayer2.video.VideoSize
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference


/**
 * Manages video overlay playback using ExoPlayer and OpenGL textures.
 * Handles video state machine and texture updates for AR rendering.
 * Optimized for fast startup and reuse of player instance.
 */
class VideoOverlayManager(private val context: android.content.Context) {
    private val TAG = "VideoOverlayManager"

    enum class VideoState {
        IDLE,
        LOADING,
        READY,
        PLAYING,
        PAUSED,
        STOPPED,
        ERROR
    }

    private val state = AtomicReference(VideoState.IDLE)
    private var exoPlayer: ExoPlayer? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var videoTextureId: Int = 0
    private var activeTargetId: String? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val frameAvailable = AtomicBoolean(false)
    private val textureTransformMatrix = FloatArray(16).apply {
        // Initialize to identity matrix
        this[0] = 1f
        this[5] = 1f
        this[10] = 1f
        this[15] = 1f
    }
    private var textureInitialized = false

    // Configuration
    private var shouldAutoPlay = true
    private var shouldLoop = true
    private var shouldMute = false
    private var videoRotation = 0


    /**
     * Initialize the video overlay manager.
     */
    fun init(): Boolean {
        Log.d(TAG, "init")
        textureInitialized = false
        setupExoPlayer()
        return true
    }

    private fun setupExoPlayer() {
        if (exoPlayer != null) return
        Log.d(TAG, "setupExoPlayer")

        // Optimized LoadControl for AR - reduce buffering to start playback faster
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                1500, // minBufferMs
                5000, // maxBufferMs
                500,  // bufferForPlaybackMs
                1000  // bufferForPlaybackAfterRebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(0, false)
            .build()

        exoPlayer = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        val stateString = when (playbackState) {
                            Player.STATE_IDLE -> "IDLE"
                            Player.STATE_BUFFERING -> "BUFFERING"
                            Player.STATE_READY -> "READY"
                            Player.STATE_ENDED -> "ENDED"
                            else -> "UNKNOWN"
                        }
                        Log.d(TAG, "onPlaybackStateChanged: $stateString for target: $activeTargetId")
                        
                        when (playbackState) {
                            Player.STATE_READY -> {
                                Log.d(TAG, "ExoPlayer READY for target: $activeTargetId. Muted: $shouldMute, Volume: $volume")
                                state.set(VideoState.READY)
                                if (shouldAutoPlay && activeTargetId != null) {
                                    play()
                                }
                            }
                            Player.STATE_ENDED -> {
                                Log.d(TAG, "ExoPlayer ENDED for target: $activeTargetId")
                                if (!shouldLoop) state.set(VideoState.STOPPED)
                            }
                            Player.STATE_BUFFERING -> {
                                if (state.get() != VideoState.PLAYING) {
                                    state.set(VideoState.LOADING)
                                    Log.d(TAG, "ExoPlayer is BUFFERING for target: $activeTargetId")
                                }
                            }
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        Log.d(TAG, "onIsPlayingChanged: $isPlaying for target: $activeTargetId. Current state: ${state.get()}")
                        if (isPlaying) {
                            state.set(VideoState.PLAYING)
                        } else if (state.get() == VideoState.PLAYING) {
                            state.set(VideoState.PAUSED)
                        }
                    }

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        Log.d(TAG, "onVideoSizeChanged: ${videoSize.width}x${videoSize.height} for target: $activeTargetId")
                        if (videoSize.width > 0 && videoSize.height > 0) {
                            surfaceTexture?.setDefaultBufferSize(videoSize.width, videoSize.height)
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "onPlayerError: ${error.message} (Code: ${error.errorCodeName}, ${error.errorCode}) for target: $activeTargetId", error)
                        state.set(VideoState.ERROR)
                    }
                    
                    override fun onVolumeChanged(volume: Float) {
                        Log.d(TAG, "onVolumeChanged: $volume for target: $activeTargetId")
                    }
                })
                
                // Set the surface if it was already created in initGL
                if (surface != null) {
                    Log.d(TAG, "setupExoPlayer: Setting existing surface to new player")
                    setVideoSurface(surface)
                }
            }
    }

    /**
     * Initialize OpenGL texture. Must be called on GL thread.
     */
    fun initGL(): Boolean {
        if (textureInitialized) {
            return true
        }
        Log.d(TAG, "initGL")
        return try {
            val textures = IntArray(1)
            GLES30.glGenTextures(1, textures, 0)
            videoTextureId = textures[0]

            if (videoTextureId == 0) {
                Log.e(TAG, "Failed to generate video texture")
                return false
            }

            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

            surfaceTexture = SurfaceTexture(videoTextureId)
            surfaceTexture?.setOnFrameAvailableListener {
                frameAvailable.set(true)
            }

            surface = Surface(surfaceTexture)
            Log.d(TAG, "initGL: Surface created: $surface")
            
            // Attach surface to player if it exists
            scope.launch(Dispatchers.Main) {
                Log.d(TAG, "initGL: Attaching surface to ExoPlayer on main thread")
                exoPlayer?.setVideoSurface(surface)
            }

            textureInitialized = true
            Log.d(TAG, "Video overlay manager GL initialized with texture ID: $videoTextureId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize video overlay manager GL: ${e.message}", e)
            false
        }
    }


    /**
     * Start loading and playing video from URL.
     */
    fun startVideo(url: String, targetId: String, autoPlay: Boolean = true, loop: Boolean = true, muted: Boolean = false, rotation: Int = 0) {
        Log.d(TAG, "startVideo: url=$url, targetId=$targetId")
        // If already playing or loading the same target, don't restart unless it's in an error state
        val currentState = state.get()
        if (activeTargetId == targetId && 
            (currentState == VideoState.LOADING || currentState == VideoState.PLAYING || currentState == VideoState.READY)) {
            Log.d(TAG, "Video already $currentState for target $targetId, skipping start")
            
            // Ensure it's playing if it should be
            if (autoPlay && currentState != VideoState.PLAYING) {
                Log.d(TAG, "startVideo: Ensuring it's playing since autoPlay is true")
                play()
            }
            return
        }

        activeTargetId = targetId
        shouldAutoPlay = autoPlay
        shouldLoop = loop
        shouldMute = muted
        videoRotation = rotation

        state.set(VideoState.LOADING)
        Log.d(TAG, "Starting video: $url for target: $targetId (Rotation: $videoRotation, muted: $muted, loop: $loop)")

        scope.launch(Dispatchers.Main) {
            try {
                setupExoPlayer()
                
                exoPlayer?.apply {
                    Log.d(TAG, "startVideo scope: Stopping and preparing player")
                    stop()
                    clearMediaItems()
                    
                    volume = if (muted) 0f else 1f
                    repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                    
                    val dataSourceFactory = DefaultDataSourceFactory(context, Util.getUserAgent(context, "Glimp"))
                    val mediaItem = MediaItem.fromUri(url)
                    val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)

                    setMediaSource(mediaSource)
                    prepare()
                    
                    // Explicitly set playWhenReady to true if autoPlay is requested
                    playWhenReady = autoPlay
                    Log.d(TAG, "startVideo scope: ExoPlayer prepared and playWhenReady set to $autoPlay")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start video in scope: ${e.message}", e)
                state.set(VideoState.ERROR)
            }
        }
    }


    /**
     * Update video texture from SurfaceTexture.
     * Must be called on OpenGL thread each frame.
     */
    fun updateTexture(): FloatArray {
        if (!textureInitialized) {
            initGL()
        }
        
        if (frameAvailable.compareAndSet(true, false)) {
            try {
                surfaceTexture?.updateTexImage()
                surfaceTexture?.getTransformMatrix(textureTransformMatrix)
                
                // Use just the videoRotation metadata.
                val finalRotation = videoRotation.toFloat()

                // We keep the vertical flip as it is necessary for SurfaceTexture to OpenGL mapping.
                applyRotationAndFlipToMatrix(textureTransformMatrix, finalRotation)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating texture: ${e.message}")
            }
        }
        return textureTransformMatrix
    }

    private fun applyRotationAndFlipToMatrix(matrix: FloatArray, degrees: Float) {
        val temp = FloatArray(16)
        android.opengl.Matrix.setIdentityM(temp, 0)
        
        // Move to center of UV space (0.5, 0.5)
        android.opengl.Matrix.translateM(temp, 0, 0.5f, 0.5f, 0f)
        
        // Apply rotation
        android.opengl.Matrix.rotateM(temp, 0, -degrees, 0f, 0f, 1f)
        
        // Apply vertical flip (Y-axis in UV space)
        android.opengl.Matrix.scaleM(temp, 0, 1f, -1f, 1f)
        
        // Move back from center
        android.opengl.Matrix.translateM(temp, 0, -0.5f, -0.5f, 0f)
        
        val result = FloatArray(16)
        android.opengl.Matrix.multiplyMM(result, 0, matrix, 0, temp, 0)
        System.arraycopy(result, 0, matrix, 0, 16)
    }


    fun stopVideo() {
        Log.d(TAG, "stopVideo for target: $activeTargetId")
        activeTargetId = null
        state.set(VideoState.STOPPED)
        scope.launch(Dispatchers.Main) {
            exoPlayer?.stop()
            exoPlayer?.clearMediaItems()
        }
    }


    fun getCurrentVideoTexture(): Int {
        val s = state.get()
        // Allow texture to be returned if it's READY, PLAYING, or PAUSED
        return if (s == VideoState.READY || s == VideoState.PLAYING || s == VideoState.PAUSED) videoTextureId else 0
    }


    fun isLoading(): Boolean = state.get() == VideoState.LOADING
    fun isError(): Boolean = state.get() == VideoState.ERROR
    
    fun play() { 
        Log.d(TAG, "play() for target: $activeTargetId")
        scope.launch(Dispatchers.Main) {
            exoPlayer?.apply {
                if (!shouldMute) volume = 1f
                playWhenReady = true
                play()
            }
        }
    }
    
    fun pause() { 
        Log.d(TAG, "pause() for target: $activeTargetId")
        state.set(VideoState.PAUSED)
        scope.launch(Dispatchers.Main) {
            exoPlayer?.apply {
                volume = 0f
                playWhenReady = false
                pause()
            }
        }
    }

    fun deinit() {
        Log.d(TAG, "deinit")
        // Stop and release ExoPlayer immediately on the current thread (Main).
        // Using launch/cancel was causing race conditions.
        try {
            exoPlayer?.stop()
            exoPlayer?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing ExoPlayer: ${e.message}", e)
        }
        exoPlayer = null
        
        try { surface?.release() } catch (e: Exception) { Log.w(TAG, "Error releasing surface: ${e.message}") }
        surface = null
        
        try { surfaceTexture?.release() } catch (e: Exception) { Log.w(TAG, "Error releasing surfaceTexture: ${e.message}") }
        surfaceTexture = null
        
        videoTextureId = 0
        textureInitialized = false
        state.set(VideoState.IDLE)
        scope.cancel()
    }
}
