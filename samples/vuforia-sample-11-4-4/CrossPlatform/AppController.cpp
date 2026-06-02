
#include "AppController.h"

#include "Log.h"

#include <algorithm>
#include <cassert>
#include <cmath>
#include <functional>
#include <string>


#ifdef VU_PLATFORM_ANDROID
/// JVM pointer captured in the VuforiaWrapper for Android
extern void* javaVM;
#endif // VU_PLATFORM_ANDROID


namespace
{
// clang-format off
constexpr char licenseKey[] = "AbM/kkL/////AAABmeULFWqqFk9chbnT52P9xuljzXX+3C8uhFfH0uVRKtRritYTsU+isaoJFqoWL25N7YutAglgq5HzzoE2A5PGE21KFeqg4OHCRM1b0m0vb8WkZeYluBcUvaStlLjeR/0DwQxdP964f0q87HIKD2zdzXczAOma1+/O4dfw6t09MtqSFGr21TJdCQAdLAhOw2bfgwwa4INbhBLp+HqkVwOEcK8JEFhTspmESxPPH2qcfY69LL/8kfn66Ad/WAoLbszjfO0+VVLzX5syim1FWy4jy1IO6NeggpF0PccqoQ7a3WHjVWMbOXJflDIDaak1SdtjPL4DYLRvy5YBjgDg4SnVdsUPzekOKu/u/MxSNStilkT6";
// clang-format on

// Cloud recognition credentials
constexpr char cloudAccessKey[] = "ff6d8de175a04ee48eb318b4383c44a8d3b2974f";
constexpr char cloudSecretKey[] = "492ab97761fc4d133d937ef44bb0d3451c6ee402";

constexpr float NEAR_PLANE = 0.01f;
constexpr float FAR_PLANE = 5.f;
}


/// Helper macro to check results of Vuforia Engine calls that are expected to succeed
#define REQUIRE_SUCCESS(command)                     \
    {                                                \
        auto vu_result_appsupport_ = command;        \
        (void)vu_result_appsupport_;                 \
        assert(vu_result_appsupport_ == VU_SUCCESS); \
    }


/*===============================================================================
 AppController public methods
 ===============================================================================*/

void
AppController::initAR(const InitConfig& initConfig, int target)
{
    mVbRenderBackend = initConfig.vbRenderBackend;
    mErrorMessageCallback = initConfig.errorMessageCallback;
    mVuforeEngineErrorCallback = initConfig.vuforiaEngineErrorCallback;
    mInitDoneCallback = initConfig.initDoneCallback;
    mCloudTargetDetectedCallback = initConfig.cloudTargetDetectedCallback;
    mTarget = target;

    mGuideViewModelTarget = nullptr;
    mCloudRecognitionActive = false;

    if (!initVuforiaInternal(initConfig.appData))
    {
        return;
    }

    if (!createObservers())
    {
        return;
    }

    mInitDoneCallback();
}


bool
AppController::startAR()
{
    LOG("AppController::startAR");

    // Bail out early if engine instance has not been created yet
    if (mEngine == nullptr)
    {
        LOG("Failed to start Vuforia as no valid engine instance is available");
        return false;
    }

    // Bail out early if engine has already been started
    if (vuEngineIsRunning(mEngine))
    {
        LOG("Failed to start Vuforia as it is already running");
        return false;
    }

    // Get the camera controller to access camera settings
    VuController* cameraController = nullptr;
    REQUIRE_SUCCESS(vuEngineGetCameraController(mEngine, &cameraController));

    // Select the camera mode to the preferred value before starting engine
    if (vuCameraControllerSetActiveVideoMode(cameraController, mCameraVideoMode) != VU_SUCCESS)
    {
        LOG("Failed to set active video mode %d for camera device", static_cast<int>(mCameraVideoMode));
    }

    // Start engine
    if (vuEngineStart(mEngine) != VU_SUCCESS)
    {
        LOG("Failed to start Vuforia");
        return false;
    }

    mARStarted = true;

    // Select the camera focus mode to continuous autofocus
    if (vuCameraControllerSetFocusMode(cameraController, VU_CAMERA_FOCUS_MODE_CONTINUOUSAUTO) != VU_SUCCESS)
    {
        LOG("Failed to select focus mode %d for camera device", static_cast<int>(VU_CAMERA_FOCUS_MODE_CONTINUOUSAUTO));
    }

    LOG("Successfully started Vuforia");
    return true;
}


bool
AppController::stopAR()
{
    LOG("AppController::stopAR");

    mARStarted = false;

    // Bail out early if engine instance has not been created yet
    if (mEngine == nullptr)
    {
        LOG("Failed to stop Vuforia as no valid engine instance is available");
        return false;
    }

    // Bail out early if engine has not been started yet
    if (!vuEngineIsRunning(mEngine))
    {
        LOG("Failed to stop Vuforia as it is currently not running");
        return false;
    }

    // Stop engine
    if (vuEngineStop(mEngine) != VU_SUCCESS)
    {
        LOG("Failed to stop Vuforia");
        return false;
    }

    LOG("Successfully stopped Vuforia");
    return true;
}


void
AppController::deinitAR()
{
    // Bail out early if engine instance has not been created yet
    if (mEngine == nullptr)
    {
        LOG("Failed to deinitialize Vuforia as no engine instance is available");
        return;
    }

    stopAR();

    destroyObservers();

    // Destroy engine instance
    if (vuEngineDestroy(mEngine) != VU_SUCCESS)
    {
        LOG("Failed to destroy engine instance");
        return;
    }

    // Invalidate engine instance
    mEngine = nullptr;

    // Invalidate render and platform controllers
    mRenderController = nullptr;
    mPlatformController = nullptr;
}


void
AppController::cameraPerformAutoFocus()
{
    if (!mARStarted)
    {
        return;
    }

    VuController* cameraController = nullptr;
    if (vuEngineGetCameraController(mEngine, &cameraController) != VU_SUCCESS)
    {
        LOG("Error attempting to perform autofocus, failed to get camera controller");
        return;
    }

    if (vuCameraControllerSetFocusMode(cameraController, VU_CAMERA_FOCUS_MODE_TRIGGERAUTO) != VU_SUCCESS)
    {
        LOG("Error attempting to perform autofocus, failed to set focus mode");
    }
}


void
AppController::cameraRestoreAutoFocus()
{
    if (!mARStarted)
    {
        return;
    }

    VuController* cameraController = nullptr;
    if (vuEngineGetCameraController(mEngine, &cameraController) != VU_SUCCESS)
    {
        LOG("Error attempting to perform autofocus, failed to get camera controller");
        return;
    }

    if (vuCameraControllerSetFocusMode(cameraController, VU_CAMERA_FOCUS_MODE_CONTINUOUSAUTO) != VU_SUCCESS)
    {
        LOG("Error attempting to perform autofocus, failed to set focus mode");
    }
}


bool
AppController::configureRendering(int width, int height, void* orientation)
{
    if (!mARStarted)
    {
        return false;
    }

    if (width <= 0 || height <= 0)
    {
        LOG("Invalid screen dimensions");
        return false;
    }

    VuViewOrientation vuOrientation;
    if (vuPlatformControllerConvertPlatformViewOrientation(mPlatformController, orientation, &vuOrientation) != VU_SUCCESS)
    {
        LOG("Failed to convert the platform-specific orientation descriptor to Vuforia view orientation");
        return false;
    }

    if (vuPlatformControllerSetViewOrientation(mPlatformController, vuOrientation) != VU_SUCCESS)
    {
        LOG("Failed to set orientation");
        return false;
    }

    mDisplayAspectRatio = (float)width / height;

    // Set the latest render view configuration in Vuforia
    VuRenderViewConfig rvConfig;
    rvConfig.resolution.data[0] = width;
    rvConfig.resolution.data[1] = height;

    if (vuRenderControllerSetRenderViewConfig(mRenderController, &rvConfig) != VU_SUCCESS)
    {
        LOG("Failed to set render view configuration");
    }

    return true;
}


bool
AppController::getVideoBackgroundTextureSize(VuVector2I& textureSize)
{
    VuVideoBackgroundViewInfo vbViewInfo;
    if (vuRenderControllerGetVideoBackgroundViewInfo(mRenderController, &vbViewInfo) != VU_SUCCESS)
    {
        LOG("Error getting video background view info");
        return false;
    }

    textureSize = vbViewInfo.vBTextureSize;
    return true;
}


bool
AppController::prepareToRender(double* viewport, VuRenderVideoBackgroundData* renderData)
{
    if (!mARStarted || mEngine == nullptr)
    {
        return false;
    }

    if (vuEngineAcquireLatestState(mEngine, &mVuforiaState) != VU_SUCCESS)
    {
        LOG("Error getting state");
        return false;
    }

    handlePendingCloudDetection();

    if (vuStateHasCameraFrame(mVuforiaState) != VU_TRUE)
    {
        return false;
    }

    if (vuStateGetRenderState(mVuforiaState, &mCurrentRenderState) != VU_SUCCESS)
    {
        LOG("Error getting render state");
        return false;
    }

    if (!mCurrentRenderState.vbMesh)
    {
        return false;
    }

    viewport[0] = mCurrentRenderState.viewport.data[0];
    viewport[1] = mCurrentRenderState.viewport.data[1];
    viewport[2] = mCurrentRenderState.viewport.data[2];
    viewport[3] = mCurrentRenderState.viewport.data[3];
    viewport[4] = 0.0f;
    viewport[5] = 1.0f;

    if (vuRenderControllerUpdateVideoBackgroundTexture(mRenderController, mVuforiaState, renderData) != VU_SUCCESS)
    {
        LOG("Error updating video background texture");
        return false;
    }

    updateDevicePose();

    return true;
}


void
AppController::finishRender()
{
    // Check for device tracker relocalizing for too long and reset if needed
    if (mLatestDevicePoseData.poseStatus == VU_OBSERVATION_POSE_STATUS_LIMITED &&
        mLatestDevicePoseData.poseStatusInfo == VU_DEVICE_POSE_OBSERVATION_STATUS_INFO_RELOCALIZING)
    {
        using namespace std::chrono;

        // Start timing if we have just entered relocalizing state
        if (!mTimingRelocalizingState)
        {
            mEnteredRelocalizingState = steady_clock::now();
        }
        mTimingRelocalizingState = true;

        // Check whether we have been relocalizing for longer than the threshold
        auto relocalizingFor = duration_cast<seconds>(steady_clock::now() - mEnteredRelocalizingState);
        if (relocalizingFor.count() > MAX_RELOCALIZING_SECONDS)
        {
            mTimingRelocalizingState = false;
            VuResult resetResult = vuEngineResetWorldTracking(mEngine);
            LOG("%s reset world tracking", resetResult == VU_SUCCESS ? "Successfully" : "Failed to");
        }
    }
    else
    {
        mTimingRelocalizingState = false;
    }

    // Clean up and release the Vuforia state
    if (mVuforiaState != nullptr && vuStateRelease(mVuforiaState) != VU_SUCCESS)
    {
        LOG("Error releasing the Vuforia state");
    }
    mVuforiaState = nullptr;
}


bool
AppController::getOrigin(VuMatrix44F& projectionMatrix, VuMatrix44F& modelViewMatrix)
{
    if (mLatestDevicePoseData.poseStatus != VU_OBSERVATION_POSE_STATUS_NO_POSE)
    {
        projectionMatrix = mCurrentRenderState.projectionMatrix;
        modelViewMatrix = mCurrentRenderState.viewMatrix;
        return true;
    }

    return false;
}


bool
AppController::getImageTargetResult(VuMatrix44F&, VuMatrix44F&, VuMatrix44F&)
{
    return false;
}


bool
AppController::getCloudImageTargetResult(VuMatrix44F& projectionMatrix, VuMatrix44F& modelViewMatrix, VuMatrix44F& scaledModelViewMatrix)
{
    if (mVuforiaState == nullptr) return false;

    VuObservationList* observationList = nullptr;
    REQUIRE_SUCCESS(vuObservationListCreate(&observationList));

    bool found = false;
    if (vuStateGetImageTargetObservations(mVuforiaState, observationList) == VU_SUCCESS)
    {
        int numObservations = 0;
        REQUIRE_SUCCESS(vuObservationListGetSize(observationList, &numObservations));

        for (int i = 0; i < numObservations; ++i)
        {
            VuObservation* observation = nullptr;
            if (vuObservationListGetElement(observationList, i, &observation) == VU_SUCCESS)
            {
                VuPoseInfo poseInfo;
                if (vuObservationGetPoseInfo(observation, &poseInfo) == VU_SUCCESS &&
                    (poseInfo.poseStatus == VU_OBSERVATION_POSE_STATUS_TRACKED ||
                     poseInfo.poseStatus == VU_OBSERVATION_POSE_STATUS_EXTENDED_TRACKED))
                {
                    // More lenient presence check for loss-debounce logic.
                    // If we have a valid pose (tracked or extended), we consider the target "found".
                    {
                        VuImageTargetObservationTargetInfo targetInfo;
                        REQUIRE_SUCCESS(vuImageTargetObservationGetTargetInfo(observation, &targetInfo));

                        projectionMatrix = mCurrentRenderState.projectionMatrix;
                        modelViewMatrix = vuMatrix44FMultiplyMatrix(mCurrentRenderState.viewMatrix, poseInfo.pose);

                        VuVector3F scale;
                        scale.data[0] = targetInfo.size.data[0];
                        scale.data[1] = targetInfo.size.data[1];
                        scale.data[2] = 1.0f;
                        scaledModelViewMatrix = vuMatrix44FScale(scale, modelViewMatrix);

                        found = true;
                        break;
                    }
                }
            }
        }
    }

    REQUIRE_SUCCESS(vuObservationListDestroy(observationList));
    return found;
}


bool
AppController::startCloudImageRecognition()
{
    // Don't destroy the local observer if it's already active and tracking
    // Just reactivate cloud reco if needed
    if (vuObserverActivate(mCloudImageTargetObserver) == VU_SUCCESS)
    {
        mCloudRecognitionActive = true;
        return true;
    }
    return false;
}


bool
AppController::stopCloudImageRecognition()
{
    if (mCloudImageTargetObserver) vuObserverDeactivate(mCloudImageTargetObserver);
    mCloudRecognitionActive = false;
    return true;
}


void
AppController::resetCloudTargetTracking()
{
    if (!mARStarted || mEngine == nullptr) return;

    if (mLocalImageTargetObserver)
    {
        vuObserverDestroy(mLocalImageTargetObserver);
        mLocalImageTargetObserver = nullptr;
    }

    {
        std::scoped_lock lock(mPendingDetectionMutex);
        mPendingTargetId.clear();
        mPendingMetadata.clear();
        mActiveTargetId.clear();
    }

    // Force a fresh scan by deactivating and reactivating
    vuObserverDeactivate(mCloudImageTargetObserver);
    startCloudImageRecognition();
}


bool
AppController::setFlash(bool on)
{
    if (mEngine == nullptr) return false;

    VuController* cameraController = nullptr;
    if (vuEngineGetCameraController(mEngine, &cameraController) != VU_SUCCESS)
    {
        return false;
    }

    VuBool isSupported = VU_FALSE;
    if (vuCameraControllerIsFlashModeSupported(cameraController, &isSupported) != VU_SUCCESS || isSupported == VU_FALSE)
    {
        return false;
    }

    return vuCameraControllerSetFlashMode(cameraController, on ? VU_TRUE : VU_FALSE) == VU_SUCCESS;
}


bool
AppController::getModelTargetResult(VuMatrix44F&, VuMatrix44F&, VuMatrix44F&)
{
    return false;
}


bool
AppController::getModelTargetGuideView(VuMatrix44F&, VuMatrix44F&, VuImageInfo&, VuBool&)
{
    return false;
}


/*===============================================================================
 AppController private methods
 ===============================================================================*/

bool
AppController::initVuforiaInternal(void* appData)
{
    if (mEngine != nullptr) return false;

    VuEngineConfigSet* configSet = nullptr;
    REQUIRE_SUCCESS(vuEngineConfigSetCreate(&configSet));

    auto licenseConfig = vuLicenseConfigDefault();
    licenseConfig.key = licenseKey;
    REQUIRE_SUCCESS(vuEngineConfigSetAddLicenseConfig(configSet, &licenseConfig));

    auto renderConfig = vuRenderConfigDefault();
    renderConfig.vbRenderBackend = mVbRenderBackend;

#if defined(VU_PLATFORM_ANDROID)
    auto vuPlatformConfig_Android = vuPlatformAndroidConfigDefault();
    vuPlatformConfig_Android.activity = appData;
    vuPlatformConfig_Android.javaVM = javaVM;
    REQUIRE_SUCCESS(vuEngineConfigSetAddPlatformAndroidConfig(configSet, &vuPlatformConfig_Android));
#endif

    REQUIRE_SUCCESS(vuEngineConfigSetAddRenderConfig(configSet, &renderConfig));

    VuErrorHandlerConfig errorHandlerConfig = vuErrorHandlerConfigDefault();
    errorHandlerConfig.errorHandler = &onEngineError;
    errorHandlerConfig.clientData = this;
    REQUIRE_SUCCESS(vuEngineConfigSetAddErrorHandlerConfig(configSet, &errorHandlerConfig));

    VuErrorCode errorCode;
    if (vuEngineCreate(&mEngine, configSet, &errorCode) != VU_SUCCESS)
    {
        mErrorMessageCallback(initErrorToString(errorCode).c_str());
        vuEngineConfigSetDestroy(configSet);
        return false;
    }
    REQUIRE_SUCCESS(vuEngineConfigSetDestroy(configSet));

    REQUIRE_SUCCESS(vuEngineGetRenderController(mEngine, &mRenderController));
    REQUIRE_SUCCESS(vuEngineGetPlatformController(mEngine, &mPlatformController));
    REQUIRE_SUCCESS(vuRenderControllerSetProjectionMatrixNearFar(mRenderController, NEAR_PLANE, FAR_PLANE));

    return true;
}


void
AppController::onEngineError(VuEngineError errorCode, void* clientData)
{
    AppController* appController = static_cast<AppController*>(clientData);
    if (appController) appController->handleEngineError(errorCode);
}


void
AppController::handleEngineError(VuEngineError errorCode)
{
    if (mVuforeEngineErrorCallback) mVuforeEngineErrorCallback(errorCode);
}


std::string
AppController::initErrorToString(VuErrorCode error)
{
    switch (error)
    {
        case VU_ENGINE_CREATION_ERROR_DEVICE_NOT_SUPPORTED: return "Device not supported";
        case VU_ENGINE_CREATION_ERROR_PERMISSION_ERROR: return "Camera permission denied";
        case VU_ENGINE_CREATION_ERROR_LICENSE_ERROR: return "License error";
        default: return "Vuforia initialization failed";
    }
}


bool
AppController::createObservers()
{
    VuDevicePoseConfig devicePoseConfig = vuDevicePoseConfigDefault();
    vuEngineCreateDevicePoseObserver(mEngine, &mDevicePoseObserver, &devicePoseConfig, nullptr);
    return createCloudImageTargetObserver();
}


bool
AppController::createCloudImageTargetObserver()
{
    auto cloudConfig = vuCloudImageTargetConfigDefault();
    cloudConfig.userAuth = cloudAccessKey;
    cloudConfig.secretAuth = cloudSecretKey;

    if (vuEngineCreateCloudImageTargetObserver(mEngine, &mCloudImageTargetObserver, &cloudConfig, nullptr) != VU_SUCCESS) return false;
    vuCloudImageTargetObserverRegisterHandlers(mCloudImageTargetObserver, onCloudImageTargetObservations, nullptr, this);
    return true;
}


void
AppController::onCloudImageTargetObservations(const VuObservationList* observationList, void* clientData)
{
    AppController* controller = static_cast<AppController*>(clientData);
    if (!controller || !controller->mARStarted) return;

    int numObservations = 0;
    if (vuObservationListGetSize(observationList, &numObservations) != VU_SUCCESS || numObservations == 0) return;

    VuObservation* observation = nullptr;
    vuObservationListGetElement(observationList, 0, &observation);

    // If we have a cloud target detection, create a local ImageTargetObserver to track it
    VuCloudImageTargetObservationTargetInfo cloudTargetInfo;
    if (vuCloudImageTargetObservationGetTargetInfo(observation, &cloudTargetInfo) == VU_SUCCESS)
    {
        std::scoped_lock lock(controller->mPendingDetectionMutex);

        std::string targetId = cloudTargetInfo.uniqueId ? cloudTargetInfo.uniqueId : "";

        // CRITICAL FIX: Only consider it a new target if it's different from what we are
        // currently tracking OR what is already pending. This prevents constant destruction/recreation
        // of the local observer which would break tracking and re-scanning.
        bool isNewTarget = (targetId != controller->mActiveTargetId) && (targetId != controller->mPendingTargetId);

        if (isNewTarget)
        {
            if (controller->mLocalImageTargetObserver != nullptr)
            {
                vuObserverDestroy(controller->mLocalImageTargetObserver);
                controller->mLocalImageTargetObserver = nullptr;
            }

            VuImageTargetCloudObservationConfig cloudObsConfig = vuImageTargetCloudObservationConfigDefault();
            cloudObsConfig.observation = observation;
            cloudObsConfig.activate = VU_TRUE;
            vuEngineCreateImageTargetObserverFromCloudObservation(controller->mEngine, &controller->mLocalImageTargetObserver, &cloudObsConfig, nullptr);

            controller->mPendingMetadata = cloudTargetInfo.metadata ? cloudTargetInfo.metadata : "";
            controller->mPendingTargetId = targetId;
            controller->mActiveTargetId = targetId;
        }
    }
}


void
AppController::handlePendingCloudDetection()
{
    std::string metadata;
    std::string targetId;

    {
        std::scoped_lock lock(mPendingDetectionMutex);
        if (mPendingTargetId.empty()) return;
        metadata = mPendingMetadata;
        targetId = mPendingTargetId;

        // Clear the pending ID immediately after grabbing it
        // so that the callback only fires once per detection.
        mPendingTargetId.clear();
    }

    if (mCloudTargetDetectedCallback)
    {
        mCloudTargetDetectedCallback(metadata.c_str(), targetId.c_str());
    }
}


void
AppController::destroyObservers()
{
    if (mLocalImageTargetObserver) vuObserverDestroy(mLocalImageTargetObserver);
    if (mCloudImageTargetObserver) vuObserverDeactivate(mCloudImageTargetObserver);
    if (mCloudImageTargetObserver) vuObserverDestroy(mCloudImageTargetObserver);
    if (mDevicePoseObserver) vuObserverDestroy(mDevicePoseObserver);
    mLocalImageTargetObserver = nullptr;
    mCloudImageTargetObserver = nullptr;
    mDevicePoseObserver = nullptr;
    mActiveTargetId.clear();
}


void
AppController::updateDevicePose()
{
    if (mVuforiaState == nullptr) return;
    VuObservationList* list = nullptr;
    vuObservationListCreate(&list);
    if (vuStateGetDevicePoseObservations(mVuforiaState, list) == VU_SUCCESS)
    {
        int size = 0;
        vuObservationListGetSize(list, &size);
        if (size > 0)
        {
            VuObservation* obs = nullptr;
            vuObservationListGetElement(list, 0, &obs);
            VuPoseInfo pose;
            vuObservationGetPoseInfo(obs, &pose);
            mLatestDevicePoseData.pose = pose.pose;
            mLatestDevicePoseData.poseStatus = pose.poseStatus;
        }
    }
    vuObservationListDestroy(list);
}
