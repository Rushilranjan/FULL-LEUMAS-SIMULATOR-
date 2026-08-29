package com.leumas.robot.render3d

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.sin

/**
 * A static, immutable triangle mesh uploaded once to a VBO.
 *
 * Vertices are interleaved position(3) + normal(3). All the robot's parts are built from two
 * primitives — a unit box and a unit cylinder — instanced with per-part model matrices, so the
 * whole machine costs a handful of small draw calls.
 */
class Mesh private constructor(private val vbo: Int, private val vertexCount: Int) {

    fun draw(program: GlProgram) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        val stride = STRIDE_FLOATS * 4
        GLES30.glEnableVertexAttribArray(program.positionAttrib)
        GLES30.glVertexAttribPointer(program.positionAttrib, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(program.normalAttrib)
        GLES30.glVertexAttribPointer(program.normalAttrib, 3, GLES30.GL_FLOAT, false, stride, 3 * 4)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, vertexCount)
    }

    companion object {
        private const val STRIDE_FLOATS = 6

        private fun upload(data: FloatArray): Mesh {
            val buffer: FloatBuffer = ByteBuffer
                .allocateDirect(data.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(data)
            buffer.position(0)
            val ids = IntArray(1)
            GLES30.glGenBuffers(1, ids, 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[0])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, buffer, GLES30.GL_STATIC_DRAW)
            return Mesh(ids[0], data.size / STRIDE_FLOATS)
        }

        /** Unit cube centred on the origin, edge length 1, with per-face normals. */
        fun box(): Mesh {
            val v = FloatArray(36 * STRIDE_FLOATS)
            var i = 0
            // face: 4 corners (a,b,c,d) wound ccw, normal n
            fun quad(
                ax: Float, ay: Float, az: Float,
                bx: Float, by: Float, bz: Float,
                cx: Float, cy: Float, cz: Float,
                dx: Float, dy: Float, dz: Float,
                nx: Float, ny: Float, nz: Float,
            ) {
                fun vert(x: Float, y: Float, z: Float) {
                    v[i++] = x; v[i++] = y; v[i++] = z; v[i++] = nx; v[i++] = ny; v[i++] = nz
                }
                vert(ax, ay, az); vert(bx, by, bz); vert(cx, cy, cz)
                vert(ax, ay, az); vert(cx, cy, cz); vert(dx, dy, dz)
            }
            val h = 0.5f
            quad(-h, -h, h, h, -h, h, h, h, h, -h, h, h, 0f, 0f, 1f)      // front +z
            quad(h, -h, -h, -h, -h, -h, -h, h, -h, h, h, -h, 0f, 0f, -1f)  // back -z
            quad(h, -h, h, h, -h, -h, h, h, -h, h, h, h, 1f, 0f, 0f)       // right +x
            quad(-h, -h, -h, -h, -h, h, -h, h, h, -h, h, -h, -1f, 0f, 0f)  // left -x
            quad(-h, h, h, h, h, h, h, h, -h, -h, h, -h, 0f, 1f, 0f)       // top +y
            quad(-h, -h, -h, h, -h, -h, h, -h, h, -h, -h, h, 0f, -1f, 0f)  // bottom -y
            return upload(v)
        }

        /**
         * Unit cylinder: radius 0.5, height 1, axis along +Y, centred on the origin. [segments]
         * radial slices. Side normals point outward; caps are flat.
         */
        fun cylinder(segments: Int = 24): Mesh {
            val list = ArrayList<Float>((segments * 12) * STRIDE_FLOATS)
            val r = 0.5f
            val top = 0.5f
            val bot = -0.5f
            fun push(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float) {
                list.add(x); list.add(y); list.add(z); list.add(nx); list.add(ny); list.add(nz)
            }
            for (s in 0 until segments) {
                val a0 = (s.toFloat() / segments) * 2f * Math.PI.toFloat()
                val a1 = ((s + 1).toFloat() / segments) * 2f * Math.PI.toFloat()
                val x0 = cos(a0) * r; val z0 = sin(a0) * r
                val x1 = cos(a1) * r; val z1 = sin(a1) * r
                // Side (two triangles), smooth outward normals.
                push(x0, bot, z0, cos(a0), 0f, sin(a0))
                push(x1, bot, z1, cos(a1), 0f, sin(a1))
                push(x1, top, z1, cos(a1), 0f, sin(a1))
                push(x0, bot, z0, cos(a0), 0f, sin(a0))
                push(x1, top, z1, cos(a1), 0f, sin(a1))
                push(x0, top, z0, cos(a0), 0f, sin(a0))
                // Top cap.
                push(0f, top, 0f, 0f, 1f, 0f)
                push(x0, top, z0, 0f, 1f, 0f)
                push(x1, top, z1, 0f, 1f, 0f)
                // Bottom cap.
                push(0f, bot, 0f, 0f, -1f, 0f)
                push(x1, bot, z1, 0f, -1f, 0f)
                push(x0, bot, z0, 0f, -1f, 0f)
            }
            return upload(list.toFloatArray())
        }
    }
}
