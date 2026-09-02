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

        /**
         * A single grain of material (change 14.3).
         *
         * An octahedron: eight flat faces, unit diameter, centred on the origin. Cheap enough to
         * draw a stream of them, and it catches the key light differently on each face as it
         * tumbles, which is what makes a granule read as a solid object rather than as a speck. The
         * previous stream drew axis-aligned boxes with no rotation, so every grain presented the
         * same flat square to the camera and the whole effect looked painted on.
         */
        fun granule(): Mesh {
            val r = 0.5f
            val top = floatArrayOf(0f, r, 0f)
            val bot = floatArrayOf(0f, -r, 0f)
            val ring = listOf(
                floatArrayOf(r, 0f, 0f),
                floatArrayOf(0f, 0f, r),
                floatArrayOf(-r, 0f, 0f),
                floatArrayOf(0f, 0f, -r),
            )
            val list = ArrayList<Float>(8 * 3 * STRIDE_FLOATS)

            fun face(a: FloatArray, b: FloatArray, c: FloatArray) {
                val ux = b[0] - a[0]; val uy = b[1] - a[1]; val uz = b[2] - a[2]
                val vx = c[0] - a[0]; val vy = c[1] - a[1]; val vz = c[2] - a[2]
                var nx = uy * vz - uz * vy
                var ny = uz * vx - ux * vz
                var nz = ux * vy - uy * vx
                val len = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
                nx /= len; ny /= len; nz /= len
                listOf(a, b, c).forEach { v ->
                    list.add(v[0]); list.add(v[1]); list.add(v[2])
                    list.add(nx); list.add(ny); list.add(nz)
                }
            }

            for (i in ring.indices) {
                val a = ring[i]
                val b = ring[(i + 1) % ring.size]
                face(top, a, b)
                face(bot, b, a)
            }
            return upload(list.toFloatArray())
        }

        /**
         * A cone section: axis along +Y, unit height, [bottomRadius] at the base tapering to
         * [topRadius] at the top, both as fractions of the unit diameter like [cylinder].
         *
         * Used for the tapered pail body and for the heap of powder inside it (change 14.3/14.4) —
         * a real bucket is not a tube, and powder mounds rather than sitting flat like a piston.
         */
        fun frustum(topRadius: Float = 0.35f, bottomRadius: Float = 0.5f, segments: Int = 24): Mesh {
            val list = ArrayList<Float>((segments * 12) * STRIDE_FLOATS)
            val top = 0.5f
            val bot = -0.5f
            // Slope of the side wall, so the normals tilt with the taper instead of pointing
            // straight out — otherwise the cone lights like a cylinder and the taper disappears.
            val slope = (bottomRadius - topRadius)
            val nY = slope / kotlin.math.sqrt(slope * slope + 1f)
            val nR = 1f / kotlin.math.sqrt(slope * slope + 1f)

            fun push(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float) {
                list.add(x); list.add(y); list.add(z); list.add(nx); list.add(ny); list.add(nz)
            }

            for (s0 in 0 until segments) {
                val a0 = (s0.toFloat() / segments) * 2f * Math.PI.toFloat()
                val a1 = ((s0 + 1).toFloat() / segments) * 2f * Math.PI.toFloat()
                val c0 = cos(a0); val s0r = sin(a0)
                val c1 = cos(a1); val s1r = sin(a1)

                val bx0 = c0 * bottomRadius; val bz0 = s0r * bottomRadius
                val bx1 = c1 * bottomRadius; val bz1 = s1r * bottomRadius
                val tx0 = c0 * topRadius; val tz0 = s0r * topRadius
                val tx1 = c1 * topRadius; val tz1 = s1r * topRadius

                push(bx0, bot, bz0, c0 * nR, nY, s0r * nR)
                push(bx1, bot, bz1, c1 * nR, nY, s1r * nR)
                push(tx1, top, tz1, c1 * nR, nY, s1r * nR)
                push(bx0, bot, bz0, c0 * nR, nY, s0r * nR)
                push(tx1, top, tz1, c1 * nR, nY, s1r * nR)
                push(tx0, top, tz0, c0 * nR, nY, s0r * nR)

                push(0f, top, 0f, 0f, 1f, 0f)
                push(tx0, top, tz0, 0f, 1f, 0f)
                push(tx1, top, tz1, 0f, 1f, 0f)

                push(0f, bot, 0f, 0f, -1f, 0f)
                push(bx1, bot, bz1, 0f, -1f, 0f)
                push(bx0, bot, bz0, 0f, -1f, 0f)
            }
            return upload(list.toFloatArray())
        }

        /**
         * A chamfered tapered prism, FLAT-shaded (change 44).
         *
         * [sides] main flanks, with the corner between each adjacent pair cut back by [chamfer] of
         * the edge length -- so a hexagon comes out as twelve faces: six wide flanks and six narrow
         * corner chamfers. No casting of this kind is ever left with sharp arrises, and the
         * chamfers are what stop a prism reading as a crude extrusion.
         *
         * Every face carries ONE normal, unlike [frustum], whose per-vertex radial normals smear
         * the shading across each facet. That is correct for something meant to look round and
         * exactly wrong here: a faceted body only reads as machined if the light STEPS at the
         * edges, and with a low side count the smeared version just looks like a lumpy cylinder.
         *
         * Same convention as the other primitives: axis +Y, unit height, widest radius 0.5, so the
         * existing model-matrix helpers place it -- including [modelBox], which is what gives it
         * the yaw the frustum helpers cannot supply.
         */
        fun castPrism(
            sides: Int = 6,
            chamfer: Float = 0.16f,
            topRadius: Float = 0.40f,
            bottomRadius: Float = 0.50f,
        ): Mesh {
            val list = ArrayList<Float>(sides * 2 * 12 * STRIDE_FLOATS)
            val top = 0.5f
            val bot = -0.5f
            val slope = bottomRadius - topRadius
            val invLen = 1f / kotlin.math.sqrt(slope * slope + 1f)
            val nY = slope * invLen

            fun push(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float) {
                list.add(x); list.add(y); list.add(z); list.add(nx); list.add(ny); list.add(nz)
            }

            // The chamfered cross-section at unit scale: two vertices per main edge, so the corner
            // between consecutive edges becomes a face of its own.
            val ring = ArrayList<FloatArray>(sides * 2)
            for (k in 0 until sides) {
                val a0 = (k.toFloat() / sides) * 2f * Math.PI.toFloat()
                val a1 = ((k + 1).toFloat() / sides) * 2f * Math.PI.toFloat()
                val x0 = cos(a0); val z0 = sin(a0)
                val x1 = cos(a1); val z1 = sin(a1)
                ring.add(floatArrayOf(x0 + (x1 - x0) * chamfer, z0 + (z1 - z0) * chamfer))
                ring.add(
                    floatArrayOf(x0 + (x1 - x0) * (1f - chamfer), z0 + (z1 - z0) * (1f - chamfer))
                )
            }

            val count = ring.size
            for (i in 0 until count) {
                val a = ring[i]
                val b = ring[(i + 1) % count]

                // Flat face normal: perpendicular to the face in plan, turned outward, then tilted
                // by the taper so the facet lights as the sloped surface it actually is.
                var nx = b[1] - a[1]
                var nz = -(b[0] - a[0])
                val l = kotlin.math.sqrt(nx * nx + nz * nz)
                if (l < 1e-6f) continue
                nx /= l; nz /= l
                if (nx * (a[0] + b[0]) + nz * (a[1] + b[1]) < 0f) { nx = -nx; nz = -nz }
                val fx = nx * invLen
                val fz = nz * invLen

                val bx0 = a[0] * bottomRadius; val bz0 = a[1] * bottomRadius
                val bx1 = b[0] * bottomRadius; val bz1 = b[1] * bottomRadius
                val tx0 = a[0] * topRadius; val tz0 = a[1] * topRadius
                val tx1 = b[0] * topRadius; val tz1 = b[1] * topRadius

                push(bx0, bot, bz0, fx, nY, fz)
                push(bx1, bot, bz1, fx, nY, fz)
                push(tx1, top, tz1, fx, nY, fz)
                push(bx0, bot, bz0, fx, nY, fz)
                push(tx1, top, tz1, fx, nY, fz)
                push(tx0, top, tz0, fx, nY, fz)

                push(0f, top, 0f, 0f, 1f, 0f)
                push(tx0, top, tz0, 0f, 1f, 0f)
                push(tx1, top, tz1, 0f, 1f, 0f)

                push(0f, bot, 0f, 0f, -1f, 0f)
                push(bx1, bot, bz1, 0f, -1f, 0f)
                push(bx0, bot, bz0, 0f, -1f, 0f)
            }
            return upload(list.toFloatArray())
        }

        /**
         * A helical thread ribbon for the lead screw (change 13.5).
         *
         * Same convention as [cylinder]: axis along +Y, unit height, outer radius 0.5, so it can be
         * placed with the existing model-matrix helpers and scaled by the screw diameter. The ribbon
         * runs from [innerRadius] out to the outer radius and climbs by one pitch per turn, which is
         * what gives the screw a readable direction of rotation — rotating the mesh about its own
         * axis then reads as the thread advancing along the rail.
         *
         * Emitted double-sided with opposing normals: the renderer leaves face culling off, and a
         * single-sided ribbon would light incorrectly from one side.
         */
        fun screwThread(turns: Int = 26, segmentsPerTurn: Int = 14, innerRadius: Float = 0.30f): Mesh {
            val steps = turns * segmentsPerTurn
            val list = ArrayList<Float>(steps * 12 * STRIDE_FLOATS)
            val outer = 0.5f
            val pitch = 1f / turns

            fun push(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float) {
                list.add(x); list.add(y); list.add(z); list.add(nx); list.add(ny); list.add(nz)
            }

            fun inner(step: Int): FloatArray {
                val t = step.toFloat() / steps
                val a = t * turns * 2f * Math.PI.toFloat()
                return floatArrayOf(cos(a) * innerRadius, t - 0.5f, sin(a) * innerRadius)
            }

            fun outerAt(step: Int): FloatArray {
                val t = step.toFloat() / steps
                val a = t * turns * 2f * Math.PI.toFloat()
                return floatArrayOf(cos(a) * outer, t - 0.5f + pitch * 0.35f, sin(a) * outer)
            }

            for (s0 in 0 until steps) {
                val i0 = inner(s0); val o0 = outerAt(s0)
                val i1 = inner(s0 + 1); val o1 = outerAt(s0 + 1)
                // Face normal from the two spanning edges of this quad.
                val ex = floatArrayOf(o0[0] - i0[0], o0[1] - i0[1], o0[2] - i0[2])
                val ey = floatArrayOf(i1[0] - i0[0], i1[1] - i0[1], i1[2] - i0[2])
                var nx = ex[1] * ey[2] - ex[2] * ey[1]
                var ny = ex[2] * ey[0] - ex[0] * ey[2]
                var nz = ex[0] * ey[1] - ex[1] * ey[0]
                val len = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
                nx /= len; ny /= len; nz /= len

                listOf(1f, -1f).forEach { side ->
                    val sx = nx * side; val sy = ny * side; val sz = nz * side
                    if (side > 0f) {
                        push(i0[0], i0[1], i0[2], sx, sy, sz)
                        push(o0[0], o0[1], o0[2], sx, sy, sz)
                        push(o1[0], o1[1], o1[2], sx, sy, sz)
                        push(i0[0], i0[1], i0[2], sx, sy, sz)
                        push(o1[0], o1[1], o1[2], sx, sy, sz)
                        push(i1[0], i1[1], i1[2], sx, sy, sz)
                    } else {
                        push(i0[0], i0[1], i0[2], sx, sy, sz)
                        push(o1[0], o1[1], o1[2], sx, sy, sz)
                        push(o0[0], o0[1], o0[2], sx, sy, sz)
                        push(i0[0], i0[1], i0[2], sx, sy, sz)
                        push(i1[0], i1[1], i1[2], sx, sy, sz)
                        push(o1[0], o1[1], o1[2], sx, sy, sz)
                    }
                }
            }
            return upload(list.toFloatArray())
        }
    }
}
