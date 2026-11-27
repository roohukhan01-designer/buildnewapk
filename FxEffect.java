package com.roohu.clipforge;

import android.content.Context;
import android.opengl.GLES20;

import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.BaseGlShaderProgram;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.GlShaderProgram;

/**
 * Cinematic GPU look in one pass: radial chromatic aberration, glitch slices, soft bloom on highlights,
 * teal/orange grade, vignette and film grain. Aberration, glitch and bloom kick on every punch moment.
 */
@UnstableApi
final class FxEffect implements GlEffect {
    private final double[] punches;

    FxEffect(double[] punches) { this.punches = punches; }

    @Override
    public GlShaderProgram toGlShaderProgram(Context context, boolean useHdr) throws VideoFrameProcessingException {
        return new Program(useHdr, punches);
    }

    private static final String VERT =
            "attribute vec4 aFramePosition;\n"
            + "varying vec2 vTexSamplingCoord;\n"
            + "void main() {\n"
            + "  gl_Position = aFramePosition;\n"
            + "  vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;\n"
            + "}\n";

    private static final String FRAG =
            "precision highp float;\n"
            + "uniform sampler2D uTexSampler;\n"
            + "uniform float uTime;\n"
            + "uniform float uPunch;\n"
            + "uniform vec2 uRes;\n"
            + "varying vec2 vTexSamplingCoord;\n"
            + "float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }\n"
            + "void main() {\n"
            + "  vec2 uv = vTexSamplingCoord;\n"
            + "  vec2 c = uv - 0.5;\n"
            + "  float r2 = dot(c, c);\n"
            + "  float tick = floor(uTime * 30.0);\n"
            + "  float band = floor(uv.y * 24.0);\n"
            + "  float on = step(0.6, hash(vec2(band * 1.7, tick)));\n"
            + "  uv.x += (hash(vec2(band, tick)) - 0.5) * 0.05 * uPunch * on;\n"
            + "  float ab = 0.0012 + 0.010 * uPunch;\n"
            + "  vec2 dir = normalize(c + vec2(0.00001)) * ab * (0.6 + r2 * 4.0);\n"
            + "  vec3 col;\n"
            + "  col.r = texture2D(uTexSampler, uv + dir).r;\n"
            + "  col.g = texture2D(uTexSampler, uv).g;\n"
            + "  col.b = texture2D(uTexSampler, uv - dir).b;\n"
            + "  vec3 zb = vec3(0.0);\n"
            + "  float zk = 0.05 * uPunch;\n"
            + "  for (int j = 0; j < 6; j++) {\n"
            + "    zb += texture2D(uTexSampler, uv - c * zk * float(j) / 5.0).rgb;\n"
            + "  }\n"
            + "  col = mix(col, zb / 6.0, min(1.0, uPunch * 0.85));\n"
            + "  vec2 px = 1.0 / uRes;\n"
            + "  vec3 bl = vec3(0.0);\n"
            + "  for (int i = 0; i < 8; i++) {\n"
            + "    float a = float(i) * 0.785398;\n"
            + "    vec2 o = vec2(cos(a), sin(a)) * px * 6.0;\n"
            + "    bl += max(texture2D(uTexSampler, uv + o).rgb - 0.72, 0.0);\n"
            + "  }\n"
            + "  col += bl * (0.16 + 0.25 * uPunch);\n"
            + "  float lum = dot(col, vec3(0.299, 0.587, 0.114));\n"
            + "  col = mix(col, col * vec3(1.05, 1.0, 0.95), smoothstep(0.5, 1.0, lum));\n"
            + "  col = mix(col, col * vec3(0.92, 1.0, 1.06), 1.0 - smoothstep(0.0, 0.5, lum));\n"
            + "  col = (col - 0.5) * 1.06 + 0.5;\n"
            + "  col *= 1.0 - 0.38 * smoothstep(0.25, 0.75, r2 * 2.2);\n"
            + "  col += (hash(uv * uRes + vec2(fract(uTime) * 100.0)) - 0.5) * 0.045;\n"
            + "  gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);\n"
            + "}\n";

    private static final class Program extends BaseGlShaderProgram {
        private final GlProgram gl;
        private final double[] pk;
        private int w = 1, h = 1;

        Program(boolean useHdr, double[] pk) throws VideoFrameProcessingException {
            super(useHdr, 1);
            this.pk = pk;
            try {
                gl = new GlProgram(VERT, FRAG);
                gl.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(),
                        GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
            } catch (GlUtil.GlException e) {
                throw new VideoFrameProcessingException(e);
            }
        }

        @Override public Size configure(int inputWidth, int inputHeight) {
            w = inputWidth; h = inputHeight;
            return new Size(inputWidth, inputHeight);
        }

        @Override public void drawFrame(int inputTexId, long presentationTimeUs) throws VideoFrameProcessingException {
            try {
                double t = presentationTimeUs / 1e6, d = ClipExporter.sinceLast(pk, t);
                gl.use();
                gl.setSamplerTexIdUniform("uTexSampler", inputTexId, 0);
                gl.setFloatUniform("uTime", (float) t);
                gl.setFloatUniform("uPunch", (float) (d >= 0 ? Math.exp(-d / 0.22) : 0));
                gl.setFloatsUniform("uRes", new float[]{w, h});
                gl.bindAttributesAndUniforms();
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                GlUtil.checkGlError();
            } catch (GlUtil.GlException e) {
                throw new VideoFrameProcessingException(e, presentationTimeUs);
            }
        }

        @Override public void release() throws VideoFrameProcessingException {
            super.release();
            try { gl.delete(); } catch (GlUtil.GlException e) { throw new VideoFrameProcessingException(e); }
        }
    }
}
