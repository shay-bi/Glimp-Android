/*===============================================================================
Copyright (c) 2024 PTC Inc. and/or Its Subsidiary Companies. All Rights Reserved.

Vuforia is a trademark of PTC Inc., registered in the United States and other
countries.
===============================================================================*/

package com.vuforia.engine.native_sample

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.opengl.*
import android.os.*
import android.util.Log
import android.view.*
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.annotation.Keep
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10

/**
 * Activity to demonstrate how to use Vuforia Image Target and Model Target features,
 * Video Background rendering and Vuforia lifecycle.
 * Rebuilt with Offscreen Rendering (FBO) for stable video recording without UI overlays.
 */
class VuforiaActivity : AppCompatActivity(), GLSurfaceView.Renderer, SurfaceHolder.Callback {

    private val TAG = "VuforiaActivity"
    private val REQUIRED_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }

    private var mPermissionsRequested = false
    private lateinit var mGLView : GLSurfaceView

    private var mTarget = 0
    private var mProgressIndicatorLayout: RelativeLayout? = null

    private var mWidth = 0
    private var mHeight = 0

    private var mVuforiaStarted = false
    private var mIsInitializing = false
    private var mSurfaceChanged = false

    private var mWindowDisplayRotation = Surface.ROTATION_0

    private var mVideoOverlayManager: VideoOverlayManager? = null

    private var mCurrentTargetId: String? = null
    private var mCurrentMetadata: String? = null
    private var mIsVideoActive = false
    private var mLastVibratedTargetId: String? = null
    private var mTargetFoundAtLeastOnce = false
    private var mLastVibrationTime: Long = 0
    private var mShowSpinner = false
    private var mTargetLoadingSpinner: ProgressBar? = null
    
    // Tracking state
    private var mLastVisibleTime: Long = 0
    private val HARD_LOSS_THRESHOLD = 8000L // 8s total loss before forcing a cloud rescan

    private val mActivityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var mArUiRoot: View
    private lateinit var mBtnRecord: ImageButton
    private lateinit var mBtnAudio: ImageButton
    private lateinit var mBtnFlash: ImageButton

    private var mIsRecording = false
    private var mIsAudioOn = true
    private var mIsFlashOn = false

    private lateinit var mARRecorder: ARRecorder
    
    // EGL objects
    private var mEglDisplay = EGL14.EGL_NO_DISPLAY
    private var mEglConfig: android.opengl.EGLConfig? = null
    private var mEglContext = EGL14.EGL_NO_CONTEXT

    // Offscreen rendering
    private var mFboId = 0
    private var mFboTextureId = 0
    private var mDepthBufferId = 0

    // Native methods
    private external fun initAR(activity: Activity, assetManager: AssetManager, target: Int)
    private external fun deinitAR()
    private external fun startAR() : Boolean
    private external fun stopAR()
    private external fun initRendering()
    private external fun setTextures(astronautWidth: Int, astronautHeight: Int, astronautBytes: ByteBuffer,
                                     landerWidth: Int, landerHeight: Int, landerBytes: ByteBuffer)
    private external fun deinitRendering()
    private external fun configureRendering(width: Int, height: Int, orientation: Int, rotation: Int) : Boolean
    private external fun renderFrame(videoTextureId: Int, showLoadingSpinner: Boolean, textureTransform: FloatArray, keepRendering: Boolean) : Boolean
    private external fun getCloudImageTargetResult(projectionArray: FloatArray, modelViewArray: FloatArray, scaledModelViewArray: FloatArray) : Boolean
    private external fun startCloudImageRecognition() : Boolean
    private external fun stopCloudImageRecognition() : Boolean
    private external fun resetCloudTargetTracking()
    private external fun setFlash(on: Boolean): Boolean

    @Keep
    private fun onCloudTargetDetected(metadata: String, targetId: String) {
        mActivityScope.launch { handleCloudTargetDetected(metadata, targetId) }
    }

    @Keep
    private fun vibrate() {
        val now = System.currentTimeMillis()
        if (now - mLastVibrationTime < 2000) return
        mLastVibrationTime = now
        mActivityScope.launch {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(100)
                }
            }
        }
    }

    @Keep
    private fun presentError(message: String) {
        mIsInitializing = false
        mActivityScope.launch {
            AlertDialog.Builder(this@VuforiaActivity)
                .setMessage(message)
                .setTitle(R.string.error_dialog_title)
                .setPositiveButton(R.string.ok) { _, _ ->
                    if (mVuforiaStarted) stopAR()
                    deinitAR()
                    finish()
                }
                .show()
        }
    }

    @Keep
    private fun initDone() {
        mIsInitializing = false
        mVuforiaStarted = startAR()
        if (mVuforiaStarted) {
            mActivityScope.launch {
                delay(500)
                startCloudImageRecognition()
            }
        }
        mActivityScope.launch { mGLView.visibility = View.VISIBLE }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        mTarget = intent.getIntExtra("Target", 0)
        mGLView = GLSurfaceView(this)
        mGLView.holder.addCallback(this)
        mGLView.setEGLContextClientVersion(3)
        mGLView.setEGLConfigChooser(createRecordableConfigChooser())
        mGLView.setRenderer(this)
        addContentView(mGLView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        mGLView.visibility = View.GONE

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        makeFullScreen()

        val overlay = View.inflate(this, R.layout.ar_overlay, null)
        addContentView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setupUI(overlay)

        // Handle Window Insets for the top bar to account for status bar and notch
        val topBar = overlay.findViewById<View>(R.id.top_bar)
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val density = resources.displayMetrics.density
            view.updatePadding(
                top = systemBars.top + (20 * density).toInt(),
                left = systemBars.left + (16 * density).toInt(),
                right = systemBars.right + (16 * density).toInt(),
                bottom = 0
            )
            insets
        }
        ViewCompat.requestApplyInsets(overlay)

        mProgressIndicatorLayout = View.inflate(applicationContext, R.layout.progress_indicator, null) as RelativeLayout
        addContentView(mProgressIndicatorLayout, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        mVideoOverlayManager = VideoOverlayManager(this)
        mVideoOverlayManager!!.init()
        mARRecorder = ARRecorder(this)
        setupRecorderCallbacks()
    }

    private fun setupUI(overlay: View) {
        mArUiRoot = overlay.findViewById(R.id.ar_ui_root)
        mBtnRecord = overlay.findViewById(R.id.btn_record)
        mBtnAudio = overlay.findViewById(R.id.btn_audio)
        mBtnFlash = overlay.findViewById(R.id.btn_flash)
        mTargetLoadingSpinner = overlay.findViewById(R.id.target_loading_spinner)

        mBtnRecord.setOnClickListener { if (mIsRecording) stopRecording() else startRecording() }
        
        val btnShop = overlay.findViewById<Button>(R.id.btn_shop)
        btnShop.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://glimp.app")))
        }
        btnShop.startAnimation(AnimationUtils.loadAnimation(this, R.anim.breathing))
        
        // Shop Button Styling (4-color diagonal gradient and blue border)
        val gradient = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                0xFFf97316.toInt(), // Orange
                0xFFfacc15.toInt(), // Yellow
                0xFFec4899.toInt(), // Pink
                0xFF0ea5e9.toInt()  // Blue
            )
        )
        gradient.cornerRadius = 24f * resources.displayMetrics.density
        val borderColor = 0xB30ea5e9.toInt() // Blue at 70% opacity
        gradient.setStroke((2 * resources.displayMetrics.density).toInt(), borderColor)
        btnShop.background = gradient

        mBtnAudio.setOnClickListener {
            mIsAudioOn = !mIsAudioOn
            mBtnAudio.setImageResource(if (mIsAudioOn) R.drawable.ic_audio_on else R.drawable.ic_audio_off)
        }
        mBtnFlash.setOnClickListener {
            mIsFlashOn = !mIsFlashOn
            if (setFlash(mIsFlashOn)) {
                mBtnFlash.setImageResource(if (mIsFlashOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
            } else {
                mIsFlashOn = !mIsFlashOn
                Toast.makeText(this, "Flash not supported", Toast.LENGTH_SHORT).show()
            }
        }
        overlay.findViewById<ImageButton>(R.id.btn_info).setOnClickListener { showInfoDialog() }
    }

    private fun startRecording() {
        if (mWidth <= 0 || mHeight <= 0) return
        RecordingService.start(this)
        val recordWidth = if (mWidth % 2 == 0) mWidth else mWidth - 1
        val recordHeight = if (mHeight % 2 == 0) mHeight else mHeight - 1
        if (!mARRecorder.startRecording(recordWidth, recordHeight, mIsAudioOn)) {
            RecordingService.stop(this)
        }
    }

    private fun stopRecording() {
        mARRecorder.stopRecording()
    }

    private fun setupRecorderCallbacks() {
        mARRecorder.onRecordingStarted = {
            mIsRecording = true
            mBtnRecord.setImageResource(R.drawable.ic_record_recording_circle)
            mBtnRecord.setBackgroundResource(R.drawable.btn_record_background_recording)
        }
        mARRecorder.onRecordingStopped = { file ->
            mIsRecording = false
            mBtnRecord.setImageResource(R.drawable.ic_record_idle)
            mBtnRecord.setBackgroundResource(R.drawable.btn_record_background)
            RecordingService.stop(this)
            file?.let { openVideoPreview(it) } ?: run {
                Toast.makeText(this, "Recording failed or too short", Toast.LENGTH_SHORT).show()
            }
        }
        mARRecorder.onError = { error ->
            mIsRecording = false
            mBtnRecord.setImageResource(R.drawable.ic_record_idle)
            mBtnRecord.setBackgroundResource(R.drawable.btn_record_background)
            RecordingService.stop(this)
            Toast.makeText(this, "Recording error: $error", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openVideoPreview(file: File) {
        val intent = Intent(this, VideoPreviewActivity::class.java).apply {
            putExtra(VideoPreviewActivity.EXTRA_VIDEO_PATH, file.absolutePath)
        }
        startActivity(intent)
    }

    override fun onPause() {
        stopCloudImageRecognition()
        if (mVuforiaStarted) stopAR()
        if (mIsRecording) stopRecording()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        makeFullScreen()
        if (runtimePermissionsGranted()) {
            if (!mVuforiaStarted) {
                mActivityScope.launch { initializeVuforia() }
            } else {
                mActivityScope.launch { startAR(); startCloudImageRecognition() }
            }
        } else if (!mPermissionsRequested) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, 0)
            mPermissionsRequested = true
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 0) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                mActivityScope.launch { initializeVuforia() }
            } else {
                Toast.makeText(this, "Camera and Audio permissions are required for AR experience", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun makeFullScreen() {
        mGLView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LOW_PROFILE or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
    }

    private suspend fun initializeVuforia() {
        if (mIsInitializing || mVuforiaStarted) return
        mIsInitializing = true
        withContext(Dispatchers.Default) {
            initAR(this@VuforiaActivity, this@VuforiaActivity.assets, mTarget)
        }
    }

    private fun runtimePermissionsGranted(): Boolean {
        return REQUIRED_PERMISSIONS.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    override fun onSurfaceCreated(unused: GL10, config: javax.microedition.khronos.egl.EGLConfig) {
        initRendering()
        mVideoOverlayManager?.initGL()
        mEglDisplay = EGL14.eglGetCurrentDisplay()
        mEglContext = EGL14.eglGetCurrentContext()
        
        val configId = IntArray(1)
        if (EGL14.eglQueryContext(mEglDisplay, mEglContext, EGL14.EGL_CONFIG_ID, configId, 0)) {
            val attribs = intArrayOf(EGL14.EGL_CONFIG_ID, configId[0], EGL14.EGL_NONE)
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(mEglDisplay, attribs, 0, configs, 0, 1, numConfigs, 0)
            mEglConfig = configs[0]
        }
    }

    private fun initFbo(width: Int, height: Int) {
        if (mFboId != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(mFboId), 0)
            GLES30.glDeleteTextures(1, intArrayOf(mFboTextureId), 0)
            GLES30.glDeleteRenderbuffers(1, intArrayOf(mDepthBufferId), 0)
        }
        val fbos = IntArray(1); GLES30.glGenFramebuffers(1, fbos, 0); mFboId = fbos[0]
        val textures = IntArray(1); GLES30.glGenTextures(1, textures, 0); mFboTextureId = textures[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mFboTextureId)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        val depths = IntArray(1); GLES30.glGenRenderbuffers(1, depths, 0); mDepthBufferId = depths[0]
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, mDepthBufferId)
        GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT24, width, height)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, mFboId)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, mFboTextureId, 0)
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, mDepthBufferId)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    override fun onDrawFrame(unused: GL10) {
        if (!mVuforiaStarted) return
        if (mSurfaceChanged || mWindowDisplayRotation != windowManager.defaultDisplay.rotation) {
            mSurfaceChanged = false
            mWindowDisplayRotation = windowManager.defaultDisplay.rotation
            configureRendering(mWidth, mHeight, resources.configuration.orientation, mWindowDisplayRotation)
            initFbo(mWidth, mHeight)
        }

        val textureTransform = mVideoOverlayManager?.updateTexture() ?: FloatArray(16).apply { this[0]=1f; this[5]=1f; this[10]=1f; this[15]=1f }
        val projectionArray = FloatArray(16); val modelViewArray = FloatArray(16); val scaledModelViewArray = FloatArray(16)
        val hasCloudTarget = getCloudImageTargetResult(projectionArray, modelViewArray, scaledModelViewArray)

        if (hasCloudTarget) {
            mLastVisibleTime = System.currentTimeMillis()
            if (mCurrentTargetId != null && !mIsVideoActive) {
                // Regained tracking before a full reset occurred - restart video
                mCurrentMetadata?.let { handleCloudTargetDetected(it, mCurrentTargetId!!) }
            }
            mTargetFoundAtLeastOnce = true
        } else if (mCurrentTargetId != null) {
            // Only consider it "lost" if we aren't loading
            if (mVideoOverlayManager?.isLoading() != true) {
                val timeSinceVisible = System.currentTimeMillis() - mLastVisibleTime
                if (timeSinceVisible >= HARD_LOSS_THRESHOLD) {
                    onTrackingLost()
                }
            } else {
                // Reset timer while loading to ensure we don't time out before video starts
                mLastVisibleTime = System.currentTimeMillis()
            }
        }

        val videoTextureId = mVideoOverlayManager?.getCurrentVideoTexture() ?: 0
        if (videoTextureId != 0 && mShowSpinner) { mShowSpinner = false }
        
        // Native render call - renders Vuforia state into current FBO
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, mFboId)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        
        val didRender = renderFrame(videoTextureId, mShowSpinner, textureTransform, mIsVideoActive && mTargetFoundAtLeastOnce)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        if (didRender) {
            // 1. Blit FBO to screen
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, mFboId)
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
            GLES30.glBlitFramebuffer(0, 0, mWidth, mHeight, 0, 0, mWidth, mHeight, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST)

            // 2. Blit FBO to recorder if recording is active
            if (mIsRecording) {
                mEglConfig?.let { mARRecorder.recordFrame(mEglDisplay, mEglContext, it, mFboId, mWidth, mHeight) }
            }
            
            if (mProgressIndicatorLayout?.visibility != View.GONE) {
                mActivityScope.launch { 
                    mProgressIndicatorLayout?.visibility = View.GONE
                    mArUiRoot.visibility = View.VISIBLE 
                }
            }
        }
    }

    private fun projectToScreen(point: FloatArray, projectionMatrix: FloatArray, modelViewMatrix: FloatArray): FloatArray {
        val mvp = FloatArray(16); Matrix.multiplyMM(mvp, 0, projectionMatrix, 0, modelViewMatrix, 0)
        val res = FloatArray(4); Matrix.multiplyMV(res, 0, mvp, 0, floatArrayOf(point[0], point[1], point[2], 1.0f), 0)
        if (res[3] != 0.0f) { res[0] /= res[3]; res[1] /= res[3] }
        return floatArrayOf((res[0] * 0.5f + 0.5f) * mWidth, (1.0f - (res[1] * 0.5f + 0.5f)) * mHeight)
    }

    override fun onSurfaceChanged(unused: GL10, width: Int, height: Int) { mWidth = width; mHeight = height; mSurfaceChanged = true }
    override fun surfaceCreated(v: SurfaceHolder) {}
    override fun surfaceChanged(v: SurfaceHolder, f: Int, w: Int, h: Int) {}
    override fun surfaceDestroyed(v: SurfaceHolder) { deinitRendering() }

    private fun onTrackingLost() {
        if (mCurrentTargetId == null) return
        
        Log.d(TAG, "onTrackingLost - performing HARD RESET")
        mCurrentTargetId = null
        mCurrentMetadata = null
        mIsVideoActive = false
        mTargetFoundAtLeastOnce = false
        mLastVibratedTargetId = null // Allow vibration on re-detection
        
        // Stop video immediately
        mVideoOverlayManager?.stopVideo()
        
        // Reset cloud tracking (native call) - DESTROYS local observer and forces rescan
        resetCloudTargetTracking()
        
        mShowSpinner = false
    }

    private fun handleCloudTargetDetected(metadata: String, targetId: String) {
        val cfg = CloudConfig.parseCloudMetadata(metadata) ?: return
        val url = CloudConfig.extractVideoURL(cfg) ?: return
        if (mLastVibratedTargetId != targetId) { vibrate(); mLastVibratedTargetId = targetId }
        
        if (mCurrentTargetId != targetId || !mIsVideoActive || mVideoOverlayManager?.isError() == true) {
            Log.d(TAG, "New target detection or video restart: $targetId. mIsVideoActive: $mIsVideoActive")
            
            // If switching to a completely new target, ensure the previous one is fully cleared
            if (mCurrentTargetId != targetId && mCurrentTargetId != null) {
                mVideoOverlayManager?.stopVideo()
                mTargetFoundAtLeastOnce = false
            }
            
            mCurrentTargetId = targetId
            mCurrentMetadata = metadata
            mIsVideoActive = true
            mLastVisibleTime = System.currentTimeMillis()
            mShowSpinner = true
            
            mVideoOverlayManager?.startVideo(
                url, targetId, 
                CloudConfig.shouldAutoPlay(cfg), 
                CloudConfig.shouldLoop(cfg), 
                CloudConfig.shouldMute(cfg), 
                CloudConfig.getVideoRotation(cfg)
            )
        }
    }

    private fun showInfoDialog() {
        InfoPopupDialogFragment().show(supportFragmentManager, "info_popup")
    }

    override fun onDestroy() {
        mVideoOverlayManager?.deinit()
        if (mVuforiaStarted) stopAR()
        deinitAR(); mActivityScope.cancel()
        super.onDestroy()
    }

    /**
     * Creates an EGL config chooser with EGL_RECORDABLE_ANDROID so the surface is compatible
     * with MediaRecorder's input surface.
     */
    private fun createRecordableConfigChooser(): GLSurfaceView.EGLConfigChooser {
        return object : GLSurfaceView.EGLConfigChooser {
            override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
                // EGL_RECORDABLE_ANDROID = 0x3142
                val attribList = intArrayOf(
                    EGL10.EGL_RENDERABLE_TYPE, 0x0040, // EGL_OPENGL_ES3_BIT
                    EGL10.EGL_RED_SIZE, 8,
                    EGL10.EGL_GREEN_SIZE, 8,
                    EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_ALPHA_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 24,
                    0x3142, EGL14.EGL_TRUE,
                    EGL10.EGL_NONE
                )
                val configs = arrayOfNulls<EGLConfig>(1)
                val numConfigs = IntArray(1)
                if (!egl.eglChooseConfig(display, attribList, configs, 1, numConfigs)) {
                    val fallbackAttribs = intArrayOf(
                        EGL10.EGL_RENDERABLE_TYPE, 0x0040,
                        EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8,
                        EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
                        EGL10.EGL_DEPTH_SIZE, 24, EGL10.EGL_NONE
                    )
                    egl.eglChooseConfig(display, fallbackAttribs, configs, 1, numConfigs)
                }
                return configs[0] ?: throw RuntimeException("Unable to choose EGL config")
            }
        }
    }

    companion object {
        init {
            System.loadLibrary("VuforiaSample")
        }

        fun getImageTargetId(): Int = 0
        fun getModelTargetId(): Int = 1
    }
}
