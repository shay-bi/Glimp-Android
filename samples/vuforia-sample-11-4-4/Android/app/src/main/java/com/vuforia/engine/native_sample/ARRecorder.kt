/*===============================================================================
Copyright (c) 2024 PTC Inc. and/or Its Subsidiary Companies. All Rights Reserved.

Vuforia is a trademark of PTC Inc., registered in the United States and other
countries.
===============================================================================*/

package com.vuforia.engine.native_sample

import android.content.Context
import android.media.MediaRecorder
import android.opengl.*
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * AR Recorder that captures only the OpenGL content (FBO) without UI overlays.
 * Uses MediaRecorder with a Surface input and EGL blitting for high performance.
 */
class ARRecorder(private val context: Context) {

    companion object {
        private const val TAG = "ARRecorder"
        private const val VIDEO_BIT_RATE = 10_000_000
        private const val VIDEO_FRAME_RATE = 30
        private const val MIN_RECORDING_MS = 1000L
    }

    enum class State {
        IDLE,
        RECORDING,
        STOPPING
    }

    private var mediaRecorder: MediaRecorder? = null
    private var recorderSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var outputFile: File? = null

    private val state = AtomicReference(State.IDLE)
    private var startTimeMs: Long = 0
    
    private var videoWidth = 0
    private var videoHeight = 0

    var onRecordingStarted: (() -> Unit)? = null
    var onRecordingStopped: ((File?) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    fun isRecording(): Boolean = state.get() == State.RECORDING

    /**
     * Starts the MediaRecorder and prepares for recording.
     */
    fun startRecording(width: Int, height: Int, recordAudio: Boolean = true): Boolean {
        Log.d(TAG, "startRecording: ${width}x${height}, audio=$recordAudio")
        if (!state.compareAndSet(State.IDLE, State.RECORDING)) return false

        this.videoWidth = width
        this.videoHeight = height

        return try {
            outputFile = createOutputFile() ?: throw Exception("Failed to create output file")
            
            mediaRecorder = (if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }).apply {
                if (recordAudio) setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                
                if (recordAudio) {
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(128000)
                    setAudioSamplingRate(44100)
                }

                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(width, height)
                setVideoEncodingBitRate(VIDEO_BIT_RATE)
                setVideoFrameRate(VIDEO_FRAME_RATE)
                setOutputFile(outputFile!!.absolutePath)
                
                prepare()
            }

            mediaRecorder!!.start()
            startTimeMs = System.currentTimeMillis()
            onRecordingStarted?.invoke()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MediaRecorder: ${e.message}")
            cleanup()
            state.set(State.IDLE)
            onError?.invoke(e.message ?: "Initialization failed")
            false
        }
    }

    /**
     * Blits the content of the provided FBO to the recorder surface.
     * Must be called from the GL thread.
     */
    fun recordFrame(eglDisplay: EGLDisplay, eglContext: EGLContext, eglConfig: android.opengl.EGLConfig, fboId: Int, width: Int, height: Int) {
        if (state.get() != State.RECORDING || mediaRecorder == null) return

        if (recorderSurface == EGL14.EGL_NO_SURFACE) {
            val surface = mediaRecorder!!.surface
            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            recorderSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, surfaceAttribs, 0)
            if (recorderSurface == EGL14.EGL_NO_SURFACE) {
                Log.e(TAG, "Failed to create EGL window surface for recorder")
                return
            }
        }

        val screenSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        
        // 1. Switch to recorder surface
        EGL14.eglMakeCurrent(eglDisplay, recorderSurface, recorderSurface, eglContext)
        
        // 2. Blit FBO to recorder surface (default FB 0)
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, fboId)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
        GLES30.glBlitFramebuffer(0, 0, width, height, 0, 0, videoWidth, videoHeight, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST)
        
        // 3. Set timestamp and swap
        EGLExt.eglPresentationTimeANDROID(eglDisplay, recorderSurface, System.nanoTime())
        EGL14.eglSwapBuffers(eglDisplay, recorderSurface)
        
        // 4. Restore screen surface
        EGL14.eglMakeCurrent(eglDisplay, screenSurface, screenSurface, eglContext)
    }

    fun stopRecording() {
        if (!state.compareAndSet(State.RECORDING, State.STOPPING)) return
        Log.d(TAG, "stopRecording")

        val duration = System.currentTimeMillis() - startTimeMs
        if (duration < MIN_RECORDING_MS) {
            try { Thread.sleep(MIN_RECORDING_MS - duration) } catch (e: Exception) {}
        }

        val resultFile = outputFile
        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "MediaRecorder stop failed: ${e.message}")
        }

        cleanup()
        state.set(State.IDLE)
        onRecordingStopped?.invoke(if (resultFile?.exists() == true && resultFile.length() > 1024) resultFile else null)
    }

    private fun cleanup() {
        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
            mediaRecorder = null
            recorderSurface = EGL14.EGL_NO_SURFACE
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup error: ${e.message}")
        }
    }

    private fun createOutputFile(): File? {
        val dir = context.getExternalFilesDir(null) ?: return null
        val recordingsDir = File(dir, "Recordings").apply { if (!exists()) mkdirs() }
        val name = "AR_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
        return File(recordingsDir, name)
    }
}
