/*===============================================================================
Copyright (c) 2023 PTC Inc. and/or Its Subsidiary Companies. All Rights Reserved.

Vuforia is a trademark of PTC Inc., registered in the United States and other
countries.
===============================================================================*/

#ifndef _VUFORIA_SHADERS_H_
#define _VUFORIA_SHADERS_H_

/////////////////////////////////////////////////////////////////////////////////////////
// texture shader: vertexTexCoord in vertex shader, texture2D sample
/////////////////////////////////////////////////////////////////////////////////////////
static const char* textureVertexShaderSrc = R"(
    attribute vec4 vertexPosition;
    attribute vec2 vertexTextureCoord;

    uniform mat4 modelViewProjectionMatrix;

    varying vec2 texCoord;

    void main()
    {
        gl_Position = modelViewProjectionMatrix * vertexPosition;
        texCoord = vertexTextureCoord;
    }
)";


static const char* textureFragmentShaderSrc = R"(
    precision mediump float;

    uniform sampler2D texSampler2D;

    varying vec2 texCoord;

    void main()
    {
        gl_FragColor = texture2D(texSampler2D, texCoord);
    }
)";


/////////////////////////////////////////////////////////////////////////////////////////
// texture color shader: vertexTexCoord in vertex shader, uniform color, texture2D sample
/////////////////////////////////////////////////////////////////////////////////////////
static const char* textureColorVertexShaderSrc = R"(
    attribute vec4 vertexPosition;
    attribute vec2 vertexTextureCoord;

    uniform mat4 modelViewProjectionMatrix;

    varying vec2 texCoord;

    void main()
    {
        gl_Position = modelViewProjectionMatrix * vertexPosition;
        texCoord = vertexTextureCoord;
    }
)";


static const char* textureColorFragmentShaderSrc = R"(
    precision mediump float;

    uniform sampler2D texSampler2D;

    varying vec2 texCoord;

    uniform vec4 uniformColor;

    void main()
    {
        vec4 texColor = texture2D(texSampler2D, texCoord);
        gl_FragColor = texColor * uniformColor;
    }
)";


/////////////////////////////////////////////////////////////////////////////////////////
// uniform color shader: uniform color in frag shader
/////////////////////////////////////////////////////////////////////////////////////////
static const char* uniformColorVertexShaderSrc = R"(
    attribute vec4 vertexPosition;

    uniform mat4 modelViewProjectionMatrix;

    void main()
    {
        gl_Position = modelViewProjectionMatrix * vertexPosition;
    }
)";


static const char* uniformColorFragmentShaderSrc = R"(
    precision mediump float;

    uniform vec4 uniformColor;

    void main()
    {
        gl_FragColor = uniformColor;
    }
)";


/////////////////////////////////////////////////////////////////////////////////////////
// vertex color shader: attribute color in vertex shader
/////////////////////////////////////////////////////////////////////////////////////////
static const char* vertexColorVertexShaderSrc = R"(
    attribute vec4 vertexPosition;
    attribute vec4 vertexColor;

    uniform mat4 modelViewProjectionMatrix;

    // Color to use per vertex, linear interpolated down at fragment shader
    varying vec4 color;

    void main()
    {
        gl_Position = modelViewProjectionMatrix * vertexPosition;
        color = vertexColor;
    }
)";

static const char* vertexColorFragmentShaderSrc = R"(
    precision mediump float;

    varying vec4 color;

    void main()
    {
        gl_FragColor = color;
    }
)";


/////////////////////////////////////////////////////////////////////////////////////////
// video overlay shader: uses samplerExternalOES for GL_TEXTURE_EXTERNAL_OES and texMatrix
/////////////////////////////////////////////////////////////////////////////////////////
static const char* videoOverlayVertexShaderSrc = R"(
    attribute vec4 vertexPosition;
    attribute vec2 vertexTextureCoord;

    uniform mat4 modelViewProjectionMatrix;
    uniform mat4 texMatrix;

    varying vec2 texCoord;

    void main()
    {
        gl_Position = modelViewProjectionMatrix * vertexPosition;

        // Mirroring iOS: Use standard UV coordinates directly with texMatrix.
        // No manual rotation here; orientation is handled by the video source and texMatrix.
        texCoord = (texMatrix * vec4(vertexTextureCoord, 0.0, 1.0)).xy;
    }
)";


static const char* videoOverlayFragmentShaderSrc = R"(
    #extension GL_OES_EGL_image_external : require
    precision mediump float;

    uniform samplerExternalOES texSamplerExternalOES;

    varying vec2 texCoord;

    void main()
    {
        gl_FragColor = texture2D(texSamplerExternalOES, texCoord);
    }
)";


/////////////////////////////////////////////////////////////////////////////////////////
// loading spinner shader: procedural smooth white arc, iOS-style
// Draws a thick circular ring (~75% arc) with spinning animation
/////////////////////////////////////////////////////////////////////////////////////////
// Vertex shader - pass local position for aspect-correct circle
static const char* loadingSpinnerVertexShaderSrc = R"(
    attribute vec4 vertexPosition;
    attribute vec2 vertexTextureCoord;

    uniform mat4 modelViewProjectionMatrix;

    varying vec2 texCoord;
    varying vec2 localPos;

    void main()
    {
        gl_Position = modelViewProjectionMatrix * vertexPosition;
        texCoord = vertexTextureCoord;
        localPos = vertexPosition.xy;
    }
)";

// Fragment shader - iOS Metal spinner: gray track + white arc + glow
static const char* loadingSpinnerFragmentShaderSrc = R"(
    precision mediump float;

    uniform float uTime;

    varying vec2 texCoord;
    varying vec2 localPos;

    void main()
    {
        float aa = 0.003;
        float dist = length(localPos);
        float angle = atan(localPos.y, localPos.x);
        float normalizedDist = dist / 0.5;

        float normalizedAngle = (-angle + 1.57079632679) / 6.28318530718;
        normalizedAngle = fract(normalizedAngle);

        float spinTime = uTime * 1.5;
        float rotatedAngle = fract(normalizedAngle + spinTime);

        float innerRadius = 0.07;
        float outerRadius = 0.09;
        float strokeWidth = outerRadius - innerRadius;

        float ringInner = smoothstep(innerRadius - aa, innerRadius + aa, normalizedDist);
        float ringOuter = 1.0 - smoothstep(outerRadius - aa, outerRadius + aa, normalizedDist);
        float ringMask = ringInner * ringOuter;

        float arcAlpha = 0.0;
        if (rotatedAngle < 0.75) {
            float lead = smoothstep(0.0, 0.03, rotatedAngle);
            float trail = 1.0 - smoothstep(0.68, 0.75, rotatedAngle);
            arcAlpha = lead * trail;
        }

        vec3 grayTrack = vec3(0.2, 0.2, 0.2);
        float trackAlpha = ringMask * 0.25;

        float glowInner = 0.05;
        float glowOuter = 0.12;
        float glowInnerS = smoothstep(glowInner - aa, glowInner + aa, normalizedDist);
        float glowOuterS = 1.0 - smoothstep(glowOuter - aa, glowOuter + aa, normalizedDist);
        float glowMask = glowInnerS * glowOuterS * arcAlpha * 0.15;

        float arcMask = ringMask * arcAlpha * 0.95;

        vec3 color = grayTrack * trackAlpha + vec3(1.0) * glowMask + vec3(1.0) * arcMask;
        float alpha = trackAlpha + glowMask + arcMask;
        alpha = min(1.0, alpha);

        gl_FragColor = vec4(color, alpha);
    }
)";

#endif // _VUFORIA_SHADERS_H_
