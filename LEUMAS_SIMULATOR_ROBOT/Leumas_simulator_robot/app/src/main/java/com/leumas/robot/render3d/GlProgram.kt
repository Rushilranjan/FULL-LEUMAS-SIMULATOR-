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
    }

    fun use() = GLES30.glUseProgram(handle)

    val positionAttrib: Int get() = aPos
    val normalAttrib: Int get() = aNormal

    /** Scene-wide uniforms: set once per frame. */
    fun setCamera(viewProj: FloatArray, viewPos: FloatArray, lightDir: FloatArray) {
        GLES30.glUniform3fv(uViewPos, 1, viewPos, 0)
        GLES30.glUniform3fv(uLightDir, 1, lightDir, 0)
        GLES30.glUniform1f(uAmbient, 0.30f)
        pendingViewProj = viewProj
    }

    private var pendingViewProj: FloatArray = identity()
    private val mvp = FloatArray(16)
    private val normalMat = FloatArray(16)

    /** Per-object uniforms, then the caller issues the draw. */
    fun setModel(model: FloatArray, r: Float, g: Float, b: Float, specular: Float = 0.25f, shininess: Float = 24f) {
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
            out vec4 fragColor;
            void main() {
                vec3 n = normalize(vNormal);
                vec3 l = normalize(-uLightDir);
                float diff = max(dot(n, l), 0.0);
                vec3 v = normalize(uViewPos - vWorldPos);
                vec3 h = normalize(l + v);
                float spec = pow(max(dot(n, h), 0.0), uShininess) * uSpecular;
                // A soft fill from the opposite side keeps shadowed faces from going pure black.
                float fill = max(dot(n, normalize(vec3(0.3, 0.2, 1.0))), 0.0) * 0.18;
                vec3 color = uColor * (uAmbient + diff * 0.85 + fill) + vec3(spec);
                fragColor = vec4(color, 1.0);
            }
        """
    }
}
