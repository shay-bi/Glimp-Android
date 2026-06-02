/*===============================================================================
Copyright (c) 2023 PTC Inc. and/or Its Subsidiary Companies. All Rights Reserved.

Vuforia is a trademark of PTC Inc., registered in the United States and other
countries.
===============================================================================*/

#include "GLESRenderer.h"

#include "GLESUtils.h"
#include "Shaders.h"

#include <MemoryStream.h>
#include <Models.h>

#include <android/asset_manager.h>
#include <cmath>

bool
GLESRenderer::init(AAssetManager* /*assetManager*/)
{
    // Setup for Video Background rendering
    mVbShaderProgramID = GLESUtils::createProgramFromBuffer(textureVertexShaderSrc, textureFragmentShaderSrc);
    mVbVertexPositionHandle = glGetAttribLocation(mVbShaderProgramID, "vertexPosition");
    mVbTextureCoordHandle = glGetAttribLocation(mVbShaderProgramID, "vertexTextureCoord");
    mVbMvpMatrixHandle = glGetUniformLocation(mVbShaderProgramID, "modelViewProjectionMatrix");
    mVbTexSampler2DHandle = glGetUniformLocation(mVbShaderProgramID, "texSampler2D");

    // Setup for augmentation rendering (uniform color)
    mUniformColorShaderProgramID = GLESUtils::createProgramFromBuffer(uniformColorVertexShaderSrc, uniformColorFragmentShaderSrc);
    mUniformColorVertexPositionHandle = glGetAttribLocation(mUniformColorShaderProgramID, "vertexPosition");
    mUniformColorMvpMatrixHandle = glGetUniformLocation(mUniformColorShaderProgramID, "modelViewProjectionMatrix");
    mUniformColorColorHandle = glGetUniformLocation(mUniformColorShaderProgramID, "uniformColor");

    // Setup for video overlay rendering
    mVideoOverlayShaderProgramID = GLESUtils::createProgramFromBuffer(videoOverlayVertexShaderSrc, videoOverlayFragmentShaderSrc);
    if (mVideoOverlayShaderProgramID == 0) {
        LOG("Failed to create video overlay shader program");
        return false;
    }
    mVideoOverlayVertexPositionHandle = glGetAttribLocation(mVideoOverlayShaderProgramID, "vertexPosition");
    mVideoOverlayTextureCoordHandle = glGetAttribLocation(mVideoOverlayShaderProgramID, "vertexTextureCoord");
    mVideoOverlayMvpMatrixHandle = glGetUniformLocation(mVideoOverlayShaderProgramID, "modelViewProjectionMatrix");
    mVideoOverlayTexSamplerExternalOESHandle = glGetUniformLocation(mVideoOverlayShaderProgramID, "texSamplerExternalOES");
    mVideoOverlayTexMatrixHandle = glGetUniformLocation(mVideoOverlayShaderProgramID, "texMatrix");

    // Setup for loading spinner (procedural arc, iOS-style)
    mLoadingSpinnerShaderProgramID = GLESUtils::createProgramFromBuffer(loadingSpinnerVertexShaderSrc, loadingSpinnerFragmentShaderSrc);
    if (mLoadingSpinnerShaderProgramID == 0) {
        LOG("Failed to create loading spinner shader program");
    } else {
        mLoadingSpinnerVertexPositionHandle = glGetAttribLocation(mLoadingSpinnerShaderProgramID, "vertexPosition");
        mLoadingSpinnerTextureCoordHandle = glGetAttribLocation(mLoadingSpinnerShaderProgramID, "vertexTextureCoord");
        mLoadingSpinnerMvpMatrixHandle = glGetUniformLocation(mLoadingSpinnerShaderProgramID, "modelViewProjectionMatrix");
        mLoadingSpinnerTimeHandle = glGetUniformLocation(mLoadingSpinnerShaderProgramID, "uTime");
    }

    // We no longer load Astronaut or Lander models to keep it simple and avoid file errors.
    mAstronautVertexCount = 0;
    mLanderVertexCount = 0;
    mAstronautTextureUnit = -1;
    mLanderTextureUnit = -1;
    mModelTargetGuideViewTextureUnit = -1;

    return true;
}


void
GLESRenderer::deinit()
{
    if (mModelTargetGuideViewTextureUnit != -1)
    {
        GLESUtils::destroyTexture(mModelTargetGuideViewTextureUnit);
        mModelTargetGuideViewTextureUnit = -1;
    }
}


void
GLESRenderer::setAstronautTexture(int /*width*/, int /*height*/, unsigned char* /*bytes*/)
{
    // No-op
}


void
GLESRenderer::setLanderTexture(int /*width*/, int /*height*/, unsigned char* /*bytes*/)
{
    // No-op
}


void
GLESRenderer::renderVideoBackground(const VuMatrix44F& projectionMatrix, const float* vertices, const float* textureCoordinates,
                                    const int numTriangles, const unsigned int* indices, int textureUnit)
{
    GLboolean depthTest = GL_FALSE;
    GLboolean cullTest = GL_FALSE;

    glGetBooleanv(GL_DEPTH_TEST, &depthTest);
    glGetBooleanv(GL_CULL_FACE, &cullTest);

    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);

    // Load the shader and upload the vertex/texcoord/index data
    glUseProgram(mVbShaderProgramID);
    glVertexAttribPointer(static_cast<GLuint>(mVbVertexPositionHandle), 3, GL_FLOAT, GL_FALSE, 0, vertices);
    glVertexAttribPointer(static_cast<GLuint>(mVbTextureCoordHandle), 2, GL_FLOAT, GL_FALSE, 0, textureCoordinates);

    glUniform1i(mVbTexSampler2DHandle, textureUnit);

    // Render the video background with the custom shader
    glEnableVertexAttribArray(static_cast<GLuint>(mVbVertexPositionHandle));
    glEnableVertexAttribArray(static_cast<GLuint>(mVbTextureCoordHandle));

    // Pass the projection matrix to OpenGL
    glUniformMatrix4fv(mVbMvpMatrixHandle, 1, GL_FALSE, projectionMatrix.data);

    // Then, we issue the render call
    glDrawElements(GL_TRIANGLES, numTriangles * 3, GL_UNSIGNED_INT, indices);

    // Finally, we disable the vertex arrays
    glDisableVertexAttribArray(static_cast<GLuint>(mVbVertexPositionHandle));
    glDisableVertexAttribArray(static_cast<GLuint>(mVbTextureCoordHandle));

    if (depthTest) glEnable(GL_DEPTH_TEST);
    if (cullTest) glEnable(GL_CULL_FACE);

    GLESUtils::checkGlError("Render video background");
    glEnable(GL_DEPTH_TEST);
}


void
GLESRenderer::renderVideoOverlay(const VuMatrix44F& projectionMatrix, const VuMatrix44F& modelViewMatrix, GLuint videoTextureId, const VuMatrix44F& textureTransform)
{
    if (videoTextureId == 0) return;
    
    if (mVideoOverlayShaderProgramID == 0) return;

    // Use the modelViewMatrix directly. trackableModelViewScaled already has the correct aspect ratio for the target.
    // The video orientation is handled by the texMatrix from SurfaceTexture.
    // We removed the manual 90-degree rotation to allow portrait videos to align with portrait targets.
    VuMatrix44F modelViewProjectionMatrix = vuMatrix44FMultiplyMatrix(projectionMatrix, modelViewMatrix);

    glDisable(GL_DEPTH_TEST);
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

    glUseProgram(mVideoOverlayShaderProgramID);

    // Enable vertex attributes. Using squareVertices from Models.h which is a unit quad.
    glEnableVertexAttribArray(mVideoOverlayVertexPositionHandle);
    glVertexAttribPointer(mVideoOverlayVertexPositionHandle, 3, GL_FLOAT, GL_FALSE, 0, (const GLvoid*)&squareVertices[0]);

    glEnableVertexAttribArray(mVideoOverlayTextureCoordHandle);
    glVertexAttribPointer(mVideoOverlayTextureCoordHandle, 2, GL_FLOAT, GL_FALSE, 0, (const GLvoid*)&squareTexCoords[0]);

    // Bind video texture (GL_TEXTURE_EXTERNAL_OES)
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(0x8D65, videoTextureId); // GL_TEXTURE_EXTERNAL_OES

    // Set uniforms
    glUniformMatrix4fv(mVideoOverlayMvpMatrixHandle, 1, GL_FALSE, (GLfloat*)modelViewProjectionMatrix.data);
    if (mVideoOverlayTexMatrixHandle != -1) {
        glUniformMatrix4fv(mVideoOverlayTexMatrixHandle, 1, GL_FALSE, (GLfloat*)textureTransform.data);
    }
    glUniform1i(mVideoOverlayTexSamplerExternalOESHandle, 0); // texture unit

    // Draw quad
    glDrawElements(GL_TRIANGLES, NUM_SQUARE_INDEX, GL_UNSIGNED_SHORT, (const GLvoid*)&squareIndices[0]);

    // Cleanup
    glDisableVertexAttribArray(mVideoOverlayTextureCoordHandle);
    glDisableVertexAttribArray(mVideoOverlayVertexPositionHandle);
    glUseProgram(0);
    glBindTexture(0x8D65, 0);

    glDisable(GL_BLEND);
    glEnable(GL_DEPTH_TEST);
}


void
GLESRenderer::renderLoadingSpinner(const VuMatrix44F& projectionMatrix, const VuMatrix44F& modelViewMatrix)
{
    if (mLoadingSpinnerShaderProgramID == 0) return;

    static float elapsedTime = 0.0f;
    elapsedTime += 0.016f;
    if (elapsedTime > 1000.0f) elapsedTime = 0.0f;

    VuVector3F scaleVec = {0.85f, 0.85f, 1.0f};
    VuMatrix44F scaledModelView = vuMatrix44FScale(scaleVec, modelViewMatrix);
    VuMatrix44F modelViewProjectionMatrix = vuMatrix44FMultiplyMatrix(projectionMatrix, scaledModelView);

    glDisable(GL_DEPTH_TEST);
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

    glUseProgram(mLoadingSpinnerShaderProgramID);

    glEnableVertexAttribArray(static_cast<GLuint>(mLoadingSpinnerVertexPositionHandle));
    glVertexAttribPointer(mLoadingSpinnerVertexPositionHandle, 3, GL_FLOAT, GL_FALSE, 0, (const GLvoid*)&squareVertices[0]);

    glEnableVertexAttribArray(static_cast<GLuint>(mLoadingSpinnerTextureCoordHandle));
    glVertexAttribPointer(mLoadingSpinnerTextureCoordHandle, 2, GL_FLOAT, GL_FALSE, 0, (const GLvoid*)&squareTexCoords[0]);

    glUniformMatrix4fv(mLoadingSpinnerMvpMatrixHandle, 1, GL_FALSE, (GLfloat*)modelViewProjectionMatrix.data);
    if (mLoadingSpinnerTimeHandle != -1) {
        glUniform1f(mLoadingSpinnerTimeHandle, elapsedTime);
    }

    glDrawElements(GL_TRIANGLES, NUM_SQUARE_INDEX, GL_UNSIGNED_SHORT, (const GLvoid*)&squareIndices[0]);

    glDisableVertexAttribArray(static_cast<GLuint>(mLoadingSpinnerTextureCoordHandle));
    glDisableVertexAttribArray(static_cast<GLuint>(mLoadingSpinnerVertexPositionHandle));
    glUseProgram(0);
    glDisable(GL_BLEND);
    glEnable(GL_DEPTH_TEST);
}

// Stubs for other methods to keep GLESRenderer.h happy
void GLESRenderer::renderWorldOrigin(VuMatrix44F&, VuMatrix44F&) {}
void GLESRenderer::renderImageTarget(VuMatrix44F&, VuMatrix44F&, VuMatrix44F&) {}
void GLESRenderer::renderModelTarget(VuMatrix44F&, VuMatrix44F&, VuMatrix44F&) {}
void GLESRenderer::renderModelTargetGuideView(VuMatrix44F&, VuMatrix44F&, const VuImageInfo&, VuBool) {}
void GLESRenderer::createTexture(int, int, unsigned char*, GLuint&) {}
void GLESRenderer::renderCube(const VuMatrix44F&, const VuMatrix44F&, float, const VuVector4F&) {}
void GLESRenderer::renderAxis(const VuMatrix44F&, const VuMatrix44F&, const VuVector3F&, float) {}
void GLESRenderer::renderModel(VuMatrix44F, const int, const float*, const float*, GLuint) {}
bool GLESRenderer::readAsset(AAssetManager*, const char*, std::vector<char>&) { return true; }
bool GLESRenderer::loadObjModel(const std::vector<char>&, int&, std::vector<float>&, std::vector<float>&) { return true; }
