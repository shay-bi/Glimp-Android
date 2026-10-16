/*===============================================================================
Copyright (c) 2023 PTC Inc. and/or Its Subsidiary Companies. All Rights Reserved.

Vuforia is a trademark of PTC Inc., registered in the United States and other
countries.
===============================================================================*/

#ifndef __APPCONTROLLER_H__
#define __APPCONTROLLER_H__

#include <VuforiaEngine/VuforiaEngine.h>

#include <chrono>
#include <cstdio>
#include <functional>
#include <memory>
#include <string>
#include <mutex>


/// The AppController provides a platform-independent encapsulation of the Vuforia lifecycle
/// and Observer operation.
class AppController
{

public:
    // Constants
    static constexpr int IMAGE_TARGET_ID = 0;
    static constexpr int MODEL_TARGET_ID = 1;

    // Type definitions
    using ErrorMessageCallback = std::function<void(const char* errorString)>;
    using VuforiaEngineErrorCallback = std::function<void(VuErrorCode errorCode)>;
    using InitDoneCallback = std::function<void()>;
    using CloudTargetDetectedCallback = std::function<void(const char* metadata, const char* targetId)>;

    /// Struct to group initialization parameters passed to initAR
    class InitConfig
    {
    public:
        VuRenderVBBackendType vbRenderBackend{ VU_RENDER_VB_BACKEND_DEFAULT };
        void* appData{ nullptr };
        ErrorMessageCallback errorMessageCallback{};
        VuforiaEngineErrorCallback vuforiaEngineErrorCallback{};
        InitDoneCallback initDoneCallback{};
        CloudTargetDetectedCallback cloudTargetDetectedCallback{};
    };


    /// Initialize Vuforia.
    void initAR(const InitConfig& initConfig, int target);

    /// Start the AR session
    bool startAR();

    /// Stop the AR session
    bool stopAR();

    /// Clean up and deinitialize Vuforia.
    void deinitAR();

    /// Request that the camera refocuses in the current position
    void cameraPerformAutoFocus();

    /// Restore the camera to continuous autofocus mode
    void cameraRestoreAutoFocus();

    /// Configure Vuforia rendering.
    bool configureRendering(int width, int height, void* orientation);

    /// Get the dimensions you should use to create the video background texture
    bool getVideoBackgroundTextureSize(VuVector2I& textureSize);

    /// Query whether the camera is currently started
    bool isARStarted() { return mARStarted; }

    /// Call this method at the start of Vuforia rendering.
    bool prepareToRender(double* viewport, VuRenderVideoBackgroundData* renderData);

    /// Call this method when Vuforia rendering is complete.
    void finishRender();

    /// Get the current RenderState
    const VuRenderState& getRenderState() { return mCurrentRenderState; }

    /// Get rendering information for the world origin position.
    bool getOrigin(VuMatrix44F& projectionMatrix, VuMatrix44F& modelViewMatrix);

    /// Get rendering information for the Image Target.
    bool getImageTargetResult(VuMatrix44F& projectionMatrix, VuMatrix44F& modelViewMatrix, VuMatrix44F& scaledModelViewMatrix);

    /// Get rendering information for the Cloud Image Target.
    bool getCloudImageTargetResult(VuMatrix44F& projectionMatrix, VuMatrix44F& modelViewMatrix, VuMatrix44F& scaledModelViewMatrix, VuObservationPoseStatus* poseStatus = nullptr);

    /// Start cloud image recognition
    bool startCloudImageRecognition();

    /// Stop cloud image recognition
    bool stopCloudImageRecognition();

    /// Reset cloud target tracking and restart recognition
    void resetCloudTargetTracking();

    /// Set flash mode
    bool setFlash(bool on);

    /// Get rendering information for the Model Target.
    bool getModelTargetResult(VuMatrix44F& projectionMatrix, VuMatrix44F& modelViewMatrix, VuMatrix44F& scaledModelViewMatrix);

    /// Get rendering information for the Model Target Guide View.
    bool getModelTargetGuideView(VuMatrix44F& projectionMatrix, VuMatrix44F& modelViewMatrix, VuImageInfo& guideViewImageInfo,
                                 VuBool& guideViewImageHasChanged);

    /// Get the PlatformController handle.
    VuController* getPlatformController() { return mPlatformController; }


private: // methods
    bool initVuforiaInternal(void* appData);
    static std::string initErrorToString(VuErrorCode error);
    static void onEngineError(VuEngineError errorCode, void* clientData);
    void handleEngineError(VuEngineError errorCode);
    bool createObservers();
    void destroyObservers();
    bool createCloudImageTargetObserver();
    static void onCloudImageTargetObservations(const VuObservationList* observationList, void* clientData);
    void updateDevicePose();

    // Internal helper to handle the handover from Reco to Tracking on the correct thread
    void handlePendingCloudDetection();

private: // data members
    ErrorMessageCallback mErrorMessageCallback;
    VuforiaEngineErrorCallback mVuforeEngineErrorCallback;
    InitDoneCallback mInitDoneCallback;

    VuEngine* mEngine{ nullptr };
    VuController* mRenderController{ nullptr };
    VuController* mPlatformController{ nullptr };

    VuRenderVBBackendType mVbRenderBackend = VuRenderVBBackendType::VU_RENDER_VB_BACKEND_DEFAULT;
    int mTarget = IMAGE_TARGET_ID;
    VuCameraVideoModePreset mCameraVideoMode = VuCameraVideoModePreset::VU_CAMERA_VIDEO_MODE_PRESET_DEFAULT;

    bool mARStarted = false;
    VuRenderState mCurrentRenderState;
    float mDisplayAspectRatio;

    VuObserver* mDevicePoseObserver = nullptr;

    struct DevicePoseData
    {
        VuMatrix44F pose{};
        VuObservationPoseStatus poseStatus{ VU_OBSERVATION_POSE_STATUS_NO_POSE };
        VuDevicePoseObservationStatusInfo poseStatusInfo{ VU_DEVICE_POSE_OBSERVATION_STATUS_INFO_UNKNOWN };
    };
    DevicePoseData mLatestDevicePoseData{};

    bool mTimingRelocalizingState{ false };
    std::chrono::steady_clock::time_point mEnteredRelocalizingState;
    static constexpr int MAX_RELOCALIZING_SECONDS{ 15 };

    VuObserver* mObjectObserver = nullptr;
    VuObserver* mCloudImageTargetObserver = nullptr;
    VuObserver* mLocalImageTargetObserver = nullptr;

    CloudTargetDetectedCallback mCloudTargetDetectedCallback;
    bool mCloudRecognitionActive = false;

    VuState* mVuforiaState = nullptr;
    VuGuideView* mGuideViewModelTarget = nullptr;

    // Last pose from a query that ran while mVuforiaState was valid (inside
    // renderFrame / prepareToRender). Kotlin queries after finishRender has
    // already released the state, so it must read this cache.
    bool mLastCloudTargetFound{ false };
    VuObservationPoseStatus mLastCloudPoseStatus{ VU_OBSERVATION_POSE_STATUS_NO_POSE };
    VuMatrix44F mLastCloudProjection{};
    VuMatrix44F mLastCloudModelView{};
    VuMatrix44F mLastCloudScaledModelView{};

    // Handover management
    std::mutex mPendingDetectionMutex;
    std::string mActiveTargetId; // The ID of the target we currently have an observer for
    std::string mPendingMetadata;
    std::string mPendingTargetId;
};

#endif /* __APPCONTROLLER_H__ */
