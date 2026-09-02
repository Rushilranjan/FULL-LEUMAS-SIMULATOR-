package com.leumas.robot.render3d

import android.opengl.GLES30
import android.opengl.Matrix

/**
 * A minimal Blinn-Phong shader program for the 3-D robot scene.
 *
 * One directional key light plus ambient and a small specular term is enough to make the procedural
 * metal and painted-steel parts read as solid volumes rather than flat silhouettes. Everything is
 * flat-colour per draw call (no textures), which keeps the pipeline dependency-free and fast.
 *
 * Post-demo change 10.3: the 2.5-D vector artist is replaced by this real 3-D renderer so the arm
 * genuinely rotates at the base and bends at the shoulder/elbow/wrist.
 */
class GlProgram {

    private val handle: Int
    private val aPos: Int
    private val aNormal: Int
    private val uMvp: Int
    private val uModel: Int
    private val uNormalMat: Int
    private val uColor: Int
    private val uLightDir: Int
    private val uViewPos: Int
    private val uAmbient: Int
    private val uSpecular: Int
    private val uShininess: Int
    private val uAlpha: Int
    private val uRim: Int

    init {
        val vs = compile(GLES30.GL_VERTEX_SHADER, VERTEX_SRC)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SRC)
        handle = GLES30.glCreateProgram()
        GLES30.glAttachShader(handle, vs)
        GLES30.glAttachShader(handle, fs)
        GLES30.glLinkProgram(handle)
        val linked = IntArray(1)
        GLES30.glGetProgramiv(handle, GLES30.GL_LINK_STATUS, linked, 0)
        check(linked[0] != 0) { "program link failed: " + GLES30.glGetProgramInfoLog(handle) }
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)

        aPos = GLES30.glGetAttribLocation(handle, "aPos")
        aNormal = GLES30.glGetAttribLocation(handle, "aNormal")
        uMvp = GLES30.glGetUniformLocation(handle, "uMvp")
        uModel = GLES30.glGetUniformLocation(handle, "uModel")
        uNormalMat = GLES30.glGetUniformLocation(handle, "uNormalMat")
        uColor = GLES30.glGetUniformLocation(handle, "uColor")
        uLightDir = GLES30.glGetUniformLocation(handle, "uLightDir")
        uViewPos = GLES30.glGetUniformLocation(handle, "uViewPos")
        uAmbient = GLES30.glGetUniformLocation(handle, "uAmbient")
        uSpecular = GLES30.glGetUniformLocation(handle, "uSpecular")
        uShininess = GLES30.glGetUniformLocation(handle, "uShininess")
        uAlpha = GLES30.glGetUniformLocation(handle, "uAlpha")
        uRim = GLES30.glGetUniformLocation(handle, "uRim")
    }

    fun use() = GLES30.glUseProgram(handle)

    val positionAttrib: Int get() = aPos
    val normalAttrib: Int get() = aNormal

    /** Scene-wide uniforms: set once per frame. */
    fun setCamera(viewProj: FloatArray, viewPos: FloatArray, lightDir: FloatArray) {
        GLES30.glUniform3fv(uViewPos, 1, viewPos, 0)
        GLES30.glUniform3fv(uLightDir, 1, lightDir, 0)
        // Change 13.1: the studio backdrop is light, so the scene needs a correspondingly
        // brighter ambient or the machine reads as a silhouette cut out of the wall.
        GLES30.glUniform1f(uAmbient, 0.46f)
        pendingViewProj = viewProj
    }

    private var pendingViewProj: FloatArray = identity()
    private val mvp = FloatArray(16)
    private val normalMat = FloatArray(16)

    /** Per-object uniforms, then the caller issues the draw. */
    fun setModel(
        model: FloatArray,
        r: Float,
        g: Float,
        b: Float,
        specular: Float = 0.25f,
        shininess: Float = 24f,
        alpha: Float = 1f,
        rim: Float = 0f,
    ) {
        Matrix.multiplyMM(mvp, 0, pendingViewProj, 0, model, 0)
        // Normal matrix = inverse-transpose of model. For rigid + uniform scale, the model matrix's
        // rotation part suffices; we invert-transpose to stay correct under the small non-uniform
        // scales used for stretched limbs.
        Matrix.invertM(normalMat, 0, model, 0)
        transposeInPlace(normalMat)
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES30.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES30.glUniformMatrix4fv(uNormalMat, 1, false, normalMat, 0)
        GLES30.glUniform3f(uColor, r, g, b)
        GLES30.glUniform1f(uSpecular, specular)
        GLES30.glUniform1f(uShininess, shininess)
        GLES30.glUniform1f(uAlpha, alpha)
        GLES30.glUniform1f(uRim, rim)
    }

    private fun transposeInPlace(m: FloatArray) {
        for (i in 0..3) for (j in i + 1..3) {
            val a = i * 4 + j
            val b = j * 4 + i
            val t = m[a]; m[a] = m[b]; m[b] = t
        }
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "shader compile failed: " + GLES30.glGetShaderInfoLog(s) }
        return s
    }

    companion object {
        fun identity(): FloatArray = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

        private const val VERTEX_SRC = """#version 300 es
            in vec3 aPos;
            in vec3 aNormal;
            uniform mat4 uMvp;
            uniform mat4 uModel;
            uniform mat4 uNormalMat;
            out vec3 vWorldPos;
            out vec3 vNormal;
            void main() {
                vec4 world = uModel * vec4(aPos, 1.0);
                vWorldPos = world.xyz;
                vNormal = mat3(uNormalMat) * aNormal;
                gl_Position = uMvp * vec4(aPos, 1.0);
            }
        """

        private const val FRAGMENT_SRC = """#version 300 es
            precision mediump float;
            in vec3 vWorldPos;
            in vec3 vNormal;
            uniform vec3 uColor;
            uniform vec3 uLightDir;   // direction the light travels
            uniform vec3 uViewPos;
            uniform float uAmbient;
            uniform float uSpecular;
            uniform float uShininess;
            uniform float uAlpha;   // change 13.8: translucent liquid
            uniform float uRim;     // change 14.4: Fresnel sheen for metal
            out vec4 fragColor;

            // Change 39. The machine was not reading as three-dimensional and the geometry was
            // never the reason -- the LIGHTING was flat, in one specific and fixable way.
            //
            // Ambient was a CONSTANT. Every surface facing away from the key light received
            // exactly the same 0.46 regardless of which way it pointed, so the whole shadow side
            // of every curved part was one uniform tone. That is the definition of flat: half of
            // every cylinder carried no shape information at all. Real ambient arrives from an
            // environment and therefore varies with the normal -- bright from the sky above,
            // darker and warmer from the floor bounce below.
            //
            // Replacing the constant with a hemisphere costs two mixes and gives every surface a
            // vertical gradient it did not have. It is the single biggest change here.
            //
            // The average is deliberately held near the old 0.46 so exposure does not shift; what
            // changes is that it now VARIES. And the key is warm while the fill is cool, which is
            // how any studio lights a product: the colour difference between lit and unlit sides
            // separates form far more strongly than a brightness difference alone.
            const vec3 SKY_AMBIENT    = vec3(0.55, 0.60, 0.70);
            const vec3 GROUND_AMBIENT = vec3(0.31, 0.29, 0.27);
            const vec3 KEY_COLOUR     = vec3(1.00, 0.97, 0.92);
            const vec3 FILL_COLOUR    = vec3(0.40, 0.50, 0.66);
            const vec3 FILL_DIR       = vec3(0.55, 0.35, 0.75);

            void main() {
                vec3 n = normalize(vNormal);
                vec3 l = normalize(-uLightDir);
                vec3 v = normalize(uViewPos - vWorldPos);
                vec3 h = normalize(l + v);

                // Hemisphere ambient, scaled so the mean matches the old flat term.
                vec3 ambient = mix(GROUND_AMBIENT, SKY_AMBIENT, n.y * 0.5 + 0.5)
                             * (uAmbient / 0.46);

                float diff = max(dot(n, l), 0.0);
                vec3 key = KEY_COLOUR * diff * 0.82;

                float fillD = max(dot(n, normalize(FILL_DIR)), 0.0);
                vec3 fill = FILL_COLOUR * fillD * 0.30;

                vec3 color = uColor * (ambient + key + fill);

                // Specular weighted by Schlick's Fresnel rather than applied flat. A real highlight
                // strengthens toward grazing angles, which is what makes a curved surface look
                // lacquered instead of dusted with white.
                float f0 = 0.04 + uSpecular * 0.30;
                float fres = f0 + (1.0 - f0) * pow(1.0 - max(dot(h, v), 0.0), 5.0);
                float spec = pow(max(dot(n, h), 0.0), uShininess) * fres * uSpecular * 2.6;
                color += KEY_COLOUR * spec;

                // Change 14.4: a Fresnel rim term. Grazing angles brighten, which is what makes a
                // surface read as polished metal rather than as flat paint with a highlight on it.
                // uRim is 0 for every part that does not opt in, so nothing else changes.
                float rim = pow(1.0 - max(dot(n, v), 0.0), 3.0);
                color += vec3(0.62, 0.68, 0.78) * rim * uRim;

                fragColor = vec4(color, uAlpha);
            }
        """
    }
}
