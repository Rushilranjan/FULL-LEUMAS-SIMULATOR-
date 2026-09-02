package com.leumas.robot.render3d

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.leumas.robot.render.Kinematics
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Renders the articulated 4-axis palletiser in real 3-D (post-demo change 10.3).
 *
 * The scene is procedural: every part is an instance of a unit box, cylinder or screw thread placed
 * with a model matrix, so there are no model assets and no extra dependencies. Joint positions come
 * from the IK solve in [Robot3DPose], lifted into world space by the base yaw and carriage position,
 * so the shoulder and elbow bend and the turret rotates as genuine 3-D motion.
 *
 * The renderer is a pure consumer: [poseProvider] hands it the latest pose each frame (computed on
 * the UI/frame thread from the shared animation state). It never drives or predicts anything. The
 * only state it owns is a wall clock, used purely to animate decorative material particles.
 */
class SceneRenderer(
    private val poseProvider: () -> Robot3DPose?,
) : GLSurfaceView.Renderer {

    private lateinit var program: GlProgram
    private lateinit var box: Mesh
    private lateinit var cyl: Mesh
    private lateinit var thread: Mesh
    private lateinit var grain: Mesh
    private lateinit var pail: Mesh
    private lateinit var heap: Mesh
    private lateinit var hopper: Mesh
    private lateinit var limb: Mesh
    private lateinit var column: Mesh
    private lateinit var turretBody: Mesh
    private lateinit var turretRing: Mesh

    private var aspect: Float = 1.7f
    private val startNanos = System.nanoTime()

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val model = FloatArray(16)
    private val scratch = FloatArray(16)

    private val eye = floatArrayOf(0.0f, 1.35f, 3.1f)
    private val lightDir = normalize(floatArrayOf(-0.35f, -1.0f, -0.45f))

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Change 14.1: the backdrop is now a single flat tone and the clear colour *is* the whole
        // backdrop — there is no wall or floor geometry left to draw.
        GLES30.glClearColor(BACKDROP[0], BACKDROP[1], BACKDROP[2], 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        // Change 13.8: the liquid in the bucket and the pour stream are translucent.
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        // Culling is left off: the procedural meshes aren't guaranteed a consistent winding, and the
        // scene is tiny, so depth testing alone keeps it correct without risking dropped faces.
        program = GlProgram()
        box = Mesh.box()
        // Change 38: every curved surface is built at more than twice the resolution it was.
        // At 28 segments a cylinder's silhouette is a visible polygon at this camera distance, and
        // no amount of shading hides a faceted edge. These meshes are a few hundred triangles each
        // and there are around fifty draws in a frame, so the cost is nothing on this hardware and
        // the whole machine stops looking chamfered.
        cyl = Mesh.cylinder(64)
        thread = Mesh.screwThread(turns = SCREW_TURNS)
        grain = Mesh.granule()
        // A real pail tapers in toward the base; the heap is a shallow mound of powder.
        pail = Mesh.frustum(topRadius = 0.50f, bottomRadius = 0.38f, segments = 56)
        // A gently tapered tube for the arm members: real arms are not parallel-sided, and the
        // taper is most of what makes a limb read as cast rather than as pipe.
        limb = Mesh.frustum(topRadius = 0.50f, bottomRadius = 0.42f, segments = 64)
        // A strong taper for the pedestal column (change 42). Used both ways up:
        // narrow-end-first for the column, wide-end-first for the flare under the bearing.
        column = Mesh.frustum(topRadius = 0.50f, bottomRadius = 0.64f, segments = 96)
        // The turret casting (change 44): a hexagon with its corners chamfered off, tapered for
        // the body and straight for the two machined collars.
        turretBody = Mesh.castPrism(sides = 6, chamfer = 0.16f, topRadius = 0.36f, bottomRadius = 0.50f)
        turretRing = Mesh.castPrism(sides = 6, chamfer = 0.16f, topRadius = 0.50f, bottomRadius = 0.50f)
        heap = Mesh.frustum(topRadius = 0.18f, bottomRadius = 0.50f, segments = 44)
        // Hopper cone: wide at the top, tapering to the dispenser inlet (change 15.1).
        hopper = Mesh.frustum(topRadius = 0.50f, bottomRadius = 0.15f, segments = 56)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        aspect = if (height == 0) 1.7f else width.toFloat() / height.toFloat()
        perspective(proj, 42f, aspect, 0.05f, 30f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        Matrix.setLookAtM(view, 0, eye[0], eye[1], eye[2], 0f, 0.55f, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)

        program.use()
        program.setCamera(viewProj, eye, lightDir)

        val timeS = (System.nanoTime() - startNanos) / 1_000_000_000f

        drawContactShadow()

        val pose = poseProvider() ?: Robot3DPose.idle()
        drawRail(pose.carriageX)
        drawRobot(pose, timeS)
    }

    // -- Ground shadow ---------------------------------------------------------------------------

    /**
     * A soft contact shadow under the machine (change 14.1).
     *
     * There is no floor any more. The wall, floor slab and near apron were three big boxes that left
     * a hard horizon seam across the arena, and the floor was opaque and reached past the camera —
     * which is what the arm was disappearing behind whenever it served a bottom-edge module
     * (change 14.2). Deleting them made the field perfectly even and fixed the occlusion in one go.
     *
     * All that replaces them is this: a few concentric flattened discs, fading outward, so the
     * machine sits on the grey instead of floating on it. Drawn first, translucent, with depth
     * writes off, so it can never occlude anything drawn afterwards — including an arm reaching down
     * in front of it.
     */
    private fun drawContactShadow() {
        GLES30.glDepthMask(false)
        // Drawn largest-first with a low alpha each, so the discs accumulate into a smooth gradient.
        // Few, darker discs produce visible concentric steps — it read as a target painted on the
        // floor on the first device run.
        for (i in SHADOW_RINGS - 1 downTo 0) {
            val t = i.toFloat() / (SHADOW_RINGS - 1)
            val radius = SHADOW_INNER + (SHADOW_OUTER - SHADOW_INNER) * t
            val alpha = SHADOW_ALPHA * (1f - t)
            // A very flat cylinder is a disc, squashed hard in Z so the shadow hugs the rail rather
            // than spreading as a big circle on a floor that no longer exists.
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, 0f, SHADOW_Y, SHADOW_Z)
            Matrix.scaleM(model, 0, radius * 2f, 0.001f, radius * SHADOW_SQUASH)
            program.setModel(model, 0.42f, 0.45f, 0.49f, specular = 0f, shininess = 4f, alpha = alpha)
            cyl.draw(program)
        }
        GLES30.glDepthMask(true)
    }

    // -- Rail ------------------------------------------------------------------------------------

    /**
     * The screw-driven linear axis (change 13.5).
     *
     * Built as a real mechanism rather than a bar with a cylinder on it: a T-slot extrusion, two
     * round guide shafts, a ball screw with an actual helical thread, pillow blocks at both ends, a
     * ball-nut on the carriage and a cable drag chain. The thread mesh is rotated about its own axis
     * in proportion to carriage travel, so the screw visibly *drives* the carriage — turn one way
     * and the machine runs right, the other way and it runs left.
     */
    private fun drawRail(carriageX: Float) {
        val span = Arena3D.RAIL_HALF * 2f + 0.5f
        val half = span / 2f

        // ---- THE RAIL (change 47) ------------------------------------------------------------
        //
        // The axis was finished the way the base was before change 43: specular 0.12 to 0.75 at
        // shininess 14 to 60, against a robot running 1.0 at 128. Under the Fresnel-weighted
        // highlight that gap is enormous -- the machine gathers a moving highlight and reads as
        // machined, the rail under it gathered almost none and read as painted card. It is the
        // largest object in the frame and it was the flattest, so it dragged the whole image down
        // however well the robot was built.
        //
        // Everything on the axis is now finished on the machine's own scale, in the same graphite
        // and steel families, with the ground parts -- shafts, screw, thread -- taken up to chrome
        // because that is what they are: hardened and polished, the shiniest things on any real
        // linear axis.

        // Extrusion body, with two inset side grooves to read as a T-slot profile.
        modelBox(0f, -0.005f, 0f, span, 0.10f, 0.30f)
        program.setModel(model, 0.30f, 0.32f, 0.36f, specular = 1f, shininess = 112f, rim = 0.45f)
        box.draw(program)
        listOf(-1f, 1f).forEach { s ->
            modelBox(0f, -0.005f, s * 0.152f, span * 0.995f, 0.036f, 0.02f)
            program.setModel(model, JOINT[0], JOINT[1], JOINT[2],
                             specular = 1f, shininess = 126f, rim = 0.4f)
            box.draw(program)
        }

        // A capping strip along the top edge of each flank, and a line of hold-down bolts down the
        // bed. An extrusion this long with no repeating feature has nothing to give the eye a
        // sense of its length -- the bolts are what make it read as a run of machine bed rather
        // than as one stretched box.
        listOf(-1f, 1f).forEach { s ->
            modelBox(0f, 0.043f, s * 0.140f, span * 0.998f, 0.014f, 0.026f)
            program.setModel(model, MACHINED[0], MACHINED[1], MACHINED[2],
                             specular = 1f, shininess = 122f, rim = 0.5f)
            box.draw(program)
        }
        run {
            val pitch = span / RAIL_BOLTS
            for (i in 0 until RAIL_BOLTS) {
                val x = -half + pitch * (i + 0.5f)
                listOf(-1f, 1f).forEach { s ->
                    cylinderBetween(x, 0.049f, s * 0.140f, x, 0.059f, s * 0.140f, radius = 0.009f)
                    program.setModel(model, 0.52f, 0.55f, 0.59f,
                                     specular = 1f, shininess = 120f, rim = 0.5f)
                    cyl.draw(program)
                }
            }
        }

        // Round guide shafts either side of the screw.
        listOf(-1f, 1f).forEach { s ->
            cylinderBetween(-half, 0.075f, s * 0.105f, half, 0.075f, s * 0.105f, radius = 0.026f)
            program.setModel(model, 0.68f, 0.71f, 0.76f, specular = 1f, shininess = 142f, rim = 0.45f)
            cyl.draw(program)
        }

        // Ball screw: a polished core plus the helical thread riding on it. The thread's spin is a
        // pure function of carriage position, so it can never drift out of step with the motion.
        cylinderBetween(-half, 0.075f, 0f, half, 0.075f, 0f, radius = 0.021f)
        program.setModel(model, 0.60f, 0.63f, 0.68f, specular = 1f, shininess = 138f, rim = 0.4f)
        cyl.draw(program)

        // One revolution must advance the carriage by exactly one thread pitch, or the thread
        // visibly creeps against the motion instead of driving it.
        val lead = span / SCREW_TURNS
        val spinDeg = carriageX / lead * 360f
        screwModel(0f, 0.075f, 0f, span, radius = 0.036f, spinDeg = spinDeg)
        program.setModel(model, 0.64f, 0.67f, 0.72f, specular = 1f, shininess = 136f, rim = 0.4f)
        thread.draw(program)

        // Pillow blocks / screw supports at both ends.
        listOf(-1f, 1f).forEach { s ->
            modelBox(s * half, 0.09f, 0f, 0.11f, 0.26f, 0.34f)
            program.setModel(model, 0.26f, 0.28f, 0.32f, specular = 1f, shininess = 118f, rim = 0.45f)
            box.draw(program)
            // Bearing housing, and a machined cap over the screw end.
            cylinderBetween(
                s * half - 0.035f, 0.075f, 0f, s * half + 0.035f, 0.075f, 0f, radius = 0.055f,
            )
            program.setModel(model, JOINT[0], JOINT[1], JOINT[2],
                             specular = 1f, shininess = 132f, rim = 0.5f)
            cyl.draw(program)
            cylinderBetween(
                s * (half + 0.030f), 0.075f, 0f, s * (half + 0.044f), 0.075f, 0f, radius = 0.038f,
            )
            program.setModel(model, MACHINED[0], MACHINED[1], MACHINED[2],
                             specular = 1f, shininess = 124f, rim = 0.5f)
            cyl.draw(program)
        }

        drawCableChain(carriageX, half)

        // Carriage riding the rail.
        modelBox(carriageX, 0.055f, 0f, 0.34f, 0.13f, 0.32f)
        program.setModel(model, 0.24f, 0.26f, 0.30f, specular = 1f, shininess = 120f, rim = 0.45f)
        box.draw(program)
        // Ball-nut wrapped around the screw.
        cylinderBetween(
            carriageX - 0.075f, 0.075f, 0f, carriageX + 0.075f, 0.075f, 0f, radius = 0.052f,
        )
        program.setModel(model, JOINT[0], JOINT[1], JOINT[2],
                         specular = 1f, shininess = 134f, rim = 0.5f)
        cyl.draw(program)
        // Linear bearings on the guide shafts.
        listOf(-1f, 1f).forEach { s ->
            cylinderBetween(
                carriageX - 0.06f, 0.075f, s * 0.105f, carriageX + 0.06f, 0.075f, s * 0.105f,
                radius = 0.042f,
            )
            program.setModel(model, MACHINED[0], MACHINED[1], MACHINED[2],
                             specular = 1f, shininess = 128f, rim = 0.5f)
            cyl.draw(program)
        }
        // Mounting plate on top of the carriage.
        modelBox(carriageX, 0.128f, 0f, 0.40f, 0.03f, 0.36f)
        program.setModel(model, 0.20f, 0.22f, 0.25f, specular = 1f, shininess = 124f, rim = 0.5f)
        box.draw(program)
    }

    /** Energy chain trailing the carriage back to the left-hand end of the axis. */
    private fun drawCableChain(carriageX: Float, half: Float) {
        val anchorX = -half + 0.06f
        val z = -0.20f
        for (i in 0 until CHAIN_LINKS) {
            val t = i.toFloat() / (CHAIN_LINKS - 1)
            val x = anchorX + (carriageX - anchorX) * t
            // Slight sag toward the middle of the run.
            val sag = 0.018f * sin(Math.PI.toFloat() * t)
            modelBox(x, 0.045f - sag, z, 0.05f, 0.035f, 0.055f)
            program.setModel(model, JOINT[0], JOINT[1], JOINT[2],
                             specular = 1f, shininess = 118f, rim = 0.4f)
            box.draw(program)
        }
    }

    // -- Robot -----------------------------------------------------------------------------------

    private fun drawRobot(pose: Robot3DPose, timeS: Float) {
        val cx = pose.carriageX
        val yawDeg = Math.toDegrees(pose.baseYaw.toDouble()).toFloat()

        // ---- THE PEDESTAL (change 42) -------------------------------------------------------
        //
        // It was two boxes and a white disc, and two of the three were measurably wrong:
        //
        //   * the carriage top is y=0.120 and the lower box started at y=0.140, so the whole
        //     pedestal -- and the robot standing on it -- was FLOATING 0.020 above the carriage
        //     it is supposed to ride;
        //   * the slewing ring was 0.300 across on a column 0.26 x 0.24, so it overhung its own
        //     support by 0.020 in x and 0.030 in z, with nothing at all beneath the rim.
        //
        // Rebuilt bottom-up as a machine base: a bolted footplate that overlaps the carriage, a
        // plinth, a cast column that tapers in and flares back out into the bearing housing,
        // gussets, a junction box feeding a conduit into the column, and a slewing bearing built
        // as an outer race, a bolt circle and an inner race instead of one white cylinder.
        //
        // Nothing here touches SHOULDER_Y or any calibrated coordinate: the whole assembly lives
        // below y=0.45, which is where the turret casting starts.

        // Footplate, overlapping the carriage top by 0.010 so there is no daylight under it.
        // Change 43 refinished the whole pedestal. The geometry was right but the MATERIALS
        // were left over from when this was two grey boxes: specular 0.15-0.6 and shininess 14-64,
        // against an arm running specular 1.0 at shininess 128. Under the Fresnel-weighted
        // highlight in the shader that difference is enormous -- the arm's surfaces gather a
        // moving highlight and read as machined, while the base gathered almost none and read as
        // matte plastic no matter how well it was modelled. Every part below is now finished on
        // the same scale as the arm, which is all "make the base match" actually required.
        modelBox(cx, 0.132f, 0f, 0.380f, 0.044f, 0.360f)
        program.setModel(model, 0.125f, 0.133f, 0.152f, specular = 1f, shininess = 74f, rim = 0.35f)
        box.draw(program)

        // Chamfer along the footplate's top edge. A cast plate is never a sharp-cornered slab, and
        // the extra edge gives the light something to catch along the whole perimeter.
        modelBox(cx, 0.157f, 0f, 0.352f, 0.014f, 0.332f)
        program.setModel(model, 0.152f, 0.161f, 0.184f, specular = 1f, shininess = 120f, rim = 0.4f)
        box.draw(program)

        // Anchor bolts at the four corners: washer, then head. The single clearest signal that a
        // machine is bolted down rather than resting on the floor.
        listOf(-1f, 1f).forEach { sx ->
            listOf(-1f, 1f).forEach { sz ->
                val bx = cx + 0.158f * sx
                val bz = 0.148f * sz
                cylinderBetween(bx, 0.153f, bz, bx, 0.162f, bz, radius = 0.024f)
                program.setModel(model, 0.30f, 0.32f, 0.35f, specular = 0.6f, shininess = 48f)
                cyl.draw(program)
                cylinderBetween(bx, 0.162f, bz, bx, 0.177f, bz, radius = 0.015f)
                program.setModel(model, 0.54f, 0.56f, 0.60f, specular = 0.9f, shininess = 84f, rim = 0.4f)
                cyl.draw(program)
            }
        }

        // Plinth.
        modelBox(cx, 0.180f, 0f, 0.320f, 0.062f, 0.300f)
        program.setModel(model, 0.163f, 0.174f, 0.198f, specular = 0.85f, shininess = 82f, rim = 0.4f)
        box.draw(program)

        // Fillet where the column grows out of the plinth: castings are filleted at every change
        // of section, and a hard butt joint between a box and a cone is the one place this
        // assembly still looked assembled from primitives.
        cylinderBetween(cx, 0.208f, 0f, cx, 0.220f, 0f, radius = 0.158f)
        program.setModel(model, 0.155f, 0.166f, 0.190f, specular = 1f, shininess = 92f, rim = 0.4f)
        cyl.draw(program)

        // Nameplate on the plinth's front face.
        modelBox(cx, 0.182f, 0.152f, 0.100f, 0.030f, 0.006f)
        program.setModel(model, 0.62f, 0.64f, 0.68f, specular = 0.8f, shininess = 70f, rim = 0.4f)
        box.draw(program)

        // Cast column: 0.151 at the plinth, tapering to 0.118. The `column` mesh runs wide-end
        // first, so `a` is the bottom of the run here.
        frustumBetween(
            floatArrayOf(cx, 0.205f, 0f), floatArrayOf(cx, 0.360f, 0f), radius = 0.118f,
        )
        program.setModel(model, 0.180f, 0.192f, 0.219f, specular = 1f, shininess = 118f, rim = 0.45f)
        column.draw(program)

        // Gussets: two crossed fins, standing about 0.02 proud of the taper.
        listOf(45f, 135f).forEach { deg ->
            modelBox(cx, 0.268f, 0f, 0.320f, 0.110f, 0.020f, yawDeg = deg)
            program.setModel(model, 0.148f, 0.158f, 0.181f, specular = 1f, shininess = 86f, rim = 0.4f)
            box.draw(program)
        }

        // Flare back out into the bearing housing -- same mesh, wide end up this time.
        frustumBetween(
            floatArrayOf(cx, 0.400f, 0f), floatArrayOf(cx, 0.358f, 0f), radius = 0.118f,
        )
        program.setModel(model, 0.196f, 0.209f, 0.238f, specular = 1f, shininess = 118f, rim = 0.5f)
        column.draw(program)

        // Junction box on the back flank, with a conduit into the column. A powered machine has to
        // be fed from somewhere, and on a robot that somewhere is always the pedestal.
        modelBox(cx, 0.252f, -0.176f, 0.150f, 0.130f, 0.058f)
        program.setModel(model, 0.095f, 0.101f, 0.118f, specular = 1f, shininess = 78f, rim = 0.35f)
        box.draw(program)
        modelBox(cx, 0.292f, -0.207f, 0.086f, 0.010f, 0.004f)
        program.setModel(model, NEON[0], NEON[1], NEON[2],
                         specular = 1f, shininess = 128f, rim = NEON_RIM)
        box.draw(program)
        cylinderBetween(cx, 0.232f, -0.150f, cx, 0.232f, -0.096f, radius = 0.019f)
        program.setModel(model, 0.090f, 0.095f, 0.111f, specular = 0.75f, shininess = 70f, rim = 0.3f)
        cyl.draw(program)

        // ---- the slewing bearing, the A1 axis -----------------------------------------------
        //
        // Change 45 made this STEP. The hex turret flange was 0.134 across the corners standing on
        // an inner race of 0.122, and the two shared 0.007 of height -- so the hexagon's corners
        // cut clean through the race wall, and being the same size and the same grey the pair read
        // as one confused lump rather than a bearing with a turret bolted to it. The yawing bolt
        // ring made it worse: at r 0.100 under a flange spanning 0.445-0.479, every one of those
        // bolts was inside the flange and had never been visible at all.
        //
        // Each diameter now steps cleanly in from the one below, so every joint in the stack shows
        // as a shoulder of the part underneath:
        //
        //     outer race 0.152 -> inner race 0.126 -> turret flange 0.118 -> casting 0.118..0.085
        //
        // The bolt ring is gone with it. It existed to make the yaw legible, and a hexagon that
        // turns does that far better than eight studs hidden under a flange.

        // Outer race, sized to the flare beneath it so nothing overhangs unsupported.
        cylinderBetween(cx, 0.398f, 0f, cx, 0.430f, 0f, radius = 0.152f)
        program.setModel(model, JOINT[0], JOINT[1], JOINT[2],
                         specular = 1f, shininess = 134f, rim = 0.5f)
        cyl.draw(program)

        // Lit band round the housing, marking the axis.
        cylinderBetween(cx, 0.408f, 0f, cx, 0.416f, 0f, radius = 0.156f)
        program.setModel(model, NEON[0], NEON[1], NEON[2],
                         specular = 1f, shininess = 128f, rim = NEON_RIM)
        cyl.draw(program)

        // Bolt circle on the outer race's top face, in the exposed shoulder outside the inner race.
        for (i in 0 until 12) {
            val ang = i * (2f * Math.PI.toFloat() / 12f)
            val bx = cx + cos(ang) * 0.140f
            val bz = sin(ang) * 0.140f
            cylinderBetween(bx, 0.430f, bz, bx, 0.439f, bz, radius = 0.009f)
            program.setModel(model, 0.60f, 0.63f, 0.68f, specular = 1f, shininess = 118f, rim = 0.5f)
            cyl.draw(program)
        }

        // Inner race: bright machined steel, the one light-toned ring in the stack, so the black
        // bearing below it and the black flange above it both read against it.
        cylinderBetween(cx, 0.428f, 0f, cx, 0.458f, 0f, radius = 0.126f)
        program.setModel(model, STEEL[0], STEEL[1], STEEL[2],
                         specular = 1f, shininess = 120f, rim = 0.5f)
        cyl.draw(program)

        // ---- THE TURRET (change 44) ----------------------------------------------------------
        //
        // It was a 0.24 x 0.22 BOX, and a box was wrong twice over.
        //
        // As a shape, a square slab is the one thing on the machine that is not a casting: six flat
        // faces, four hard vertical arrises, no taper, and -- being square -- two of its faces
        // always present dead flat to the camera with no gradient across them at all. Nothing else
        // here is built that way.
        //
        // Worse, it BURIED THE SHOULDER. The shoulder pivot is at y=0.62 and spans +/-0.106 across
        // its axis, while the box ran 0.45..0.67 at +/-0.11-0.12 half-width -- so the entire A2
        // joint, boss, pin, housings, neon and caps, sat INSIDE the casting and was never once
        // visible. The arm appeared to grow out of a solid block. The elbow and wrist both read as
        // real pivots and the shoulder read as nothing.
        //
        // It is now a hexagonal casting with its corners chamfered, tapering as it rises, and it
        // stops BELOW the pivot -- 0.560 against a housing whose lowest point is 0.569. Two ears
        // carry on up to the shoulder and straddle it, following the same clevis contract as every
        // other axis (CLEVIS_IN..CLEVIS_OUT at LUG_R), so A2 now looks like A3 and A4 instead of
        // being swallowed. Everything below yaws with A1.

        // Machined base flange, bolted down to the inner race and sized INSIDE it -- 0.118
        // across the corners against a race of 0.126, so a shoulder of bright steel shows all the
        // way round and the two parts cannot merge into each other.
        modelBox(cx, 0.470f, 0f, 0.254f, 0.030f, 0.254f, yawDeg = yawDeg)
        program.setModel(model, JOINT[0], JOINT[1], JOINT[2],
                         specular = 1f, shininess = 134f, rim = 0.5f)
        turretRing.draw(program)

        // The casting: same width as its flange at the foot, tapering as it rises.
        modelBox(cx, 0.517f, 0f, 0.254f, 0.076f, 0.254f, yawDeg = yawDeg)
        program.setModel(model, SHELL[0], SHELL[1], SHELL[2], specular = 1f, shininess = 124f, rim = 0.5f)
        turretBody.draw(program)

        // Dark relief where the casting meets its flange, so the two read as separate parts.
        modelBox(cx, 0.484f, 0f, 0.246f, 0.009f, 0.246f, yawDeg = yawDeg)
        program.setModel(model, 0.035f, 0.038f, 0.048f, specular = 1f, shininess = 130f, rim = 0.45f)
        turretRing.draw(program)

        // Machined collar capping the casting, where the yoke grows out of it. Its top is 0.560,
        // clear of the shoulder housing's lowest point at 0.569.
        modelBox(cx, 0.552f, 0f, 0.190f, 0.016f, 0.190f, yawDeg = yawDeg)
        program.setModel(model, MACHINED[0], MACHINED[1], MACHINED[2],
                         specular = 1f, shininess = 126f, rim = 0.5f)
        turretRing.draw(program)

        // THE YOKE. The same two lugs every other pivot on this machine gets, at the same offsets
        // in joint radii, so the shoulder is built to the identical contract.
        run {
            val pivot = floatArrayOf(cx, Arena3D.SHOULDER_Y, 0f)
            val ear = sideAxis(pose.baseYaw)
            listOf(-1f, 1f).forEach { sgn ->
                cylinderBetween3(
                    add(pivot, scale(ear, J_SHOULDER * CLEVIS_IN * sgn)),
                    add(pivot, scale(ear, J_SHOULDER * CLEVIS_OUT * sgn)),
                    radius = J_SHOULDER * LUG_R,
                )
                program.setModel(model, SHELL[0], SHELL[1], SHELL[2],
                                 specular = 1f, shininess = 128f, rim = 0.5f)
                cyl.draw(program)

                // No relief here either; see the lug in drawLimb for why.
            }
        }

        // The neon band that used to ring the turret's base is gone (change 42). At radius 0.135
        // inside a casting whose half-extents are 0.120 x 0.110, and at y 0.455-0.470 inside a box
        // spanning 0.450-0.670, the ring was a cylinder threaded THROUGH the turret -- so it broke
        // out through the flat faces as a jagged cyan crescent. The bearing below carries the axis
        // light now, which is where it belongs anyway: on the thing that actually rotates.

        // Counterweight drum, cantilevered behind the turret (in the yawed -forward direction).
        //
        // Change 14.7: this used to sit 0.26 back with a 0.06 half-length, while the turret casting
        // is only 0.22 deep — so its near face was at 0.20 against a turret back face at 0.12, an
        // 0.08-unit gap with nothing spanning it. It read exactly as the user described: a black
        // disc levitating behind the robot. It is pulled in to overlap the casting and a boss now
        // bridges the two. Both are placed from the same `fwd`, so they stay joined at every yaw.
        val fwd = forward(pose.baseYaw)
        val drumCentre = DRUM_OFFSET
        val drumX = cx - fwd[0] * drumCentre
        val drumZ = -fwd[2] * drumCentre
        // Change 44: dropped from -0.02. The casting now tops out at 0.560, so at 0.600 the
        // drum and its boss hung off the empty air above it.
        val drumY = Arena3D.SHOULDER_Y - 0.10f

        // Change 44 rebuilt this as the A2 DRIVE MOTOR rather than a plain drum.
        //
        // The old box hid most of it. With the casting cut back to expose the shoulder there is
        // nothing in front of it any more, and what was left was a radius-0.11 cylinder in near
        // black at specular 0.3 -- against a machine finished at 1.0/128 it caught no highlight at
        // all, so it read as a flat black hole cut in the backdrop rather than as a part. It is
        // also what a real arm actually carries there: the A2 servo and its gearbox, which is
        // worth building as such.
        val motorFront = floatArrayOf(cx - fwd[0] * 0.055f, drumY, -fwd[2] * 0.055f)
        val motorBack = floatArrayOf(drumX - fwd[0] * 0.048f, drumY, drumZ - fwd[2] * 0.048f)
        val motorEnd = floatArrayOf(drumX + fwd[0] * 0.052f, drumY, drumZ + fwd[2] * 0.052f)

        // Gearbox snout, from the casting out to the motor.
        cylinderBetween3(motorFront, motorBack, radius = 0.052f)
        program.setModel(model, 0.24f, 0.26f, 0.29f, specular = 1f, shininess = 112f, rim = 0.45f)
        cyl.draw(program)

        // Motor body.
        cylinderBetween3(motorBack, motorEnd, radius = 0.076f)
        program.setModel(model, 0.19f, 0.20f, 0.23f, specular = 1f, shininess = 116f, rim = 0.45f)
        cyl.draw(program)

        // Cooling ribs along it, which is most of what makes a motor read as a motor.
        for (i in 0 until MOTOR_RIBS) {
            val t = (i + 0.5f) / MOTOR_RIBS
            val c = add(motorBack, scale(sub(motorEnd, motorBack), t))
            cylinderBetween3(
                sub(c, scale(fwd, 0.005f)), add(c, scale(fwd, 0.005f)), radius = 0.082f,
            )
            program.setModel(model, 0.30f, 0.32f, 0.36f, specular = 0.9f, shininess = 92f, rim = 0.4f)
            cyl.draw(program)
        }

        // Machined end cap, with a lit ring round it.
        val capFace = add(motorEnd, scale(fwd, 0.004f))
        cylinderBetween3(motorEnd, capFace, radius = 0.078f)
        program.setModel(model, NEON[0], NEON[1], NEON[2],
                         specular = 1f, shininess = 128f, rim = NEON_RIM)
        cyl.draw(program)
        cylinderBetween3(capFace, add(capFace, scale(fwd, 0.014f)), radius = 0.050f)
        program.setModel(model, 0.34f, 0.36f, 0.40f, specular = 1f, shininess = 110f, rim = 0.45f)
        cyl.draw(program)

        // Arm joints in world space, from the planar IK solution.
        val arm: Kinematics.ArmSolution = pose.arm
        val shoulder = worldOf(arm.shoulder.x, arm.shoulder.y, cx, pose.baseYaw)
        val elbow = worldOf(arm.elbow.x, arm.elbow.y, cx, pose.baseYaw)
        val wrist = worldOf(arm.wrist.x, arm.wrist.y, cx, pose.baseYaw)
        val gripper = worldOf(arm.gripper.x, arm.gripper.y, cx, pose.baseYaw)

        // -- the arm ---------------------------------------------------------------------------
        //
        // Change 37: each member was ONE bare cylinder. The geometry is unchanged -- same joints,
        // same lengths, same radii, so the reach and every calibrated point are exactly as they
        // were -- but each is now built up the way a real machine is: a dark structural core with
        // a lighter shell clamped over its middle, seams where the panels meet, a lit ring at each
        // end and a painted band. What sells a machine at this distance is not resolution, it is
        // the number of distinct EDGES the eye can find along a surface, and a single smooth tube
        // offers exactly two.
        // Each member is told which pivot it roots on and which it forks around, so the clevis
        // arithmetic above has both radii to work from. Passing them was the whole fix: the two
        // halves could not previously agree on a fit because neither knew the other's size.
        drawLimb(shoulder, elbow, 0.075f, J_SHOULDER, J_ELBOW, sideAxis(pose.baseYaw))
        drawLimb(elbow, wrist, 0.060f, J_ELBOW, J_WRIST, sideAxis(pose.baseYaw))
        drawLimb(wrist, gripper, 0.048f, J_WRIST, 0f, sideAxis(pose.baseYaw), dressed = false)

        // THE RAM (change 40). This was a plain chrome rod, and a plain rod is the one part of a
        // linkage that carries no information: you cannot tell which way it drives or where the
        // power comes from. It is now a real actuator -- a fat barrel anchored at the shoulder, a
        // polished rod extending from it, and a clevis at each end -- which is the single most
        // recognisable mechanical component there is and reads instantly as a machine under load.
        // Change 45 re-anchored it, because it was doing two things wrong.
        //
        // Both ends were a flat WORLD-VERTICAL offset -- shoulder + 0.10 up, elbow + 0.08 up -- so
        // the actuator floated above the arm rather than being bolted to it. At most poses the
        // barrel was hidden behind the lower arm and all that showed was a dark clevis hanging in
        // free space beside the elbow, joined to nothing. That is the stray black block.
        //
        // And it spanned SHOULDER to ELBOW, which are the two ends of one rigid member. A ram
        // across a single link drives nothing at all.
        //
        // It now runs from a bracket near the foot of the lower arm to one part-way up the UPPER
        // arm, so it spans A3 and visibly drives it, and both offsets are taken perpendicular to
        // the member they sit on, in the arm's own plane -- so it stays bolted down at every pose
        // instead of only when the arm happens to be vertical. The reference vector picks the back
        // face of the arm and blends up with back, so the choice cannot flip as the arm swings
        // through horizontal.
        val ramSide = sideAxis(pose.baseYaw)
        val ramRef = normalize(sub(UP, scale(forward(pose.baseYaw), 0.6f)))
        val dirLower = normalize(sub(elbow, shoulder))
        val dirUpper = normalize(sub(wrist, elbow))

        fun outward(d: FloatArray): FloatArray {
            val v = normalize(cross(ramSide, d))
            val dot = v[0] * ramRef[0] + v[1] * ramRef[1] + v[2] * ramRef[2]
            return if (dot < 0f) scale(v, -1f) else v
        }

        val footA = add(shoulder, scale(dirLower, 0.058f))
        val footB = add(elbow, scale(dirUpper, 0.132f))
        val rodA = add(footA, scale(outward(dirLower), 0.070f))
        val rodB = add(footB, scale(outward(dirUpper), 0.052f))
        val ramDir = normalize(sub(rodB, rodA))
        val ramMid = add(rodA, scale(ramDir, len(sub(rodB, rodA)) * RAM_BARREL))

        // The brackets the ram is pinned to. Without these its ends still float, however well the
        // anchor points are chosen -- a clevis has to be bolted to a casting.
        listOf(footA to rodA, footB to rodB).forEach { (foot, pin) ->
            cylinderBetween3(foot, pin, radius = 0.017f)
            program.setModel(model, SHELL[0], SHELL[1], SHELL[2],
                             specular = 1f, shininess = 128f, rim = 0.5f)
            cyl.draw(program)
        }

        // Barrel.
        cylinderBetween3(rodA, ramMid, radius = 0.030f)
        program.setModel(model, MACHINED[0], MACHINED[1], MACHINED[2],
                         specular = 1f, shininess = 126f, rim = 0.5f)
        cyl.draw(program)
        // Gland nut at the barrel mouth.
        cylinderBetween3(
            sub(ramMid, scale(ramDir, 0.010f)), add(ramMid, scale(ramDir, 0.006f)), radius = 0.034f,
        )
        program.setModel(model, 0.46f, 0.49f, 0.54f, specular = 1f, shininess = 120f, rim = 0.6f)
        cyl.draw(program)
        // Polished rod.
        cylinderBetween3(ramMid, rodB, radius = 0.014f)
        program.setModel(model, 0.86f, 0.88f, 0.92f, specular = 1f, shininess = 128f, rim = 0.8f)
        cyl.draw(program)
        // Clevis at each end.
        listOf(rodA, rodB).forEach { end ->
            cylinderBetween3(
                sub(end, scale(ramDir, 0.014f)), add(end, scale(ramDir, 0.014f)), radius = 0.026f,
            )
            program.setModel(model, JOINT[0], JOINT[1], JOINT[2],
                             specular = 1f, shininess = 134f, rim = 0.5f)
            cyl.draw(program)
        }

        // The dress cable. Every arm in the reference photographs has one and it is most of what
        // reads as "machine" rather than "shape": a loom slung from the shoulder, sagging past the
        // elbow, clipped to the wrist. Drawn as a quadratic so it bows the way a cable hangs.
        drawCableRun(rodA, elbow, wrist)

        // Joint housings, each a lit bearing rather than a dark stub.
        drawJoint(shoulder, pose.baseYaw, J_SHOULDER)
        drawJoint(elbow, pose.baseYaw, J_ELBOW)
        drawJoint(wrist, pose.baseYaw, J_WRIST)

        drawEndEffector(pose, wrist, gripper, timeS)
    }

    /**
     * One arm member, dressed (change 37).
     *
     * Geometry is untouched: [a] to [b] at [radius], exactly the tube it replaces. Everything here
     * is skin. The build order matters and is the same one a real arm has --
     *
     *   1. a dark structural core, run the full length and left showing at both ends, so each
     *      member visibly plugs INTO its joints instead of butting against them;
     *   2. a pale shell clamped over the middle, glossy, which is the surface the light works on;
     *   3. seams where the shell's panels meet -- three thin rings, slightly proud, and the single
     *      cheapest thing on this list. Edges are what the eye counts;
     *   4. a lit ring at each end, which is what makes it read as powered rather than painted;
     *   5. a painted identity band.
     *
     * There is no emissive channel in the shader, so the lit rings are a bright colour at maximum
     * [rim] -- the fresnel term lifts the silhouette and that is what a glowing edge looks like.
     */
    /**
     * One arm member, as a casting rather than a tube (change 40).
     *
     * **The geometry was the problem, not the shading.** Every member was a constant
     * cross-section, and a constant cross-section is the one thing no real arm has. Worse, each
     * one simply ABUTTED the next at a shared point, so the arm was a string of sausages meeting
     * end to end. Nothing about that reads as a mechanism, however well it is lit or however many
     * rings are painted on it.
     *
     * A real articulated arm is built the opposite way round: each member ends in a FORK, and the
     * next member's boss sits BETWEEN the fork's arms with a pin through all three. That single
     * feature is most of what makes an arm look like an arm -- the members visibly carry load into
     * one another instead of floating in a line -- and it is why a joint is always WIDER than the
     * limbs it joins, never narrower.
     *
     * So each member is now: a wide root collar where it leaves its parent pivot; a shell that
     * tapers from that root to a narrower waist; a raised waist band; and a fork at the far end,
     * two plates straddling the space the next pivot's boss drops into.
     *
     * [a] and [b] are exactly the joint centres they always were. Every form here is arranged
     * around them, so the reach and the calibration are untouched.
     */
    private fun drawLimb(
        a: FloatArray,
        b: FloatArray,
        radius: Float,
        rootJoint: Float,
        tipJoint: Float,
        side: FloatArray,
        dressed: Boolean = true,
    ) {
        val span = sub(b, a)
        val length = len(span)
        if (length < 1e-4f) return
        val dir = scale(span, 1f / length)

        // Where the casting begins: clear of the pivot behind it, and clear of the lug that
        // straddles that pivot. The neck spans the gap.
        val rootStand = rootJoint * ROOT_STANDOFF
        val rootBase = add(a, scale(dir, rootStand))
        val rootEnd = add(rootBase, scale(dir, length * LIMB_ROOT))

        // Where it ends: clear of the boss ahead of it, with the lug welding the two together.
        val standoff = if (tipJoint > 0f) tipJoint * (BOSS_R + 0.14f) else radius * 0.9f
        val forkStart = add(b, scale(dir, -standoff))

        // Neck out of the pivot. Narrower than the clevis inner face, so it threads between the
        // lugs without touching either of them.
        cylinderBetween3(a, rootBase, radius = rootJoint * NECK_R)
        program.setModel(model, JOINT[0], JOINT[1], JOINT[2],
                         specular = 1f, shininess = 136f, rim = 0.5f)
        cyl.draw(program)

        // Root collar: the heavy end, where load enters from the pivot behind it.
        val rootR = radius * 1.16f
        cylinderBetween3(rootBase, rootEnd, radius = rootR)
        program.setModel(model, SHELL[0], SHELL[1], SHELL[2],
                         specular = 1f, shininess = 128f, rim = 0.5f)
        cyl.draw(program)

        // Dark relief groove at the root's shoulder, so the collar reads as a separate casting
        // bolted on rather than as a bulge in the same tube. Undressed members skip it.
        if (dressed) {
            cylinderBetween3(
                sub(rootEnd, scale(dir, 0.008f)), add(rootEnd, scale(dir, 0.008f)),
                radius = rootR * 0.88f,
            )
            program.setModel(model, JOINT[0], JOINT[1], JOINT[2],
                             specular = 1f, shininess = 132f, rim = 0.45f)
            cyl.draw(program)
        }

        // The shell, tapering root -> waist. Its two end radii are known here rather than left to
        // the mesh, so the bands below can be placed exactly proud of it instead of guessed at.
        val shellRoot = rootR * 0.94f
        val shellTip = shellRoot * LIMB_TAPER
        frustumBetween(forkStart, rootEnd, radius = shellRoot)
        program.setModel(model, SHELL[0], SHELL[1], SHELL[2],
                         specular = 1f, shininess = 128f, rim = 0.55f)
        limb.draw(program)

        // Waist band, sitting exactly on the taper wherever along it the band falls.
        //
        // Change 47: the FOREARM does without it. That member runs straight into the tool, and the
        // span from its collar to the vessel was carrying more edges than anything else on the
        // machine; it is now collar, shell, and one flange at the end, and nothing between them.
        if (dressed) {
            val waist = add(rootEnd, scale(sub(forkStart, rootEnd), 0.62f))
            cylinderBetween3(
                sub(waist, scale(dir, 0.014f)), add(waist, scale(dir, 0.014f)),
                radius = (shellRoot + (shellTip - shellRoot) * 0.62f) * 1.03f,
            )
            program.setModel(model, MACHINED[0], MACHINED[1], MACHINED[2],
                             specular = 1f, shininess = 126f, rim = 0.55f)
            cyl.draw(program)
        }

        if (tipJoint > 0f) {
            // THE LUGS. Flat eyes whose faces are square to the pivot axis, one each side of the
            // boss with CLEVIS_GAP of daylight between. Being discs on the pivot axis rather than
            // rods along the member, they cannot reach across the centre-line and swallow the
            // boss, which is exactly what the old fork rods were doing.
            listOf(-1f, 1f).forEach { sgn ->
                cylinderBetween3(
                    add(b, scale(side, tipJoint * CLEVIS_IN * sgn)),
                    add(b, scale(side, tipJoint * CLEVIS_OUT * sgn)),
                    radius = tipJoint * LUG_R,
                )
                program.setModel(model, SHELL[0], SHELL[1], SHELL[2],
                                 specular = 1f, shininess = 128f, rim = 0.5f)
                cyl.draw(program)

                // Change 47 removed the machined relief that used to sit here, and it was not a
                // taste call -- it was a BUG. The relief ran from the lug's outer face INWARD, so
                // its outer cap and the lug's outer cap were at exactly the same depth, over an
                // annulus 0.007 wide. Two coplanar faces is the textbook z-fighting case: the
                // depth test picks between them per pixel, and what came out was a ring of radial
                // dashes around every pivot that crawled as the arm moved. That is the "lines
                // surrounding the joints", and why they appeared to rotate.
                //
                // It could have been fixed by offsetting the relief. It is deleted instead,
                // because the ask was for fewer lines and the pivot is better without it: lug,
                // housing, bezel, cap, and nothing else.
            }
        } else {
            // The last member ends at the tool, not at a pivot, so it gets a wrist flange rather
            // than an empty clevis with nothing between its jaws.
            val flange = add(b, scale(dir, -radius * 0.22f))
            cylinderBetween3(flange, b, radius = radius * 1.18f)
            program.setModel(model, SHELL[0], SHELL[1], SHELL[2],
                             specular = 1f, shininess = 128f, rim = 0.5f)
            cyl.draw(program)
            // No bolt circle here (change 46). The A6 housing seats directly on this flange and
            // covers it, so eight studs bought nothing but eight more rings in the one part of the
            // machine that already had the most.
        }
    }

    /**
     * The dress cable, shoulder to wrist (change 37).
     *
     * A quadratic through a control point pushed below and behind the elbow, so it hangs and then
     * is drawn back in, which is how a loom behaves when the arm folds. Segmented, with a clip
     * partway along; the clip is what tells you it is attached rather than floating.
     */
    private fun drawCableRun(from: FloatArray, via: FloatArray, to: FloatArray) {
        val control = add(via, floatArrayOf(0f, -CABLE_SAG, 0f))
        var previous = from
        for (i in 1..CABLE_SEGMENTS) {
            val t = i.toFloat() / CABLE_SEGMENTS
            val inv = 1f - t
            val p = floatArrayOf(
                inv * inv * from[0] + 2f * inv * t * control[0] + t * t * to[0],
                inv * inv * from[1] + 2f * inv * t * control[1] + t * t * to[1],
                inv * inv * from[2] + 2f * inv * t * control[2] + t * t * to[2],
            )
            cylinderBetween3(previous, p, radius = CABLE_RADIUS)
            program.setModel(model, 0.09f, 0.09f, 0.10f, specular = 0.35f, shininess = 28f, rim = 0.35f)
            cyl.draw(program)
            if (i == CABLE_SEGMENTS / 2) {
                cylinderBetween3(previous, p, radius = CABLE_RADIUS * 1.9f)
                program.setModel(model, 0.30f, 0.32f, 0.36f, specular = 0.6f, shininess = 44f)
                cyl.draw(program)
            }
            previous = p
        }
    }

    private fun drawJoint(p: FloatArray, yaw: Float, radius: Float) {
        // The pivot's half of the clevis contract: the boss between the lugs, the pin through it,
        // and -- on BOTH flanks now -- the drive housing seated on the lug's outer face, its neon
        // ring, and the black cap. See the contract above for where each lands.
        val side = sideAxis(yaw)

        // Load-bearing boss, spanning the gap between the lugs with air either side of it.
        cylinderBetween3(
            sub(p, scale(side, radius * BOSS_HALF)), add(p, scale(side, radius * BOSS_HALF)),
            radius = radius * BOSS_R,
        )
        program.setModel(model, JOINT[0], JOINT[1], JOINT[2],
                         specular = 1f, shininess = 138f, rim = 0.5f)
        cyl.draw(program)

        // The pin, finishing flush with the lug faces rather than standing proud of them.
        cylinderBetween3(
            sub(p, scale(side, radius * CLEVIS_OUT)), add(p, scale(side, radius * CLEVIS_OUT)),
            radius = radius * PIN_R,
        )
        program.setModel(model, 0.52f, 0.54f, 0.58f, specular = 1f, shininess = 140f, rim = 0.7f)
        cyl.draw(program)

        listOf(-1f, 1f).forEach { sgn ->
            val face = add(p, scale(side, radius * CLEVIS_OUT * sgn))
            val end = add(p, scale(side, radius * HOUSING_OUT * sgn))

            // Drive housing: a shallow disc square to the axis, mirrored onto both flanks.
            cylinderBetween3(face, end, radius = radius * HOUSING_R)
            program.setModel(model, JOINT[0], JOINT[1], JOINT[2],
                             specular = 1f, shininess = 138f, rim = 0.5f)
            cyl.draw(program)

            // No cooling ribs. Three of them at 0.007 thick totalled 0.021 inside a housing only
            // (1.14-0.92)*0.070 = 0.015 deep -- 1.4x its whole depth -- so they spilled out either
            // end and the pivot wore a serrated collar instead of a clean disc. A shallow cover
            // does not need fins; the bolt ring below already says it is a bolted-on housing, and
            // leaving them off is flatter, which is the direction this was going in anyway.

            // Polished bezel on the housing's outer face. See [BEZEL]: this was the cyan ring
            // that made every pivot read blue.
            cylinderBetween3(
                end, add(p, scale(side, radius * NEON_OUT * sgn)), radius = radius * (HOUSING_R + 0.02f),
            )
            program.setModel(model, BEZEL[0], BEZEL[1], BEZEL[2],
                             specular = 1f, shininess = 142f, rim = 0.75f)
            cyl.draw(program)

            // Black cap over the pin's end.
            cylinderBetween3(
                add(p, scale(side, radius * NEON_OUT * sgn)),
                add(p, scale(side, radius * CAP_OUT * sgn)),
                radius = radius * CAP_R,
            )
            program.setModel(model, 0.035f, 0.038f, 0.048f,
                             specular = 1f, shininess = 132f, rim = 0.45f)
            cyl.draw(program)

            // No bolt heads on the housing (change 47). They were the only other feature on the
            // pivot face, and being at fixed angles in the joint's own frame they were also the
            // only thing there that could read as turning. With them gone every part of the axis
            // is rotationally symmetric, so there is nothing left to appear to spin at all.
        }
    }

    private fun drawEndEffector(pose: Robot3DPose, wrist: FloatArray, gripper: FloatArray, timeS: Float) {
        val fdir = normalize(sub(gripper, wrist))

        // Side axis in the horizontal plane, rolled about the forearm by the wrist (A4).
        //
        // Change 13.3: when the forearm approaches vertical, cross(fdir, UP) collapses and its
        // direction becomes meaningless. This used to switch hard to the turret's side axis below a
        // threshold, which snapped the fingers every time a top-edge reach crossed vertical. Now the
        // two candidates are blended across a band, so the hand rotates through the singularity
        // instead of jumping across it.
        val rawSide = cross(fdir, UP)
        val fallback = sideAxis(pose.baseYaw)
        val confidence = smoothstep((len(rawSide) - SIDE_BLEND_LO) / (SIDE_BLEND_HI - SIDE_BLEND_LO))
        var side = normalize(
            add(scale(normalize(rawSide), confidence), scale(fallback, 1f - confidence))
        )
        if (len(side) < 1e-3f) side = fallback
        side = rotateAbout(side, fdir, pose.wristRoll)

        drawVessel(pose, gripper, side, fdir, timeS)
    }

    // -- Vessel and dispensers (change 15.1) -----------------------------------------------------

    /**
     * The process vessel on the end effector, its dispenser, and the material stream.
     *
     * **Change 15.1 replaced the bucket.** A pail that tipped to pour was the wrong machine: the rig
     * doses measured quantities, and you cannot tip a bucket accurately. The tool is now a small
     * process vessel bolted rigidly to the wrist — no bail, no yoke, no swing — with a conical hopper
     * and a powered dispenser underneath. Because it is fed by a pump it never tips, which is also
     * why [Payload.tilt] is gone.
     *
     * **Change 16.6 finished the job.** 15.1 kept two bracket rods between the wrist and the vessel
     * flange, and with daylight between them they still read as a carry handle — the bucket the
     * change was supposed to abolish, hung from a yoke. The mount is now a single continuous body:
     * actuator, bearing race, tapered neck, flange, all on one axis with no gap anywhere. The head
     * is not carried by the arm, it is part of it, which is how a real dosing end effector is
     * built and the only way it reads as one from every angle the turret swings through.
     *
     * The dispenser is chosen by material, because one mechanism cannot honestly do both:
     *
     *  - **Liquid** gets a true peristaltic head — a circular race, a three-roller rotor, and
     *    flexible tubing squeezed between them. This is the standard way to dose a liquid without
     *    the pumped fluid ever touching the pump.
     *  - **Solid** gets a screw auger under the hopper cone. A peristaltic head physically cannot
     *    move dry powder; an auger is what a real powder-dosing skid uses.
     *
     * Both are driven by [Payload.pumpAngle], which only advances while material is actually
     * flowing, so the mechanism is visibly doing the work rather than free-running.
     */
    private fun drawVessel(
        pose: Robot3DPose,
        gripper: FloatArray,
        side: FloatArray,
        fdir: FloatArray,
        timeS: Float,
    ) {
        val payload = pose.payload
        // The vessel hangs plumb. It is rigidly mounted, so it does not swing with the wrist and it
        // never tips — the axis is world-up regardless of arm pose.
        val axis = UP
        val down = floatArrayOf(0f, -1f, 0f)
        val out = normalize(cross(axis, side))

        val top = add(gripper, scale(down, VESSEL_DROP))
        val bottom = add(top, scale(down, VESSEL_HEIGHT))
        val hopperTip = add(bottom, scale(down, HOPPER_HEIGHT))

        // -- mount: one continuous machined neck from the wrist into the vessel --------------------
        //
        // Change 16.6 removed the two bracket struts. They read as a carry handle -- a pail slung
        // under the tool on a pair of rods, with daylight between the two -- which is exactly the
        // bucket the vessel was meant to stop being. A dosing head on a real arm is not *carried*
        // by the wrist, it IS the wrist: one machined body, flange to flange, nothing hanging.
        //
        // So the mount is now solid and continuous. The wrist actuator seats a collar, the collar
        // tapers into the vessel flange, and every section shares the same axis with no gap
        // between them, so from any angle the tool reads as one part rather than an assembly.

        // ---- THE WRIST HOUSING (change 45) ---------------------------------------------------
        //
        // This was a 0.10 CUBE, aligned to the WORLD axes and painted flat near-black at specular
        // 0.35. Being world-aligned it lined up with nothing at any pose -- not the forearm coming
        // in above it at whatever angle the solver picked, and not the vessel hanging plumb below
        // it. And at that gloss it caught no highlight, so against a machine finished at 1.0/128
        // it read as a black hole between two carefully built parts rather than as a part itself.
        //
        // It is in fact the A6 axis, and that is an interesting piece of machine: the forearm
        // arrives at an angle, the tool hangs vertical, and the turn between the two happens right
        // here. So it is built as exactly that, and as the handover between the arm's language and
        // the vessel's -- an angled boss taking the load off the forearm's flange, a hexagonal
        // drive housing in the shell red echoing the turret casting at the far end of the arm,
        // then a gloss-black bearing stack split by a lit line, and out of that the steel race and
        // neck the vessel is built from. Red above, black at the axis, steel below.
        val toolYawDeg = Math.toDegrees(atan2(side[2], -side[0]).toDouble()).toFloat()

        // Angled boss, seated on the forearm's own wrist flange.
        cylinderBetween3(
            add(gripper, scale(fdir, -0.036f)), add(gripper, scale(fdir, 0.010f)),
            radius = 0.043f,
        )
        program.setModel(model, SHELL[0], SHELL[1], SHELL[2],
                         specular = 1f, shininess = 128f, rim = 0.5f)
        cyl.draw(program)

        // Hex drive housing, on the tool's own vertical axis and turning with it.
        modelBox(gripper[0], gripper[1] + 0.006f, gripper[2], 0.104f, 0.038f, 0.104f, toolYawDeg)
        program.setModel(model, SHELL[0], SHELL[1], SHELL[2],
                         specular = 1f, shininess = 128f, rim = 0.5f)
        turretRing.draw(program)

        // Gloss-black bearing collar, with the split line lit: the A6 rotary joint itself. One
        // collar rather than the two it was, so the tool end carries one fewer ring.
        val bearSplit = add(gripper, scale(down, 0.026f))
        cylinderBetween3(add(gripper, scale(down, 0.012f)), bearSplit, radius = 0.046f)
        program.setModel(model, JOINT[0], JOINT[1], JOINT[2],
                         specular = 1f, shininess = 140f, rim = 0.5f)
        cyl.draw(program)

        cylinderBetween3(bearSplit, add(bearSplit, scale(down, 0.005f)), radius = 0.0435f)
        program.setModel(model, NEON[0], NEON[1], NEON[2],
                         specular = 1f, shininess = 128f, rim = NEON_RIM)
        cyl.draw(program)

        // Bearing race under the actuator -- the surface the head rotates on.
        val raceTop = add(gripper, scale(down, 0.030f))
        val raceBottom = add(raceTop, scale(down, 0.016f))
        cylinderBetween3(raceTop, raceBottom, radius = NECK_RADIUS * 1.22f)
        program.setModel(model, 0.66f, 0.69f, 0.74f, specular = 1f, shininess = 124f, rim = 0.55f)
        cyl.draw(program)

        // The neck itself, tapering race -> flange. This is the piece that used to be two rods and
        // a gap; being a single frustum is the whole point of the change.
        frustumBetween(add(top, scale(axis, 0.004f)), raceBottom, radius = VESSEL_RADIUS * 1.12f)
        program.setModel(model, 0.26f, 0.28f, 0.31f, specular = 0.72f, shininess = 62f, rim = 0.6f)
        hopper.draw(program)

        // A pair of raised ribs up the neck. Detail rather than structure -- they make the taper
        // read as machined stock instead of a smooth cone, and they are on the body, so they add
        // nothing to lift and cannot be mistaken for a handle.
        listOf(-1f, 1f).forEach { sgn ->
            val ribOut = normalize(add(scale(side, sgn), scale(out, sgn * 0.35f)))
            val ribTop = add(add(raceBottom, scale(ribOut, NECK_RADIUS * 0.86f)), scale(down, 0.004f))
            val ribFoot = add(add(top, scale(ribOut, VESSEL_RADIUS * 0.94f)), scale(axis, 0.008f))
            cylinderBetween3(ribTop, ribFoot, radius = 0.0075f)
            program.setModel(model, 0.38f, 0.40f, 0.44f, specular = 0.85f, shininess = 76f, rim = 0.7f)
            cyl.draw(program)
        }

        // -- vessel shell ----------------------------------------------------------------------------
        //
        // The body is drawn *after* the contents, as glass, so the level is visible. A steel cylinder
        // hid its own contents completely: the fill and the heap were rendering correctly (see
        // DispenserTest) but were sealed inside an opaque tube, so on device the vessel looked empty
        // the whole way through the transfer. Sight glasses and clear polycarbonate dosing vessels
        // are standard for exactly this reason — an operator has to see the level.

        // Top flange ring.
        cylinderBetween3(
            add(top, scale(axis, -0.008f)), add(top, scale(axis, 0.010f)),
            radius = VESSEL_RADIUS * 1.12f,
        )
        program.setModel(model, 0.72f, 0.75f, 0.80f, specular = 0.9f, shininess = 80f, rim = 0.9f)
        cyl.draw(program)

        // Conical hopper, tapering to the dispenser inlet.
        frustumBetween(hopperTip, bottom, radius = VESSEL_RADIUS)
        program.setModel(model, STEEL[0] * 0.94f, STEEL[1] * 0.94f, STEEL[2] * 0.94f,
            specular = 0.8f, shininess = 66f, rim = 0.7f)
        hopper.draw(program)

        val solid = payload.kind == MaterialKind.SOLID
        val fill = payload.fill.coerceIn(0f, 1f)

        // -- contents ------------------------------------------------------------------------------

        if (fill > 0.02f) {
            val height = (VESSEL_HEIGHT - 0.022f) * fill
            val contentBottom = add(bottom, scale(axis, 0.010f))
            val contentTop = add(contentBottom, scale(axis, height))
            val contentRadius = VESSEL_RADIUS * 0.88f
            if (solid) {
                frustumBetween(contentBottom, contentTop, radius = contentRadius)
                program.setModel(model, POWDER[0], POWDER[1], POWDER[2], specular = 0.08f, shininess = 6f)
                heap.draw(program)
            } else {
                cylinderBetween3(contentBottom, contentTop, radius = contentRadius)
                drawTranslucent { alpha ->
                    program.setModel(
                        model, LIQUID[0], LIQUID[1], LIQUID[2],
                        specular = 0.6f, shininess = 60f, alpha = alpha,
                    )
                    cyl.draw(program)
                }
                if (payload.stream > 0.05f) {
                    drawRipples(contentTop, axis, contentRadius, timeS)
                }
            }
        }

        // Glass body over the contents.
        cylinderBetween3(bottom, top, radius = VESSEL_RADIUS)
        drawTranslucent { _ ->
            program.setModel(
                model, 0.86f, 0.91f, 0.96f,
                specular = 0.95f, shininess = 95f, alpha = GLASS_ALPHA, rim = 1.0f,
            )
            cyl.draw(program)
        }

        // A lit band at the vessel's shoulder.
        //
        // Change 46 stripped the ring of flange bolts that used to sit beside it, and took the
        // glass from two hoops to one. Counted up, the span between the forearm and the vessel had
        // become sixteen separate concentric rings inside about a tenth of the arm's length -- the
        // pivot's lug, relief, housing, bezel and cap, the hex housing, three in the bearing stack,
        // the race, eight flange studs, two hoops and the shoulder band -- and at that density
        // none of them reads as anything. What is left is the band, one hoop, and the bearing
        // split, so each of the three now means something.
        run {
            val shoulderY = add(bottom, scale(axis, VESSEL_HEIGHT * 0.92f))
            cylinderBetween3(
                add(shoulderY, scale(axis, -0.005f)), add(shoulderY, scale(axis, 0.005f)),
                radius = VESSEL_RADIUS * 1.09f,
            )
            program.setModel(
                model, ACCENT[0], ACCENT[1], ACCENT[2],
                specular = 1f, shininess = 110f, rim = NEON_RIM,
            )
            cyl.draw(program)

        }

        // Steel hoops banding the glass, which is what stops a clear tube reading as a plain
        // cylinder and gives the vessel its scale.
        listOf(0.44f).forEach { f ->
            val y = add(bottom, scale(axis, VESSEL_HEIGHT * f))
            cylinderBetween3(
                add(y, scale(axis, -0.005f)), add(y, scale(axis, 0.005f)),
                radius = VESSEL_RADIUS * 1.05f,
            )
            program.setModel(model, 0.48f, 0.51f, 0.56f, specular = 0.85f, shininess = 70f, rim = 0.7f)
            cyl.draw(program)
        }

        // -- dispenser -------------------------------------------------------------------------------

        // **One dispenser, for every sequence** (change 30).
        //
        // This used to pick the mechanism from the material: a screw auger for
        // powder, a peristaltic head for liquid, on the honest argument that a
        // peristaltic head cannot move dry solids. Reported from the rig as "the
        // robot keeps switching between 2 different pumps at the bottom", and it
        // was worse than choosing per sequence -- it switched WITHIN one.
        //
        // [Payload.EMPTY] defaults its kind to SOLID, and the poser publishes
        // EMPTY whenever no transfer is in flight. A carry is 42 s against a 40 s
        // arm animation, so every single carry has an idle gap where the tool
        // reverted to the auger and then flipped back. On the liquid line that is
        // a pump turning into an auger and back, twice a cycle, for a material
        // that never changes.
        //
        // Fixing the default would have stopped the flicker, but the operator
        // asked for one pump across all four sequences, which is also the simpler
        // machine: real dosing skids are built around one head. The auger is gone
        // rather than left unreachable -- git has it if the solid line ever wants
        // its own mechanism back.
        //
        // The MATERIAL still differs: powder draws as a granule cone, liquid as a
        // falling stream. That is what is being moved, not what moves it.
        val nozzle =
            drawPeristalticHead(hopperTip, out, side, axis, forward(pose.baseYaw), payload)

        // The service wire from the vessel body down to the pump (change 39). Every dosing head
        // has one -- the drive and the level sensor have to be wired to something -- and its
        // absence is the sort of thing the eye notices without being able to name: a pump running
        // with nothing connected to it. Bowed outward so it stands off the vessel wall rather than
        // being painted on it.
        run {
            // Change 42: it leaves the vessel at the BOTTOM. It was anchored 58% of the way up
            // the wall, which is nowhere a wire ever enters a vessel -- the gland is on the bottom
            // flange, beside the thing it feeds. Off the bottom the run is short and direct, the
            // wall above stays clean, and the cable reads as SERVING the pump rather than as being
            // draped down the side of the tank past it.
            val anchor = add(
                add(bottom, scale(axis, VESSEL_HEIGHT * 0.04f)),
                scale(out, VESSEL_RADIUS * 0.97f),
            )
            val head = add(hopperTip, scale(axis, PUMP_RADIUS * 0.18f))
            val bow = add(
                add(anchor, scale(axis, -HOPPER_HEIGHT * 0.50f)),
                // Bowed clear of the cone rather than hugging it: the hopper tapers from the
                // vessel radius down to the pump, so a cable running straight between those two
                // points stays inside its silhouette the whole way and is never actually seen.
                scale(out, WIRE_BOW * 0.85f),
            )
            var previous = anchor
            for (i in 1..WIRE_SEGMENTS) {
                val t = i.toFloat() / WIRE_SEGMENTS
                val inv = 1f - t
                val p = floatArrayOf(
                    inv * inv * anchor[0] + 2f * inv * t * bow[0] + t * t * head[0],
                    inv * inv * anchor[1] + 2f * inv * t * bow[1] + t * t * head[1],
                    inv * inv * anchor[2] + 2f * inv * t * bow[2] + t * t * head[2],
                )
                cylinderBetween3(previous, p, radius = WIRE_RADIUS)
                program.setModel(model, 0.10f, 0.10f, 0.12f, specular = 0.45f, shininess = 34f, rim = 0.4f)
                cyl.draw(program)
                previous = p
            }
            // A gland at each end, each on the axis its cable actually enters along: radial
            // into the vessel's bottom flange, axial into the pump body.
            listOf(anchor to out, head to axis).forEach { (c, n) ->
                cylinderBetween3(
                    add(c, scale(n, -0.008f)), add(c, scale(n, 0.008f)),
                    radius = WIRE_RADIUS * 2.1f,
                )
                program.setModel(model, 0.42f, 0.44f, 0.48f, specular = 0.8f, shininess = 70f)
                cyl.draw(program)
            }
        }

        // -- material transfer -----------------------------------------------------------------------

        if (payload.stream > 0.01f) {
            // **Which way material travels is decided by which END of the transfer this is.**
            //
            // Dispensing, the arm has driven the dispenser up against the module's wall and the
            // pump pushes material STRAIGHT INTO it -- horizontally, along the direction the
            // turret is facing, which is by construction the direction of the calibrated point.
            // It is pumped, not dropped, so it travels at a constant rate and does not arc.
            //
            // Collecting, the macro is above the arm and gravity does the work, so material comes
            // DOWN INTO the vessel. Drawing that out of the nozzle underneath -- as it was before
            // change 36 -- is the wrong end of the machine entirely: material leaving the discharge
            // while the vessel fills reads as the tool emptying itself onto the floor.
            val intoWall = forward(pose.baseYaw)
            if (solid) {
                if (payload.pouring) {
                    drawPowderStream(
                        from = nozzle,
                        to = add(nozzle, scale(intoWall, DISPENSE_REACH)),
                        gravity = false,
                        payload = payload,
                        timeS = timeS,
                    )
                } else {
                    drawPowderStream(
                        from = add(top, scale(axis, POWDER_RISE)),
                        to = top,
                        gravity = true,
                        payload = payload,
                        timeS = timeS,
                    )
                }
            } else if (payload.pouring) {
                drawLiquidFall(
                    from = nozzle,
                    to = add(nozzle, scale(intoWall, DISPENSE_REACH)),
                    flow = payload.stream,
                    suction = false,
                    pumped = true,
                    timeS = timeS,
                )
            } else {
                // Liquid intake is unchanged: the head is a reversible peristaltic pump and the
                // vessel has no open top to pour into (change 16.6), so it draws in through the
                // nozzle exactly as it pushes out.
                drawLiquidFall(
                    from = nozzle,
                    to = add(nozzle, scale(down, POUR_FALL)),
                    flow = payload.stream,
                    suction = true,
                    pumped = false,
                    timeS = timeS,
                )
            }
        }
    }

    /**
     * A falling stream of liquid.
     *
     * **Change 26 removed the station pipework this used to run through.** 25.3 put a glass riser at
     * the module so the stream had something to pour into, on the reasoning that a jet stopping in
     * mid-air near the frame edge was the thing that looked wrong. The riser did dock correctly and
     * the water did run down it, and it still looked bad: a hard-edged glass tube standing in the
     * middle of an otherwise soft scene drew far more attention than the liquid it was carrying, and
     * every module got one. The jet is better off alone.
     *
     * What is left is the physics, which is the part that was working. Two facts do most of it:
     *
     *  - **It accelerates.** Travel is `v0*u + (1-v0)*u^2` and speed is its derivative, so the two
     *    can never disagree. Drops shed at an even rate spread further apart the longer they fall,
     *    which is the cue that reads as falling rather than hanging.
     *  - **It necks down.** Volume is conserved, so cross-section goes as 1/v and radius as
     *    1/sqrt(v). That taper is the most recognisable thing about water leaving a tap.
     *
     * Change 26 adds the third one, which is what makes it read as a **body** rather than a painted
     * line:
     *
     *  - **It ripples.** A real jet is unstable: surface tension amplifies varicose waves that grow
     *    as it falls until the neck pinches off and it beads (Plateau-Rayleigh). Those travelling
     *    bulges vary the SILHOUETTE, so the column stops being a smooth cone and starts turning in
     *    the light, and they grow into exactly where the breakup happens instead of the breakup
     *    being an arbitrary cut. Amplitude ramps with u^2 for that reason.
     *
     * The drops scatter across a genuine disc, using two axes built across the fall, rather than in
     * the single plane the old ones used -- some pass nearer the camera than others, which is most
     * of what sells the depth.
     *
     * The far end never arrives anywhere on screen, because the module it is going to or coming
     * from is outside the frame -- that is the whole reason 25.3 tried to build one. It is NOT faded
     * out with alpha, because alpha and the depth writes this needs (see below) cannot both be had.
     * It disperses geometrically instead: the drops shrink away to nothing, which is what a jet does
     * anyway once it has fallen far enough, and it means no cut-off disc hanging in mid-air.
     *
     * [suction] reverses the flow for a collection -- see the call site for why both directions
     * leave through the nozzle.
     *
     * **Depth writes stay ON here, unlike every other translucent part of the machine.** With them
     * off, the flat end caps of every stacked cylinder blend straight through the surface in front,
     * and a cap shades differently from the sides: measured on device the column alternated
     * (64,165,219) with (40,103,141) in a regular rung all the way down, which read as a screw
     * thread. Finer segmentation makes it worse, not better -- more segments means more caps. A
     * stream is a solid body of water and must never blend with itself, so the near surface wins and
     * every interior face is discarded.
     */
    private fun drawLiquidFall(
        from: FloatArray,
        to: FloatArray,
        flow: Float,
        suction: Boolean,
        pumped: Boolean,
        timeS: Float,
    ) {
        val span = sub(to, from)
        val height = len(span)
        if (height < 1e-4f || flow <= 0.01f) return
        val dir = scale(span, 1f / height)

        // A basis across the fall, so drops scatter through a disc instead of a plane. The seed axis
        // is swapped when the fall is near-vertical, which it usually is, or the cross collapses.
        val seedAxis = if (kotlin.math.abs(dir[1]) > 0.9f) floatArrayOf(1f, 0f, 0f) else UP
        val across = normalize(cross(dir, seedAxis))
        val through = normalize(cross(dir, across))

        val breakup = FALL_BREAKUP
        val base = LIQUID_RADIUS * sqrt(flow)
        // Suction runs the whole thing backwards: the waves travel toward the nozzle and the drops
        // converge into the column rather than shedding from it.
        val sense = if (suction) -1f else 1f

        // A PUMPED jet is pushed at a constant rate: it neither accelerates nor necks down,
        // because nothing is speeding it up. Gravity does both, which is why a falling stream
        // and a pumped one cannot share one law.
        fun speedAt(u: Float) =
            if (pumped) 1f else EXIT_SPEED + 2f * (1f - EXIT_SPEED) * u.coerceIn(0f, 1f)
        fun travelAt(u: Float) =
            if (pumped) u.coerceIn(0f, 1f)
            else (EXIT_SPEED * u + (1f - EXIT_SPEED) * u * u).coerceIn(0f, 1f)
        fun pointAt(u: Float) = add(from, scale(dir, height * travelAt(u)))
        // Amplitude grows along the fall, so the breakup happens where the waves have actually
        // grown into it. Linear rather than quadratic: u^2 is closer to the real instability but it
        // keeps the ripple under 2% of the radius for the first quarter of the fall, and on the rig
        // that quarter is most of what is on screen.
        fun rippleAt(u: Float) =
            1f + WAVE_AMP * u * sin(WAVE_FREQ * travelAt(u) - sense * timeS * WAVE_SPEED)
        // A pumped jet holds its bore: nothing speeds it up, so there is nothing to neck it
        // down. It still ripples -- surface waves do not need gravity.
        fun radiusAt(u: Float) =
            (if (pumped) base else base * sqrt(EXIT_SPEED / speedAt(u))) * rippleAt(u)

        // The coherent column, from the outlet to the breakup point. Many fine segments so the
        // radius step between neighbours is sub-pixel, each running slightly into the next so no
        // hairline shows where two meet.
        val segments = maxOf(4, (COLUMN_SEGMENTS * breakup).toInt())
        for (i in 0 until segments) {
            val u0 = breakup * i / segments
            val u1 = breakup * (i + 1) / segments
            cylinderBetween3(
                pointAt(u0),
                add(pointAt(u1), scale(dir, height * COLUMN_OVERLAP)),
                radius = radiusAt(u0).coerceAtLeast(0.003f),
            )
            // Rim light is what makes a cylinder read as round rather than as a flat blue band.
            program.setModel(
                model, LIQUID[0], LIQUID[1], LIQUID[2],
                specular = 0.7f, shininess = 62f, alpha = LIQUID_ALPHA, rim = 0.85f,
            )
            cyl.draw(program)
        }

        // The broken tail. Phases are jittered per drop -- an evenly spaced sequence reads as a
        // bead necklace -- and each drop is a stubby cylinder swept along the fall, which has a
        // round silhouette where the granule mesh (an octahedron) showed angular facets.
        for (i in 0 until FALL_DROPS) {
            val seed = (i * 0.6180339f) % 1f
            val seed2 = (i * 0.2794f) % 1f
            val seed3 = (i * 0.4373f) % 1f
            val jitter = (seed2 - 0.5f) * DROP_JITTER
            val raw = (((sense * timeS * FALL_RATE + seed + jitter) % 1f) + 1f) % 1f
            val u = breakup + (1f - breakup) * raw

            // A drop is a little fatter than the thread it pinched off from, and they vary. Past
            // [DROP_FADE] they shrink away, which is how a pour that never lands ends without
            // either a cut-off disc or an alpha fade the depth writes cannot afford.
            val disperse = 1f - smoothstep((u - DROP_FADE) / (1f - DROP_FADE))
            val r = radiusAt(u) * (1.10f + 0.55f * seed2) * disperse
            if (r < 0.0025f) continue

            val angle = seed3 * 2f * Math.PI.toFloat()
            val spread = DROP_SPREAD * (u - breakup) * (0.35f + seed2)
            val p = add(
                pointAt(u),
                add(scale(across, cos(angle) * spread), scale(through, sin(angle) * spread)),
            )
            val half = scale(dir, r * 0.6f)
            cylinderBetween3(sub(p, half), add(p, half), radius = r)
            program.setModel(
                model, LIQUID[0], LIQUID[1], LIQUID[2],
                specular = 0.8f, shininess = 66f, alpha = LIQUID_ALPHA, rim = 0.9f,
            )
            cyl.draw(program)
        }
    }

    /**
     * Peristaltic head under the hopper, for liquid (change 15.1).
     *
     * A circular race with a three-roller rotor squeezing a flexible tube against it. The tube runs
     * from the hopper outlet, wraps the race, and leaves at the nozzle; the rollers occlude it in
     * turn, which is what moves the liquid. The pumped fluid only ever touches the tube, which is
     * the reason this pump type is used for dosing in the first place.
     *
     * Returns the nozzle tip, where the material leaves.
     */
    private fun drawPeristalticHead(
        inlet: FloatArray,
        out: FloatArray,
        side: FloatArray,
        axis: FloatArray,
        dispense: FloatArray,
        payload: Payload,
    ): FloatArray {
        // ---- THE PUMP'S OWN FRAME (change 43) -----------------------------------------------
        //
        // THIS is why beads came off the pump and hung in free space.
        //
        // The head's race lies along `side`, and every part that rings it -- rollers, cover bolts,
        // the tube -- was placed as `out*cos(theta) + axis*sin(theta)`. For that ring to be a
        // circle in the race's own face, BOTH basis vectors have to be perpendicular to `side`.
        // `out` is, by construction: it is cross(axis, side). **`axis` is not.** `axis` is hard
        // world-up, while `side` is rolled about the forearm by the wrist (A4) in
        // drawEndEffector -- so the moment the wrist rolls with a non-horizontal forearm, `side`
        // picks up a vertical component and stops being perpendicular to up.
        //
        // The ring was then drawn in a plane TILTED out of the race's face. Near theta = +/-90
        // degrees, where the `axis` term dominates, that tilt pushes parts along the race axis and
        // straight out through its end caps -- so a handful of rollers popped outside the housing
        // and floated beside the pump, and the bolt circle slid off the cover plate. It depended
        // on wrist roll, which is exactly why it only happened SOMETIMES.
        //
        // Gram-Schmidt fixes it at the root: take the component of `axis` perpendicular to the
        // race axis and use that. `pumpUp` and `pumpOut` are then both square to `side`, the ring
        // is a true circle in the cover face, and nothing on the head can leave the housing at any
        // wrist angle. Everything downstream reads these two instead of `out`/`axis`.
        val raceAxis = normalize(side)
        val axisDotRace = axis[0] * raceAxis[0] + axis[1] * raceAxis[1] + axis[2] * raceAxis[2]
        val pumpUp = normalize(sub(axis, scale(raceAxis, axisDotRace)))
        val pumpOut = normalize(cross(pumpUp, raceAxis))

        // The head sits just below the hopper outlet, its rotor face turned toward the camera.
        val hub = add(inlet, scale(axis, -PUMP_RADIUS * 0.85f))

        // Race housing: a short cylinder whose axis lies along the hand's side direction.
        cylinderBetween3(
            add(hub, scale(side, -PUMP_WIDTH / 2f)), add(hub, scale(side, PUMP_WIDTH / 2f)),
            radius = PUMP_RADIUS,
        )
        program.setModel(model, 0.30f, 0.33f, 0.38f, specular = 0.65f, shininess = 54f, rim = 0.5f)
        cyl.draw(program)

        // Lit rim around the race, so the head reads as powered and its rotation is legible even
        // when the rollers themselves are edge-on to the camera (change 37).
        listOf(-1f, 1f).forEach { sgn ->
            val face = add(hub, scale(side, PUMP_WIDTH * 0.5f * sgn))
            cylinderBetween3(
                face, add(face, scale(side, 0.004f * sgn)), radius = PUMP_RADIUS * 0.96f,
            )
            program.setModel(
                model, ACCENT[0], ACCENT[1], ACCENT[2],
                specular = 1f, shininess = 110f, rim = NEON_RIM,
            )
            cyl.draw(program)

            // Change 46 capped this lit face to kill the cyan disc; change 47 takes the cap back
            // off. The cover was hiding the head's bolt circle against a black plate, and those
            // studs are the one thing that shows the pump is a built assembly rather than a disc.
            // The face reads blue again, and that is the trade that was asked for.

            // Bolt ring on the lit face.
            for (i in 0 until PUMP_BOLTS) {
                val a = i * (2f * Math.PI.toFloat() / PUMP_BOLTS)
                val o = add(scale(pumpOut, cos(a) * PUMP_RADIUS * 0.74f),
                            scale(pumpUp, sin(a) * PUMP_RADIUS * 0.74f))
                val c = add(face, o)
                cylinderBetween3(c, add(c, scale(side, 0.010f * sgn)), radius = 0.006f)
                program.setModel(model, 0.50f, 0.52f, 0.56f, specular = 0.85f, shininess = 78f)
                cyl.draw(program)
            }
        }

        // Rotor hub.
        cylinderBetween3(
            add(hub, scale(side, -PUMP_WIDTH * 0.62f)), add(hub, scale(side, PUMP_WIDTH * 0.62f)),
            radius = PUMP_RADIUS * 0.34f,
        )
        program.setModel(model, 0.16f, 0.16f, 0.18f, specular = 0.5f, shininess = 44f)
        cyl.draw(program)

        // Three rollers, 120 degrees apart, turning with the dispenser. Their orbit is capped so
        // the outermost point of a roller can never pass the housing wall: PUMP_ORBIT +
        // PUMP_ROLLER_R must stay inside PUMP_RADIUS. It does (0.047 against 0.056), but the cap
        // is written down rather than left as a coincidence between four separate constants --
        // this is the mechanism that was escaping, and it should not be able to again.
        val orbit = PUMP_ORBIT.coerceAtMost(PUMP_RADIUS * 0.94f - PUMP_ROLLER_R)
        val spin = Math.toRadians(payload.pumpAngle.toDouble()).toFloat()
        for (i in 0 until PUMP_ROLLERS) {
            val theta = spin + i * (2f * Math.PI.toFloat() / PUMP_ROLLERS)
            val offset = add(
                scale(pumpOut, cos(theta) * orbit),
                scale(pumpUp, sin(theta) * orbit),
            )
            val centre = add(hub, offset)
            cylinderBetween3(
                add(centre, scale(side, -PUMP_WIDTH * 0.40f)),
                add(centre, scale(side, PUMP_WIDTH * 0.40f)),
                radius = PUMP_ROLLER_R,
            )
            program.setModel(model, 0.82f, 0.84f, 0.88f, specular = 0.85f, shininess = 76f, rim = 0.8f)
            cyl.draw(program)
        }

        // Flexible tube: down from the hopper outlet, around the outside of the race, out to the
        // nozzle. Swept as short segments so it reads as bent tubing rather than a rigid pipe.
        val tubeR = PUMP_ORBIT + PUMP_ROLLER_R * 0.9f
        var previous = inlet
        for (i in 0..PUMP_TUBE_SEGMENTS) {
            val t = i.toFloat() / PUMP_TUBE_SEGMENTS
            // Sweep from straight up, round the front of the race, to pointing down and outward.
            val theta = (PUMP_TUBE_FROM + PUMP_TUBE_ARC * t) * Math.PI.toFloat()
            val point = add(
                hub,
                add(scale(pumpOut, cos(theta) * tubeR), scale(pumpUp, sin(theta) * tubeR)),
            )
            cylinderBetween3(previous, point, radius = PUMP_TUBE_R)
            program.setModel(model, 0.86f, 0.88f, 0.90f, specular = 0.5f, shininess = 40f, alpha = 1f)
            cyl.draw(program)
            previous = point
        }

        // THE VALVE, POINTING SIDEWAYS (change 37) -- along the dispense axis, because that is
        // the way material leaves. Built as a real fitting rather than one stub: a flange where it
        // meets the tube, a body, a lit collar, and a tapered tip.
        val flange = add(previous, scale(dispense, 0.006f))
        cylinderBetween3(previous, flange, radius = PUMP_TUBE_R * 2.1f)
        program.setModel(model, 0.30f, 0.32f, 0.36f, specular = 0.7f, shininess = 58f, rim = 0.5f)
        cyl.draw(program)

        val bodyEnd = add(flange, scale(dispense, PUMP_NOZZLE * 0.62f))
        cylinderBetween3(flange, bodyEnd, radius = PUMP_TUBE_R * 1.7f)
        program.setModel(model, 0.46f, 0.48f, 0.53f, specular = 0.88f, shininess = 84f, rim = 0.6f)
        cyl.draw(program)

        // Lit collar: the one part of the tool that says it is live.
        val collar = add(bodyEnd, scale(dispense, 0.004f))
        cylinderBetween3(bodyEnd, collar, radius = PUMP_TUBE_R * 1.85f)
        program.setModel(
            model, ACCENT[0], ACCENT[1], ACCENT[2],
            specular = 1f, shininess = 110f, rim = NEON_RIM,
        )
        cyl.draw(program)

        val nozzleTip = add(collar, scale(dispense, PUMP_NOZZLE * 0.38f))
        frustumBetween(nozzleTip, collar, radius = PUMP_TUBE_R * 1.6f)
        program.setModel(model, 0.24f, 0.26f, 0.30f, specular = 0.75f, shininess = 66f, rim = 0.5f)
        hopper.draw(program)
        return nozzleTip
    }

    /** Concentric rings spreading across the liquid surface where the stream lands (change 14.3). */
    private fun drawRipples(surface: FloatArray, axis: FloatArray, maxRadius: Float, timeS: Float) {
        GLES30.glDepthMask(false)
        for (i in 0 until RIPPLE_RINGS) {
            val phase = ((timeS * 0.85f) + i.toFloat() / RIPPLE_RINGS) % 1f
            val radius = maxRadius * (0.18f + 0.82f * phase)
            val alpha = LIQUID_ALPHA * (1f - phase) * 0.75f
            cylinderBetween3(
                add(surface, scale(axis, -0.002f)), add(surface, scale(axis, 0.002f)),
                radius = radius,
            )
            program.setModel(
                model, 0.62f, 0.86f, 0.98f, specular = 0.5f, shininess = 50f, alpha = alpha,
            )
            cyl.draw(program)
        }
        GLES30.glDepthMask(true)
    }

    /**
     * Material in flight between the module and the pail (change 14.3).
     *
     * Both directions trace the same arc, because in both cases the material travels between the
     * pail mouth and the machine standing beyond the screen edge; collecting runs along it inward,
     * pouring outward.
     *
     * The solid stream is a **cone of individually tumbling granules**. Each grain gets a
     * deterministic seed driving its phase, its lateral offset, its size, its shade and its own
     * rotation axis and rate, and the lateral spread widens along the path so the stream leaves the
     * module as a cone and funnels back into the mouth. The previous version drew axis-aligned boxes
     * with no rotation at a fixed offset, which is why it read as a flat dotted line.
     *
     * The liquid stream is a smooth tapered column with a lateral wobble, plus splash droplets
     * thrown off where it lands.
     */
    /**
     * Powder falling from the dispenser onto the tray (change 34).
     *
     * **It used to fly sideways.** The old cone ran 0.34 outward along the tool axis and dropped
     * 0.26 as the square of that -- a slanted parabola aimed at the module beyond the screen
     * edge, from a time when nothing was drawn at the module to aim AT. On the rig it read
     * as grain being thrown past the coordinate and out of frame, which is what "the molecules
     * move slanted in a weird way" and "it throws below" are both describing.
     *
     * Now it runs in a straight line from one end of the transfer to the other, with the same
     * constant-acceleration law the liquid uses -- travel `v0*u + (1-v0)*u^2` -- so the grains
     * visibly speed up as they fall. They are also bigger and much slower than before: the old
     * forty small fast grains read as a dust spray, and the material this rig doses is a granular
     * solid, not smoke.
     *
     * The scatter is a narrow disc about the fall axis rather than a widening cone, because the
     * fall is meant to look plumb. Each grain still tumbles on its own axis at its own rate.
     */
    private fun drawPowderStream(
        from: FloatArray,
        to: FloatArray,
        gravity: Boolean,
        payload: Payload,
        timeS: Float,
    ) {
        val span = sub(to, from)
        val length = len(span)
        if (length <= 1e-4f || payload.stream <= 0.01f) return
        val dir = scale(span, 1f / length)
        // A basis across the stream, so the scatter is a disc about its own axis whichever way it
        // points. The seed axis is swapped when the stream is near-vertical, or the cross collapses.
        val seedAxis = if (kotlin.math.abs(dir[1]) > 0.9f) floatArrayOf(1f, 0f, 0f) else UP
        val across = normalize(cross(dir, seedAxis))
        val through = normalize(cross(dir, across))

        for (i in 0 until POWDER_GRAINS) {
            // Deterministic per-grain jitter, so the scatter is granular but never flickers.
            val seed = (i * 0.6180339f) % 1f
            val seed2 = (i * 0.2794f) % 1f
            val seed3 = (i * 0.4373f) % 1f

            val u = ((timeS * POWDER_SPEED + seed) % 1f + 1f) % 1f
            // Falling grains accelerate, so they bunch at the start and stretch out. PUMPED grains
            // are pushed at a constant rate and stay evenly spaced, which is what makes a pumped
            // stream read as driven rather than dropped.
            val travel =
                if (gravity) (POWDER_EXIT * u + (1f - POWDER_EXIT) * u * u).coerceIn(0f, 1f) else u

            val angle = seed3 * 2f * Math.PI.toFloat()
            val spread = CONE_SPREAD * (0.35f + 0.65f * seed2)
            val p = add(
                add(from, scale(dir, length * travel)),
                add(scale(across, cos(angle) * spread), scale(through, sin(angle) * spread)),
            )

            val grainSize = (GRAIN_MIN + (GRAIN_MAX - GRAIN_MIN) * seed) * payload.stream
            val spinAxis = normalize(floatArrayOf(0.4f + seed, 1f - seed2, 0.3f + seed2))
            val spinDeg = (timeS * (40f + 90f * seed2) + seed * 360f) % 360f
            modelAtRotated(p, grainSize, spinAxis, spinDeg)

            // Shade jitter so the stream has depth rather than reading as one flat colour.
            val shade = 0.86f + 0.20f * seed2
            program.setModel(
                model,
                POWDER[0] * shade, POWDER[1] * shade, POWDER[2] * shade,
                specular = 0.10f, shininess = 10f,
            )
            grain.draw(program)
        }
    }

    /**
     * Draw translucent geometry.
     *
     * Depth writes are switched off so overlapping translucent parts of the same body do not punch
     * holes in each other; depth *testing* stays on so the opaque machine still occludes them.
     */
    private inline fun drawTranslucent(body: (alpha: Float) -> Unit) {
        GLES30.glDepthMask(false)
        body(LIQUID_ALPHA)
        GLES30.glDepthMask(true)
    }

    // -- World mapping ---------------------------------------------------------------------------

    /** Lift a reach-plane point (u along facing, planeY screen-style) into world space. */
    private fun worldOf(u: Float, planeY: Float, carriageX: Float, yaw: Float): FloatArray {
        val f = forward(yaw)
        return floatArrayOf(
            carriageX + u * f[0],
            Arena3D.SHOULDER_Y - planeY,
            u * f[2],
        )
    }

    private fun forward(yaw: Float) = floatArrayOf(sin(yaw), 0f, cos(yaw))
    private fun sideAxis(yaw: Float) = normalize(cross(forward(yaw), UP))

    // -- Matrix builders -------------------------------------------------------------------------

    private fun modelBox(px: Float, py: Float, pz: Float, sx: Float, sy: Float, sz: Float, yawDeg: Float = 0f) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, px, py, pz)
        if (yawDeg != 0f) Matrix.rotateM(model, 0, yawDeg, 0f, 1f, 0f)
        Matrix.scaleM(model, 0, sx, sy, sz)
    }

    private fun modelAt(p: FloatArray, sx: Float, sy: Float, sz: Float) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, p[0], p[1], p[2])
        Matrix.scaleM(model, 0, sx, sy, sz)
    }

    /**
     * Place a uniformly-scaled object spun about an arbitrary axis (change 14.3).
     *
     * [modelAt] has no rotation, which is why the old material stream rendered every grain
     * presenting the same face to the camera. Granules need to tumble to read as solids.
     */
    private fun modelAtRotated(p: FloatArray, size: Float, axis: FloatArray, angleDeg: Float) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, p[0], p[1], p[2])
        Matrix.rotateM(model, 0, angleDeg, axis[0], axis[1], axis[2])
        Matrix.scaleM(model, 0, size, size, size)
    }

    /**
     * Orient the unit frustum (axis +Y) so it spans A..B, with [radius] as its widest radius.
     *
     * Same convention as [cylinderBetween3]; the taper itself lives in the mesh.
     */
    private fun frustumBetween(a: FloatArray, b: FloatArray, radius: Float) {
        val dir = sub(b, a)
        val length = len(dir).coerceAtLeast(1e-4f)
        val mid = floatArrayOf((a[0] + b[0]) / 2f, (a[1] + b[1]) / 2f, (a[2] + b[2]) / 2f)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, mid[0], mid[1], mid[2])
        alignYTo(scale(dir, 1f / length))
        Matrix.multiplyMM(scratch, 0, model, 0, rot, 0)
        System.arraycopy(scratch, 0, model, 0, 16)
        Matrix.scaleM(model, 0, radius * 2f, length, radius * 2f)
    }

    /**
     * Place the screw-thread mesh along the rail (+X), spun about its own axis by [spinDeg].
     *
     * The spin is applied *before* the axis alignment, so it turns about the screw rather than about
     * a world axis. A helix rotated about its own axis appears to translate along it, which is what
     * makes the screw read as driving the carriage.
     */
    private fun screwModel(px: Float, py: Float, pz: Float, length: Float, radius: Float, spinDeg: Float) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, px, py, pz)
        alignYTo(floatArrayOf(1f, 0f, 0f))
        Matrix.multiplyMM(scratch, 0, model, 0, rot, 0)
        System.arraycopy(scratch, 0, model, 0, 16)
        Matrix.rotateM(model, 0, spinDeg, 0f, 1f, 0f)
        Matrix.scaleM(model, 0, radius * 2f, length, radius * 2f)
    }

    /**
     * Place a mesh spanning A..B, spun about its own axis (change 15.1).
     *
     * Same as [cylinderBetween3] but with a roll applied before the axis alignment, so the geometry
     * turns about the line it lies along rather than about a world axis. Used for the auger
     * flighting; [screwModel] does the same thing for the rail, which is fixed to +X.
     */
    private fun spunBetween(a: FloatArray, b: FloatArray, radius: Float, spinDeg: Float) {
        val dir = sub(b, a)
        val length = len(dir).coerceAtLeast(1e-4f)
        val mid = floatArrayOf((a[0] + b[0]) / 2f, (a[1] + b[1]) / 2f, (a[2] + b[2]) / 2f)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, mid[0], mid[1], mid[2])
        alignYTo(scale(dir, 1f / length))
        Matrix.multiplyMM(scratch, 0, model, 0, rot, 0)
        System.arraycopy(scratch, 0, model, 0, 16)
        Matrix.rotateM(model, 0, spinDeg, 0f, 1f, 0f)
        Matrix.scaleM(model, 0, radius * 2f, length, radius * 2f)
    }

    private fun cylinderBetween(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, radius: Float) {
        cylinderBetween3(floatArrayOf(ax, ay, az), floatArrayOf(bx, by, bz), radius)
    }

    /** Orient the unit cylinder (axis +Y) so it spans A..B with the given radius. */
    private fun cylinderBetween3(a: FloatArray, b: FloatArray, radius: Float) {
        val dir = sub(b, a)
        val length = len(dir).coerceAtLeast(1e-4f)
        val mid = floatArrayOf((a[0] + b[0]) / 2f, (a[1] + b[1]) / 2f, (a[2] + b[2]) / 2f)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, mid[0], mid[1], mid[2])
        alignYTo(scale(dir, 1f / length))
        Matrix.multiplyMM(scratch, 0, model, 0, rot, 0)
        System.arraycopy(scratch, 0, model, 0, 16)
        Matrix.scaleM(model, 0, radius * 2f, length, radius * 2f)
    }

    private val rot = FloatArray(16)

    /** Build [rot] as the rotation taking +Y to unit vector [d]. */
    private fun alignYTo(d: FloatArray) {
        Matrix.setIdentityM(rot, 0)
        val dot = d[1].coerceIn(-1f, 1f)
        if (dot > 0.9999f) return
        if (dot < -0.9999f) {
            Matrix.setRotateM(rot, 0, 180f, 1f, 0f, 0f)
            return
        }
        val axis = normalize(cross(UP, d))
        val angle = Math.toDegrees(acos(dot).toDouble()).toFloat()
        Matrix.setRotateM(rot, 0, angle, axis[0], axis[1], axis[2])
    }

    // -- vec helpers -----------------------------------------------------------------------------

    private fun sub(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
    private fun add(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])
    private fun scale(a: FloatArray, s: Float) = floatArrayOf(a[0] * s, a[1] * s, a[2] * s)
    private fun len(a: FloatArray) = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    private fun smoothstep(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Rodrigues rotation of [v] about unit [axis] by [angle] radians. */
    private fun rotateAbout(v: FloatArray, axis: FloatArray, angle: Float): FloatArray {
        val c = cos(angle)
        val s = sin(angle)
        val dot = axis[0] * v[0] + axis[1] * v[1] + axis[2] * v[2]
        val cr = cross(axis, v)
        return floatArrayOf(
            v[0] * c + cr[0] * s + axis[0] * dot * (1 - c),
            v[1] * c + cr[1] * s + axis[1] * dot * (1 - c),
            v[2] * c + cr[2] * s + axis[2] * dot * (1 - c),
        )
    }

    private companion object {
        val UP = floatArrayOf(0f, 1f, 0f)

        /** Flat studio backdrop (change 14.1), matching LeumasColors.Backdrop (#BFC5CC). */
        val BACKDROP = floatArrayOf(0.749f, 0.773f, 0.800f)

        // Contact shadow under the machine (change 14.1).
        //
        // SHADOW_Y sits below the rail extrusion, which spans -0.055..0.045 — at the original
        // -0.005 the discs were *inside* the rail body and poked through it.
        const val SHADOW_RINGS = 12
        const val SHADOW_Y = -0.10f
        const val SHADOW_Z = 0.02f
        const val SHADOW_INNER = 0.30f
        const val SHADOW_OUTER = 1.85f
        const val SHADOW_SQUASH = 0.26f
        const val SHADOW_ALPHA = 0.055f

        /** How far behind the turret centre the A2 drive motor sits (change 14.7). */
        const val DRUM_OFFSET = 0.17f

        /** Cooling ribs along the A2 motor body (change 44). */
        const val MOTOR_RIBS = 4

        /** Brushed steel for the pail (change 14.4). */
        val STEEL = floatArrayOf(0.62f, 0.65f, 0.70f)

        // Material colours (change 13.8).
        val POWDER = floatArrayOf(0.85f, 0.74f, 0.48f)
        val LIQUID = floatArrayOf(0.24f, 0.62f, 0.88f)
        const val LIQUID_ALPHA = 0.72f

        /**
         * Thread starts along the ball screw (change 13.5).
         *
         * Shared by the mesh and the spin rate: the lead is `span / SCREW_TURNS`, so the thread
         * advances exactly one pitch per revolution and the screw reads as driving the carriage.
         */
        const val SCREW_TURNS = 26

        const val CHAIN_LINKS = 14

        /** Hold-down bolts down each flank of the rail bed (change 47). */
        const val RAIL_BOLTS = 16

        // Process vessel on the end effector (change 15.1).
        /** Opacity of the glass vessel body: enough to read as glass, clear enough to see the level. */
        const val GLASS_ALPHA = 0.40f

        const val VESSEL_RADIUS = 0.075f
        const val VESSEL_HEIGHT = 0.110f
        /** Wrist to vessel flange. Change 16.6: tightened from 0.085 so the neck is a short
         * machined section rather than a span the eye reads as a gap the vessel hangs across. */
        const val VESSEL_DROP = 0.062f

        /** Radius of the neck joining the wrist race to the vessel flange (change 16.6). */
        const val NECK_RADIUS = 0.040f
        const val HOPPER_HEIGHT = 0.045f

        // Peristaltic head, for dosing liquid.
        const val PUMP_RADIUS = 0.056f
        const val PUMP_WIDTH = 0.042f
        const val PUMP_ORBIT = 0.034f
        const val PUMP_ROLLER_R = 0.013f
        const val PUMP_ROLLERS = 3
        const val PUMP_TUBE_R = 0.009f
        const val PUMP_TUBE_SEGMENTS = 10
        const val PUMP_NOZZLE = 0.034f

        /**
         * The flexible tube's sweep round the race, in half-turns: from straight up out of the
         * hopper, round the front, to pointing down and outward.
         *
         */
        /**
         * The tube's sweep round the race, in half-turns of pi: from straight up out of the hopper
         * round to the DISPENSE side (change 37), where the valve is.
         *
         * It used to carry on to 1.15 and point the outlet at the floor, which was right when
         * material was dropped and wrong now that it is pumped horizontally into the module's wall.
         * A valve aimed down while the stream leaves sideways is the machine contradicting itself.
         */
        const val PUMP_TUBE_FROM = 0.50f
        const val PUMP_TUBE_ARC = -0.50f

        const val POWDER_GRAINS = 40
        /**
         * Grain release rate. Change 34 slowed this from 1.35 -- with bigger grains the old rate
         * read as a blur, and the requirement is for the drop to be graceful.
         */
        const val POWDER_SPEED = 0.45f

        /** Widest lateral spread of the granule cone, at the module end (change 14.3). */
        /**
         * Lateral scatter of the falling powder, as a radius about the fall axis.
         *
         * Change 34 cut this from 0.055 and changed its meaning: it was the widest point of a cone
         * that FANNED OUT along a slanted arc, and it is now a narrow, constant disc about a plumb
         * drop. The fall is supposed to look vertical.
         */
        const val CONE_SPREAD = 0.016f

        /** Grain size. Change 34 roughly doubled these: the old ones read as dust, not granules. */
        const val GRAIN_MIN = 0.020f
        const val GRAIN_MAX = 0.034f

        /**
         * How far material is pumped out of the dispenser into the module's wall (change 36).
         *
         * The arm brings the dispenser up against the calibrated point and the stream runs
         * from there outward, past the arena edge and off frame, where the real module is.
         */
        const val DISPENSE_REACH = 0.34f

        /**
         * How far above the vessel mouth an incoming charge is drawn from (change 36).
         *
         * Long enough to read as falling from the module overhead rather than appearing at
         * the rim, short enough not to start above the wrist and look like it comes from the
         * arm's own elbow.
         */
        const val POWDER_RISE = 0.26f

        /** Speed the powder leaves the dispenser at, relative to its landing speed. */
        const val POWDER_EXIT = 0.40f


        const val LIQUID_SEGMENTS = 14
        const val LIQUID_RADIUS = 0.026f
        const val SPLASH_DROPS = 6
        const val RIPPLE_RINGS = 3


        // Falling liquid (change 25.2, reworked by 26).
        /**
         * Speed the stream leaves at, as a fraction of its speed on landing. Sets how hard the
         * column necks down: radius goes as 1/sqrt(v), so 0.45 narrows it to about 54% by the
         * bottom of the fall.
         */
        const val EXIT_SPEED = 0.45f

        /**
         * How far the stream falls before it is out of frame and into the module (change 26).
         *
         * Shortened from 0.58 after seeing it on the rig. Length here is not a free choice: every
         * feature of the stream is placed as a FRACTION of it, and the modules are calibrated on the
         * frame edges, so only the first fifth or so of a long fall is ever on screen. At 0.58 the
         * whole visible run was the smooth head of the column -- the ripples had not grown and the
         * breakup was hundreds of pixels below the bottom of the display. It was a featureless blue
         * cylinder, which is exactly the complaint. At 0.34 the visible part covers roughly the
         * first half, which is where the waves grow and the column starts to bead.
         */
        const val POUR_FALL = 0.34f

        /** Fraction of a fall that holds together before breaking into drops. */
        const val FALL_BREAKUP = 0.45f

        /** Where a dispersing pour's drops start shrinking away to nothing. */
        const val DROP_FADE = 0.62f

        /**
         * Varicose surface waves along the column (change 26).
         *
         * A falling jet is unstable -- surface tension amplifies these until the neck pinches and it
         * beads. They are what stops the column reading as a smooth painted cone: the bulges vary
         * the silhouette, so it turns in the light as it moves. Amplitude grows as u^2 so the
         * breakup happens where the waves have actually grown, rather than at an arbitrary cut.
         */
        const val WAVE_AMP = 0.34f
        const val WAVE_FREQ = 34f
        const val WAVE_SPEED = 5.2f

        /**
         * Cylinders stacked to fake the column's taper, and how far each overlaps the next.
         *
         * Fine enough that the radius step between neighbours is sub-pixel; see [drawLiquidFall]
         * for why a coarse stack reads as a corkscrew.
         */
        const val COLUMN_SEGMENTS = 30
        const val COLUMN_OVERLAP = 0.003f

        const val FALL_DROPS = 13
        const val FALL_RATE = 1.15f

        /** Jitter on a drop's release phase, so the tail is not an evenly spaced necklace. */
        const val DROP_JITTER = 0.055f

        /** How far drops wander off the axis, through a disc rather than a plane (change 26). */
        const val DROP_SPREAD = 0.030f


        // Arm dressing (change 37).
        /**
         * Neon. No emissive channel exists, so every light on this machine is a near-white-hot
         * colour drawn at full rim -- the fresnel term lifts the silhouette, which is what reads as
         * a glow. Kept just off pure cyan so it kicks against the cool white shell rather than
         * blending into it.
         */
        val NEON = floatArrayOf(0.20f, 0.58f, 0.68f)

        /**
         * Rim weight for the lights (change 41).
         *
         * They were drawn at a full 1.0, which drives the fresnel term to its maximum on every
         * grazing pixel and blows the ring out to near-white -- the pigment underneath stops
         * mattering at all. Dimming only the colour would not have fixed that; the rim is the
         * half doing the shouting. Both come down together here.
         */
        const val NEON_RIM = 0.55f

        /** Legacy alias: the pump and vessel lights use the same neon. */
        val ACCENT = NEON

        /**
         * The shell colour (change 39).
         *
         * A deep warm red rather than a primary one: pure red reads as plastic under this lighting
         * because the green and blue channels go to nothing and the shading gradient has only one
         * channel left to work in. Keeping a little of both leaves the form legible on the shadow
         * side, which is exactly where the machine has to look solid.
         */
        val SHELL = floatArrayOf(0.72f, 0.13f, 0.14f)

        /**
         * Gloss black for every pivot, collar and machined boss (change 45).
         *
         * The joints were mid-greys -- 0.14 to 0.30 at specular 0.6-0.85 -- which put them in the
         * same tonal band as the pedestal behind them and left the machine reading as red parts on
         * a grey machine. Taking them to near-black and the gloss to maximum splits the palette
         * into three things that cannot be confused: warm red castings, black polished pivots, and
         * the cyan light. That is the automotive scheme, and it works because black at this gloss
         * carries almost no diffuse tone -- what you see is the HIGHLIGHT sliding over it, which
         * is pure shape information and exactly what a pivot wants to show.
         *
         * Kept a touch blue rather than neutral: a cool black reads as blued steel next to a warm
         * red, where a neutral one reads as a hole.
         */
        val JOINT = floatArrayOf(0.045f, 0.047f, 0.052f)

        /** A step up from [JOINT], for machined faces that must separate from the black around them. */
        val MACHINED = floatArrayOf(0.105f, 0.109f, 0.118f)

        /**
         * Polished bezel, replacing the neon ring that used to sit on each pivot (change 46).
         *
         * The joints still read BLUE, and the pigment was never the reason -- a cyan ring the full
         * width of the drive housing was the largest single feature on every axis, so whatever
         * colour the casting under it was, the joint was a blue disc. Colour cannot fix a
         * composition problem. The ring is now dark chrome: it still catches a hard highlight at
         * the same place, which is what gave the pivot its edge, but the axis reads as black
         * polished metal with a bright rim rather than as a light.
         *
         * The lights are not gone from the machine, only from the pivots -- the bearing band, the
         * A6 split line, the pump and the vessel still carry the accent, which is where it belongs:
         * on the parts that are actually doing something.
         */
        val BEZEL = floatArrayOf(0.30f, 0.31f, 0.33f)

        // The arm as a mechanism (change 40).
        /** Fraction of a member's length taken by its heavy root collar. */
        const val LIMB_ROOT = 0.16f

        /** The `limb` frustum's own taper (0.42/0.50), needed to place bands exactly on it. */
        const val LIMB_TAPER = 0.84f

        // ---- THE CLEVIS CONTRACT (change 42) ----------------------------------------------
        //
        // The pivots were merging into the members, and the reason was that NOTHING TIED THE TWO
        // HALVES TOGETHER. drawLimb sized its fork from the LIMB radius, drawJoint sized its boss
        // from the JOINT radius, and those are different numbers at every axis -- so the fit was
        // whatever the two happened to work out to. Measured:
        //
        //     elbow   fork inner face 0.0225   boss face 0.0350   -> 0.0125 of solid overlap
        //     wrist   fork inner face 0.0180   boss face 0.0275   -> 0.0095 of solid overlap
        //
        // That is 16-17% of a limb radius of one casting buried inside another, at every pivot.
        // Worse, the fork plates were CYLINDERS RUN ALONG THE MEMBER, so each was as wide across
        // the pivot axis as it was deep -- 0.093 wide when the gap it had to fit was 0.070 -- and
        // it could not help but swallow the boss. And the actuator pod, offset 0.92..1.32 joint
        // radii along that same axis, sat entirely INSIDE the plate, on one flank only, which is
        // where the lopsidedness came from.
        //
        // Everything below is measured in ONE unit: the radius of the pivot it belongs to. Both
        // halves read the same constants, so the fit is arithmetic rather than eyeballed, and the
        // parts land in a fixed order out from the centre-line:
        //
        //     0.00 - 0.50   boss           (drawJoint)  the load-bearing barrel
        //     0.50 - 0.66   AIR            CLEVIS_GAP   daylight, which is what makes the pivot
        //                                               read as two parts instead of one lump
        //     0.66 - 0.92   lug            (drawLimb)   flat eye, faces square to the pivot axis
        //     0.92 - 1.14   drive housing  (drawJoint)  seats flush on the lug's outer face
        //     1.14 - 1.19   neon ring      (drawJoint)
        //     1.19 - 1.25   pin cap        (drawJoint)
        //
        // Both flanks get the identical stack, so the axis is symmetric about itself -- that is
        // the "no unevenness" half. The pin ends at 0.92, flush inside the lug faces rather than
        // standing proud of them, and the whole assembly is 1.25 joint radii half-width against
        // the old pod's 1.32, so it is flatter as well as even.
        const val BOSS_HALF = 0.50f
        const val BOSS_R = 0.84f
        const val CLEVIS_GAP = 0.16f
        const val CLEVIS_IN = BOSS_HALF + CLEVIS_GAP      // 0.66 -- lug inner face
        const val CLEVIS_OUT = 0.92f                      // lug outer face, and the pin's ends
        const val LUG_R = 1.10f
        const val PIN_R = 0.20f

        /**
         * The drive housing, which replaces the flank-mounted pod.
         *
         * Coaxial with the pin rather than hung off the side of it. That fixes three things at
         * once: it cannot foul the fork, because it sits on the lug's own machined face; it can be
         * mirrored onto both flanks, so the axis stops looking lopsided; and a shallow disc square
         * to the pivot is flatter than a drum cantilevered off it.
         */
        const val HOUSING_OUT = 1.14f
        const val HOUSING_R = 0.60f
        const val NEON_OUT = 1.19f
        const val CAP_OUT = 1.25f
        const val CAP_R = 0.34f

        /**
         * Where a member's casting starts, out from its own pivot, in joint radii.
         *
         * It has to clear the PREVIOUS member's lug, which is a disc of radius LUG_R centred on
         * that pivot -- anything closer in than LUG_R cuts straight through it. The gap between
         * pivot and casting is spanned by a neck, which at NECK_R is thinner than CLEVIS_IN and
         * therefore threads between the two lugs without touching either.
         */
        const val ROOT_STANDOFF = 1.16f
        const val NECK_R = 0.60f

        /** Joint radii, named once so a member and its pivot cannot disagree about them. */
        const val J_SHOULDER = 0.085f
        const val J_ELBOW = 0.070f
        const val J_WRIST = 0.055f

        /** Fraction of the ram that is barrel rather than exposed rod. */
        const val RAM_BARREL = 0.52f

        const val JOINT_BOLTS = 8
        const val PUMP_BOLTS = 10

        // The vessel-to-pump service wire (change 39).
        const val WIRE_SEGMENTS = 9
        const val WIRE_RADIUS = 0.007f
        const val WIRE_BOW = 0.055f
        const val FLANGE_BOLTS = 8
        const val CABLE_SEGMENTS = 10
        const val CABLE_RADIUS = 0.009f
        const val CABLE_SAG = 0.10f

        /** Band over which the hand's side axis blends to the turret fallback (change 13.3). */
        const val SIDE_BLEND_LO = 0.03f
        const val SIDE_BLEND_HI = 0.20f

        fun normalize(v: FloatArray): FloatArray {
            val l = hypot(hypot(v[0], v[1]), v[2])
            return if (l < 1e-6f) v else floatArrayOf(v[0] / l, v[1] / l, v[2] / l)
        }

        /** Right-handed perspective, matching gluPerspective. */
        fun perspective(out: FloatArray, fovyDeg: Float, aspect: Float, near: Float, far: Float) {
            val f = (1.0 / kotlin.math.tan(Math.toRadians(fovyDeg / 2.0))).toFloat()
            java.util.Arrays.fill(out, 0f)
            out[0] = f / aspect
            out[5] = f
            out[10] = (far + near) / (near - far)
            out[11] = -1f
            out[14] = (2f * far * near) / (near - far)
        }
    }
}
