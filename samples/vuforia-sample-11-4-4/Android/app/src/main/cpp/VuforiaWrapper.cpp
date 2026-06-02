/*===============================================================================
Copyright (c) 2024 PTC Inc. and/or Its Subsidiary Companies. All Rights Reserved.

Vuforia is a trademark of PTC Inc., registered in the United States and other
countries.
===============================================================================*/

#include <jni.h>

#include "GLESRenderer.h"
#include <AppController.h>
#include <Log.h>

#include <VuforiaEngine/VuforiaEngine.h>

#include <GLES3/gl31.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>

#include <cassert>
#include <chrono>
#include <mutex>
#include <optional>
#include <vector>

#include <arcore_c_api.h>

// Cross-platform AppController providing high level Vuforia Engine operations
AppController controller;

/// JVM pointer obtained in the JNI_OnLoad method below and consumed in the cross-platform code
void* javaVM;

// Struct to hold data that we need to store between calls
struct
{
    JavaVM* vm = nullptr;
    jobject activity = nullptr;
    AAssetManager* assetManager = nullptr;
    jmethodID presentErrorMethodID = nullptr;
    jmethodID initDoneMethodID = nullptr;
    jmethodID onCloudTargetDetectedMethodID = nullptr;
    jmethodID vibrateMethodID = nullptr;

    GLESRenderer renderer;

    bool usingARCore{ false };
} gWrapperData;

// Provider pointers that allow for interacting with ARCore
std::optional<VuPlatformARCoreInfo> gARCoreInfo{};

// Mutex protecting access to the provider pointers in gARCoreInfo
std::mutex gARCoreInfoMutex;


// JNI Implementation
#ifdef __cplusplus
extern "C"
{
#endif

// Local method declarations

/// Present a pop-up to the user with the given error string.
void callPresentError(const char* errorString);

/// Call the onCloudTargetDetected method in the Activity
void callOnCloudTargetDetected(const char* metadata, const char* targetId);

/// Call the vibrate method in the Activity
void callVibrate();

/// Get the Fusion Provider (ARCore) pointers by querying Vuforia Engine.
/**
 * Assumes gARCoreInfoMutex has been locked.
 * @return true on success; false otherwise.
 */
bool getFusionProviderPointers();

/// Demonstrate how to use the Fusion Provider (ARCore) pointers obtained from Vuforia Engine.
void accessFusionProviderPointers();

/// RAII helper to manage JNIEnv and thread attachment
class JNIThreadAttacher
{
public:
    JNIThreadAttacher() : mEnv(nullptr), mNeedsDetach(false)
    {
        if (gWrapperData.vm == nullptr) return;

        jint res = gWrapperData.vm->GetEnv((void**)&mEnv, JNI_VERSION_1_6);
        if (res == JNI_EDETACHED)
        {
            if (gWrapperData.vm->AttachCurrentThread(&mEnv, nullptr) == 0)
            {
                mNeedsDetach = true;
            }
            else
            {
                LOG("Failed to attach current thread");
                mEnv = nullptr;
            }
        }
    }

    ~JNIThreadAttacher()
    {
        if (mNeedsDetach && gWrapperData.vm != nullptr)
        {
            gWrapperData.vm->DetachCurrentThread();
        }
    }

    JNIEnv* env() const { return mEnv; }

private:
    JNIEnv* mEnv;
    bool mNeedsDetach;
};


/// Called by JNI binding when the client code loads the library
/**
 * Called ONLY when a Java Activity is used - won't get called if a native Activity is used
 */
JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* reserved)
{
    LOG("JNI_OnLoad");

    assert(vm != nullptr);

    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK)
    {
        LOG("Failed to get JNI environment from JavaVM");
        return -1;
    }

    // Cache Java VM
    javaVM = vm;
    gWrapperData.vm = vm;

    LOG("Retrieved and stored JavaVM");
    return JNI_VERSION_1_6;
}


JNIEXPORT void JNICALL
Java_com_vuforia_engine_native_1sample_VuforiaActivity_initAR(JNIEnv* env, jobject /* this */, jobject activity, jobject assetManager,
                                                              jint target)
{
    // Store the Java VM pointer so we can get a JNIEnv in callbacks
    if (env->GetJavaVM(&gWrapperData.vm) != 0)
    {
        return;
    }
    gWrapperData.activity = env->NewGlobalRef(activity);
    jclass clazz = env->GetObjectClass(activity);
    gWrapperData.presentErrorMethodID = env->GetMethodID(clazz, "presentError", "(Ljava/lang/String;)V");
    gWrapperData.initDoneMethodID = env->GetMethodID(clazz, "initDone", "()V");
    gWrapperData.onCloudTargetDetectedMethodID = env->GetMethodID(clazz, "onCloudTargetDetected", "(Ljava/lang/String;Ljava/lang/String;)V");
    gWrapperData.vibrateMethodID = env->GetMethodID(clazz, "vibrate", "()V");
    env->DeleteLocalRef(clazz);

    AppController::InitConfig initConfig;
    initConfig.vbRenderBackend = VuRenderVBBackendType::VU_RENDER_VB_BACKEND_GLES3;
    initConfig.appData = activity;

    // Setup callbacks
    initConfig.errorMessageCallback = [](const char* errorString) {
        LOG("Error message callback invoked. Message: %s", errorString);
        callPresentError(errorString);
    };
    initConfig.vuforiaEngineErrorCallback = [](VuErrorCode errorCode) {
        LOG("Vuforia engine error callback invoked. Error code: 0x%02x", errorCode);

        auto resetARCoreInfo = []() {
            std::scoped_lock lock(gARCoreInfoMutex);
            gARCoreInfo.reset();
        };

        switch (errorCode)
        {
            case VU_ENGINE_ERROR_INVALID_LICENSE:
                LOG("ARCore pointers might have been invalidated. Resetting our copy.");
                resetARCoreInfo();
                callPresentError("Invalid license reported");
                break;
            case VU_ENGINE_ERROR_CAMERA_DEVICE_LOST:
                LOG("ARCore pointers might have been invalidated. Resetting our copy.");
                resetARCoreInfo();
                callPresentError("Camera device lost");
                break;
            case VU_ENGINE_ERROR_PLATFORM_FUSION_PROVIDER_INFO_INVALIDATED:
                LOG("ARCore pointers will be invalidated. Resetting our copy.");
                resetARCoreInfo();
                break;
            default:
                LOG("Got an unexpected Engine error code 0x%02x", errorCode);
                break;
        }
    };
    initConfig.initDoneCallback = []() {
        LOG("InitDone callback");
        JNIThreadAttacher attacher;
        if (attacher.env() != nullptr && gWrapperData.activity != nullptr)
        {
            attacher.env()->CallVoidMethod(gWrapperData.activity, gWrapperData.initDoneMethodID);
        }
    };
    initConfig.cloudTargetDetectedCallback = [](const char* metadata, const char* targetId) {
        LOG("Cloud target detected callback: %s", targetId);
        // Vibration is handled on the Java side to prevent spamming
        callOnCloudTargetDetected(metadata, targetId);
    };

    // Get a native AAssetManager
    gWrapperData.assetManager = AAssetManager_fromJava(env, assetManager);
    if (gWrapperData.assetManager == nullptr)
    {
        initConfig.errorMessageCallback("Error: Failed to get the asset manager");
        return;
    }

    // Start Vuforia initialization
    controller.initAR(initConfig, target);
}


JNIEXPORT jboolean JNICALL
Java_com_vuforia_engine_native_1sample_VuforiaActivity_startAR(JNIEnv* /* env */, jobject /* this */)
{
    // Update usingARCore flag to avoid checking this every frame
    auto platformController = controller.getPlatformController();
    if (platformController == nullptr)
    {
        LOG("Platform controller is null, cannot check for ARCore");
        return JNI_FALSE;
    }

    VuFusionProviderPlatformType fusionProviderPlatformType{ VU_FUSION_PROVIDER_PLATFORM_TYPE_UNKNOWN };
    vuPlatformControllerGetFusionProviderPlatformType(platformController, &fusionProviderPlatformType);
    gWrapperData.usingARCore = (fusionProviderPlatformType == VU_FUSION_PROVIDER_PLATFORM_TYPE_ARCORE);

    if (!controller.startAR())
    {
        return JNI_FALSE;
    }

    if (gWrapperData.usingARCore)
    {
        if (!getFusionProviderPointers())
        {
            // This error is not fatal, as these pointers can also be obtained later
            LOG("Failed to get ARCore provider pointers right after starting Vuforia");
        }
    }

    return JNI_TRUE;
}


JNIEXPORT void JNICALL
Java_com_vuforia_engine_native_1sample_VuforiaActivity_stopAR(JNIEnv* /* env */, jobject /* this */)
{
    controller.stopAR();
}


JNIEXPORT void JNICALL
Java_com_vuforia_engine_native_1sample_VuforiaActivity_deinitAR(JNIEnv* env, jobject /* this */)
{
    controller.deinitAR();

    gWrapperData.assetManager = nullptr;
    if (gWrapperData.activity != nullptr)
    {
        env->DeleteGlobalRef(gWrapperData.activity);
        gWrapperData.activity = nullptr;
    }
}


JNIEXPORT void JNICALL
Java_com_vuforia_engine_native_1sample_VuforiaActivity_cameraPerformAutoFocus(JNIEnv* /* env */, jobject /* this */)
{
    controller.cameraPerformAutoFocus();
}


JNIEXPORT void JNICALL
Java_com_vuforia_engine_native_1sample_VuforiaActivity_cameraRestoreAutoFocus(JNIEnv* /* env */, jobject /* this */)
{
    controller.cameraRestoreAutoFocus();
}


JNIEXPORT void JNICALL
Java_com_vuforia_engine_native_1sample_VuforiaActivity_initRendering(JNIEnv* /* env */, jobject /* this */)
{
    // Define clear color
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);

    if (!gWrapperData.renderer.init(gWrapperData.assetManager))
    {
        LOG("Error initialising rendering");
    }
}


JNIEXPORT void JNICALL
Java_com_vuforia_engine_native_1sample_VuforiaActivity_setTextures(JNIEnv* env, jobject /* this */, jint astronautWidth,
                                                                   jint astronautHeight, jobject astronautByteBuffer, jint landerWidth,
                                                                   jint landerHeight, jobject landerByteBuffer)
{
    // Textures are loaded using the BitmapFactory which isn't available from the NDK.
    // They are loaded in the Kotlin code and passed to this method to create GLES textures.
    auto astronautBytes = static_cast<unsigned char*>(env->GetDirectBufferAddress(astronautByteBuffer));
    gWrapperData.renderer.setAstronautTexture(astronautWidth, astronautHeight, astronautBytes);
    auto landerBytes = static_cast<unsigned char*>(env->GetDirectBufferAddress(landerByteBuffer));
    gWrapperData.renderer.setLanderTexture(landerWidth, landerHeight, landerBytes);
}


JNIEXPORT void JNICALL
Java_com_vuforia_engine_native_1sample_VuforiaActivity_deinitRendering(JNIEnv* /* env */, jobject /* this */)
{
    gWrapperData.renderer.deinit();
}


JNIEXPORT jboolean JNICALL
Java_com_vuforia_engine_native_1sample_VuforiaActivity_configureRendering(JNIEnv* /* env */, jobject /* this */, jint width, jint height,
                                                                          jint orientation, jint rotation)
{
    std::vector<int> androidOrientation{ orientation, rotation };
    return controller.configureRendering(width, height, androidOrientation.data()) ? JNI_TRUE : JNI_FALSE;
}


JNIEXPORT jboolean JNICALL
Java_com_vuforia_engine_native_1sample_VuforiaActivity_renderFrame(JNIEnv* env, jobject /* this */, jint videoTextureId, jboolean showLoadingSpinner, jfloatArray textureTransformArray, jboolean keepRendering)
{
    if (!controller.isARStarted())
    {
        return JNI_FALSE;
    }

    // Get texture transform from Java array
    VuMatrix44F textureTransform;
    if (textureTransformArray != nullptr)
    {
        jfloat* transformData = env->GetFloatArrayElements(textureTransformArray, nullptr);
        for (int i = 0; i < 16; ++i)
        {
            textureTransform.data[i] = transformData[i];
        }
        env->ReleaseFloatArrayElements(textureTransformArray, transformData, JNI_ABORT);
    }
    else
    {
        // Identity matrix if null
        for (int i = 0; i < 16; ++i) textureTransform.data[i] = (i % 5 == 0) ? 1.0f : 0.0f;
    }

    // Clear colour and depth buffers
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

    int vbTextureUnit = 0;
    VuRenderVideoBackgroundData renderVideoBackgroundData;
    renderVideoBackgroundData.renderData = nullptr;
    renderVideoBackgroundData.textureData = nullptr;
    renderVideoBackgroundData.textureUnitData = &vbTextureUnit;
    double viewport[6];
    if (controller.prepareToRender(viewport, &renderVideoBackgroundData))
    {
        // Set viewport for current view
        glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);

        auto renderState = controller.getRenderState();
        gWrapperData.renderer.renderVideoBackground(renderState.vbProjectionMatrix, renderState.vbMesh->pos, renderState.vbMesh->tex,
                                                    renderState.vbMesh->numFaces, renderState.vbMesh->faceIndices, vbTextureUnit);

        // Try cloud image target first (for Glimp app)
        VuMatrix44F trackableProjection;
        VuMatrix44F trackableModelView;
        VuMatrix44F trackableModelViewScaled;

        static VuMatrix44F lastProjection;
        static VuMatrix44F lastModelViewScaled;

        bool hasCloudTarget = controller.getCloudImageTargetResult(trackableProjection, trackableModelView, trackableModelViewScaled);

        if (hasCloudTarget || keepRendering)
        {
            if (hasCloudTarget)
            {
                lastProjection = trackableProjection;
                lastModelViewScaled = trackableModelViewScaled;
            }
            else
            {
                trackableProjection = lastProjection;
                trackableModelViewScaled = lastModelViewScaled;
            }

            // Render video overlay if available, otherwise render loading spinner
            if (videoTextureId > 0)
            {
                // Use scaled matrix so video fits the target size
                gWrapperData.renderer.renderVideoOverlay(trackableProjection, trackableModelViewScaled, static_cast<GLuint>(videoTextureId), textureTransform);
            }

            if (showLoadingSpinner)
            {
                gWrapperData.renderer.renderLoadingSpinner(trackableProjection, trackableModelViewScaled);
            }
        }

        controller.finishRender();
    }

    return JNI_TRUE;
}


JNIEXPORT jboolean JNICALL
Java_com_vuforia_engine_native_1sample_VuforiaActivity_getCloudImageTargetResult(JNIEnv* env, jobject /* this */, jfloatArray projectionArray,
                                                                                 jfloatArray modelViewArray, jfloatArray scaledModelViewArray)
{
    VuMatrix44F projection, modelView, scaledModelView;
    if (controller.getCloudImageTargetResult(projection, modelView, scaledModelView))
    {
        env->SetFloatArrayRegion(projectionArray, 0, 16, projection.data);
        env->SetFloatArrayRegion(modelViewArray, 0, 16, modelView.data);
        env->SetFloatArrayRegion(scaledModelViewArray, 0, 16, scaledModelView.data);
        return JNI_TRUE;
    }
    return JNI_FALSE;
}


JNIEXPORT jboolean JNICALL
Java_com_vuforia_engine_native_1sample_VuforiaActivity_startCloudImageRecognition(JNIEnv* /* env */, jobject /* this */)
{
    return controller.startCloudImageRecognition() ? JNI_TRUE : JNI_FALSE;
}


JNIEXPORT jboolean JNICALL
Java_com_vuforia_engine_native_1sample_VuforiaActivity_stopCloudImageRecognition(JNIEnv* /* env */, jobject /* this */)
{
    return controller.stopCloudImageRecognition() ? JNI_TRUE : JNI_FALSE;
}


JNIEXPORT void JNICALL
Java_com_vuforia_engine_native_1sample_VuforiaActivity_resetCloudTargetTracking(JNIEnv* /* env */, jobject /* this */)
{
    controller.resetCloudTargetTracking();
}


JNIEXPORT jboolean JNICALL
Java_com_vuforia_engine_native_1sample_VuforiaActivity_setFlash(JNIEnv* /* env */, jobject /* this */, jboolean on)
{
    return controller.setFlash(on) ? JNI_TRUE : JNI_FALSE;
}


// Local method implementations

void
callPresentError(const char* errorString)
{
    JNIThreadAttacher attacher;
    if (attacher.env() != nullptr && gWrapperData.activity != nullptr)
    {
        jstring js = attacher.env()->NewStringUTF(errorString);
        attacher.env()->CallVoidMethod(gWrapperData.activity, gWrapperData.presentErrorMethodID, js);
        attacher.env()->DeleteLocalRef(js);
    }
}


void
callOnCloudTargetDetected(const char* metadata, const char* targetId)
{
    JNIThreadAttacher attacher;
    if (attacher.env() != nullptr && gWrapperData.activity != nullptr)
    {
        jstring jMetadata = attacher.env()->NewStringUTF(metadata);
        jstring jTargetId = attacher.env()->NewStringUTF(targetId);
        attacher.env()->CallVoidMethod(gWrapperData.activity, gWrapperData.onCloudTargetDetectedMethodID, jMetadata, jTargetId);
        attacher.env()->DeleteLocalRef(jMetadata);
        attacher.env()->DeleteLocalRef(jTargetId);
    }
}

void
callVibrate()
{
    JNIThreadAttacher attacher;
    if (attacher.env() != nullptr && gWrapperData.activity != nullptr)
    {
        attacher.env()->CallVoidMethod(gWrapperData.activity, gWrapperData.vibrateMethodID);
    }
}


bool
getFusionProviderPointers()
{
    auto platformController = controller.getPlatformController();
    if (platformController == nullptr)
    {
        return false;
    }

    VuFusionProviderPlatformType fusionProviderPlatformType{ VU_FUSION_PROVIDER_PLATFORM_TYPE_UNKNOWN };
    if (vuPlatformControllerGetFusionProviderPlatformType(platformController, &fusionProviderPlatformType) != VU_SUCCESS)
    {
        return false;
    }

    if (fusionProviderPlatformType == VU_FUSION_PROVIDER_PLATFORM_TYPE_ARCORE)
    {
        VuPlatformARCoreInfo arCoreInfo;
        if (vuPlatformControllerGetARCoreInfo(platformController, &arCoreInfo) == VU_SUCCESS)
        {
            std::scoped_lock lock(gARCoreInfoMutex);
            gARCoreInfo = arCoreInfo;
            LOG("Retrieved ARCore provider pointers (ArSession: %p, ArFrame: %p)", arCoreInfo.arSession, arCoreInfo.arFrame);
            return true;
        }
    }

    return false;
}


void
accessFusionProviderPointers()
{
    std::scoped_lock lock(gARCoreInfoMutex);

    if (!gARCoreInfo.has_value())
    {
        LOG("Fusion provider pointers not available");
        return;
    }

    // Demonstrate how to use the ARCore pointers
    // Note: This is just an example. In a real application, you would use the pointers to call ARCore APIs.
    ArSession* session = static_cast<ArSession*>(gARCoreInfo->arSession);
    ArFrame* frame = static_cast<ArFrame*>(gARCoreInfo->arFrame);

    LOG("Using ARCore pointers (ArSession: %p, ArFrame: %p)", session, frame);
}

#ifdef __cplusplus
}
#endif
