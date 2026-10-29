package com.aicreatorstudio.app.export

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.Effect
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.aicreatorstudio.app.model.GrainSignal

/**
 * Media3 adapter for [GrainSignal] — the "Detail > Grain" slider of the Color
 * Grading panel, as a custom fragment shader.
 *
 * ## The gap this closes
 *
 * `ColorGrading.grain` was the **last write-only field in the project**, and the
 * only one that was write-only in *both* renderers: the panel wrote it, the DB
 * persisted it, and neither the preview nor the export read it back. Every other
 * field closed in this series had a preview behaviour to reproduce; grain had
 * none, so its appearance had to be *decided* rather than matched. See
 * [GrainSignal] for the model that was chosen.
 *
 * ## Why this is a shader and not an overlay
 *
 * The established pattern in this package is a full-frame `BitmapOverlay`
 * ([VignetteOverlay], [CanvasBackgroundOverlay]) — cheap because the plate is
 * generated below frame resolution and the GPU interpolates it back. Grain is
 * *high-frequency* noise, so that interpolation turns it into soft blobs. The
 * alternatives were all worse:
 *
 *  - a per-frame full-resolution bitmap upload — ~8 MB per frame at 1080p, so
 *    ~250 MB/s at 30fps, which is not affordable on a mobile GPU;
 *  - a single static noise texture — free per frame, but it reads as dirt on the
 *    lens rather than film, because real grain moves;
 *  - `ByteBufferGlEffect` — the same custom GL surface as this, plus a per-frame
 *    CPU pass over every pixel.
 *
 * So the noise is generated per pixel, per frame, in the fragment shader. The
 * cost is one hash per pixel and no texture uploads at all.
 *
 * ## Two usages, one shader (preview == export)
 *
 *  - **Export** ([VideoExportEngine]): a fresh instance per segment, with the
 *    clip's own grade (immutable use).
 *  - **Live preview** (editor `PlayerView` via `ExoPlayer.setVideoEffects`):
 *    ONE instance lives for the life of the screen and [grain] is written per
 *    slider tick. That costs nothing per tick — the value is read out of the
 *    live instance in `drawFrame`, so a drag never rebuilds the GL pipeline.
 *    Same arrangement as [SharpnessEffect] and [VignetteOverlay].
 *
 * ## The custom GL surface, and how far it is verified
 *
 * This is the project's first hand-written shader, so the surface is kept as
 * small as it can be. Every Media3 API used here was read out of the 1.5.0
 * artifacts rather than assumed:
 *
 *  - `BaseGlShaderProgram(boolean, int)` is public with an abstract
 *    `configure`/`drawFrame` and a non-final `release()`;
 *  - the vertex shader is Media3's **own** `vertex_shader_transformation_es2`
 *    used verbatim — Media3's assets are merged into the app's, so it needs no
 *    copy of its own;
 *  - `GlProgram` discovers the shader's declared uniforms by enumerating
 *    `GL_ACTIVE_UNIFORMS` at link time, so `uGrainAmplitude` and `uSeed` need no
 *    registration — and `setFloatsUniformIfPresent` is a silent no-op for a name
 *    the shader does not declare, which is why the names are checked once in the
 *    constructor instead. See `requireUniform`.
 *
 * What is **not** verified: that the shader compiles and produces the intended
 * picture on a real GPU. Unit tests pin [GrainSignal]'s maths, but this project
 * has no GL harness, so the fragment shader needs a device smoke test.
 *
 * ## Additive by construction
 *
 * [build] returns `null` at rest, so a project that never touches the Grain
 * slider appends nothing here and its effect chain is byte-for-byte what it was
 * before this pass existed.
 */
@UnstableApi
class GrainEffect(
    @Volatile var grain: Float = 0f
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        GrainShaderProgram(context, useHdr, this)

    companion object {
        /**
         * The effect to append, or `null` when the slider is at rest.
         *
         * Returning `null` rather than an identity effect keeps the GL chain
         * for an untouched project free of an extra pass, an extra texture and
         * an extra program compile.
         */
        fun build(grain: Float): Effect? =
            if (GrainSignal.isActive(grain)) GrainEffect(grain) else null
    }
}

/**
 * The shader program behind [GrainEffect].
 *
 * Modelled on Media3's own `AlphaScaleShaderProgram`, which is the smallest
 * concrete `BaseGlShaderProgram` in the library: bind the NDC quad, sample the
 * input texture, write a fragment. The only difference is that the fragment
 * colour is perturbed by a hash of the pixel position.
 *
 * The effect instance is held rather than the float, so a slider tick in the
 * preview is visible on the very next frame without any plumbing.
 */
@UnstableApi
private class GrainShaderProgram(
    context: Context,
    useHdr: Boolean,
    private val effect: GrainEffect
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {

    private val glProgram: GlProgram = try {
        GlProgram(context, VERTEX_SHADER_PATH, FRAGMENT_SHADER_PATH)
    } catch (e: Exception) {
        // Includes a shader that fails to compile or link. Media3 surfaces this
        // as a processing error rather than a crash, and only for a project that
        // actually uses the Grain slider — `build` returns null at rest.
        throw VideoFrameProcessingException("Grain shader could not be loaded", e)
    }

    /**
     * Reused across frames. `setFloatsUniformIfPresent` keeps the array it is
     * given, so a fresh one per frame would be an allocation per frame on the GL
     * thread — the same reason [SharpnessEffect] caches its convolution
     * function.
     */
    private val amplitudeValue = FloatArray(1)
    private val seedValue = FloatArray(1)

    init {
        // Draw over the whole normalised device coordinate space, -1..1 on both
        // axes, as the vertex shader's four-vertex triangle strip expects.
        glProgram.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
        )
        val identity = GlUtil.create4x4IdentityMatrix()
        glProgram.setFloatsUniform("uTransformationMatrix", identity)
        glProgram.setFloatsUniform("uTexTransformationMatrix", identity)

        // A uniform the shader does not declare is a silent no-op through
        // `setFloatsUniformIfPresent`, and this effect would then have no
        // symptom at all — grain would simply never appear. Check the names
        // once, here, where the failure is loud and happens at graph
        // construction rather than on the third frame of a render.
        requireUniform(UNIFORM_AMPLITUDE)
        requireUniform(UNIFORM_SEED)
    }

    /** The pass is frame-size-preserving; the muxer rejects a mid-stream resize. */
    override fun configure(inputWidth: Int, inputHeight: Int): Size =
        Size(inputWidth, inputHeight)

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform(
                "uTexSampler",
                inputTexId,
                /* texUnitIndex= */ 0
            )
            // Read from the live effect, so a preview slider tick lands on the
            // next frame with no pipeline rebuild.
            amplitudeValue[0] = GrainSignal.amplitude(effect.grain)
            seedValue[0] = GrainSignal.seedFor(presentationTimeUs)
            glProgram.setFloatsUniformIfPresent(UNIFORM_AMPLITUDE, amplitudeValue)
            glProgram.setFloatsUniformIfPresent(UNIFORM_SEED, seedValue)
            glProgram.bindAttributesAndUniforms()

            // The four-vertex triangle strip forms a quad.
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    /**
     * Frees the GL program as well as the texture pool.
     *
     * Media3's own `AlphaScaleShaderProgram` does not bother, but the preview
     * rebuilds its pipeline whenever an effect switches on or off, so a missing
     * `delete` would leak one program per toggle for the life of the screen.
     */
    override fun release() {
        super.release()
        try {
            glProgram.delete()
        } catch (_: Exception) {
            // A program that cannot be deleted is reclaimed when the EGL context
            // is destroyed. Not worth failing an export or a preview over, and
            // there is nowhere useful to report it from here.
        }
    }

    private fun requireUniform(name: String) {
        if (glProgram.getUniformLocation(name) < 0) {
            throw VideoFrameProcessingException(
                "Grain shader does not declare the $name uniform"
            )
        }
    }

    private companion object {
        /**
         * Media3's own transformation vertex shader, used verbatim. It is
         * already in the app's merged assets because `media3-effect` is a
         * dependency, so this pass ships only the fragment shader.
         */
        const val VERTEX_SHADER_PATH = "shaders/vertex_shader_transformation_es2.glsl"

        /** The one asset this pass adds. Uniquely named to avoid colliding with Media3's. */
        const val FRAGMENT_SHADER_PATH = "shaders/fragment_shader_grain_es2.glsl"

        const val UNIFORM_AMPLITUDE = "uGrainAmplitude"
        const val UNIFORM_SEED = "uSeed"
    }
}
