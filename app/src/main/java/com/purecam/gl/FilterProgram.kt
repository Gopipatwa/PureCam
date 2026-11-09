package com.purecam.gl

import android.opengl.GLES11Ext
import android.opengl.GLES20

/** The single shader used for preview, recording and photo post-processing. */
class FilterProgram(private val external: Boolean) {

    private val program = GlUtil.program(VERTEX, fragment(external))
    private val aPos = GLES20.glGetAttribLocation(program, "aPos")
    private val aTex = GLES20.glGetAttribLocation(program, "aTex")
    private val uTex = loc("uTex")
    private val uTexMatrix = loc("uTexMatrix")
    private val uCropScale = loc("uCropScale")
    private val uFlip = loc("uFlip")
    private val uRot = loc("uRot")
    private val uColorMatrix = loc("uColorMatrix")
    private val uColorOffset = loc("uColorOffset")
    private val uSaturation = loc("uSaturation")
    private val uContrast = loc("uContrast")
    private val uBrightness = loc("uBrightness")
    private val uCurve = loc("uCurve")
    private val uFade = loc("uFade")
    private val uVignette = loc("uVignette")
    private val uGrain = loc("uGrain")
    private val uIntensity = loc("uIntensity")
    private val uShadowTint = loc("uShadowTint")
    private val uHighlightTint = loc("uHighlightTint")
    private val uSplit = loc("uSplit")
    private val uMode = loc("uMode")
    private val uLevels = loc("uLevels")
    private val uTexel = loc("uTexel")
    private val uResolution = loc("uResolution")
    private val uTime = loc("uTime")
    private val uPeaking = loc("uPeaking")
    private val uBaseCurve = loc("uBaseCurve")
    private val uBaseSat = loc("uBaseSat")
    private val uFast = loc("uFast")

    private val quad = GlUtil.floatBuffer(
        floatArrayOf(
            // x, y, u, v
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        ),
    )

    private fun loc(name: String) = GLES20.glGetUniformLocation(program, name)

    class DrawArgs(
        var cropX: Float = 1f,
        var cropY: Float = 1f,
        var flipX: Boolean = false,
        var flipY: Boolean = false,
        var outW: Int = 1,
        var outH: Int = 1,
        var texelX: Float = 0.001f,
        var texelY: Float = 0.001f,
        var time: Float = 0f,
        var peaking: Boolean = false,
        /** Quarter turns to counter-rotate for the display (landscape UI). */
        var rotation: Int = 0,
        /** Colour-profile fallback when the camera can't apply a tone curve itself. */
        var baseCurve: Float = 0f,
        var baseSat: Float = 1f,
        /** Skip all filter maths (Original filter, no peaking): far less GPU work per frame. */
        var fast: Boolean = false,
    )

    fun draw(texture: Int, texMatrix: FloatArray, p: FilterParams, intensity: Float, a: DrawArgs) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(if (external) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES20.glUniform2f(uCropScale, a.cropX, a.cropY)
        GLES20.glUniform2f(uFlip, if (a.flipX) 1f else 0f, if (a.flipY) 1f else 0f)
        GLES20.glUniform1f(uRot, a.rotation.toFloat())
        GLES20.glUniformMatrix3fv(uColorMatrix, 1, false, p.columnMajor, 0)
        GLES20.glUniform3fv(uColorOffset, 1, p.offset, 0)
        GLES20.glUniform1f(uSaturation, p.saturation)
        GLES20.glUniform1f(uContrast, p.contrast)
        GLES20.glUniform1f(uBrightness, p.brightness)
        GLES20.glUniform1f(uCurve, p.curve)
        GLES20.glUniform1f(uFade, p.fade)
        GLES20.glUniform1f(uVignette, p.vignette)
        GLES20.glUniform1f(uGrain, p.grain)
        GLES20.glUniform1f(uIntensity, intensity)
        GLES20.glUniform3fv(uShadowTint, 1, p.shadowTint, 0)
        GLES20.glUniform3fv(uHighlightTint, 1, p.highlightTint, 0)
        GLES20.glUniform1f(uSplit, p.split)
        GLES20.glUniform1f(uMode, p.mode.toFloat())
        GLES20.glUniform1f(uLevels, p.levels)
        GLES20.glUniform2f(uTexel, a.texelX, a.texelY)
        GLES20.glUniform2f(uResolution, a.outW.toFloat(), a.outH.toFloat())
        GLES20.glUniform1f(uTime, a.time)
        GLES20.glUniform1f(uPeaking, if (a.peaking) 1f else 0f)
        GLES20.glUniform1f(uBaseCurve, a.baseCurve)
        GLES20.glUniform1f(uBaseSat, a.baseSat)
        GLES20.glUniform1f(uFast, if (a.fast) 1f else 0f)

        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        quad.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
    }

    fun release() {
        GLES20.glDeleteProgram(program)
    }

    companion object {
        private const val VERTEX = """
attribute vec4 aPos;
attribute vec2 aTex;
uniform vec2 uCropScale;
uniform vec2 uFlip;
uniform float uRot;
varying vec2 vTex;
varying vec2 vPos;
void main() {
    gl_Position = aPos;
    vPos = aTex;
    vec2 t = aTex;
    if (uRot > 0.5 && uRot < 1.5) {
        t = vec2(t.y, 1.0 - t.x);
    } else if (uRot > 1.5 && uRot < 2.5) {
        t = vec2(1.0 - t.x, 1.0 - t.y);
    } else if (uRot > 2.5) {
        t = vec2(1.0 - t.y, t.x);
    }
    if (uFlip.x > 0.5) t.x = 1.0 - t.x;
    if (uFlip.y > 0.5) t.y = 1.0 - t.y;
    vTex = 0.5 + (t - 0.5) * uCropScale;
}
"""

        private fun fragment(external: Boolean): String {
            val header = if (external) {
                "#extension GL_OES_EGL_image_external : require\n"
            } else ""
            val sampler = if (external) "samplerExternalOES" else "sampler2D"
            return header + """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 vTex;
varying vec2 vPos;
uniform $sampler uTex;
uniform mat4 uTexMatrix;
uniform mat3 uColorMatrix;
uniform vec3 uColorOffset;
uniform float uSaturation;
uniform float uContrast;
uniform float uBrightness;
uniform float uCurve;
uniform float uFade;
uniform float uVignette;
uniform float uGrain;
uniform float uIntensity;
uniform vec3 uShadowTint;
uniform vec3 uHighlightTint;
uniform float uSplit;
uniform float uMode;
uniform float uLevels;
uniform vec2 uTexel;
uniform vec2 uResolution;
uniform float uTime;
uniform float uPeaking;
uniform float uBaseCurve;
uniform float uBaseSat;
uniform float uFast;

vec3 src(vec2 c) {
    return texture2D(uTex, (uTexMatrix * vec4(c, 0.0, 1.0)).xy).rgb;
}

float luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

float hash(vec2 p) {
    p = fract(p * vec2(0.1031, 0.1030));
    p += dot(p, p.yx + 33.33);
    return fract((p.x + p.y) * p.x);
}

float edge(vec2 c) {
    float tl = luma(src(c + uTexel * vec2(-1.0, -1.0)));
    float t  = luma(src(c + uTexel * vec2( 0.0, -1.0)));
    float tr = luma(src(c + uTexel * vec2( 1.0, -1.0)));
    float l  = luma(src(c + uTexel * vec2(-1.0,  0.0)));
    float r  = luma(src(c + uTexel * vec2( 1.0,  0.0)));
    float bl = luma(src(c + uTexel * vec2(-1.0,  1.0)));
    float b  = luma(src(c + uTexel * vec2( 0.0,  1.0)));
    float br = luma(src(c + uTexel * vec2( 1.0,  1.0)));
    float gx = -tl - 2.0 * l - bl + tr + 2.0 * r + br;
    float gy = -tl - 2.0 * t - tr + bl + 2.0 * b + br;
    return sqrt(gx * gx + gy * gy);
}

void main() {
    vec3 orig = src(vTex);
    if (uBaseCurve > 0.0 || uBaseSat != 1.0) {
        vec3 oc = clamp(orig, 0.0, 1.0);
        orig = mix(oc, oc * oc * (3.0 - 2.0 * oc), uBaseCurve);
        orig = clamp(mix(vec3(luma(orig)), orig, uBaseSat), 0.0, 1.0);
    }
    if (uFast > 0.5) {
        gl_FragColor = vec4(orig, 1.0);
        return;
    }
    vec3 c = orig;
    if (uMode > 0.5 && uMode < 1.5) {
        float e = edge(vTex);
        c = vec3(1.0 - clamp(e * 1.6, 0.0, 1.0));
    }
    c = uColorMatrix * c + uColorOffset;
    float l = luma(c);
    c = mix(vec3(l), c, uSaturation);
    c = (c - 0.5) * uContrast + 0.5 + uBrightness;
    vec3 cc = clamp(c, 0.0, 1.0);
    c = mix(c, cc * cc * (3.0 - 2.0 * cc), uCurve);
    l = clamp(luma(c), 0.0, 1.0);
    c += uSplit * (uShadowTint * (1.0 - smoothstep(0.0, 0.55, l)) + uHighlightTint * smoothstep(0.45, 1.0, l));
    c = mix(c, vec3(0.14) + c * 0.82, uFade);
    if (uMode > 1.5 && uMode < 2.5) {
        c = floor(clamp(c, 0.0, 1.0) * uLevels + 0.5) / uLevels;
    }
    if (uMode > 2.5) {
        c = 1.0 - c;
    }
    vec2 d = vPos - 0.5;
    c *= 1.0 - uVignette * smoothstep(0.35, 1.0, length(d) * 1.414);
    c += (hash(vPos * uResolution + uTime) - 0.5) * uGrain;
    c = clamp(c, 0.0, 1.0);
    c = mix(orig, c, uIntensity);
    if (uPeaking > 0.5) {
        if (edge(vTex) > 0.38) c = mix(c, vec3(1.0, 0.12, 0.12), 0.9);
    }
    gl_FragColor = vec4(c, 1.0);
}
"""
        }
    }
}
