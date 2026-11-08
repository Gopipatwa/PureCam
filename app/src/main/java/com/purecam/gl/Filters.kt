package com.purecam.gl

/**
 * Classic, deterministic colour filters (no ML). Each one is a set of parameters for a single
 * "uber" fragment shader: colour matrix → saturation → contrast/brightness → split toning →
 * fade → posterize/invert → vignette → grain. The same shader renders the live preview, the saved
 * photo, Live Photo clips and videos, so what you see is what you get.
 */
data class FilterParams(
    /** Row-major 3×3 colour matrix: out = M · in + offset. */
    val matrix: FloatArray = IDENTITY3,
    val offset: FloatArray = floatArrayOf(0f, 0f, 0f),
    val saturation: Float = 1f,
    val contrast: Float = 1f,
    val brightness: Float = 0f,
    /** Filmic S-curve strength (0 = linear): deeper blacks and rolled-off highlights. */
    val curve: Float = 0f,
    val fade: Float = 0f,
    val vignette: Float = 0f,
    val grain: Float = 0f,
    val shadowTint: FloatArray = floatArrayOf(0f, 0f, 0f),
    val highlightTint: FloatArray = floatArrayOf(0f, 0f, 0f),
    val split: Float = 0f,
    /** 0 normal, 1 sketch, 2 posterize, 3 negative */
    val mode: Int = 0,
    val levels: Float = 5f,
) {
    /** Column-major copy for glUniformMatrix3fv. */
    val columnMajor: FloatArray by lazy {
        floatArrayOf(
            matrix[0], matrix[3], matrix[6],
            matrix[1], matrix[4], matrix[7],
            matrix[2], matrix[5], matrix[8],
        )
    }

    /** CPU version of the colour part of the shader – used for the little filter swatches. */
    fun applyCpu(rgb: FloatArray): FloatArray {
        var r = rgb[0]; var g = rgb[1]; var b = rgb[2]
        if (mode == 1) { val l = luma(r, g, b); r = 1f - l * 0.3f; g = r; b = r }
        val m = matrix
        val nr = m[0] * r + m[1] * g + m[2] * b + offset[0]
        val ng = m[3] * r + m[4] * g + m[5] * b + offset[1]
        val nb = m[6] * r + m[7] * g + m[8] * b + offset[2]
        r = nr; g = ng; b = nb
        var l = luma(r, g, b)
        r = l + (r - l) * saturation; g = l + (g - l) * saturation; b = l + (b - l) * saturation
        r = (r - 0.5f) * contrast + 0.5f + brightness
        g = (g - 0.5f) * contrast + 0.5f + brightness
        b = (b - 0.5f) * contrast + 0.5f + brightness
        r += curve * (scurve(r) - r); g += curve * (scurve(g) - g); b += curve * (scurve(b) - b)
        l = luma(r, g, b).coerceIn(0f, 1f)
        val sh = 1f - smooth(0f, 0.55f, l)
        val hi = smooth(0.45f, 1f, l)
        r += split * (shadowTint[0] * sh + highlightTint[0] * hi)
        g += split * (shadowTint[1] * sh + highlightTint[1] * hi)
        b += split * (shadowTint[2] * sh + highlightTint[2] * hi)
        r += fade * (0.14f - 0.18f * r); g += fade * (0.14f - 0.18f * g); b += fade * (0.14f - 0.18f * b)
        if (mode == 2) {
            r = Math.round(r.coerceIn(0f, 1f) * levels) / levels
            g = Math.round(g.coerceIn(0f, 1f) * levels) / levels
            b = Math.round(b.coerceIn(0f, 1f) * levels) / levels
        }
        if (mode == 3) { r = 1f - r; g = 1f - g; b = 1f - b }
        return floatArrayOf(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
    }

    private fun luma(r: Float, g: Float, b: Float) = 0.2126f * r + 0.7152f * g + 0.0722f * b
    private fun scurve(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        return c * c * (3 - 2 * c)
    }
    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)

    companion object {
        val IDENTITY3 = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val NONE = FilterParams()
    }
}

data class Filter(val id: String, val name: String, val params: FilterParams)

object Filters {
    const val ORIGINAL = "original"

    private val MONO = floatArrayOf(
        0.2126f, 0.7152f, 0.0722f,
        0.2126f, 0.7152f, 0.0722f,
        0.2126f, 0.7152f, 0.0722f,
    )
    private val SEPIA = floatArrayOf(
        0.393f, 0.769f, 0.189f,
        0.349f, 0.686f, 0.168f,
        0.272f, 0.534f, 0.131f,
    )
    /** Monochrome with a slight red bias, like a panchromatic B&W sensor. */
    private val LEICA_MONO = floatArrayOf(
        0.34f, 0.56f, 0.10f,
        0.34f, 0.56f, 0.10f,
        0.34f, 0.56f, 0.10f,
    )

    /** Monochrome luminance, then tinted (r, g, b multipliers). */
    private fun tintedMono(r: Float, g: Float, b: Float) = floatArrayOf(
        0.34f * r, 0.56f * r, 0.10f * r,
        0.34f * g, 0.56f * g, 0.10f * g,
        0.34f * b, 0.56f * b, 0.10f * b,
    )

    private fun diag(r: Float, g: Float, b: Float) = floatArrayOf(r, 0f, 0f, 0f, g, 0f, 0f, 0f, b)
    private fun v(r: Float, g: Float, b: Float) = floatArrayOf(r, g, b)

    val all: List<Filter> = listOf(
        Filter(ORIGINAL, "Original", FilterParams.NONE),

        // Leica-style looks (our own recreation): restrained colour, deep filmic blacks, natural vignette.
        Filter("leica_authentic", "Leica Authentic", FilterParams(
            matrix = diag(1.02f, 1.0f, 0.97f), saturation = 0.88f, contrast = 1.06f, curve = 0.45f,
            brightness = -0.015f, vignette = 0.45f,
            shadowTint = v(-0.015f, 0.0f, 0.02f), highlightTint = v(0.03f, 0.02f, 0.0f), split = 1f,
        )),
        Filter("leica_vibrant", "Leica Vibrant", FilterParams(
            matrix = diag(1.03f, 1.0f, 0.98f), saturation = 1.22f, contrast = 1.06f, curve = 0.35f,
            vignette = 0.25f, highlightTint = v(0.02f, 0.01f, -0.01f), split = 1f,
        )),
        Filter("leica_natural", "Leica Natural", FilterParams(
            saturation = 0.95f, contrast = 1.02f, curve = 0.2f, vignette = 0.15f,
        )),
        Filter("leica_mono", "Leica Mono", FilterParams(
            matrix = LEICA_MONO, curve = 0.35f, contrast = 1.05f, vignette = 0.3f, grain = 0.02f,
        )),
        Filter("leica_mono_hc", "Leica Mono HC", FilterParams(
            matrix = LEICA_MONO, contrast = 1.35f, curve = 0.6f, brightness = -0.02f, vignette = 0.45f, grain = 0.03f,
        )),
        Filter("leica_sepia", "Leica Sepia", FilterParams(
            matrix = tintedMono(1.08f, 0.95f, 0.76f), curve = 0.3f, vignette = 0.35f, grain = 0.02f,
        )),
        Filter("leica_blue", "Leica Blue", FilterParams(
            matrix = tintedMono(0.85f, 0.97f, 1.15f), curve = 0.3f, vignette = 0.35f,
        )),

        Filter("vivid", "Vivid", FilterParams(saturation = 1.35f, contrast = 1.1f)),
        Filter("natural", "Natural+", FilterParams(saturation = 1.12f, contrast = 1.05f, brightness = 0.01f)),
        Filter("warm", "Warm", FilterParams(matrix = diag(1.08f, 1.0f, 0.88f), offset = v(0.02f, 0.01f, -0.01f))),
        Filter("cool", "Cool", FilterParams(matrix = diag(0.9f, 1.0f, 1.1f), offset = v(-0.01f, 0f, 0.02f))),
        Filter("golden", "Golden", FilterParams(matrix = diag(1.1f, 1.02f, 0.85f), brightness = 0.02f, highlightTint = v(0.06f, 0.03f, -0.04f), split = 1f)),
        Filter("teal_orange", "Teal & Orange", FilterParams(contrast = 1.1f, saturation = 1.1f, shadowTint = v(-0.06f, 0.03f, 0.10f), highlightTint = v(0.10f, 0.04f, -0.06f), split = 1f)),
        Filter("cinematic", "Cinematic", FilterParams(contrast = 1.15f, saturation = 0.92f, fade = 0.08f, vignette = 0.35f, shadowTint = v(-0.05f, 0.03f, 0.09f), highlightTint = v(0.08f, 0.03f, -0.05f), split = 1f)),
        Filter("film", "Film", FilterParams(contrast = 1.05f, saturation = 0.9f, fade = 0.15f, grain = 0.06f, shadowTint = v(-0.02f, 0.03f, 0.06f), highlightTint = v(0.05f, 0.03f, -0.02f), split = 1f)),
        Filter("vintage", "Vintage", FilterParams(matrix = diag(1.05f, 1.0f, 0.82f), offset = v(0.03f, 0.02f, 0f), saturation = 0.75f, fade = 0.25f, vignette = 0.45f, grain = 0.03f)),
        Filter("polaroid", "Instant", FilterParams(
            matrix = floatArrayOf(1.438f, -0.062f, -0.062f, -0.122f, 1.378f, -0.122f, -0.016f, -0.016f, 1.483f),
            offset = v(-0.03f, 0.05f, -0.02f), contrast = 0.95f, vignette = 0.2f,
        )),
        Filter("fade", "Fade", FilterParams(fade = 0.35f, saturation = 0.8f, contrast = 0.9f)),
        Filter("matte", "Matte", FilterParams(fade = 0.3f, contrast = 0.95f, saturation = 0.9f, brightness = 0.01f)),
        Filter("pastel", "Pastel", FilterParams(saturation = 0.7f, brightness = 0.06f, fade = 0.2f, contrast = 0.9f)),
        Filter("chrome", "Chrome", FilterParams(saturation = 1.2f, contrast = 1.15f, matrix = diag(1.02f, 1.0f, 1.04f))),
        Filter("dramatic", "Dramatic", FilterParams(contrast = 1.35f, saturation = 1.15f, vignette = 0.5f, brightness = -0.03f)),
        Filter("lomo", "Lomo", FilterParams(contrast = 1.25f, saturation = 1.3f, vignette = 0.75f)),
        Filter("neon", "Neon", FilterParams(contrast = 1.15f, saturation = 1.25f, shadowTint = v(-0.06f, 0.06f, 0.14f), highlightTint = v(0.14f, -0.02f, 0.12f), split = 1f)),
        Filter("ice", "Ice", FilterParams(matrix = diag(0.88f, 1.0f, 1.15f), saturation = 0.8f, brightness = 0.03f)),
        Filter("mono", "Mono", FilterParams(matrix = MONO)),
        Filter("noir", "Noir", FilterParams(matrix = MONO, contrast = 1.45f, vignette = 0.4f, grain = 0.03f)),
        Filter("mono_soft", "Soft B&W", FilterParams(matrix = MONO, fade = 0.2f, contrast = 0.9f)),
        Filter("sepia", "Sepia", FilterParams(matrix = SEPIA)),
        Filter("posterize", "Posterize", FilterParams(mode = 2, levels = 5f, saturation = 1.1f)),
        Filter("sketch", "Sketch", FilterParams(mode = 1)),
        Filter("negative", "Negative", FilterParams(mode = 3)),
    )

    fun byId(id: String): Filter = all.firstOrNull { it.id == id } ?: all.first()
}
