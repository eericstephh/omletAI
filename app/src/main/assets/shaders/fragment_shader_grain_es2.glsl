#version 100
// Fragment shader for ColorGrading.grain — monochrome luminance noise with a
// mid-tone response curve.
//
// This is a TRANSCRIPTION of model/GrainSignal.kt, which is the specification
// and is unit-tested on the JVM. Keep the two in step: the amplitude comes from
// GrainSignal.amplitude, the response curve is GrainSignal.response, and the
// seed is GrainSignal.seedFor. The unit tests can only pin the maths, so the
// arithmetic below has to match it by hand.
//
// ## Why a custom shader rather than an overlay
//
// Grain is *high-frequency* noise, so it cannot use the downscaled-plate trick
// that makes the vignette cheap (see VignettePlate) — at reduced resolution it
// comes out as soft blobs rather than grain. The alternatives were a per-frame
// full-resolution bitmap upload (prohibitively expensive: ~8 MB per frame at
// 1080p, ~250 MB/s at 30fps) or a static noise texture (which reads as dirt on
// the lens, not film). Generating the noise per pixel in the fragment shader
// costs one hash per pixel and no uploads at all.
//
// ## Colours
//
// The chain is fed SDR sRGB-ish data (the app does not export HDR), so the luma
// weights below are Rec.709 and the perturbation is applied directly in the
// sampled space. The delta is small (±0.125 at most) and clamped, so an HDR
// input would be perturbed in linear light without breaking — it would just
// read as a slightly different curve.

// highp is what makes the noise genuinely per-pixel. `gl_FragCoord.xy` reaches
// 1080-4096 on this app's output sizes, and mediump's 10-bit mantissa quantises
// those to multiples of a few units — neighbouring pixels would share a value
// and the grain would come out as visible blocks. highp is not guaranteed in
// ES 2.0 fragment shaders, hence the guard; the fallback degrades to coarser
// grain rather than failing to compile.
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform sampler2D uTexSampler;
// Peak luminance perturbation, in the sampled space. Comes from
// GrainSignal.amplitude(). Zero disables the effect entirely.
uniform float uGrainAmplitude;
// Re-seeds the noise every frame so the grain moves. Comes from
// GrainSignal.seedFor(); in [0, 1) so that the offsets added below stay small
// enough for the mediump fallback to represent exactly.
uniform float uSeed;

varying vec2 vTexSamplingCoord;

// Dave Hoskins' "hash without sine". Preferred over the usual
// `fract(sin(dot(p, k)) * 43758.5453)`: that one multiplies by such a large
// constant that mediump loses most of its entropy, and the classic symptom is
// faint diagonal structure in what should be noise.
float hash21(vec2 p) {
  vec3 p3 = fract(vec3(p.xyx) * 0.1031);
  p3 += dot(p3, p3.yzx + 33.33);
  return fract((p3.x + p3.y) * p3.z);
}

void main() {
  vec4 src = texture2D(uTexSampler, vTexSamplingCoord);

  // Rec. 709 luma, matching the rest of the grade pipeline.
  float luma = dot(src.rgb, vec3(0.2126, 0.7152, 0.0722));

  // Mid-tone response, from GrainSignal.response(): zero in the blacks and the
  // whites, peaking at mid-grey. This is the single biggest difference between
  // convincing grain and an obviously fake grain — flat noise across the whole
  // range reads as TV static, and it wrecks clean blacks.
  float response = 4.0 * luma * (1.0 - luma);

  // `gl_FragCoord` is in output-frame pixels, so there is exactly one noise
  // sample per pixel and the grain is one output pixel across at any
  // resolution. The seed offsets decorrelate consecutive frames.
  vec2 noiseCoord = gl_FragCoord.xy + vec2(uSeed * 137.0, uSeed * 311.0);
  float noise = hash21(noiseCoord) - 0.5;

  // Monochrome: one delta on all three channels. That is how film grain
  // behaves, and it is why the grain does not shift the picture's hue — a
  // per-channel hash would look like chroma noise instead.
  vec3 delta = vec3(noise * uGrainAmplitude * response);

  gl_FragColor = vec4(clamp(src.rgb + delta, 0.0, 1.0), src.a);
}
