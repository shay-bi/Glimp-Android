/*===============================================================================
Copyright (c) 2023 PTC Inc. and/or Its Subsidiary Companies. All Rights Reserved.

Vuforia is a trademark of PTC Inc., registered in the United States and other
countries.
===============================================================================*/

#ifndef _VUFORIA_GLESRENDERER_H_
#define _VUFORIA_GLESRENDERER_H_

#include <VuforiaEngine/VuforiaEngine.h>

#include <GLES3/gl31.h>

#include <vector>

struct AAssetManager;

/// Helper class to handle GLES rendering of the video background and augmentations
class GLESRenderer
{
public:
    GLESRenderer() = default;

    bool init(AAssetManager* assetManager);
    void deinit();

    void setAstronautTexture(int width, int height, unsigned char* bytes);
    void setLanderTexture(int width, int height, unsigned char* bytes);

    void renderVideoBackground(const VuMatrix44F& projectionMatrix, const float* vertices, const float* textureCoordinates,
                                const int numTriangles, const unsigned int* indices, int textureUnit);

    void renderWorldOrigin(VuMatrix44F& projectionMatrix, VuMatrix44F& modelViewMatrix);

    void renderImageTarget(VuMatrix44F& projectionMatrix, VuMatrix44F& modelViewMatrix, VuMatrix44F& scaledModelViewMatrix);

    void renderModelTarget(VuMatrix44F& projectionMatrix, VuMatrix44F& modelViewMatrix, VuMatrix44F& scaledModelViewMatrix);

    void renderModelTargetGuideView(VuMatrix44F& projectionMatrix, VuMatrix44F& modelViewMatrix, const VuImageInfo& image,
                                    VuBool guideViewImageHasChanged);

    void renderVideoOverlay(const VuMatrix44F& projectionMatrix, const VuMatrix44F& modelViewMatrix, GLuint videoTextureId, const VuMatrix44F& textureTransform);

    void renderLoadingSpinner(const VuMatrix44F& projectionMatrix, const VuMatrix44F& modelViewMatrix);

private:
    void createTexture(int width, int height, unsigned char* bytes, GLuint& textureId);

    void renderCube(const VuMatrix44F& projectionMatrix, const VuMatrix44F& modelViewMatrix, float scale, const VuVector4F& color);

    void renderAxis(const VuMatrix44F& projectionMatrix, const VuMatrix44F& modelViewMatrix, const VuVector3F& scale, float lineWidth);

    void renderModel(VuMatrix44F modelViewProjectionMatrix, const int numVertices, const float* vertices,
                     const float* textureCoordinates, GLuint textureId);

    // handles for Video Background rendering
    GLuint mVbShaderProgramID = 0;
    GLint mVbVertexPositionHandle = -1;
    GLint mVbTextureCoordHandle = -1;
    GLint mVbMvpMatrixHandle = -1;
    GLint mVbTexSampler2DHandle = -1;

    // handles for augmentation rendering
    GLuint mUniformColorShaderProgramID = 0;
    GLint mUniformColorVertexPositionHandle = -1;
    GLint mUniformColorMvpMatrixHandle = -1;
    GLint mUniformColorColorHandle = -1;

    // handles for guide view rendering
    GLuint mTextureUniformColorShaderProgramID = 0;
    GLint mTextureUniformColorVertexPositionHandle = -1;
    GLint mTextureUniformColorTextureCoordHandle = -1;
    GLint mTextureUniformColorMvpMatrixHandle = -1;
    GLint mTextureUniformColorTexSampler2DHandle = -1;
    GLint mTextureUniformColorColorHandle = -1;

    // handles for axis rendering
    GLuint mVertexColorShaderProgramID = 0;
    GLint mVertexColorVertexPositionHandle = -1;
    GLint mVertexColorColorHandle = -1;
    GLint mVertexColorMvpMatrixHandle = -1;

    // handles for video overlay rendering
    GLuint mVideoOverlayShaderProgramID = 0;
    GLint mVideoOverlayVertexPositionHandle = -1;
    GLint mVideoOverlayTextureCoordHandle = -1;
    GLint mVideoOverlayMvpMatrixHandle = -1;
    GLint mVideoOverlayTexSamplerExternalOESHandle = -1;
    GLint mVideoOverlayTexMatrixHandle = -1;

    // handles for loading spinner rendering
    GLuint mLoadingSpinnerShaderProgramID = 0;
    GLint mLoadingSpinnerVertexPositionHandle = -1;
    GLint mLoadingSpinnerTextureCoordHandle = -1;
    GLint mLoadingSpinnerMvpMatrixHandle = -1;
    GLint mLoadingSpinnerTimeHandle = -1;

    // models data
    int mAstronautVertexCount = 0;
    std::vector<float> mAstronautVertices;
    std::vector<float> mAstronautTexCoords;
    GLuint mAstronautTextureUnit = 0;

    int mLanderVertexCount = 0;
    std::vector<float> mLanderVertices;
    std::vector<float> mLanderTexCoords;
    GLuint mLanderTextureUnit = 0;

    GLuint mModelTargetGuideViewTextureUnit = 0;

    bool readAsset(AAssetManager* assetManager, const char* filename, std::vector<char>& data);
    bool loadObjModel(const std::vector<char>& data, int& numVertices, std::vector<float>& vertices, std::vector<float>& texCoords);
};

#endif // _VUFORIA_GLESRENDERER_H_
