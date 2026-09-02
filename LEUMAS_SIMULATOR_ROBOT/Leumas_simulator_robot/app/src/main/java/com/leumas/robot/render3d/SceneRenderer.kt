package com.leumas.robot.render3d

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.leumas.robot.render.Kinematics
import kotlin.math.acos
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

        // Extrusion body, with two inset side grooves to read as a T-slot profile.
        modelBox(0f, -0.005f, 0f, span, 0.10f, 0.30f)
        program.setModel(model, 0.62f, 0.65f, 0.69f, specular = 0.22f, shininess = 26f)
        box.draw(program)
        listOf(-1f, 1f).forEach { s ->
            modelBox(0f, -0.005f, s * 0.152f, span * 0.995f, 0.036f, 0.02f)
            program.setModel(model, 0.34f, 0.37f, 0.41f, specular = 0.15f, shininess = 18f)
            box.draw(program)
        }

        // Round guide shafts either side of the screw.
        listOf(-1f, 1f).forEach { s ->
            cylinderBetween(-half, 0.075f, s * 0.105f, half, 0.075f, s * 0.105f, radius = 0.026f)
            program.setModel(model, 0.80f, 0.83f, 0.86f, specular = 0.75f, shininess = 60f)
            cyl.draw(program)
        }

        // Ball screw: a polished core plus the helical thread riding on it. The thread's spin is a
        // pure function of carriage position, so it can never drift out of step with the motion.
        cylinderBetween(-half, 0.075f, 0f, half, 0.075f, 0f, radius = 0.021f)
        program.setModel(model, 0.72f, 0.75f, 0.78f, specular = 0.6f, shininess = 48f)
        cyl.draw(program)

        // One revolution must advance the carriage by exactly one thread pitch, or the thread
        // visibly creeps against the motion instead of driving it.
        val lead = span / SCREW_TURNS
        val spinDeg = carriageX / lead * 360f
        screwModel(0f, 0.075f, 0f, span, radius = 0.036f, spinDeg = spinDeg)
        program.setModel(model, 0.76f, 0.79f, 0.82f, specular = 0.7f, shininess = 54f)
        thread.draw(program)

        // Pillow blocks / screw supports at both ends.
        listOf(-1f, 1f).forEach { s ->
            modelBox(s * half, 0.09f, 0f, 0.11f, 0.26f, 0.34f)
            program.setModel(model, 0.28f, 0.31f, 0.35f, specular = 0.18f, shininess = 20f)
            box.draw(program)
            cylinderBetween(
                s * half - 0.035f, 0.075f, 0f, s * half + 0.035f, 0.075f, 0f, radius = 0.055f,
            )
            program.setModel(model, 0.16f, 0.17f, 0.19f, specular = 0.4f, shininess = 34f)
            cyl.draw(program)
        }

        drawCableChain(carriageX, half)

        // Carriage riding the rail.
        modelBox(carriageX, 0.055f, 0f, 0.34f, 0.13f, 0.32f)
        program.setModel(model, 0.30f, 0.33f, 0.37f, specular = 0.24f, shininess = 24f)
        box.draw(program)
        // Ball-nut wrapped around the screw.
        cylinderBetween(
            carriageX - 0.075f, 0.075f, 0f, carriageX + 0.075f, 0.075f, 0f, radius = 0.052f,
        )
        program.setModel(model, 0.18f, 0.20f, 0.23f, specular = 0.45f, shininess = 40f)
        cyl.draw(program)
        // Linear bearings on the guide shafts.
        listOf(-1f, 1f).forEach { s ->
            cylinderBetween(
                carriageX - 0.06f, 0.075f, s * 0.105f, carriageX + 0.06f, 0.075f, s * 0.105f,
                radius = 0.042f,
            )
            program.setModel(model, 0.20f, 0.22f, 0.25f, specular = 0.4f, shininess = 34f)
            cyl.draw(program)
        }
        // Mounting plate on top of the carriage.
        modelBox(carriageX, 0.128f, 0f, 0.40f, 0.03f, 0.36f)
        program.setModel(model, 0.22f, 0.24f, 0.28f, specular = 0.25f, shininess = 22f)
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
            program.setModel(model, 0.14f, 0.15f, 0.17f, specular = 0.12f, shininess = 14f)
            box.draw(program)
        }
    }

    // -- Robot -----------------------------------------------------------------------------------

    private fun drawRobot(pose: Robot3DPose, timeS: Float) {
        val cx = pose.carriageX
        val yawDeg = Math.toDegrees(pose.baseYaw.toDouble()).toFloat()

        // Base pedestal (fixed, rides the carriage).
        modelBox(cx, 0.22f, 0f, 0.32f, 0.16f, 0.30f)
        program.setModel(model, 0.19f, 0.21f, 0.24f, specular = 0.15f, shininess = 14f)
        box.draw(program)
        modelBox(cx, 0.37f, 0f, 0.26f, 0.16f, 0.24f)
        program.setModel(model, 0.22f, 0.24f, 0.27f, specular = 0.15f, shininess = 14f)
        box.draw(program)

        // Slewing bearing ring (white), the A1 axis — a flat disc.
        cylinderBetween(cx, 0.44f, 0f, cx, 0.47f, 0f, radius = 0.15f)
        program.setModel(model, 0.88f, 0.89f, 0.91f, specular = 0.5f, shininess = 40f)
        cyl.draw(program)

        // Everything above the ring yaws with A1.
        // Turret casting (red).
        // Change 38: white, like the rest of it. The red casting was the last thing on the
        // machine fighting the shell for attention, and in the references this casting is the
        // largest clean surface on the whole arm.
        modelBox(cx, Arena3D.SHOULDER_Y - 0.06f, 0f, 0.24f, 0.22f, 0.22f, yawDeg = yawDeg)
        program.setModel(model, SHELL[0], SHELL[1], SHELL[2], specular = 1f, shininess = 120f, rim = 0.45f)
        box.draw(program)

        // A neon band round the turret's base, where it meets the slewing ring.
        cylinderBetween(cx, Arena3D.SHOULDER_Y - 0.165f, 0f, cx, Arena3D.SHOULDER_Y - 0.150f, 0f,
                        radius = 0.135f)
        program.setModel(model, NEON[0], NEON[1], NEON[2], specular = 1f, shininess = 128f, rim = 1f)
        cyl.draw(program)

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
        val drumY = Arena3D.SHOULDER_Y - 0.02f

        // Boss joining the turret casting to the drum.
        cylinderBetween(
            cx - fwd[0] * 0.05f, drumY, -fwd[2] * 0.05f,
            drumX, drumY, drumZ,
            radius = 0.055f,
        )
        program.setModel(model, 0.14f, 0.14f, 0.15f, specular = 0.3f, shininess = 26f)
        cyl.draw(program)

        cylinderBetween(
            drumX - fwd[0] * 0.06f, drumY, drumZ - fwd[2] * 0.06f,
            drumX + fwd[0] * 0.06f, drumY, drumZ + fwd[2] * 0.06f,
            radius = 0.11f,
        )
        program.setModel(model, 0.08f, 0.08f, 0.08f, specular = 0.3f, shininess = 30f)
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
        drawLimb(shoulder, elbow, 0.075f, side = sideAxis(pose.baseYaw))
        drawLimb(elbow, wrist, 0.060f, side = sideAxis(pose.baseYaw))
        drawLimb(wrist, gripper, 0.048f, side = sideAxis(pose.baseYaw))

        // THE RAM (change 40). This was a plain chrome rod, and a plain rod is the one part of a
        // linkage that carries no information: you cannot tell which way it drives or where the
        // power comes from. It is now a real actuator -- a fat barrel anchored at the shoulder, a
        // polished rod extending from it, and a clevis at each end -- which is the single most
        // recognisable mechanical component there is and reads instantly as a machine under load.
        val rodA = floatArrayOf(shoulder[0], shoulder[1] + 0.10f, shoulder[2])
        val rodB = floatArrayOf(elbow[0], elbow[1] + 0.08f, elbow[2])
        val ramDir = normalize(sub(rodB, rodA))
        val ramMid = add(rodA, scale(ramDir, len(sub(rodB, rodA)) * RAM_BARREL))

        // Barrel.
        cylinderBetween3(rodA, ramMid, radius = 0.030f)
        program.setModel(model, 0.24f, 0.25f, 0.28f, specular = 0.85f, shininess = 88f, rim = 0.5f)
        cyl.draw(program)
        // Gland nut at the barrel mouth.
        cylinderBetween3(
            sub(ramMid, scale(ramDir, 0.010f)), add(ramMid, scale(ramDir, 0.006f)), radius = 0.034f,
        )
        program.setModel(model, 0.40f, 0.42f, 0.46f, specular = 0.9f, shininess = 96f, rim = 0.6f)
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
            program.setModel(model, 0.19f, 0.20f, 0.23f, specular = 0.7f, shininess = 62f, rim = 0.4f)
            cyl.draw(program)
        }

        // The dress cable. Every arm in the reference photographs has one and it is most of what
        // reads as "machine" rather than "shape": a loom slung from the shoulder, sagging past the
        // elbow, clipped to the wrist. Drawn as a quadratic so it bows the way a cable hangs.
        drawCableRun(rodA, elbow, wrist)

        // Joint housings, each a lit bearing rather than a dark stub.
        drawJoint(shoulder, pose.baseYaw, 0.085f)
        drawJoint(elbow, pose.baseYaw, 0.070f)
        drawJoint(wrist, pose.baseYaw, 0.055f)

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
    private fun drawLimb(a: FloatArray, b: FloatArray, radius: Float, side: FloatArray) {
        val span = sub(b, a)
        val length = len(span)
        if (length < 1e-4f) return
        val dir = scale(span, 1f / length)

        val rootEnd = add(a, scale(dir, length * LIMB_ROOT))
        val forkStart = add(b, scale(dir, -length * LIMB_FORK))

        // Root collar: the heavy end, where load enters from the pivot behind it.
        cylinderBetween3(a, rootEnd, radius = radius * 1.16f)
        program.setModel(model, SHELL[0], SHELL[1], SHELL[2],
                         specular = 1f, shininess = 128f, rim = 0.5f)
        cyl.draw(program)

        // Dark relief groove at the root's shoulder, so the collar reads as a separate casting
        // bolted on rather than as a bulge in the same tube.
        cylinderBetween3(
            sub(rootEnd, scale(dir, 0.008f)), add(rootEnd, scale(dir, 0.008f)),
            radius = radius * 1.02f,
        )
        program.setModel(model, 0.15f, 0.16f, 0.18f, specular = 0.5f, shininess = 44f)
        cyl.draw(program)

        // The shell, tapering root -> waist.
        frustumBetween(forkStart, rootEnd, radius = radius * 1.08f)
        program.setModel(model, SHELL[0], SHELL[1], SHELL[2],
                         specular = 1f, shininess = 128f, rim = 0.55f)
        limb.draw(program)

        // Waist band, and a machined face plate on the flank. Two edges where there were none.
        val waist = add(a, scale(dir, length * 0.62f))
        cylinderBetween3(
            sub(waist, scale(dir, 0.014f)), add(waist, scale(dir, 0.014f)),
            radius = radius * 1.01f,
        )
        program.setModel(model, 0.30f, 0.31f, 0.34f, specular = 0.85f, shininess = 88f, rim = 0.5f)
        cyl.draw(program)

        // THE FORK: two plates straddling the pivot, which the next member's boss sits between.
        listOf(-1f, 1f).forEach { sgn ->
            val plane = scale(side, radius * LIMB_FORK_GAP * sgn)
            cylinderBetween3(
                add(forkStart, plane),
                add(add(b, scale(dir, radius * 0.30f)), plane),
                radius = radius * 0.62f,
            )
            program.setModel(model, SHELL[0], SHELL[1], SHELL[2],
                             specular = 1f, shininess = 128f, rim = 0.5f)
            cyl.draw(program)

            // Eye around the pin, proud of the plate.
            val eye = add(b, plane)
            cylinderBetween3(
                add(eye, scale(side, radius * 0.10f * sgn)),
                add(eye, scale(side, radius * 0.30f * sgn)),
                radius = radius * 0.86f,
            )
            program.setModel(model, 0.22f, 0.23f, 0.26f, specular = 0.8f, shininess = 82f, rim = 0.5f)
            cyl.draw(program)

            // Neon round the eye -- the light belongs on the OUTSIDE of the fork, which is the
            // face the camera sees, not buried in the middle of the assembly.
            cylinderBetween3(
                add(eye, scale(side, radius * 0.30f * sgn)),
                add(eye, scale(side, radius * 0.40f * sgn)),
                radius = radius * 0.80f,
            )
            program.setModel(model, NEON[0], NEON[1], NEON[2],
                             specular = 1f, shininess = 128f, rim = 1f)
            cyl.draw(program)
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
        // The pivot itself: the boss that sits BETWEEN the previous member's fork arms, the pin
        // through it, a black cap, and the actuator that drives it.
        //
        // Change 40 made the cap BLACK. It was white, which put the brightest thing on the machine
        // in the middle of every joint and flattened the neon into a thin outline around a glare.
        // Against black the ring is the only lit thing there, so it reads as light rather than as
        // paint -- and a dark pivot between pale members is what every reference photograph shows.
        val side = sideAxis(yaw)

        // Load-bearing boss, narrow, spanning the fork gap.
        cylinderBetween3(
            sub(p, scale(side, radius * 0.55f)), add(p, scale(side, radius * 0.55f)),
            radius = radius * 0.94f,
        )
        program.setModel(model, 0.14f, 0.15f, 0.17f, specular = 0.6f, shininess = 56f)
        cyl.draw(program)

        // The pin, running right through and standing proud on both sides.
        cylinderBetween3(
            sub(p, scale(side, radius * 1.35f)), add(p, scale(side, radius * 1.35f)),
            radius = radius * 0.24f,
        )
        program.setModel(model, 0.66f, 0.69f, 0.74f, specular = 1f, shininess = 120f, rim = 0.7f)
        cyl.draw(program)

        // Black cap on each end of the pin.
        listOf(-1f, 1f).forEach { sgn ->
            val face = add(p, scale(side, radius * 1.35f * sgn))
            cylinderBetween3(
                face, add(face, scale(side, radius * 0.10f * sgn)), radius = radius * 0.40f,
            )
            program.setModel(model, 0.05f, 0.05f, 0.06f, specular = 0.7f, shininess = 90f, rim = 0.3f)
            cyl.draw(program)
        }

        // THE ACTUATOR POD, offset to one flank: the motor and gearbox that turn this axis. An arm
        // with no visible drive is a shape; the pod is the single cheapest thing that says the
        // joint is powered, and every reference has one bulging off the side of each pivot.
        val podAt = add(p, scale(side, -radius * POD_OFFSET))
        val podOut = scale(side, -radius * POD_LENGTH)
        cylinderBetween3(podAt, add(podAt, podOut), radius = radius * POD_RADIUS)
        program.setModel(model, 0.17f, 0.18f, 0.21f, specular = 0.7f, shininess = 66f, rim = 0.4f)
        cyl.draw(program)

        // Cooling fins.
        for (i in 0 until POD_FINS) {
            val t = (i + 0.5f) / POD_FINS
            val c = add(podAt, scale(podOut, t))
            cylinderBetween3(
                sub(c, scale(side, 0.004f)), add(c, scale(side, 0.004f)),
                radius = radius * (POD_RADIUS + 0.16f),
            )
            program.setModel(model, 0.34f, 0.36f, 0.40f, specular = 0.85f, shininess = 84f, rim = 0.5f)
            cyl.draw(program)
        }

        // Lit end cap on the pod.
        val podEnd = add(podAt, podOut)
        cylinderBetween3(
            podEnd, add(podEnd, scale(side, -radius * 0.10f)), radius = radius * (POD_RADIUS - 0.06f),
        )
        program.setModel(model, NEON[0], NEON[1], NEON[2],
                         specular = 1f, shininess = 128f, rim = 1f)
        cyl.draw(program)
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

        drawVessel(pose, gripper, side, timeS)
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

        // Wrist actuator housing: the driven joint the whole head is keyed to.
        modelAt(gripper, 0.10f, 0.10f, 0.10f)
        program.setModel(model, 0.17f, 0.17f, 0.18f, specular = 0.35f, shininess = 30f)
        box.draw(program)

        // Bearing race under the actuator -- the surface the head rotates on.
        val raceTop = add(gripper, scale(down, 0.030f))
        val raceBottom = add(raceTop, scale(down, 0.016f))
        cylinderBetween3(raceTop, raceBottom, radius = NECK_RADIUS * 1.22f)
        program.setModel(model, 0.30f, 0.32f, 0.35f, specular = 0.75f, shininess = 70f, rim = 0.5f)
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

        // A lit band at the vessel's shoulder, and bolts round the top flange (change 37). The
        // vessel is the part of the tool the eye rests on longest, and it was two hoops on glass.
        run {
            val shoulderY = add(bottom, scale(axis, VESSEL_HEIGHT * 0.92f))
            cylinderBetween3(
                add(shoulderY, scale(axis, -0.005f)), add(shoulderY, scale(axis, 0.005f)),
                radius = VESSEL_RADIUS * 1.09f,
            )
            program.setModel(
                model, ACCENT[0], ACCENT[1], ACCENT[2],
                specular = 1f, shininess = 110f, rim = 1f,
            )
            cyl.draw(program)

            for (i in 0 until FLANGE_BOLTS) {
                val a = i * (2f * Math.PI.toFloat() / FLANGE_BOLTS)
                val o = add(scale(side, cos(a) * VESSEL_RADIUS * 0.98f),
                            scale(out, sin(a) * VESSEL_RADIUS * 0.98f))
                val c = add(top, o)
                cylinderBetween3(
                    add(c, scale(axis, -0.004f)), add(c, scale(axis, 0.010f)), radius = 0.006f,
                )
                program.setModel(model, 0.52f, 0.54f, 0.58f, specular = 0.85f, shininess = 78f)
                cyl.draw(program)
            }
        }

        // Steel hoops banding the glass, which is what stops a clear tube reading as a plain
        // cylinder and gives the vessel its scale.
        listOf(0.30f, 0.70f).forEach { f ->
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
            val anchor = add(
                add(bottom, scale(axis, VESSEL_HEIGHT * 0.58f)),
                scale(out, VESSEL_RADIUS * 0.94f),
            )
            val head = add(hopperTip, scale(axis, -PUMP_RADIUS * 0.55f))
            val bow = add(
                add(anchor, scale(axis, -VESSEL_HEIGHT * 0.42f)),
                scale(out, WIRE_BOW),
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
            // Gland where it enters the vessel, and again at the pump.
            listOf(anchor, head).forEach { c ->
                cylinderBetween3(
                    add(c, scale(out, -0.008f)), add(c, scale(out, 0.008f)),
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
                specular = 1f, shininess = 110f, rim = 1f,
            )
            cyl.draw(program)

            // Bolt ring on the cover plate.
            for (i in 0 until PUMP_BOLTS) {
                val a = i * (2f * Math.PI.toFloat() / PUMP_BOLTS)
                val o = add(scale(out, cos(a) * PUMP_RADIUS * 0.74f),
                            scale(axis, sin(a) * PUMP_RADIUS * 0.74f))
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

        // Three rollers, 120 degrees apart, turning with the dispenser.
        val spin = Math.toRadians(payload.pumpAngle.toDouble()).toFloat()
        for (i in 0 until PUMP_ROLLERS) {
            val theta = spin + i * (2f * Math.PI.toFloat() / PUMP_ROLLERS)
            val offset = add(
                scale(out, cos(theta) * PUMP_ORBIT),
                scale(axis, sin(theta) * PUMP_ORBIT),
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
                add(scale(out, cos(theta) * tubeR), scale(axis, sin(theta) * tubeR)),
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
            specular = 1f, shininess = 110f, rim = 1f,
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

        /** How far behind the turret centre the counterweight drum sits (change 14.7). */
        const val DRUM_OFFSET = 0.17f

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
        val NEON = floatArrayOf(0.35f, 0.94f, 1.00f)

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

        // The arm as a mechanism (change 40).
        /** Fraction of a member's length taken by its heavy root collar. */
        const val LIMB_ROOT = 0.16f

        /** Fraction taken by the fork at the far end. */
        const val LIMB_FORK = 0.20f

        /** How far each fork plate stands off the centreline, in limb radii. */
        const val LIMB_FORK_GAP = 0.92f

        /** Actuator pod: offset from the pivot, length and radius, all in joint radii. */
        const val POD_OFFSET = 1.55f
        const val POD_LENGTH = 1.05f
        const val POD_RADIUS = 0.62f
        const val POD_FINS = 4

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
